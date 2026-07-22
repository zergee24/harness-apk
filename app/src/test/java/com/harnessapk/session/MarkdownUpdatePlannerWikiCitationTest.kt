package com.harnessapk.session

import com.harnessapk.wiki.WikiRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownUpdatePlannerWikiCitationTest {
    @Test
    fun planningMessagesConvertRepeatedAssistantCitationAndExposeOneExactFootnote() {
        val citation = citation()
        val messages = buildMarkdownUpdatePlanningMessages(
            projectName = "史料笔记",
            projectContext = "",
            markdowns = emptyList(),
            assistantMarkdown = "甲[资料一](harness-wiki://citation/${citation.citationId})，乙[资料二](harness-wiki://citation/${citation.citationId})。",
            wikiCitations = WikiMarkdownCitationSet(listOf(citation)),
        )

        val prompt = messages.last().text
        assertTrue(prompt.contains("甲[^hwiki-answer00-1]，乙[^hwiki-answer00-1]。"))
        assertEquals(2, prompt.lines().count { it.startsWith("[^hwiki-answer00-1]:") })
        assertTrue(prompt.contains("可用 Wiki 脚注："))
        assertFalse(prompt.contains(citation.citationId))
        assertFalse(prompt.contains("harness-wiki://"))
        assertTrue(messages.first().text.contains("只能使用“可用 Wiki 脚注”中给出的来源"))
    }

    @Test
    fun planningMessagesKeepTwoWikiFootnotesAndDescribeMissingComparisonEvidence() {
        val first = citation(
            citationId = "00000000-0000-0000-0000-000000000001",
            displayOrdinal = 1,
            wikiId = "twenty-four-histories",
            wikiVersion = 2,
            wikiTitle = "二十四史",
            sourceTitle = "汉书",
        )
        val second = citation(
            citationId = "00000000-0000-0000-0000-000000000002",
            displayOrdinal = 2,
            wikiId = "zizhi-tongjian",
            wikiVersion = 3,
            wikiTitle = "资治通鉴",
            sourceTitle = "资治通鉴",
            sectionPath = "资治通鉴 / 卷一",
        )
        val secondRef = WikiRef(second.wikiId, second.wikiVersion)
        val messages = buildMarkdownUpdatePlanningMessages(
            projectName = "史料笔记",
            projectContext = "",
            markdowns = emptyList(),
            assistantMarkdown = "比较[甲](harness-wiki://citation/${first.citationId})与[乙](harness-wiki://citation/${second.citationId})。",
            wikiCitations = WikiMarkdownCitationSet(listOf(first, second)),
            wikiCoverage = WikiEvidenceCoverage(
                requestedComparisonRefs = setOf(WikiRef(first.wikiId, first.wikiVersion), secondRef),
                queriedRefs = setOf(WikiRef(first.wikiId, first.wikiVersion), secondRef),
                verifiedCitationCounts = mapOf(WikiRef(first.wikiId, first.wikiVersion) to 1),
                missingRefs = setOf(secondRef),
            ),
        )

        val prompt = messages.last().text
        assertTrue(prompt.contains("[^hwiki-answer00-1]: 《汉书》· 卷一；二十四史 v2；第 1 节。"))
        assertTrue(prompt.contains("[^hwiki-answer00-2]: 《资治通鉴》· 卷一；资治通鉴 v3；第 1 节。"))
        assertTrue(prompt.contains("比较覆盖信息："))
        assertTrue(prompt.contains("请求比较 Wiki：2"))
        assertTrue(prompt.contains("缺少可靠证据：1"))
        assertTrue(messages.first().text.contains("当前检索未找到依据"))
    }

    @Test
    fun planningMessagesKeepMetadataAsReadableMarkdownText() {
        val citation = citation(
            wikiTitle = "资料库 [甲]",
            sourceTitle = "《史书 [卷一]》",
            sectionPath = "史书 [卷一] / 本纪 [上]",
            locatorLabel = "第 1 节 [原文]",
        )

        val messages = buildMarkdownUpdatePlanningMessages(
            projectName = "史料笔记",
            projectContext = "",
            markdowns = emptyList(),
            assistantMarkdown = "摘录[来源](harness-wiki://citation/${citation.citationId})。",
            wikiCitations = WikiMarkdownCitationSet(listOf(citation)),
        )

        assertTrue(
            messages.last().text.contains(
                "[^hwiki-answer00-1]: 《史书 ［卷一］》· 本纪 ［上］；资料库 ［甲］ v1；第 1 节 ［原文］。",
            ),
        )
    }

    @Test
    fun fileChangePlanningConvertsOnlyCitedConversationContext() {
        val citation = citation()
        val messages = buildMarkdownFileChangePlanningMessages(
            projectName = "史料笔记",
            projectContext = "",
            markdowns = emptyList(),
            userRequest = "把上面的比较写进 notes.md，链接保留 https://example.com。",
            conversationContext = "助手：可参考[这段原文](harness-wiki://citation/${citation.citationId})。",
            wikiCitations = WikiMarkdownCitationSet(listOf(citation)),
        )

        val prompt = messages.last().text
        assertTrue(prompt.contains("助手：可参考[^hwiki-answer00-1]。"))
        assertTrue(prompt.contains("本轮用户文件变更请求：\n把上面的比较写进 notes.md，链接保留 https://example.com。"))
        assertFalse(prompt.contains(citation.citationId))
        assertFalse(prompt.contains("harness-wiki://"))
    }

    @Test
    fun parsedPlannerResponseRejectsInventedWikiFootnote() {
        val error = assertThrows(MarkdownUpdatePlanningException::class.java) {
            parseAndValidateMarkdownUpdatePlanResponse(
                response = plannerResponse(
                    "# 笔记\\n\\n结论[^hwiki-invented]\\n\\n[^hwiki-invented]: 伪造来源。",
                ),
                wikiCitations = WikiMarkdownCitationSet(listOf(citation())),
            )
        }

        assertTrue(error.message.orEmpty().contains("未授权的 Wiki 脚注"))
    }

    @Test
    fun parsedPlannerResponseRejectsInternalWikiLink() {
        val citation = citation()
        val error = assertThrows(MarkdownUpdatePlanningException::class.java) {
            parseAndValidateMarkdownUpdatePlanResponse(
                response = plannerResponse(
                    "# 笔记\\n\\n结论[来源](harness-wiki://citation/${citation.citationId})",
                ),
                wikiCitations = WikiMarkdownCitationSet(listOf(citation)),
            )
        }

        assertTrue(error.message.orEmpty().contains("应用内部引用链接"))
    }

    private fun citation(
        citationId: String = "00000000-0000-0000-0000-000000000001",
        displayOrdinal: Int = 1,
        wikiId: String = "histories",
        wikiVersion: Int = 1,
        wikiTitle: String = "二十四史",
        sourceTitle: String = "汉书",
        sectionPath: String = "汉书 / 卷一",
        locatorLabel: String = "第 1 节",
    ): WikiMarkdownCitation = WikiMarkdownCitation(
        citationId = citationId,
        sourceMessageId = "answer-0001",
        displayOrdinal = displayOrdinal,
        wikiId = wikiId,
        wikiVersion = wikiVersion,
        wikiTitle = wikiTitle,
        sourceTitle = sourceTitle,
        sectionPath = sectionPath,
        locatorLabel = locatorLabel,
        originalTextSnapshot = "可核对原文",
        originalTextSha256 = "a".repeat(64),
    )

    private fun plannerResponse(markdown: String): String =
        """
        {
          "updates": [
            {
              "operation": "create",
              "path": "notes.md",
              "title": "笔记",
              "reason": "测试",
              "markdown": "$markdown"
            }
          ]
        }
        """.trimIndent()
}
