package com.harnessapk.session

import com.harnessapk.wiki.WikiRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoWikiComparisonPolicyTest {
    private val first = citation(
        citationId = "00000000-0000-0000-0000-000000000001",
        displayOrdinal = 1,
        wikiId = "history.24",
        wikiVersion = 2,
        wikiTitle = "二十四史",
        sourceTitle = "汉书",
    )
    private val second = citation(
        citationId = "00000000-0000-0000-0000-000000000002",
        displayOrdinal = 2,
        wikiId = "history.zztj",
        wikiVersion = 3,
        wikiTitle = "资治通鉴",
        sourceTitle = "资治通鉴",
    )
    private val firstRef = WikiRef(first.wikiId, first.wikiVersion)
    private val secondRef = WikiRef(second.wikiId, second.wikiVersion)

    @Test
    fun bothVerifiedWikiSidesMayBeWrittenWithTheirPortableFootnotes() {
        val citations = WikiMarkdownCitationSet(listOf(first, second))
        val markdown = portable(
            "二十四史记为甲[甲](harness-wiki://citation/${first.citationId})，" +
                "资治通鉴记为乙[乙](harness-wiki://citation/${second.citationId})。",
            citations,
        )

        val validated = WikiMarkdownProposalValidator.validate(
            proposal(markdown),
            citations = citations,
            wikiCoverage = coverage(missingRefs = emptySet()),
        )

        assertEquals(markdown, validated.markdown)
    }

    @Test
    fun oneCitedSideCannotClaimTheTwoBooksAgree() {
        val citations = WikiMarkdownCitationSet(listOf(first))
        val markdown = portable("两书一致[甲](harness-wiki://citation/${first.citationId})。", citations)

        assertUnsafe(markdown, citations, coverage(missingRefs = setOf(secondRef)))
    }

    @Test
    fun noHitComparisonCannotClaimTheTwoBooksHaveNoRecord() {
        assertUnsafe(
            markdown = "两书均无记载。",
            citations = WikiMarkdownCitationSet.EMPTY,
            coverage = coverage(missingRefs = setOf(firstRef, secondRef)),
        )
    }

    @Test
    fun failedComparisonSideCannotBeTurnedIntoAnAbsoluteSourceAbsenceClaim() {
        val citations = WikiMarkdownCitationSet(listOf(first))
        val markdown = portable("另一书从未提及该事[甲](harness-wiki://citation/${first.citationId})。", citations)

        assertUnsafe(markdown, citations, coverage(missingRefs = setOf(secondRef)))
    }

    @Test
    fun providerCannotInventAThirdWikiFootnote() {
        val error = assertThrows(WikiMarkdownValidationException::class.java) {
            WikiMarkdownProposalValidator.validate(
                proposal("结论[^hwiki-third-1]\n\n[^hwiki-third-1]: 《伪书》· 卷一；伪资料 v1；第 1 节。"),
                citations = WikiMarkdownCitationSet(listOf(first, second)),
                wikiCoverage = coverage(missingRefs = emptySet()),
            )
        }

        assertTrue(error.message.orEmpty().contains("未授权的 Wiki 脚注"))
    }

    @Test
    fun cautiousCurrentRetrievalLanguageIsAllowedForTheMissingSide() {
        val citations = WikiMarkdownCitationSet(listOf(first))
        val markdown = portable(
            "当前检索未在资治通鉴中找到可核对原文，不能据此断言没有记载。" +
                "[甲](harness-wiki://citation/${first.citationId})",
            citations,
        )

        val validated = WikiMarkdownProposalValidator.validate(
            proposal(markdown),
            citations = citations,
            wikiCoverage = coverage(missingRefs = setOf(secondRef)),
        )

        assertEquals(markdown, validated.markdown)
    }

    private fun assertUnsafe(
        markdown: String,
        citations: WikiMarkdownCitationSet,
        coverage: WikiEvidenceCoverage,
    ) {
        val error = assertThrows(WikiMarkdownValidationException::class.java) {
            WikiMarkdownProposalValidator.validate(
                proposal(markdown),
                citations = citations,
                wikiCoverage = coverage,
            )
        }
        assertTrue(error.message.orEmpty().contains("比较"))
    }

    private fun coverage(missingRefs: Set<WikiRef>): WikiEvidenceCoverage = WikiEvidenceCoverage(
        requestedComparisonRefs = setOf(firstRef, secondRef),
        queriedRefs = setOf(firstRef, secondRef),
        verifiedCitationCounts = mapOf(firstRef to 1),
        missingRefs = missingRefs,
    )

    private fun portable(markdown: String, citations: WikiMarkdownCitationSet): String =
        WikiMarkdownCitationFormatter.toPortableMarkdown(markdown, citations)

    private fun proposal(markdown: String): MarkdownUpdateProposal = MarkdownUpdateProposal(
        operation = MarkdownUpdateOperation.CREATE,
        path = "notes/comparison.md",
        title = "对比",
        reason = "测试",
        markdown = markdown,
    )

    private fun citation(
        citationId: String,
        displayOrdinal: Int,
        wikiId: String,
        wikiVersion: Int,
        wikiTitle: String,
        sourceTitle: String,
    ): WikiMarkdownCitation = WikiMarkdownCitation(
        citationId = citationId,
        sourceMessageId = "answer-0001",
        displayOrdinal = displayOrdinal,
        wikiId = wikiId,
        wikiVersion = wikiVersion,
        wikiTitle = wikiTitle,
        sourceTitle = sourceTitle,
        sectionPath = "$sourceTitle / 卷一",
        locatorLabel = "第 1 节",
        originalTextSnapshot = "可核对原文",
        originalTextSha256 = "a".repeat(64),
    )
}
