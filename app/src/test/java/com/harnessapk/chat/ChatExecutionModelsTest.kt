package com.harnessapk.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExecutionModelsTest {
    @Test
    fun nextExecutionSequenceIncrementsWithinConversation() {
        val entries = listOf(
            executionEntry(sequence = 1),
            executionEntry(sequence = 4),
        )

        assertEquals(5L, nextExecutionSequence(entries))
    }

    @Test
    fun requestHistoryExcludesOtherQueuedUserMessages() {
        val messages = listOf(
            chatMessage(id = "user-complete", role = MessageRole.USER, content = "已完成"),
            chatMessage(id = "assistant-complete", role = MessageRole.ASSISTANT, content = "已回复"),
            chatMessage(id = "user-current", role = MessageRole.USER, content = "当前"),
            chatMessage(id = "user-future", role = MessageRole.USER, content = "未来"),
        )
        val entries = listOf(
            executionEntry(id = "complete", userMessageId = "user-complete", status = ChatExecutionStatus.SUCCEEDED),
            executionEntry(id = "current", userMessageId = "user-current", status = ChatExecutionStatus.RUNNING),
            executionEntry(id = "future", userMessageId = "user-future", status = ChatExecutionStatus.QUEUED),
        )

        assertEquals(
            listOf("已完成", "已回复", "当前"),
            executionRequestHistory(messages, entries, currentEntryId = "current").map(ChatMessage::content),
        )
    }

    @Test
    fun interruptedRecoveryDoesNotChangeQueuedEntry() {
        assertEquals(
            ChatExecutionStatus.INTERRUPTED,
            recoveredExecutionStatus(ChatExecutionStatus.RUNNING),
        )
        assertEquals(
            ChatExecutionStatus.QUEUED,
            recoveredExecutionStatus(ChatExecutionStatus.QUEUED),
        )
    }

    @Test
    fun requestContextRoundTripsThroughPersistentSnapshot() {
        val context = ChatExecutionRequestContext(
            webSearchEnabled = true,
            webSearchSettings = com.harnessapk.websearch.WebSearchSettings(maxResults = 8),
        )

        assertEquals(context, decodeExecutionRequestContext(encodeExecutionRequestContext(context)))
    }

    @Test
    fun executionHistoryAddsCurrentUserMessageOnlyOnce() {
        val current = chatMessage(id = "user-current", role = MessageRole.USER, content = "当前")
        val history = listOf(
            chatMessage(id = "assistant", role = MessageRole.ASSISTANT, content = "历史回复"),
            current,
        )

        assertEquals(
            listOf("历史回复", "当前"),
            executionHistoryWithCurrent(history, current).map(ChatMessage::content),
        )
    }

    @Test
    fun foregroundNotificationSummarizesActiveConversations() {
        assertEquals("正在生成 2 个回复", foregroundNotificationText(activeCount = 2))
    }

    @Test
    fun finishedRunnerDoesNotRemoveItsReplacement() {
        val original = Any()
        val replacement = Any()

        assertFalse(shouldRemoveRunner(replacement, original))
        assertTrue(shouldRemoveRunner(original, original))
    }

    private fun executionEntry(
        id: String = "entry",
        userMessageId: String = "user",
        sequence: Long = 1L,
        status: ChatExecutionStatus = ChatExecutionStatus.QUEUED,
    ) = ChatExecutionEntry(
        id = id,
        conversationId = "conversation",
        userMessageId = userMessageId,
        assistantMessageId = null,
        targetAssistantMessageId = null,
        sequence = sequence,
        type = ChatExecutionType.NORMAL,
        status = status,
        providerId = null,
        model = null,
        reasoningEffort = ReasoningEffort.HIGH,
        requestContext = ChatExecutionRequestContext(),
        errorMessage = null,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun chatMessage(
        id: String,
        role: MessageRole,
        content: String,
    ) = ChatMessage(
        id = id,
        conversationId = "conversation",
        role = role,
        content = content,
        status = MessageStatus.SUCCEEDED,
        providerId = null,
        model = null,
        errorMessage = null,
    )
}
