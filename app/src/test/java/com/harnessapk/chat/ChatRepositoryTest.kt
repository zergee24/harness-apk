package com.harnessapk.chat

import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.ConversationDao
import com.harnessapk.storage.ConversationEntity
import com.harnessapk.storage.ConversationMemoryDao
import com.harnessapk.storage.ConversationMemoryEntity
import com.harnessapk.storage.MessageAttachmentDao
import com.harnessapk.storage.MessageAttachmentEntity
import com.harnessapk.storage.MessageDao
import com.harnessapk.storage.MessageEntity
import com.harnessapk.storage.MessagePartDao
import com.harnessapk.storage.MessagePartEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRepositoryTest {
    @Test
    fun insertUserMessageRenamesDefaultConversationFromContent() = runTest {
        val conversationDao = FakeConversationDao()
        var now = 10L
        val repository = repository(conversationDao, TimeProvider { now })
        val conversationId = repository.createConversation()

        now = 20L
        repository.insertUserMessage(
            conversationId = conversationId,
            content = "帮我规划一份周末家庭露营清单，需要带孩子用品",
            attachments = emptyList(),
        )

        val conversation = conversationDao.findById(conversationId)!!
        assertEquals("帮我规划一份周末家庭露营清单，需要带孩子", conversation.title)
        assertEquals(20L, conversation.updatedAt)
    }

    @Test
    fun insertUserMessageDoesNotOverrideCustomConversationTitle() = runTest {
        val conversationDao = FakeConversationDao()
        val repository = repository(conversationDao, TimeProvider { 10L })
        val conversationId = repository.createConversation("家庭计划")

        repository.insertUserMessage(
            conversationId = conversationId,
            content = "这个标题不要被自动覆盖",
            attachments = emptyList(),
        )

        assertEquals("家庭计划", conversationDao.findById(conversationId)!!.title)
    }

    @Test
    fun updateConversationTitleTrimsAndPersistsCustomTitle() = runTest {
        val conversationDao = FakeConversationDao()
        val repository = repository(conversationDao, TimeProvider { 30L })
        val conversationId = repository.createConversation()

        repository.updateConversationTitle(conversationId, "  Kimi 错误排查  ")

        val conversation = conversationDao.findById(conversationId)!!
        assertEquals("Kimi 错误排查", conversation.title)
        assertEquals(30L, conversation.updatedAt)
    }

    @Test
    fun updateConversationPromptPersistsAgentPromptFields() = runTest {
        val conversationDao = FakeConversationDao()
        val repository = repository(conversationDao, TimeProvider { 35L })
        val conversationId = repository.createConversation()

        repository.updateConversationPrompt(
            id = conversationId,
            original = "帮我审产品方案",
            optimized = "你是产品方案评审助手，按目标、约束、风险输出。",
            final = "你是严格的产品方案评审助手。",
        )

        val conversation = repository.conversation(conversationId)!!
        assertEquals("帮我审产品方案", conversation.promptOriginal)
        assertEquals("你是产品方案评审助手，按目标、约束、风险输出。", conversation.promptOptimized)
        assertEquals("你是严格的产品方案评审助手。", conversation.promptFinal)
        assertEquals(35L, conversation.updatedAt)
    }

    @Test
    fun updateConversationPromptDoesNotTouchConversationWhenPromptIsUnchanged() = runTest {
        val conversationDao = FakeConversationDao()
        var now = 35L
        val repository = repository(conversationDao, TimeProvider { now })
        val conversationId = repository.createConversation()
        repository.updateConversationPrompt(
            id = conversationId,
            original = "帮我审产品方案",
            optimized = "结构化优化结果",
            final = "最终提示词",
        )

        now = 70L
        repository.updateConversationPrompt(
            id = conversationId,
            original = "帮我审产品方案",
            optimized = "结构化优化结果",
            final = "最终提示词",
        )

        assertEquals(35L, repository.conversation(conversationId)!!.updatedAt)
    }

    @Test
    fun createConversationPersistsProjectIdWhenProvided() = runTest {
        val repository = repository(FakeConversationDao(), TimeProvider { 36L })

        val conversationId = repository.createConversation(
            title = "移动端 Harness · 项目会话",
            projectId = "project-1",
        )

        val conversation = repository.conversation(conversationId)!!
        assertEquals("project-1", conversation.projectId)
    }

    @Test
    fun markAssistantCancelledPersistsCancelledStatusWithoutError() = runTest {
        val repository = repository(FakeConversationDao(), TimeProvider { 40L })
        val conversationId = repository.createConversation()
        val assistantId = repository.insertAssistantPending(
            conversationId = conversationId,
            providerId = "openai",
            model = "gpt-5.5",
        )

        repository.markAssistantCancelled(assistantId)

        val message = repository.listMessages(conversationId).single()
        assertEquals(MessageStatus.CANCELLED, message.status)
        assertEquals(null, message.errorMessage)
    }

    @Test
    fun markAssistantCancelledStabilizesPersistedStreamingParts() = runTest {
        val repository = repository(FakeConversationDao(), TimeProvider { 40L })
        val conversationId = repository.createConversation()
        val assistantId = repository.insertAssistantPending(
            conversationId = conversationId,
            providerId = "openai",
            model = "gpt-5.5",
        )
        repository.replaceMessagePartsFromSnapshot(
            assistantId,
            StreamingMessageSnapshot(
                status = MessageStatus.STREAMING,
                parts = listOf(
                    UiMessagePartDraft(
                        index = 0,
                        type = UiMessagePartType.TEXT,
                        content = "正在回复",
                        stable = false,
                    ),
                ),
            ),
        )

        repository.markAssistantCancelled(assistantId)

        assertEquals(MessageStatus.CANCELLED, repository.listMessages(conversationId).single().status)
        assertEquals(true, repository.listMessageParts(assistantId).single().stable)
    }

    @Test
    fun appendAssistantTextDoesNotReviveCancelledMessage() = runTest {
        val repository = repository(FakeConversationDao(), TimeProvider { 41L })
        val conversationId = repository.createConversation()
        val assistantId = repository.insertAssistantPending(
            conversationId = conversationId,
            providerId = "openai",
            model = "gpt-5.5",
        )
        repository.markAssistantCancelled(assistantId)

        repository.appendAssistantText(assistantId, "late delta")

        val message = repository.listMessages(conversationId).single()
        assertEquals(MessageStatus.CANCELLED, message.status)
        assertEquals("", message.content)
    }

    @Test
    fun markAssistantSucceededDoesNotOverrideCancelledMessage() = runTest {
        val repository = repository(FakeConversationDao(), TimeProvider { 42L })
        val conversationId = repository.createConversation()
        val assistantId = repository.insertAssistantPending(
            conversationId = conversationId,
            providerId = "openai",
            model = "gpt-5.5",
        )
        repository.markAssistantCancelled(assistantId)

        repository.markAssistantSucceeded(assistantId)

        assertEquals(MessageStatus.CANCELLED, repository.listMessages(conversationId).single().status)
    }

    @Test
    fun cancelActiveAssistantMessagesMarksPendingAndStreamingMessagesCancelled() = runTest {
        val repository = repository(FakeConversationDao(), TimeProvider { 43L })
        val conversationId = repository.createConversation()
        val pendingId = repository.insertAssistantPending(conversationId, "openai", "gpt-5.5")
        val streamingId = repository.insertAssistantPending(conversationId, "openai", "gpt-5.5")
        repository.appendAssistantText(streamingId, "正在回复")
        val doneId = repository.insertAssistantPending(conversationId, "openai", "gpt-5.5")
        repository.markAssistantSucceeded(doneId)

        val cancelledCount = repository.cancelActiveAssistantMessages(conversationId)

        val messages = repository.listMessages(conversationId).associateBy { it.id }
        assertEquals(2, cancelledCount)
        assertEquals(MessageStatus.CANCELLED, messages[pendingId]!!.status)
        assertEquals(MessageStatus.CANCELLED, messages[streamingId]!!.status)
        assertEquals(MessageStatus.SUCCEEDED, messages[doneId]!!.status)
    }

    @Test
    fun insertSystemEventPersistsCenteredConversationEvent() = runTest {
        val repository = repository(FakeConversationDao(), TimeProvider { 50L })
        val conversationId = repository.createConversation()

        repository.insertSystemEvent(conversationId, "已手动压缩早期 4 条消息，保留最近上下文。")

        val message = repository.listMessages(conversationId).single()
        assertEquals(MessageRole.SYSTEM, message.role)
        assertEquals(MessageStatus.SUCCEEDED, message.status)
        assertEquals("已手动压缩早期 4 条消息，保留最近上下文。", message.content)
        assertEquals(50L, message.createdAt)
    }

    @Test
    fun listMessagePartsBackfillsLegacyContentAsSingleTextPart() = runTest {
        val repository = repository(FakeConversationDao(), TimeProvider { 55L })
        val conversationId = repository.createConversation()
        val messageId = repository.insertUserMessage(conversationId, "旧消息正文", emptyList())

        val parts = repository.listMessageParts(messageId)

        assertEquals(1, parts.size)
        assertEquals(UiMessagePartType.TEXT, parts.single().type)
        assertEquals("旧消息正文", parts.single().content)
        assertEquals(true, parts.single().stable)
    }

    @Test
    fun replaceMessagePartsFromSnapshotPersistsPartsAndUpdatesLegacyVisibleContent() = runTest {
        val repository = repository(FakeConversationDao(), TimeProvider { 56L })
        val conversationId = repository.createConversation()
        val messageId = repository.insertAssistantPending(conversationId, "openai", "gpt-5.5")
        val snapshot = StreamingMessageSnapshot(
            status = MessageStatus.STREAMING,
            parts = listOf(
                UiMessagePartDraft(
                    index = 0,
                    type = UiMessagePartType.REASONING,
                    content = "内部推理",
                    stable = true,
                ),
                UiMessagePartDraft(
                    index = 1,
                    type = UiMessagePartType.TEXT,
                    content = "可见答案",
                    stable = false,
                ),
            ),
        )

        repository.replaceMessagePartsFromSnapshot(messageId, snapshot)

        val parts = repository.listMessageParts(messageId)
        assertEquals(listOf(UiMessagePartType.REASONING, UiMessagePartType.TEXT), parts.map { it.type })
        assertEquals(listOf("内部推理", "可见答案"), parts.map { it.content })
        assertEquals("可见答案", repository.listMessages(conversationId).single().content)
        assertEquals(MessageStatus.STREAMING, repository.listMessages(conversationId).single().status)
    }

    @Test
    fun replaceMessagePartsFromSnapshotPreservesRenderablePartTypesAndMetadata() = runTest {
        val repository = repository(FakeConversationDao(), TimeProvider { 57L })
        val conversationId = repository.createConversation()
        val messageId = repository.insertAssistantPending(conversationId, "openai", "gpt-5.5")
        val snapshot = StreamingMessageSnapshot(
            status = MessageStatus.SUCCEEDED,
            parts = listOf(
                UiMessagePartDraft(
                    index = 0,
                    type = UiMessagePartType.REASONING,
                    content = "先判断任务",
                    stable = true,
                ),
                UiMessagePartDraft(
                    index = 1,
                    type = UiMessagePartType.TEXT,
                    content = "这是正文答案。",
                    stable = true,
                ),
                UiMessagePartDraft(
                    index = 2,
                    type = UiMessagePartType.IMAGE,
                    content = "image://local/1",
                    metadata = mapOf("mimeType" to "image/png"),
                    stable = true,
                ),
                UiMessagePartDraft(
                    index = 3,
                    type = UiMessagePartType.SEARCH_RESULT,
                    content = "搜索摘要",
                    metadata = mapOf("title" to "来源标题", "url" to "https://example.com"),
                    stable = true,
                ),
                UiMessagePartDraft(
                    index = 4,
                    type = UiMessagePartType.FILE_CHANGE,
                    content = "更新 README.md",
                    metadata = mapOf("path" to "README.md"),
                    stable = true,
                ),
            ),
        )

        repository.replaceMessagePartsFromSnapshot(messageId, snapshot)

        val parts = repository.listMessageParts(messageId)
        assertEquals(
            listOf(
                UiMessagePartType.REASONING,
                UiMessagePartType.TEXT,
                UiMessagePartType.IMAGE,
                UiMessagePartType.SEARCH_RESULT,
                UiMessagePartType.FILE_CHANGE,
            ),
            parts.map { it.type },
        )
        assertEquals("https://example.com", parts[3].metadata["url"])
        assertEquals("README.md", parts[4].metadata["path"])
        assertEquals("这是正文答案。", repository.listMessages(conversationId).single().content)
    }

    @Test
    fun manualContextCompressionSavesMemoryAndInsertsSystemEvent() = runTest {
        val conversationDao = FakeConversationDao()
        var now = 60L
        val repository = repository(conversationDao, TimeProvider { now })
        val conversationId = repository.createConversation()
        repository.insertUserMessage(conversationId, "记住：默认用中文回答。".repeat(3), emptyList())
        now = 61L
        repository.insertAssistantPending(conversationId, "provider", "model").also {
            repository.appendAssistantText(it, "已记录中文偏好。".repeat(3))
            repository.markAssistantSucceeded(it)
        }
        now = 62L
        repository.insertUserMessage(conversationId, "继续", emptyList())
        val useCase = ManualContextCompressionUseCase(
            chatRepository = repository,
            timeProvider = TimeProvider { 70L },
            contextCompressor = ContextCompressor(
                ContextCompressionPolicy(maxRequestChars = 2_000, recentTargetChars = 5, memoryMaxChars = 220),
            ),
        )

        val result = useCase.compress(conversationId)

        assertEquals(true, result.compressed)
        assertEquals(2, repository.memoryForConversation(conversationId)!!.compressedMessageCount)
        val event = repository.listMessages(conversationId).last()
        assertEquals(MessageRole.SYSTEM, event.role)
        assertEquals("已手动压缩早期 2 条消息，保留最近上下文。", event.content)
    }

    private fun repository(
        conversationDao: ConversationDao,
        timeProvider: TimeProvider,
    ): ChatRepository = ChatRepository(
        conversationDao = conversationDao,
        messageDao = FakeMessageDao(),
        messagePartDao = FakeMessagePartDao(),
        attachmentDao = FakeMessageAttachmentDao(),
        memoryDao = FakeConversationMemoryDao(),
        timeProvider = timeProvider,
    )
}

