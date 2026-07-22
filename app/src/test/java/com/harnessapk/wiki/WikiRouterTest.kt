package com.harnessapk.wiki

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiRouterTest {
    private val zztj = WikiRef("history.zztj", 1)
    private val histories = WikiRef("history.24", 1)

    @Test
    fun `empty query and empty authorization do not issue searches`() = runTest {
        val calls = mutableListOf<WikiSearchRequest>()
        val router = WikiRouter(searchWiki = { _, request ->
            calls += request
            WikiSearchResult(request.ref, request.query, request.channel, emptyList())
        })

        val noQuery = router.route(WikiRouteRequest(WikiQueryAuthorization(setOf(zztj)), "   "))
        val noScope = router.route(WikiRouteRequest(WikiQueryAuthorization(emptySet()), "王安石"))

        assertEquals(WikiRouteReason.NO_QUERY, noQuery.reason)
        assertEquals(WikiRouteReason.EMPTY_SCOPE, noScope.reason)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `one authorized Wiki bypasses automatic selection`() = runTest {
        val router = routerFor(mapOf(zztj to scoutHits(WikiSearchChannel.SUMMARY)))

        val decision = router.route(WikiRouteRequest(WikiQueryAuthorization(setOf(zztj)), "王安石"))

        assertEquals(listOf(zztj), decision.selectedRefs)
        assertEquals(WikiRouteReason.SINGLE_AUTHORIZED, decision.reason)
    }

    @Test
    fun `only named and comparison requests retain only authorized named Wikis`() = runTest {
        val router = routerFor(
            mapOf(
                zztj to scoutHits(WikiSearchChannel.SUMMARY),
                histories to scoutHits(WikiSearchChannel.SUMMARY),
            ),
        )
        val authorization = WikiQueryAuthorization(setOf(zztj, histories))

        val only = router.route(
            WikiRouteRequest(
                authorization = authorization,
                query = "王安石",
                intent = WikiTurnIntent(WikiTurnIntentMode.ONLY_NAMED, setOf("history.zztj"), false),
            ),
        )
        val comparison = router.route(
            WikiRouteRequest(
                authorization = authorization,
                query = "王安石",
                intent = WikiTurnIntent(
                    WikiTurnIntentMode.COMPARE_NAMED,
                    setOf("history.24", "history.zztj"),
                    true,
                ),
            ),
        )

        assertEquals(listOf(zztj), only.selectedRefs)
        assertEquals(listOf(histories, zztj), comparison.selectedRefs)
        assertEquals(WikiRouteReason.EXPLICIT_COMPARISON, comparison.reason)
    }

    @Test
    fun `close automatic scores select two but a dominant score selects one`() = runTest {
        val closeRouter = routerFor(
            mapOf(
                zztj to scoutHits(
                    WikiSearchChannel.SUMMARY,
                    WikiSearchChannel.TERM,
                    WikiSearchChannel.NORMALIZED,
                    WikiSearchChannel.TEMPORAL,
                ),
                histories to scoutHits(
                    WikiSearchChannel.SUMMARY,
                    WikiSearchChannel.TERM,
                    WikiSearchChannel.NORMALIZED,
                    WikiSearchChannel.TEMPORAL,
                ),
            ),
        )
        val dominantRouter = routerFor(
            mapOf(
                zztj to scoutHits(
                    WikiSearchChannel.SUMMARY,
                    WikiSearchChannel.TERM,
                    WikiSearchChannel.NORMALIZED,
                    WikiSearchChannel.TEMPORAL,
                ),
                histories to scoutHits(WikiSearchChannel.SUMMARY),
            ),
        )
        val authorization = WikiQueryAuthorization(setOf(zztj, histories))

        val close = closeRouter.route(WikiRouteRequest(authorization, "王安石"))
        val dominant = dominantRouter.route(WikiRouteRequest(authorization, "王安石"))

        assertEquals(listOf(histories, zztj), close.selectedRefs)
        assertEquals(listOf(zztj), dominant.selectedRefs)
    }

    @Test
    fun `all below score floor returns no selected Wiki`() = runTest {
        val decision = routerFor(emptyMap()).route(
            WikiRouteRequest(WikiQueryAuthorization(setOf(zztj, histories)), "王安石"),
        )

        assertEquals(emptyList<WikiRef>(), decision.selectedRefs)
        assertEquals(WikiRouteReason.NO_CANDIDATE_ABOVE_FLOOR, decision.reason)
    }

    private fun routerFor(
        responses: Map<WikiRef, Map<WikiSearchChannel, List<WikiSourceHit>>>,
    ): WikiRouter = WikiRouter(searchWiki = { _, request ->
        WikiSearchResult(
            ref = request.ref,
            query = request.query,
            channel = request.channel,
            hits = responses[request.ref]?.get(request.channel).orEmpty(),
        )
    })
}

private fun scoutHits(vararg channels: WikiSearchChannel): Map<WikiSearchChannel, List<WikiSourceHit>> =
    channels.associateWith { channel ->
        listOf(
            WikiSourceHit(
                chunk = WikiChunk(
                    id = "${channel.name.lowercase()}-chunk",
                    sectionId = "${channel.name.lowercase()}-section",
                    ordinal = 1,
                    originalText = "王安石",
                    locator = WikiSourceLocator("locator-${channel.name}", "${channel.name}-chunk", "位置", "{}"),
                    contentHash = "a".repeat(64),
                ),
                matches = listOf(WikiSourceMatch(channel, channel.name)),
            ),
        )
    }
