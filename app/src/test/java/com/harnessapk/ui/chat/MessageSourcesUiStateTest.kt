package com.harnessapk.ui.chat

import com.harnessapk.chat.UiMessagePartDraft
import com.harnessapk.chat.UiMessagePartType
import com.harnessapk.wiki.MessageWikiCitation
import com.harnessapk.wiki.WikiCitationVerificationState
import com.harnessapk.wiki.WikiRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MessageSourcesUiStateTest {
    @Test
    fun wikiOnlySourcesUseOneCollapsedSummaryAndGroupByWiki() {
        val state = messageSourcesUiState(
            parts = listOf(sourcePart(UiMessagePartType.WIKI_SOURCES)),
            citations = listOf(
                citation(1, "二十四史", "汉书"),
                citation(2, "资治通鉴", "卷一"),
                citation(3, "二十四史", "史记"),
            ),
        )

        assertNotNull(state)
        assertEquals("引用 3 · 二十四史 2 · 资治通鉴 1", state!!.collapsedSummary)
        assertEquals(listOf("二十四史", "资治通鉴"), state.wikiGroups.map { it.wikiTitle })
        assertEquals(listOf(2, 1), state.wikiGroups.map { it.citations.size })
        assertEquals(emptyList<String>(), state.agentSources)
    }

    @Test
    fun agentOnlySourcesKeepExistingLabelsUnderPeopleMaterialsGroup() {
        val state = messageSourcesUiState(
            parts = listOf(
                sourcePart(
                    UiMessagePartType.AGENT_SOURCES,
                    "资料 1 · 年谱 · 1901 年\n资料 2 · 访谈 · 1936 年",
                ),
            ),
            citations = emptyList(),
        )

        assertNotNull(state)
        assertEquals("人物资料 2", state!!.collapsedSummary)
        assertEquals(listOf("资料 1 · 年谱 · 1901 年", "资料 2 · 访谈 · 1936 年"), state.agentSources)
    }

    @Test
    fun combinedSourcesAppendPeopleMaterialsOnlyWhenAgentEvidenceExists() {
        val state = messageSourcesUiState(
            parts = listOf(
                sourcePart(UiMessagePartType.WIKI_SOURCES),
                sourcePart(UiMessagePartType.AGENT_SOURCES, "资料 1 · 人物资料库 · 第一章"),
            ),
            citations = listOf(citation(1, "资治通鉴", "卷一")),
        )

        assertEquals("引用 1 · 资治通鉴 1 · 人物资料 1", state!!.collapsedSummary)
    }

    @Test
    fun projectSourcesLeadUnifiedSummaryWithoutBecomingWikiOrAgentEvidence() {
        val state = messageSourcesUiState(
            parts = listOf(
                sourcePart(
                    UiMessagePartType.PROJECT_SOURCES,
                    "依据 1 · context.md · 关键决策\n依据 2 · reports/m3.md · 测试",
                ),
                sourcePart(UiMessagePartType.WIKI_SOURCES),
            ),
            citations = listOf(citation(1, "工程 Wiki", "Room")),
        )

        assertEquals("项目依据 2 · 引用 1 · 工程 Wiki 1", state!!.collapsedSummary)
        assertEquals(2, state.projectSources.size)
        assertEquals(emptyList<String>(), state.agentSources)
    }

    @Test
    fun projectCitationTokensKeepStableOneTapEvidenceMapping() {
        val state = messageSourcesUiState(
            parts = listOf(
                sourcePart(
                    type = UiMessagePartType.PROJECT_SOURCES,
                    content = "依据 1 · context.md\n依据 2 · report.md",
                    metadata = mapOf(
                        "evidenceIds" to "[\"evidence-1\",\"evidence-2\"]",
                        "tokens" to "[\"⟦P1⟧\",\"⟦P2⟧\"]",
                    ),
                ),
            ),
            citations = emptyList(),
        )

        assertEquals(listOf("⟦P1⟧", "⟦P2⟧"), state!!.projectTokens)
        assertEquals(listOf("evidence-1", "evidence-2"), state.projectEvidenceIds)
    }

    @Test
    fun validProjectCitationTokensBecomeInlineLinksWithoutChangingUnknownTokens() {
        val state = MessageSourcesUiState(
            wikiGroups = emptyList(),
            agentSources = emptyList(),
            projectEvidenceIds = listOf("evidence-1"),
            projectTokens = listOf("⟦P1⟧"),
        )

        assertEquals(
            "已确认[⟦P1⟧](harness-project://evidence/evidence-1)，未知⟦P9⟧",
            linkProjectCitationTokens("已确认⟦P1⟧，未知⟦P9⟧", state),
        )
    }

    private fun sourcePart(
        type: UiMessagePartType,
        content: String = "引用",
        metadata: Map<String, String> = emptyMap(),
    ) = UiMessagePartDraft(
        index = 0,
        type = type,
        content = content,
        metadata = metadata,
        stable = true,
    )

    private fun citation(ordinal: Int, wikiTitle: String, sourceTitle: String) = MessageWikiCitation(
        id = "00000000-0000-4000-8000-${ordinal.toString().padStart(12, '0')}",
        messageId = "message-1",
        displayOrdinal = ordinal,
        ref = WikiRef("history.$ordinal", 1),
        wikiTitle = wikiTitle,
        documentId = "document-$ordinal",
        sectionId = "section-$ordinal",
        chunkId = "chunk-$ordinal",
        sourceTitle = sourceTitle,
        sectionPath = "第一章",
        locatorLabel = "第 $ordinal 段",
        originalTextSnapshot = "原文 $ordinal",
        originalTextSha256 = "a".repeat(64),
        answerRangesJson = "[[0,1]]",
        verificationState = WikiCitationVerificationState.VERIFIED,
        createdAt = 0L,
    )
}