private class FakeMessagePartDao : MessagePartDao {
    private val rows = linkedMapOf<String, MessagePartEntity>()

    override fun observeForMessage(messageId: String): Flow<List<MessagePartEntity>> =
        MutableStateFlow(rows.values.filter { it.messageId == messageId }.sortedBy { it.partIndex })

    override suspend fun listForMessage(messageId: String): List<MessagePartEntity> =
        rows.values.filter { it.messageId == messageId }.sortedBy { it.partIndex }

    override suspend fun insertAll(parts: List<MessagePartEntity>) {
        parts.forEach { rows[it.id] = it }
    }

    override suspend fun replaceForMessage(messageId: String, parts: List<MessagePartEntity>) {
        rows.entries.removeIf { it.value.messageId == messageId }
        parts.forEach { rows[it.id] = it }
    }

    override suspend fun deleteForMessage(messageId: String) {
        rows.entries.removeIf { it.value.messageId == messageId }
    }

    override suspend fun markStableForMessage(messageId: String, updatedAt: Long) {
        rows.replaceAll { _, row ->
            if (row.messageId == messageId) {
                row.copy(stable = true, updatedAt = updatedAt)
            } else {
                row
            }
        }
    }
}

private class FakeConversationDao : ConversationDao {
    private val rows = linkedMapOf<String, ConversationEntity>()
    private val flow = MutableStateFlow<List<ConversationEntity>>(emptyList())

