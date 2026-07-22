package com.harnessapk.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLinkInteractionTest {
    @Test
    fun validInternalCitationLinkResolvesToItsExactId() {
        val citationId = "2f17f6dc-4fe9-4d3a-beb2-9ef46c4379ca"

        assertEquals(
            MarkdownLinkTarget.WikiCitation(citationId),
            markdownLinkTarget("harness-wiki://citation/$citationId"),
        )
    }

    @Test
    fun malformedInternalCitationLinkIsIgnored() {
        assertEquals(
            MarkdownLinkTarget.Ignored,
            markdownLinkTarget("harness-wiki://citation/not-a-uuid"),
        )
        assertEquals(
            MarkdownLinkTarget.Ignored,
            markdownLinkTarget("harness-wiki://other/2f17f6dc-4fe9-4d3a-beb2-9ef46c4379ca"),
        )
    }

    @Test
    fun externalHttpLinkPassesThroughButUnsafeSchemesAreIgnored() {
        val external = markdownLinkTarget("https://example.com/reference?q=history")

        assertEquals(
            MarkdownLinkTarget.ExternalUrl("https://example.com/reference?q=history"),
            external,
        )
        assertEquals(MarkdownLinkTarget.Ignored, markdownLinkTarget("javascript:alert(1)"))
        assertTrue(external !is MarkdownLinkTarget.WikiCitation)
    }

    @Test
    fun copyingMarkdownKeepsCitationNumberWithoutInternalUrl() {
        val citationId = "2f17f6dc-4fe9-4d3a-beb2-9ef46c4379ca"

        assertEquals(
            "结论¹，另见[网站](https://example.com)",
            markdownTextForCopy("结论[¹](harness-wiki://citation/$citationId)，另见[网站](https://example.com)"),
        )
    }
}
