package com.harnessapk.ui.chat

import com.harnessapk.chat.ChatExecutionEntry
import com.harnessapk.chat.ChatExecutionPhase
import com.harnessapk.chat.ChatExecutionRequestContext
import com.harnessapk.chat.ChatExecutionStatus
import com.harnessapk.chat.ChatExecutionType
import com.harnessapk.chat.defaultReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingStatusLabelsTest {
    @Test
    fun runningStepsUsePresentTenseLabels() {
        assertEquals("正在联网搜索", processingStepLabel(entry(phase = ChatExecutionPhase.SEARCHING_WEB)))
        assertEquals("正在检索资料", processingStepLabel(entry(phase = ChatExecutionPhase.RETRIEVING_KNOWLEDGE)))
        assertEquals("已发给模型，等待回复", processingStepLabel(entry(phase = ChatExecutionPhase.GENERATING)))
    }

    @Test
    fun retryBackoffIsExplainedOnQueuedEntry() {
        val label = processingStepLabel(entry(status = ChatExecutionStatus.QUEUED, retries = 2))
        assertEquals("网络中断，正在自动重试（第 2/4 次）", label)
    }

    @Test
    fun userSideStatusOnlyBeforeAssistantBubbleExists() {
        assertTrue(userSidePendingStatusVisible(entry(phase = ChatExecutionPhase.SEARCHING_WEB)))
        assertFalse(userSidePendingStatusVisible(entry(assistantMessageId = "a1")))
        assertFalse(userSidePendingStatusVisible(entry(status = ChatExecutionStatus.QUEUED)))
        assertTrue(userSidePendingStatusVisible(entry(status = ChatExecutionStatus.QUEUED, retries = 1)))
    }

    @Test
    fun slowHintAppearsOnlyWhileWaitingForModel() {
        val generating = entry(phase = ChatExecutionPhase.GENERATING)
        assertNull(processingSlowHint(generating, elapsedSeconds = 5))
        assertTrue(processingSlowHint(generating, elapsedSeconds = 20)!!.contains("思考"))
        assertTrue(processingSlowHint(generating, elapsedSeconds = 50)!!.contains("推理强度"))
        assertNull(processingSlowHint(entry(phase = ChatExecutionPhase.SEARCHING_WEB), elapsedSeconds = 50))
    }

    private fun entry(
        status: ChatExecutionStatus = ChatExecutionStatus.RUNNING,
        phase: ChatExecutionPhase? = null,
        retries: Int = 0,
        assistantMessageId: String? = null,
    ) = ChatExecutionEntry(
        id = "e1",
        conversationId = "c1",
        userMessageId = "u1",
        assistantMessageId = assistantMessageId,
        targetAssistantMessageId = null,
        sequence = 1L,
        type = ChatExecutionType.NORMAL,
        status = status,
        providerId = null,
        model = null,
        reasoningEffort = defaultReasoningEffort(),
        requestContext = ChatExecutionRequestContext(),
        phase = phase,
        automaticRetryCount = retries,
        errorMessage = null,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
