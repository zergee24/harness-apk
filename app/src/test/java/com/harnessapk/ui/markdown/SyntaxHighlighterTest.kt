package com.harnessapk.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlighterTest {
    @Test
    fun tokenizesKotlinKeywordStringAndComment() {
        val tokens = tokenizeCode("""val name = "AI" // comment""", "kotlin")

        assertTrue(tokens.any { it.kind == CodeTokenKind.KEYWORD && it.literal == "val" })
        assertTrue(tokens.any { it.kind == CodeTokenKind.STRING && it.literal == "\"AI\"" })
        assertTrue(tokens.any { it.kind == CodeTokenKind.COMMENT && it.literal == "// comment" })
    }

    @Test
    fun tokenizesJsonStringsAndNumbers() {
        val tokens = tokenizeCode("""{"tokens": 200000}""", "json")

        assertTrue(tokens.any { it.kind == CodeTokenKind.STRING && it.literal == "\"tokens\"" })
        assertTrue(tokens.any { it.kind == CodeTokenKind.NUMBER && it.literal == "200000" })
    }

    @Test
    fun unknownLanguageFallsBackToPlainText() {
        val tokens = tokenizeCode("plain text", "unknown")

        assertEquals(listOf(CodeToken("plain text", CodeTokenKind.PLAIN)), tokens)
    }
}
