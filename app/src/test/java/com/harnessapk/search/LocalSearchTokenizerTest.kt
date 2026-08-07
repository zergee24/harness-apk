package com.harnessapk.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSearchTokenizerTest {
    @Test
    fun chineseAndLatinQueriesProduceStableFtsTokens() {
        val tokens = LocalSearchTokenizer.tokens("家庭计划 GPT-5")

        assertTrue(tokens.containsAll(listOf("家庭", "庭计", "计划", "家庭计", "庭计划", "gpt", "5")))
        assertEquals(tokens.sorted().distinct(), tokens)
        assertTrue(LocalSearchTokenizer.matchExpression("家庭计划").contains("\"家庭\""))
    }

    @Test
    fun blankQueryHasNoMatchExpression() {
        assertEquals("", LocalSearchTokenizer.matchExpression("  "))
    }
}
