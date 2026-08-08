package com.harnessapk.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationContextBarTest {
    @Test
    fun summaryKeepsTheFourPrimaryContextDimensionsInOneLine() {
        val summary = ConversationContextSummary(
            projectName = "健康计划",
            identityName = "营养顾问",
            enabledWikiCount = 2,
            model = "gpt-5.6-terra",
            reasoningEffortLabel = "超高",
            webSearchEnabled = true,
            contextPercent = 18,
        )

        assertEquals("gpt-5.6-terra · 超高", summary.primaryText())
        assertEquals("健康计划 · 营养顾问 · Wiki 2 · 联网", summary.secondaryText())
    }

    @Test
    fun defaultEmptyContextDoesNotShowPlaceholderMetadataOrContextUsage() {
        val summary = ConversationContextSummary(
            projectName = null,
            identityName = "普通助手",
            enabledWikiCount = 0,
            model = "gpt-5.6-terra",
            reasoningEffortLabel = "超高",
            webSearchEnabled = false,
            contextPercent = 0,
        )

        assertEquals("gpt-5.6-terra · 超高", summary.primaryText())
        assertEquals("", summary.secondaryText())
    }

    @Test
    fun projectChangeCreatesContinuationAfterFirstUserMessage() {
        assertEquals(
            ConversationProjectChange.KEEP_CURRENT,
            conversationProjectChange("project-a", "project-a", hasUserMessage = true),
        )
        assertEquals(
            ConversationProjectChange.UPDATE_CURRENT,
            conversationProjectChange("project-a", "project-b", hasUserMessage = false),
        )
        assertEquals(
            ConversationProjectChange.CONTINUE_IN_NEW,
            conversationProjectChange("project-a", "project-b", hasUserMessage = true),
        )
    }
}
