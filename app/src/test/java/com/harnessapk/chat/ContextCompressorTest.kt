package com.harnessapk.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressorTest {
    @Test
    fun keepsFullHistoryWhenItFitsBudget() {
        val compressor = ContextCompressor(
            ContextCompressionPolicy(maxRequestChars = 2_000, recentTargetChars = 1_200),
        )
        val messages = listOf(
            userMessage("u1", "先记住我喜欢中文回答", createdAt = 1L),
            assistantMessage("a1", "好的，我会用中文回答", createdAt = 2L),
            userMessage("u2", "今天测试一下", createdAt = 3L),
        )

        val result = compressor.prepare(
            conversationId = "c1",
            messages = messages,
            currentUserMessageId = "u2",
            currentImageDataUrls = emptyList(),
            existingMemory = null,
            nowMillis = 10L,
        )

        assertFalse(result.compressed)
        assertEquals(null, result.memoryToSave)
        assertEquals(listOf("user", "assistant", "user"), result.messages.map { it.role })
        assertEquals(listOf("先记住我喜欢中文回答", "好的，我会用中文回答", "今天测试一下"), result.messages.map { it.text })
    }

    @Test
    fun compressesOlderMessagesIntoLocalMemoryAndKeepsRecentTail() {
        val compressor = ContextCompressor(
            ContextCompressionPolicy(maxRequestChars = 180, recentTargetChars = 70, memoryMaxChars = 220),
        )
        val messages = listOf(
            userMessage("u1", "记住：默认用 Kimi，模型是 kimi-k2.7-code。".repeat(3), createdAt = 1L),
            assistantMessage("a1", "已记录这个默认模型。".repeat(3), createdAt = 2L),
            userMessage("u2", "再记住：OpenAI 代理地址是 happycode。".repeat(3), createdAt = 3L),
            assistantMessage("a2", "我会保留这个代理地址。".repeat(3), createdAt = 4L),
            userMessage("u3", "现在回答一个很短的问题", createdAt = 5L),
        )

        val result = compressor.prepare(
            conversationId = "c1",
            messages = messages,
            currentUserMessageId = "u3",
            currentImageDataUrls = emptyList(),
            existingMemory = null,
            nowMillis = 20L,
        )

        assertTrue(result.compressed)
        val memoryToSave = requireNotNull(result.memoryToSave)
        assertTrue(memoryToSave.summary.contains("Kimi"))
        assertTrue(memoryToSave.summary.contains("happycode"))
        assertEquals("system", result.messages.first().role)
        assertTrue(result.messages.first().text.contains("本机保存的早期对话记忆"))
        assertEquals("现在回答一个很短的问题", result.messages.last().text)
    }

    @Test
    fun usesExistingMemoryAndOnlyAddsNewlyCompressedMessages() {
        val compressor = ContextCompressor(
            ContextCompressionPolicy(maxRequestChars = 100, recentTargetChars = 45, memoryMaxChars = 260),
        )
        val existing = ConversationMemory(
            conversationId = "c1",
            summary = "- 用户偏好：回答要简洁。",
            coveredThroughMessageId = "a1",
            coveredThroughCreatedAt = 2L,
            compressedMessageCount = 2,
            updatedAt = 10L,
        )
        val messages = listOf(
            userMessage("u1", "回答要简洁", createdAt = 1L),
            assistantMessage("a1", "已记录", createdAt = 2L),
            userMessage("u2", "新记忆：截图要先压缩再上传。".repeat(4), createdAt = 3L),
            assistantMessage("a2", "已记录截图压缩要求。".repeat(4), createdAt = 4L),
            userMessage("u3", "继续", createdAt = 5L),
        )

        val result = compressor.prepare(
            conversationId = "c1",
            messages = messages,
            currentUserMessageId = "u3",
            currentImageDataUrls = emptyList(),
            existingMemory = existing,
            nowMillis = 30L,
        )

        assertTrue(result.compressed)
        assertTrue(result.messages.first().text.contains("回答要简洁"))
        assertTrue(result.messages.first().text.contains("截图"))
        assertFalse(result.memoryToSave!!.summary.contains("回答要简洁\n- 用户偏好：回答要简洁"))
    }

    @Test
    fun currentUserMessageIsNotDuplicatedAndKeepsImagesOnCurrentMessage() {
        val compressor = ContextCompressor(
            ContextCompressionPolicy(maxRequestChars = 2_000, recentTargetChars = 1_200),
        )
        val messages = listOf(
            userMessage("u1", "第一条", createdAt = 1L),
            assistantMessage("a1", "第一条回复", createdAt = 2L),
            userMessage("u2", "带截图的问题", createdAt = 3L),
            assistantMessage("pending", "", status = MessageStatus.PENDING, createdAt = 4L),
        )

        val result = compressor.prepare(
            conversationId = "c1",
            messages = messages,
            currentUserMessageId = "u2",
            currentImageDataUrls = listOf("data:image/jpeg;base64,abc"),
            existingMemory = null,
            nowMillis = 20L,
        )

        assertEquals(listOf("第一条", "第一条回复", "带截图的问题"), result.messages.map { it.text })
        assertEquals(listOf("data:image/jpeg;base64,abc"), result.messages.last().imageDataUrls)
    }

    @Test
    fun forceCompressionCompactsOlderMessagesEvenWhenRequestFitsBudget() {
        val compressor = ContextCompressor(
            ContextCompressionPolicy(maxRequestChars = 2_000, recentTargetChars = 5, memoryMaxChars = 220),
        )
        val messages = listOf(
            userMessage("u1", "记住：默认用中文回答。".repeat(3), createdAt = 1L),
            assistantMessage("a1", "已记录中文偏好。".repeat(3), createdAt = 2L),
            userMessage("u2", "继续", createdAt = 3L),
        )

        val result = compressor.prepare(
            conversationId = "c1",
            messages = messages,
            currentUserMessageId = "u2",
            currentImageDataUrls = emptyList(),
            existingMemory = null,
            nowMillis = 20L,
            force = true,
        )

        assertTrue(result.compressed)
        assertEquals(2, result.memoryToSave!!.compressedMessageCount)
        assertTrue(result.memoryToSave.summary.contains("中文"))
        assertEquals("继续", result.messages.last().text)
    }

    @Test
    fun forceCompressionIsNoopWhenThereAreNoOlderMessagesToCompact() {
        val compressor = ContextCompressor(
            ContextCompressionPolicy(maxRequestChars = 2_000, recentTargetChars = 1_200),
        )

        val result = compressor.prepare(
            conversationId = "c1",
            messages = listOf(userMessage("u1", "只有一条消息", createdAt = 1L)),
            currentUserMessageId = "u1",
            currentImageDataUrls = emptyList(),
            existingMemory = null,
            nowMillis = 20L,
            force = true,
        )

        assertFalse(result.compressed)
        assertEquals(null, result.memoryToSave)
    }

    @Test
    fun compressionEventTextShowsManualAndAutomaticActions() {
        val memory = ConversationMemory(
            conversationId = "c1",
            summary = "summary",
            coveredThroughMessageId = "a1",
            coveredThroughCreatedAt = 2L,
            compressedMessageCount = 6,
            updatedAt = 10L,
        )

        assertEquals("已自动压缩早期 6 条消息，保留最近上下文。", contextCompressionEventText(CompressionTrigger.AUTO, memory))
        assertEquals("已手动压缩早期 6 条消息，保留最近上下文。", contextCompressionEventText(CompressionTrigger.MANUAL, memory))
    }

    @Test
    fun shouldRecordCompressionEventOnlyWhenCoverageAdvances() {
        val existing = ConversationMemory(
            conversationId = "c1",
            summary = "old",
            coveredThroughMessageId = "a1",
            coveredThroughCreatedAt = 2L,
            compressedMessageCount = 2,
            updatedAt = 10L,
        )
        val sameCoverage = existing.copy(updatedAt = 20L)
        val advanced = existing.copy(coveredThroughMessageId = "a2", coveredThroughCreatedAt = 4L, compressedMessageCount = 4)

        assertTrue(shouldRecordCompressionEvent(existingMemory = null, nextMemory = existing))
        assertFalse(shouldRecordCompressionEvent(existingMemory = existing, nextMemory = sameCoverage))
        assertTrue(shouldRecordCompressionEvent(existingMemory = existing, nextMemory = advanced))
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

    private fun assistantMessage(
        id: String,
        content: String,
        status: MessageStatus = MessageStatus.SUCCEEDED,
        createdAt: Long,
    ): ChatMessage = ChatMessage(
        id = id,
        conversationId = "c1",
        role = MessageRole.ASSISTANT,
        content = content,
        status = status,
        providerId = "provider",
        model = "model",
        errorMessage = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