    override fun observeActive(): Flow<List<ConversationEntity>> = flow

    override suspend fun findById(id: String): ConversationEntity? = rows[id]

    override suspend fun insert(entity: ConversationEntity) {
        rows[entity.id] = entity
        refresh()
    }

    override suspend fun update(entity: ConversationEntity) {
        rows[entity.id] = entity
        refresh()
    }

    override suspend fun archive(id: String, updatedAt: Long) {
        rows[id]?.let { rows[id] = it.copy(isArchived = true, updatedAt = updatedAt) }
        refresh()
    }

    private fun refresh() {
        flow.value = rows.values.filter { !it.isArchived }.sortedByDescending { it.updatedAt }
    }
}

private class FakeMessageDao : MessageDao {
    private val rows = linkedMapOf<String, MessageEntity>()

    override fun observeForConversation(conversationId: String): Flow<List<MessageEntity>> =
        MutableStateFlow(rows.values.filter { it.conversationId == conversationId })

    override suspend fun listForConversation(conversationId: String): List<MessageEntity> =
        rows.values.filter { it.conversationId == conversationId }.sortedBy { it.createdAt }

    override suspend fun findById(id: String): MessageEntity? = rows[id]

    override suspend fun insert(entity: MessageEntity) {
        rows[entity.id] = entity
    }

