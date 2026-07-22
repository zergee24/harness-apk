package com.harnessapk.wiki

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

data class WikiRouteRequest(
    val authorization: WikiQueryAuthorization,
    val query: String,
    val intent: WikiTurnIntent = WikiTurnIntent(
        mode = WikiTurnIntentMode.AUTO,
        namedWikiIds = emptySet(),
        compareRequested = false,
    ),
)

enum class WikiRouteReason {
    NO_QUERY,
    EMPTY_SCOPE,
    SINGLE_AUTHORIZED,
    EXPLICIT_ONLY,
    EXPLICIT_COMPARISON,
    NAMED_WIKI_UNAVAILABLE,
    AUTO_TOP,
    AUTO_TOP_TWO,
    NO_CANDIDATE_ABOVE_FLOOR,
}

data class WikiRouteCandidate(
    val ref: WikiRef,
    val score: Double,
    val channelRanks: Map<WikiSearchChannel, Int?>,
    val sourceHits: List<WikiSourceHit>,
    val exactAliasMatch: Boolean,
    val timeOverlap: Boolean,
    val sectionDiversity: Int,
    val errorCode: String? = null,
)

data class WikiRouteDecision(
    val routerVersion: String,
    val reason: WikiRouteReason,
    val candidates: List<WikiRouteCandidate>,
    val selectedRefs: List<WikiRef>,
)

