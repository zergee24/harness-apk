package com.harnessapk.wiki

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiQueryGatewayTest {
    @Test
    fun `gateway refuses an installed but unauthorized Wiki before opening content`() = runTest {
        val store = RecordingWikiContentStore()
        val gateway = WikiQueryGateway(store)
        val authorization = WikiQueryAuthorization(setOf(WikiRef("history.zztj", 1)))

        val failure = runCatching {
            gateway.searchWiki(
                authorization,
                WikiSearchRequest(
                    ref = WikiRef("history.24", 1),
                    query = "汉武帝",
                    channel = WikiSearchChannel.SUMMARY,
                    limit = 10,
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is WikiAuthorizationException)
        assertEquals(0, store.searchRequests.size)
        assertEquals(0, store.chunkRequests.size)
    }

    @Test
    fun `gateway limits search to the requested known channel and fifty results`() = runTest {
        val ref = WikiRef("history.zztj", 1)
        val store = RecordingWikiContentStore(
            hits = listOf(
                sourceHit("summary", WikiSearchChannel.SUMMARY),
                sourceHit("term", WikiSearchChannel.TERM),
            ),
        )
        val gateway = WikiQueryGateway(store)

        val result = gateway.searchWiki(
            WikiQueryAuthorization(setOf(ref)),
            WikiSearchRequest(ref, "王安石", WikiSearchChannel.SUMMARY, limit = 50),
        )

        assertEquals(listOf("summary"), result.hits.map(WikiSourceHit::chunkId))
        assertEquals(50, store.searchRequests.single().limit)
        assertEquals(ref, store.searchRequests.single().ref)
    }

    @Test
    fun `gateway rejects search limits above fifty`() = runTest {
        val ref = WikiRef("history.zztj", 1)
        val store = RecordingWikiContentStore()

        val failure = runCatching {
            WikiQueryGateway(store).searchWiki(
                WikiQueryAuthorization(setOf(ref)),
                WikiSearchRequest(ref, "王安石", WikiSearchChannel.ORIGINAL, limit = 51),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, store.searchRequests.size)
    }

    @Test
    fun `read source returns adjacent chunks only from the same exact authorized section`() = runTest {
        val ref = WikiRef("history.zztj", 1)
        val source = chunk("source", sectionId = "section-1")
        val store = RecordingWikiContentStore(
            chunks = mapOf(source.id to source),
            neighbors = WikiChunkNeighbors(
                previous = chunk("previous", sectionId = "section-1"),
                next = chunk("other-section", sectionId = "section-2"),
            ),
        )

        val result = WikiQueryGateway(store).readSource(
            WikiQueryAuthorization(setOf(ref)),
            WikiReadRequest(ref, source.id, includeAdjacent = true),
        )

        assertEquals("previous", result.previous?.id)
        assertEquals(null, result.next)
        assertEquals(listOf(ref), store.chunkRequests)
        assertEquals(listOf(ref), store.neighborRequests)
    }
}

private data class SearchRequestRecord(
    val ref: WikiRef,
    val query: String,
    val limit: Int,
)

private class RecordingWikiContentStore(
    private val hits: List<WikiSourceHit> = emptyList(),
    private val chunks: Map<String, WikiChunk> = emptyMap(),
    private val neighbors: WikiChunkNeighbors? = null,
) : WikiContentStore {
    val searchRequests = mutableListOf<SearchRequestRecord>()
    val chunkRequests = mutableListOf<WikiRef>()
    val neighborRequests = mutableListOf<WikiRef>()

    override suspend fun listDocuments(ref: WikiRef): List<WikiDocument> = emptyList()
    override suspend fun findDocument(ref: WikiRef, documentId: String): WikiDocument? = null
    override suspend fun listSections(ref: WikiRef, parentSectionId: String?): List<WikiSection> = emptyList()
    override suspend fun findSection(ref: WikiRef, sectionId: String): WikiSection? = null
    override suspend fun listChunks(ref: WikiRef, sectionId: String, limit: Int): List<WikiChunk> = emptyList()
    override suspend fun findChunk(ref: WikiRef, chunkId: String): WikiChunk? {
        chunkRequests += ref
        return chunks[chunkId]
    }

    override suspend fun chunkNeighbors(ref: WikiRef, chunkId: String): WikiChunkNeighbors? {
        neighborRequests += ref
        return neighbors
    }

    override suspend fun stats(ref: WikiRef): WikiContentStats = WikiContentStats(0, 0, 0)
    override suspend fun summariesFor(ref: WikiRef, ownerType: String, ownerId: String): List<WikiSummary> = emptyList()
    override suspend fun listTerms(ref: WikiRef, limit: Int): List<WikiTerm> = emptyList()
    override suspend fun aliasesFor(ref: WikiRef, termId: String): List<WikiAlias> = emptyList()
    override suspend fun annotationsFor(ref: WikiRef, ownerType: String, ownerId: String): List<WikiAnnotation> = emptyList()
    override suspend fun linksFrom(ref: WikiRef, sourceType: String, sourceId: String): List<WikiLink> = emptyList()

    override suspend fun searchSources(ref: WikiRef, query: String, limit: Int): List<WikiSourceHit> {
        searchRequests += SearchRequestRecord(ref, query, limit)
        return hits
    }

    override suspend fun evidenceFor(ref: WikiRef, ownerType: String, ownerId: String): List<WikiChunk> = emptyList()
}

private fun sourceHit(id: String, channel: WikiSearchChannel): WikiSourceHit = WikiSourceHit(
    chunk = chunk(id),
    matches = listOf(WikiSourceMatch(channel, channel.name)),
)

private fun chunk(id: String, sectionId: String = "section-1"): WikiChunk = WikiChunk(
    id = id,
    sectionId = sectionId,
    ordinal = 1,
    originalText = "原文 $id",
    locator = WikiSourceLocator("locator-$id", id, "位置 $id", "{}"),
    contentHash = "a".repeat(64),
)