    override suspend fun update(entity: MessageEntity) {
        rows[entity.id] = entity
    }

    override suspend fun deleteForConversation(conversationId: String) {
        rows.entries.removeIf { it.value.conversationId == conversationId }
    }
}

private class FakeMessageAttachmentDao : MessageAttachmentDao {
    private val rows = mutableListOf<MessageAttachmentEntity>()

    override fun observeForMessage(messageId: String): Flow<List<MessageAttachmentEntity>> =
        MutableStateFlow(rows.filter { it.messageId == messageId })

    override suspend fun listForMessage(messageId: String): List<MessageAttachmentEntity> =
        rows.filter { it.messageId == messageId }.sortedBy { it.createdAt }

    override suspend fun insert(entity: MessageAttachmentEntity) {
        rows += entity
    }
}

private class FakeConversationMemoryDao : ConversationMemoryDao {
    private val rows = linkedMapOf<String, ConversationMemoryEntity>()

    override suspend fun findByConversationId(conversationId: String): ConversationMemoryEntity? =
        rows[conversationId]

    override fun observeForConversation(conversationId: String): Flow<ConversationMemoryEntity?> =
        MutableStateFlow(rows[conversationId])

    override suspend fun upsert(entity: ConversationMemoryEntity) {
        rows[entity.conversationId] = entity
    }
}
