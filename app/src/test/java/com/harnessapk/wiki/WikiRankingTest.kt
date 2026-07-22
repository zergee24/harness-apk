package com.harnessapk.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiRankingTest {
    @Test
    fun reciprocalRankFusionIsStableAndDoesNotUseResultSetSize() {
        val compact = reciprocalRankFusion(
            rankings = listOf(listOf("a", "b"), listOf("a")),
        )
        val expanded = reciprocalRankFusion(
            rankings = listOf(listOf("a", "b", "c", "d", "e"), listOf("a", "x", "y")),
        )

        assertEquals(listOf("a", "b"), compact.take(2).map(WikiRrfRank::id))
        assertEquals(compact.first().score, expanded.first().score, 0.000001)
        assertTrue(expanded.map(WikiRrfRank::id).containsAll(listOf("a", "b")))
    }

    @Test
    fun reciprocalRankFusionRejectsInvalidK() {
        assertTrue(runCatching { reciprocalRankFusion(listOf(listOf("a")), k = 0) }.isFailure)
    }
}
