package com.harnessapk.wiki

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiRetrieverTest {
    private val histories = WikiRef("history.24", 1)
    private val zztj = WikiRef("history.zztj", 1)

    @Test
    fun `default retrieval obeys evidence budgets and records missing comparison side`() = runTest {
        val retriever = WikiRetriever(searchWiki = { _, request ->
            WikiSearchResult(
                ref = request.ref,
                query = request.query,
                channel = request.channel,
                hits = if (request.ref == histories && request.channel == WikiSearchChannel.ORIGINAL) {
                    (1..14).map { index ->
                        retrievalHit(
                            id = "history-$index",
                            sectionId = "section-${index % 4}",
                            text = "王安石${"甲".repeat(1_150)}$index",
                            channel = request.channel,
                            ordinal = index,
                        )
                    }
                } else {
                    emptyList()
                },
            )
        })

        val result = retriever.retrieve(
            WikiRetrievalRequest(
                authorization = WikiQueryAuthorization(setOf(histories, zztj)),
                query = "比较王安石变法",
                routeDecision = routeDecision(listOf(histories, zztj), WikiRouteReason.EXPLICIT_COMPARISON),
                intent = WikiTurnIntent(
                    mode = WikiTurnIntentMode.COMPARE_NAMED,
                    namedWikiIds = setOf(histories.wikiId, zztj.wikiId),
                    compareRequested = true,
                ),
                wikiTitles = mapOf(histories to "二十四史", zztj to "资治通鉴"),
            ),
        )

        assertEquals(WikiRetrievalStatus.HIT, result.status)
        assertTrue(result.evidence.size <= 10)
        assertTrue(result.evidence.sumOf { it.originalText.length } <= 12_000)
        assertTrue(result.evidence.groupingBy(WikiEvidence::sectionId).eachCount().values.all { it <= 3 })
        assertEquals(setOf(histories.wikiId), result.evidence.map { it.ref.wikiId }.toSet())
        assertEquals(setOf(zztj.wikiId), result.missingComparisonWikiIds)
        assertEquals((1..result.evidence.size).map { "⟦W$it⟧" }, result.evidence.map(WikiEvidence::token))
        assertTrue(result.evidence.all { it.originalText.startsWith("王安石") })
    }

    @Test
    fun `no hit selected Wiki expands once to another authorized Wiki`() = runTest {
        val retriever = WikiRetriever(searchWiki = { _, request ->
            WikiSearchResult(
                ref = request.ref,
                query = request.query,
                channel = request.channel,
                hits = if (request.ref == zztj && request.channel == WikiSearchChannel.NORMALIZED) {
                    listOf(
                        retrievalHit(
                            id = "zztj-hit",
                            sectionId = "volume-1",
                            text = "司马光记王安石事",
                            channel = request.channel,
                            ordinal = 1,
                        ),
                    )
                } else {
                    emptyList()
                },
            )
        })

        val result = retriever.retrieve(
            WikiRetrievalRequest(
                authorization = WikiQueryAuthorization(setOf(histories, zztj)),
                query = "王安石",
                routeDecision = routeDecision(listOf(histories), WikiRouteReason.AUTO_TOP),
            ),
        )

        assertEquals(WikiRetrievalStatus.HIT, result.status)
        assertEquals(listOf(zztj), result.evidence.map(WikiEvidence::ref).distinct())
        assertTrue(result.usages.single { it.ref == zztj }.deepHitCount > 0)
    }

    private fun routeDecision(selected: List<WikiRef>, reason: WikiRouteReason): WikiRouteDecision =
        WikiRouteDecision(
            routerVersion = "wiki-router-v1",
            reason = reason,
            candidates = emptyList(),
            selectedRefs = selected,
        )
}

private fun retrievalHit(
    id: String,
    sectionId: String,
    text: String,
    channel: WikiSearchChannel,
    ordinal: Int,
): WikiSourceHit = WikiSourceHit(
    chunk = WikiChunk(
        id = id,
        sectionId = sectionId,
        ordinal = ordinal,
        originalText = text,
        locator = WikiSourceLocator("locator-$id", id, "位置 $id", "{}"),
        contentHash = "b".repeat(64),
    ),
    matches = listOf(WikiSourceMatch(channel, channel.name)),
)
