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
            webSearchEnabled = true,
            contextPercent = 18,
        )

        assertEquals("健康计划 · 营养顾问 · Wiki 2 · gpt-5.6-terra", summary.primaryText())
        assertEquals("联网 · 上下文 18%", summary.secondaryText())
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
