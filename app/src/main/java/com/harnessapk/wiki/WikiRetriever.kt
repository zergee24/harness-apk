package com.harnessapk.wiki

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

data class WikiSourceDescriptor(
    val documentId: String,
    val sourceTitle: String,
    val sectionPath: String,
)

enum class WikiRetrievalStatus {
    HIT,
    NO_EVIDENCE,
    FAILED,
}

data class WikiRetrievalRequest(
    val authorization: WikiQueryAuthorization,
    val query: String,
    val routeDecision: WikiRouteDecision,
    val intent: WikiTurnIntent = WikiTurnIntent(
        mode = WikiTurnIntentMode.AUTO,
        namedWikiIds = emptySet(),
        compareRequested = false,
    ),
    val wikiTitles: Map<WikiRef, String> = emptyMap(),
)

data class WikiUsage(
    val ref: WikiRef,
    val scoutRank: Int?,
    val deepHitCount: Int,
    val selectedEvidenceCount: Int,
    val enteredContext: Boolean,
    val errorCode: String? = null,
)

data class WikiEvidence(
    val token: String,
    val ref: WikiRef,
    val wikiTitle: String,
    val documentId: String,
    val sectionId: String,
    val chunkId: String,
    val sourceTitle: String,
    val sectionPath: String,
    val locatorLabel: String,
    val originalText: String,
    val originalTextSha256: String,
)

data class WikiRetrievalResult(
    val retrieverVersion: String,
    val status: WikiRetrievalStatus,
    val routeDecision: WikiRouteDecision,
    val usages: List<WikiUsage>,
    val evidence: List<WikiEvidence>,
    val missingComparisonWikiIds: Set<String>,
)

