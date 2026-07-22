package com.harnessapk.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WikiMarkdownProposalValidatorTest {
    private val citationId = "2f17f6dc-4fe9-4d3a-beb2-9ef46c4379ca"
    private val citationSet = WikiMarkdownCitationSet(listOf(citation()))

    @Test
    fun proposalValidatorRejectsHarnessOnlyLinksAndInventedFootnotes() {
        assertThrows(WikiMarkdownValidationException::class.java) {
            WikiMarkdownProposalValidator.validate(
                proposal("[来源](harness-wiki://citation/$citationId)"),
                citationSet,
            )
        }
        assertThrows(WikiMarkdownValidationException::class.java) {
            WikiMarkdownProposalValidator.validate(
                proposal("结论[^hwiki-invented-9]"),
                citationSet,
            )
        }
    }

    @Test
    fun validatorRejectsAlteredDefinitionAndRemovesUnusedKnownDefinition() {
        assertThrows(WikiMarkdownValidationException::class.java) {
            WikiMarkdownProposalValidator.validate(
                proposal(
                    """
                    结论[^hwiki-a1b2c3d4-1]

                    [^hwiki-a1b2c3d4-1]: 《伪书》· 卷第一；资治通鉴 v1；周威烈王二十三年。
                    """.trimIndent(),
                ),
                citationSet,
            )
        }

        val validated = WikiMarkdownProposalValidator.validate(
            proposal(
                """
                没有使用 Wiki 的普通笔记。[^user-note]

                [^user-note]: 用户已有脚注。
                [^hwiki-a1b2c3d4-1]: 《资治通鉴》· 卷第一；资治通鉴 v1；周威烈王二十三年。
                """.trimIndent(),
            ),
            citationSet,
        )

        assertEquals(
            """
            没有使用 Wiki 的普通笔记。[^user-note]

            [^user-note]: 用户已有脚注。
            """.trimIndent(),
            validated.markdown,
        )
    }

    private fun proposal(markdown: String) = MarkdownUpdateProposal(
        operation = MarkdownUpdateOperation.CREATE,
        path = "notes/history.md",
        title = "历史笔记",
        reason = "测试",
        markdown = markdown,
    )

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
