package com.harnessapk.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WikiMarkdownCitationFormatterTest {
    private val citationId = "2f17f6dc-4fe9-4d3a-beb2-9ef46c4379ca"
    private val citationSet = WikiMarkdownCitationSet(listOf(citation()))

    @Test
    fun internalCitationAnchorBecomesPortableFootnote() {
        val result = WikiMarkdownCitationFormatter.toPortableMarkdown(
            markdown = "司马光作《资治通鉴》。[¹](harness-wiki://citation/$citationId)",
            citations = citationSet,
        )

        assertEquals(
            """
            司马光作《资治通鉴》。[^hwiki-a1b2c3d4-1]

            [^hwiki-a1b2c3d4-1]: 《资治通鉴》· 卷第一；资治通鉴 v1；周威烈王二十三年。
            """.trimIndent(),
            result,
        )
    }

    @Test
    fun repeatedCitationSharesDefinitionAndPreservesOrdinaryLinksAndFootnotes() {
        val result = WikiMarkdownCitationFormatter.toPortableMarkdown(
            markdown = """
                甲[¹](harness-wiki://citation/$citationId)，乙[¹](harness-wiki://citation/$citationId)。
                [官网](https://example.com)[^user-note]

                [^user-note]: 用户已有脚注。
            """.trimIndent(),
            citations = citationSet,
        )

        assertEquals(
            2,
            Regex(Regex.escape("[^hwiki-a1b2c3d4-1]") + "(?!:)").findAll(result).count(),
        )
        assertEquals(1, Regex(Regex.escape("[^hwiki-a1b2c3d4-1]:")).findAll(result).count())
        assertFalse(result.contains("harness-wiki://"))
        assertFalse(result.contains(citationId))
        assertEquals(true, result.contains("[官网](https://example.com)[^user-note]"))
        assertEquals(true, result.contains("[^user-note]: 用户已有脚注。"))
    }

    @Test(expected = WikiMarkdownCitationException::class)
    fun unknownInternalCitationCannotBeWrittenToPortableMarkdown() {
        WikiMarkdownCitationFormatter.toPortableMarkdown(
            markdown = "[¹](harness-wiki://citation/6a8f46ae-cb95-4b47-92e4-fd0414a2178a)",
            citations = citationSet,
        )
    }

    private fun citation() = WikiMarkdownCitation(
        citationId = citationId,
        sourceMessageId = "a1b2c3d4-9999-4aaa-8bbb-cccccccccccc",
        displayOrdinal = 1,
        wikiId = "history.zztj",
        wikiVersion = 1,
        wikiTitle = "资治通鉴",
        sourceTitle = "资治通鉴",
        sectionPath = "卷第一",
        locatorLabel = "周威烈王二十三年",
        originalTextSnapshot = "臣光曰：以史为鉴。",
        originalTextSha256 = "a".repeat(64),
    )
}