class WikiRetriever(
    private val searchWiki: suspend (WikiQueryAuthorization, WikiSearchRequest) -> WikiSearchResult,
    private val describeSource: suspend (WikiQueryAuthorization, WikiRef, WikiChunk) -> WikiSourceDescriptor =
        { _, ref, chunk ->
            WikiSourceDescriptor(
                documentId = chunk.sectionId,
                sourceTitle = ref.wikiId,
                sectionPath = chunk.sectionId,
            )
        },
    private val evidenceSelector: WikiEvidenceSelector = WikiEvidenceSelector(),
    private val perWikiTimeoutMillis: Long = DEFAULT_PER_WIKI_TIMEOUT_MILLIS,
) {
    constructor(
        gateway: WikiQueryGateway,
        evidenceSelector: WikiEvidenceSelector = WikiEvidenceSelector(),
        perWikiTimeoutMillis: Long = DEFAULT_PER_WIKI_TIMEOUT_MILLIS,
    ) : this(gateway::searchWiki, gateway::describeSource, evidenceSelector, perWikiTimeoutMillis)

    suspend fun retrieve(request: WikiRetrievalRequest): WikiRetrievalResult {
        val query = request.query.trim()
        if (query.isBlank()) return emptyResult(request, WikiRetrievalStatus.NO_EVIDENCE)
        WikiSourceSearch.validateQuery(query)
        val authorized = request.authorization.refs()
        if (authorized.isEmpty()) return emptyResult(request, WikiRetrievalStatus.NO_EVIDENCE)

        val selectedRefs = request.routeDecision.selectedRefs
            .distinct()
            .sortedWith(WIKI_REF_ORDER)
        selectedRefs.forEach(request.authorization::requireAllowed)
        val primaryRefs = when {
            selectedRefs.isNotEmpty() -> selectedRefs
            request.intent.mode == WikiTurnIntentMode.AUTO -> authorized
            else -> emptyList()
        }
        val comparisonRefs = comparisonRefs(request.intent, authorized, selectedRefs)
        if (primaryRefs.isEmpty()) return emptyResult(request, WikiRetrievalStatus.NO_EVIDENCE, comparisonRefs)
        val outcomes = mutableListOf<WikiDeepSearchOutcome>()

        outcomes += searchRefs(
            authorization = request.authorization,
            refs = primaryRefs,
            query = query,
            limit = PRIMARY_CHANNEL_LIMIT,
            channels = DEEP_CHANNELS,
            wikiTitles = request.wikiTitles,
        )
        var selection = selectEvidence(query, outcomes, comparisonRefs)

        // Stage two broadens source candidates within the already selected Wiki versions.
        if (selection.selected.isEmpty() || comparisonHasGap(selection.selected, comparisonRefs)) {
            outcomes += searchRefs(
                authorization = request.authorization,
                refs = primaryRefs,
                query = query,
                limit = EXPANDED_CHANNEL_LIMIT,
                channels = EXPANDED_CHANNELS,
                wikiTitles = request.wikiTitles,
            )
            selection = selectEvidence(query, outcomes, comparisonRefs, relaxedSemanticWeights = true)
        }

        // Only after local expansion do we spend work on authorized Wiki versions not chosen by the scout.
        val searchedRefs = outcomes.map(WikiDeepSearchOutcome::ref).toSet()
        val fallbackRefs = authorized.filterNot { it in searchedRefs }
        if ((selection.selected.isEmpty() || comparisonHasGap(selection.selected, comparisonRefs)) && fallbackRefs.isNotEmpty()) {
            outcomes += searchRefs(
                authorization = request.authorization,
                refs = fallbackRefs,
                query = query,
                limit = EXPANDED_CHANNEL_LIMIT,
                channels = DEEP_CHANNELS,
                wikiTitles = request.wikiTitles,
            )
            selection = selectEvidence(query, outcomes, comparisonRefs, relaxedSemanticWeights = true)
        }

        val selected = selection.selected
        val evidence = selected.mapIndexed { index, candidate ->
            val descriptor = runCatchingDescriptor(request.authorization, candidate)
            WikiEvidence(
                token = "⟦W${index + 1}⟧",
                ref = candidate.ref,
                wikiTitle = candidate.wikiTitle,
                documentId = descriptor.documentId,
                sectionId = candidate.sectionId,
                chunkId = candidate.chunkId,
                sourceTitle = descriptor.sourceTitle,
                sectionPath = descriptor.sectionPath,
                locatorLabel = candidate.hit.locator.label,
                originalText = candidate.hit.originalText,
                originalTextSha256 = sha256(candidate.hit.originalText),
            )
        }
        val mergedOutcomes = mergeOutcomes(outcomes)
        val selectedByRef = evidence.groupingBy(WikiEvidence::ref).eachCount()
        val usages = authorized.map { ref ->
            val outcome = mergedOutcomes[ref]
            WikiUsage(
                ref = ref,
                scoutRank = scoutRank(request.routeDecision, ref),
                deepHitCount = outcome?.candidates?.size ?: 0,
                selectedEvidenceCount = selectedByRef[ref] ?: 0,
                enteredContext = ref in selectedByRef,
                errorCode = outcome?.errorCode,
            )
        }
        val missingComparisonWikiIds = comparisonRefs
            .filterNot { it in selectedByRef }
            .mapTo(sortedSetOf(), WikiRef::wikiId)
        val hasSuccessfulSearch = outcomes.any { it.errorCode == null }
        val status = when {
            evidence.isNotEmpty() -> WikiRetrievalStatus.HIT
            !hasSuccessfulSearch && outcomes.isNotEmpty() -> WikiRetrievalStatus.FAILED
            else -> WikiRetrievalStatus.NO_EVIDENCE
        }
        return WikiRetrievalResult(
            retrieverVersion = RETRIEVER_VERSION,
            status = status,
            routeDecision = request.routeDecision,
            usages = usages,
            evidence = evidence,
            missingComparisonWikiIds = missingComparisonWikiIds,
        )
    }

    private suspend fun searchRefs(
        authorization: WikiQueryAuthorization,
        refs: List<WikiRef>,
        query: String,
        limit: Int,
        channels: List<WikiSearchChannel>,
        wikiTitles: Map<WikiRef, String>,
    ): List<WikiDeepSearchOutcome> = coroutineScope {
        refs.map { ref ->
            async {
                searchRef(authorization, ref, query, limit, channels, wikiTitles[ref] ?: ref.wikiId)
            }
        }.awaitAll()
    }

    private suspend fun searchRef(
        authorization: WikiQueryAuthorization,
        ref: WikiRef,
        query: String,
        limit: Int,
        channels: List<WikiSearchChannel>,
        wikiTitle: String,
    ): WikiDeepSearchOutcome {
        val channelResults = try {
            withTimeoutOrNull(perWikiTimeoutMillis) {
                coroutineScope {
                    channels.map { channel ->
                        async {
                            try {
                                DeepChannelResult(
                                    channel = channel,
                                    result = searchWiki(
                                        authorization,
                                        WikiSearchRequest(ref, query, channel, limit),
                                    ),
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                DeepChannelResult(channel = channel, result = null)
                            }
                        }
                    }.awaitAll()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return WikiDeepSearchOutcome(ref, emptyList(), "SEARCH_FAILED")
        } ?: return WikiDeepSearchOutcome(ref, emptyList(), "TIMEOUT")
        val successful = channelResults.mapNotNull(DeepChannelResult::result)
        if (successful.isEmpty() && channelResults.isNotEmpty()) {
            return WikiDeepSearchOutcome(ref, emptyList(), "SEARCH_FAILED")
        }
        val hitsById = linkedMapOf<String, WikiSourceHit>()
        val rankings = successful.map { result ->
            result.hits.map { hit ->
                hitsById[hit.chunkId] = mergeHit(hitsById[hit.chunkId], hit)
                hit.chunkId
            }
        }
        val candidates = reciprocalRankFusion(rankings)
            .mapNotNull { rank ->
                hitsById[rank.id]?.let { hit ->
                    WikiEvidenceCandidate(
                        ref = ref,
                        wikiTitle = wikiTitle,
                        documentId = hit.chunk.sectionId,
                        sourceTitle = wikiTitle,
                        sectionPath = hit.chunk.sectionId,
                        hit = hit,
                        rankScore = rank.score,
                    )
                }
            }
        return WikiDeepSearchOutcome(ref, candidates, errorCode = null)
    }

    private fun selectEvidence(
        query: String,
        outcomes: List<WikiDeepSearchOutcome>,
        comparisonRefs: Set<WikiRef>,
        relaxedSemanticWeights: Boolean = false,
    ): WikiEvidenceSelectionResult = evidenceSelector.select(
        WikiEvidenceSelectionRequest(
            query = query,
            candidates = mergeOutcomes(outcomes).values.flatMap(WikiDeepSearchOutcome::candidates),
            comparisonRefs = comparisonRefs,
            relaxedSemanticWeights = relaxedSemanticWeights,
        ),
    )

    private suspend fun runCatchingDescriptor(
        authorization: WikiQueryAuthorization,
        candidate: WikiEvidenceCandidate,
    ): WikiSourceDescriptor = try {
        describeSource(authorization, candidate.ref, candidate.hit.chunk)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        WikiSourceDescriptor(
            documentId = candidate.documentId,
            sourceTitle = candidate.sourceTitle,
            sectionPath = candidate.sectionPath,
        )
    }

    private fun mergeOutcomes(outcomes: List<WikiDeepSearchOutcome>): Map<WikiRef, WikiDeepSearchOutcome> =
        outcomes.groupBy(WikiDeepSearchOutcome::ref).mapValues { (_, refOutcomes) ->
            val candidates = refOutcomes.flatMap(WikiDeepSearchOutcome::candidates)
            val errorCode = if (refOutcomes.any { it.errorCode == null }) {
                null
            } else {
                refOutcomes.firstOrNull { it.errorCode != null }?.errorCode
            }
            WikiDeepSearchOutcome(refOutcomes.first().ref, candidates, errorCode)
        }

    private fun comparisonRefs(
        intent: WikiTurnIntent,
        authorized: List<WikiRef>,
        selectedRefs: List<WikiRef>,
    ): Set<WikiRef> {
        if (!intent.compareRequested && intent.mode != WikiTurnIntentMode.COMPARE_NAMED) return emptySet()
        val named = authorized.filter { it.wikiId in intent.namedWikiIds }.toSet()
        return if (named.isNotEmpty()) named else selectedRefs.toSet()
    }

    private fun comparisonHasGap(selected: List<WikiEvidenceCandidate>, comparisonRefs: Set<WikiRef>): Boolean =
        comparisonRefs.any { ref -> selected.none { it.ref == ref } }

    private fun scoutRank(decision: WikiRouteDecision, ref: WikiRef): Int? = decision.candidates
        .sortedWith(
            compareByDescending<WikiRouteCandidate> { it.score }
                .thenBy { it.ref.wikiId }
                .thenBy { it.ref.version },
        )
        .indexOfFirst { it.ref == ref }
        .takeIf { it >= 0 }
        ?.plus(1)

    private fun emptyResult(
        request: WikiRetrievalRequest,
        status: WikiRetrievalStatus,
        comparisonRefs: Set<WikiRef> = emptySet(),
    ): WikiRetrievalResult = WikiRetrievalResult(
        retrieverVersion = RETRIEVER_VERSION,
        status = status,
        routeDecision = request.routeDecision,
        usages = request.authorization.refs().map { ref ->
            WikiUsage(ref, scoutRank(request.routeDecision, ref), 0, 0, false)
        },
        evidence = emptyList(),
        missingComparisonWikiIds = comparisonRefs.mapTo(sortedSetOf(), WikiRef::wikiId),
    )

    private fun mergeHit(existing: WikiSourceHit?, incoming: WikiSourceHit): WikiSourceHit =
        if (existing == null) incoming else existing.copy(
            matches = (existing.matches + incoming.matches)
                .distinct()
                .sortedWith(
                    compareBy<WikiSourceMatch>(WikiSourceMatch::channel)
                        .thenBy(WikiSourceMatch::label)
                        .thenBy { it.conceptKey.orEmpty() },
                ),
        )

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class DeepChannelResult(
        val channel: WikiSearchChannel,
        val result: WikiSearchResult?,
    )

    private data class WikiDeepSearchOutcome(
        val ref: WikiRef,
        val candidates: List<WikiEvidenceCandidate>,
        val errorCode: String?,
    )

    private companion object {
        const val RETRIEVER_VERSION = "wiki-retriever-v1"
        const val DEFAULT_PER_WIKI_TIMEOUT_MILLIS = 1_200L
        const val PRIMARY_CHANNEL_LIMIT = 20
        const val EXPANDED_CHANNEL_LIMIT = 50
        val WIKI_REF_ORDER = compareBy<WikiRef> { it.wikiId }.thenBy { it.version }
        val DEEP_CHANNELS = listOf(
            WikiSearchChannel.ORIGINAL,
            WikiSearchChannel.NORMALIZED,
            WikiSearchChannel.TERM,
            WikiSearchChannel.TEMPORAL,
            WikiSearchChannel.SUMMARY,
        )
        val EXPANDED_CHANNELS = listOf(
            WikiSearchChannel.ORIGINAL,
            WikiSearchChannel.NORMALIZED,
        )
    }
}