class WikiRouter(
    private val searchWiki: suspend (WikiQueryAuthorization, WikiSearchRequest) -> WikiSearchResult,
    private val scoutTimeoutMillis: Long = DEFAULT_SCOUT_TIMEOUT_MILLIS,
) {
    constructor(
        gateway: WikiQueryGateway,
        scoutTimeoutMillis: Long = DEFAULT_SCOUT_TIMEOUT_MILLIS,
    ) : this(gateway::searchWiki, scoutTimeoutMillis)

    suspend fun route(request: WikiRouteRequest): WikiRouteDecision {
        val query = request.query.trim()
        if (query.isEmpty()) return decision(WikiRouteReason.NO_QUERY)
        val authorized = request.authorization.refs()
        if (authorized.isEmpty()) return decision(WikiRouteReason.EMPTY_SCOPE)

        val explicitRefs = authorized.filter { ref -> ref.wikiId in request.intent.namedWikiIds }
        when (request.intent.mode) {
            WikiTurnIntentMode.ONLY_NAMED -> {
                return if (explicitRefs.isEmpty()) {
                    decision(WikiRouteReason.NAMED_WIKI_UNAVAILABLE)
                } else {
                    decision(WikiRouteReason.EXPLICIT_ONLY, selectedRefs = explicitRefs)
                }
            }

            WikiTurnIntentMode.COMPARE_NAMED -> {
                return if (explicitRefs.isEmpty()) {
                    decision(WikiRouteReason.NAMED_WIKI_UNAVAILABLE)
                } else {
                    decision(WikiRouteReason.EXPLICIT_COMPARISON, selectedRefs = explicitRefs)
                }
            }

            WikiTurnIntentMode.AUTO -> Unit
        }
        if (authorized.size == 1) {
            return decision(WikiRouteReason.SINGLE_AUTHORIZED, selectedRefs = authorized)
        }

        val candidates = coroutineScope {
            authorized.map { ref ->
                async {
                    scout(
                        authorization = request.authorization,
                        ref = ref,
                        query = query,
                        intent = request.intent,
                    )
                }
            }.awaitAll()
        }.sortedWith(compareBy<WikiRouteCandidate> { it.ref.wikiId }.thenBy { it.ref.version })
        val ranked = candidates.sortedWith(
            compareByDescending<WikiRouteCandidate> { it.score }
                .thenBy { it.ref.wikiId }
                .thenBy { it.ref.version },
        )
        val first = ranked.firstOrNull { it.errorCode == null && it.score >= ROUTE_SCORE_FLOOR }
            ?: return WikiRouteDecision(ROUTER_VERSION, WikiRouteReason.NO_CANDIDATE_ABOVE_FLOOR, candidates, emptyList())
        val second = ranked.firstOrNull { candidate ->
            candidate != first &&
                candidate.errorCode == null &&
                candidate.score >= ROUTE_SCORE_FLOOR &&
                candidate.score / first.score >= SECOND_ROUTE_RELATIVE_FLOOR
        }
        val selected = listOfNotNull(first, second)
            .map(WikiRouteCandidate::ref)
            .sortedWith(compareBy<WikiRef> { it.wikiId }.thenBy { it.version })
        return WikiRouteDecision(
            routerVersion = ROUTER_VERSION,
            reason = if (second == null) WikiRouteReason.AUTO_TOP else WikiRouteReason.AUTO_TOP_TWO,
            candidates = candidates,
            selectedRefs = selected,
        )
    }

    private suspend fun scout(
        authorization: WikiQueryAuthorization,
        ref: WikiRef,
        query: String,
        intent: WikiTurnIntent,
    ): WikiRouteCandidate {
        val responses = try {
            withTimeoutOrNull(scoutTimeoutMillis) {
                coroutineScope {
                    SCOUT_CHANNELS.map { channel ->
                        async {
                            channel to runCatching {
                                searchWiki(
                                    authorization,
                                    WikiSearchRequest(ref, query, channel, SCOUT_CHANNEL_RESULT_LIMIT),
                                )
                            }
                        }
                    }.awaitAll()
                }
            } ?: return errorCandidate(ref, intent, "TIMEOUT")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return errorCandidate(ref, intent, "SEARCH_FAILED")
        }
        val successful = responses.mapNotNull { (channel, result) ->
            result.getOrNull()?.let { channel to it }
        }
        if (successful.isEmpty() && responses.any { (_, result) -> result.isFailure }) {
            return errorCandidate(ref, intent, "SEARCH_FAILED")
        }
        val hitsById = linkedMapOf<String, WikiSourceHit>()
        val rankings = successful.map { (_, result) ->
            result.hits.map { hit ->
                hitsById.putIfAbsent(hit.chunkId, hit)
                hit.chunkId
            }
        }
        val fused = reciprocalRankFusion(rankings)
        val sourceHits = fused.mapNotNull { rank -> hitsById[rank.id] }
        val channelRanks = SCOUT_CHANNELS.associateWith { channel ->
            successful.firstOrNull { (candidate, _) -> candidate == channel }
                ?.second
                ?.hits
                ?.indexOfFirst { hit -> hit.chunkId == fused.firstOrNull()?.id }
                ?.takeIf { index -> index >= 0 }
                ?.plus(1)
        }
        val exactAliasMatch = ref.wikiId in intent.namedWikiIds
        val timeOverlap = hasTimeExpression(query) && successful.any { (channel, result) ->
            channel == WikiSearchChannel.TEMPORAL && result.hits.isNotEmpty()
        }
        val sectionDiversity = sourceHits.map(WikiSourceHit::chunk).map(WikiChunk::sectionId).distinct().size
        val topRrf = fused.firstOrNull()?.score ?: 0.0
        val normalizedRrf = topRrf / (SCOUT_CHANNELS.size.toDouble() / (WikiSourceSearch.RRF_K + 1).toDouble())
        val score = normalizedRrf +
            (if (exactAliasMatch) EXACT_ALIAS_BONUS else 0.0) +
            (if (timeOverlap) TIME_OVERLAP_BONUS else 0.0) +
            ((sectionDiversity - 1).coerceAtLeast(0) * SECTION_DIVERSITY_BONUS).coerceAtMost(MAX_SECTION_DIVERSITY_BONUS)
        return WikiRouteCandidate(
            ref = ref,
            score = score,
            channelRanks = channelRanks,
            sourceHits = sourceHits,
            exactAliasMatch = exactAliasMatch,
            timeOverlap = timeOverlap,
            sectionDiversity = sectionDiversity,
        )
    }

    private fun errorCandidate(ref: WikiRef, intent: WikiTurnIntent, code: String): WikiRouteCandidate =
        WikiRouteCandidate(
            ref = ref,
            score = 0.0,
            channelRanks = emptyMap(),
            sourceHits = emptyList(),
            exactAliasMatch = ref.wikiId in intent.namedWikiIds,
            timeOverlap = false,
            sectionDiversity = 0,
            errorCode = code,
        )

    private fun decision(reason: WikiRouteReason, selectedRefs: List<WikiRef> = emptyList()): WikiRouteDecision =
        WikiRouteDecision(ROUTER_VERSION, reason, emptyList(), selectedRefs)

    private companion object {
        const val ROUTER_VERSION = "wiki-router-v1"
        const val DEFAULT_SCOUT_TIMEOUT_MILLIS = 750L
        const val SCOUT_CHANNEL_RESULT_LIMIT = 12
        const val ROUTE_SCORE_FLOOR = 0.14
        const val SECOND_ROUTE_RELATIVE_FLOOR = 0.78
        const val EXACT_ALIAS_BONUS = 0.15
        const val TIME_OVERLAP_BONUS = 0.10
        const val SECTION_DIVERSITY_BONUS = 0.04
        const val MAX_SECTION_DIVERSITY_BONUS = 0.12
        val SCOUT_CHANNELS = listOf(
            WikiSearchChannel.SUMMARY,
            WikiSearchChannel.TERM,
            WikiSearchChannel.TEMPORAL,
            WikiSearchChannel.NORMALIZED,
        )
        val TIME_EXPRESSION = Regex("(?:[0-9]{3,4}年?|[一二三四五六七八九十]+年)")

        fun hasTimeExpression(query: String): Boolean = TIME_EXPRESSION.containsMatchIn(query)
    }
}
