package com.harnessapk.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiSourceSearchTest {
    @Test
    fun `original and normalized query tokens preserve their respective character forms`() {
        assertTrue(WikiSourceSearch.originalTokens("司馬光").contains("司馬光"))
        assertTrue(WikiSourceSearch.normalizedTokens("司馬光").contains("司马光"))
        assertEquals(
            "247d47ffeb90feaa2d08af39d7685e773dfc08137f81a741b15083ba71170279",
            WikiSourceSearch.normalizationMapHash,
        )
    }

    @Test
    fun `rrf fuses channels deterministically and preserves match labels`() {
        val result = WikiSourceSearch.fuse(
            channelRankings = listOf(
                listOf(WikiSearchCandidate("chunk-a", WikiSourceMatch(WikiSearchChannel.ORIGINAL, "原文"))),
                listOf(
                    WikiSearchCandidate("chunk-b", WikiSourceMatch(WikiSearchChannel.NORMALIZED, "归一化原文")),
                    WikiSearchCandidate("chunk-a", WikiSourceMatch(WikiSearchChannel.NORMALIZED, "归一化原文")),
                ),
                listOf(WikiSearchCandidate("chunk-b", WikiSourceMatch(WikiSearchChannel.SUMMARY, "摘要"))),
            ),
            limit = 20,
        )

        assertEquals(listOf("chunk-b", "chunk-a"), result.map(WikiRankedSource::chunkId))
        assertEquals(
            setOf(WikiSearchChannel.NORMALIZED, WikiSearchChannel.SUMMARY),
            result.first().matches.map(WikiSourceMatch::channel).toSet(),
        )
    }

    @Test
    fun `oversized source search limit fails closed`() {
        assertTrue(runCatching { WikiSourceSearch.validateLimit(101) }.exceptionOrNull() is IllegalArgumentException)
    }
}
