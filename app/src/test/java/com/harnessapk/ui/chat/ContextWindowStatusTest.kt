package com.harnessapk.ui.chat

import com.harnessapk.chat.ChatMessage
import com.harnessapk.chat.ContextCompressionPolicy
import com.harnessapk.chat.ConversationMemory
import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import com.harnessapk.provider.ModelConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextWindowStatusTest {
    @Test
    fun contextWindowStatusCountsRawMessagesWhenThereIsNoMemory() {
        val status = contextWindowStatus(
            messages = listOf(
                userMessage("u1", "a".repeat(1_200), createdAt = 1L),
                assistantMessage("a1", "b".repeat(800), createdAt = 2L),
            ),
            memory = null,
            policy = ContextCompressionPolicy(maxRequestChars = 18_000),
        )

        assertEquals(2_000, status.usedChars)
        assertEquals(18_000, status.maxChars)
        assertEquals(0, status.compressedMessageCount)
        assertEquals("上下文 2.0k / 18.0k", contextWindowStatusText(status))
    }

    @Test
    fun contextWindowStatusUsesSelectedModelConfigLimit() {
        val status = contextWindowStatus(
            messages = listOf(userMessage("u1", "a".repeat(1_200), createdAt = 1L)),
            memory = null,
            modelConfig = ModelConfig(id = "gpt-5.5", contextWindowTokens = 400_000),
        )

        assertEquals(400_000, status.maxTokens)
        assertEquals(70, status.compressionThresholdPercent)
        assertEquals("上下文 1.2k / 400.0k", contextWindowStatusText(status))
        assertEquals("0% · 1.2k", contextWindowStatusCompactText(status))
    }

    @Test
    fun contextWindowStatusTextFormatsMillionTokenModels() {
        val status = contextWindowStatus(
            messages = listOf(userMessage("u1", "a".repeat(1_200), createdAt = 1L)),
            memory = null,
            modelConfig = ModelConfig(id = "gpt-5.5", contextWindowTokens = 1_000_000),
        )

        assertEquals("上下文 1.2k / 1.0M", contextWindowStatusText(status))
    }

    @Test
    fun contextWindowUsagePercentIsClampedForCompactIndicator() {
        val status = contextWindowStatus(
            messages = listOf(userMessage("u1", "a".repeat(21_000), createdAt = 1L)),
            memory = null,
            policy = ContextCompressionPolicy(maxRequestChars = 18_000),
        )

        assertEquals(100, contextWindowUsagePercent(status))
        assertEquals(1f, contextWindowUsageProgress(status))
        assertEquals("100% · 21.0k", contextWindowStatusCompactText(status))
    }

    @Test
    fun manualCompressionIsAvailableOnlyAtOrAboveThreshold() {
        assertEquals(
            false,
            contextWindowCanManualCompress(
                ContextWindowStatus(
                    usedTokens = 69_999,
                    maxTokens = 100_000,
                    compressionThresholdPercent = 70,
                    compressedMessageCount = 0,
                ),
            ),
        )
        assertEquals(
            true,
            contextWindowCanManualCompress(
                ContextWindowStatus(
                    usedTokens = 70_000,
                    maxTokens = 100_000,
                    compressionThresholdPercent = 70,
                    compressedMessageCount = 0,
                ),
            ),
        )
    }

    @Test
    fun contextWindowStatusCountsMemoryAndOnlyNewMessagesAfterCompression() {
        val memory = ConversationMemory(
            conversationId = "c1",
            summary = "m".repeat(500),
            coveredThroughMessageId = "a1",
            coveredThroughCreatedAt = 2L,
            compressedMessageCount = 2,
            updatedAt = 10L,
        )
        val status = contextWindowStatus(
            messages = listOf(
                userMessage("u1", "a".repeat(1_200), createdAt = 1L),
                assistantMessage("a1", "b".repeat(800), createdAt = 2L),
                userMessage("u2", "c".repeat(300), createdAt = 3L),
            ),
            memory = memory,
            policy = ContextCompressionPolicy(maxRequestChars = 18_000),
        )

        assertEquals(800, status.usedChars)
        assertEquals(2, status.compressedMessageCount)
        assertEquals("上下文 0.8k / 18.0k · 已压缩 2 条", contextWindowStatusText(status))
    }

    private fun userMessage(id: String, content: String, createdAt: Long): ChatMessage = ChatMessage(
        id = id,
        conversationId = "c1",
        role = MessageRole.USER,
        content = content,
        status = MessageStatus.SUCCEEDED,
        providerId = null,
        model = null,
        errorMessage = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun assistantMessage(id: String, content: String, createdAt: Long): ChatMessage = ChatMessage(
        id = id,
        conversationId = "c1",
        role = MessageRole.ASSISTANT,
        content = content,
        status = MessageStatus.SUCCEEDED,
        providerId = "provider",
        model = "model",
        errorMessage = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
