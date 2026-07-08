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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class ChatRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val attachmentDao: MessageAttachmentDao,
    private val memoryDao: ConversationMemoryDao,
    private val timeProvider: TimeProvider,
) {
    fun observeConversations(): Flow<List<Conversation>> = conversationDao.observeActive().map { rows ->
        rows.map { it.toDomain() }
    }

    suspend fun conversation(id: String): Conversation? = conversationDao.findById(id)?.toDomain()

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messageDao.observeForConversation(conversationId).map { rows -> rows.map { it.toDomain() } }

    fun observeMemory(conversationId: String): Flow<ConversationMemory?> =
        memoryDao.observeForConversation(conversationId).map { it?.toDomain() }

    suspend fun createConversation(
        title: String = DefaultConversationTitle,
        projectId: String? = null,
    ): String {
        val now = timeProvider.nowMillis()
        val id = UUID.randomUUID().toString()
        conversationDao.insert(
            ConversationEntity(
                id = id,
                title = title,
                createdAt = now,
                updatedAt = now,
                defaultProviderId = null,
                defaultModel = null,
                isArchived = false,
                projectId = projectId?.trim()?.ifBlank { null },
                promptOriginal = "",
                promptOptimized = "",
                promptFinal = "",
            ),
        )
        return id
    }

    suspend fun archiveConversation(id: String) {
        conversationDao.archive(id, timeProvider.nowMillis())
    }

    suspend fun updateConversationTitle(id: String, title: String) {
        val normalizedTitle = title.trim()
        require(normalizedTitle.isNotBlank()) { "标题不能为空" }
        val conversation = conversationDao.findById(id) ?: return
        conversationDao.update(
            conversation.copy(
                title = normalizedTitle,
                updatedAt = timeProvider.nowMillis(),
            ),
        )
    }

    suspend fun updateConversationPrompt(
        id: String,
        original: String,
        optimized: String,
        final: String,
    ) {
        val conversation = conversationDao.findById(id) ?: return
        if (
            conversation.promptOriginal == original &&
            conversation.promptOptimized == optimized &&
            conversation.promptFinal == final
        ) {
            return
        }
        conversationDao.update(
            conversation.copy(
                promptOriginal = original,
                promptOptimized = optimized,
                promptFinal = final,
                updatedAt = timeProvider.nowMillis(),
            ),
        )
    }

    suspend fun updateConversationProject(
        id: String,
        projectId: String?,
    ) {
        val conversation = conversationDao.findById(id) ?: return
        val normalizedProjectId = projectId?.trim()?.ifBlank { null }
        if (conversation.projectId == normalizedProjectId) return
        conversationDao.update(
            conversation.copy(projectId = normalizedProjectId),
        )
    }

    suspend fun insertUserMessage(
        conversationId: String,
        content: String,
        attachments: List<PendingImageAttachment>,
    ): String {
        val now = timeProvider.nowMillis()
        val id = UUID.randomUUID().toString()
        messageDao.insert(
            MessageEntity(
                id = id,
                conversationId = conversationId,
                role = MessageRole.USER.name,
                content = content,
                status = MessageStatus.SUCCEEDED.name,
                providerId = null,
                model = null,
                createdAt = now,
                updatedAt = now,
                errorCode = null,
                errorMessage = null,
            ),
        )
        attachments.forEach { attachment ->
            attachmentDao.insert(
                MessageAttachmentEntity(
                    id = UUID.randomUUID().toString(),
                    messageId = id,
                    type = "image",
                    uri = attachment.uri.toString(),
                    mimeType = attachment.mimeType,
                    createdAt = now,
                ),
            )
        }
        touchConversation(
            id = conversationId,
            updatedAt = now,
            titleSuggestion = smartConversationTitle(content),
        )
        return id
    }

    suspend fun insertAssistantPending(
        conversationId: String,
        providerId: String?,
        model: String?,
    ): String {
        val now = timeProvider.nowMillis()
        val id = UUID.randomUUID().toString()
        messageDao.insert(
            MessageEntity(
                id = id,
                conversationId = conversationId,
                role = MessageRole.ASSISTANT.name,
                content = "",
                status = MessageStatus.PENDING.name,
                providerId = providerId,
                model = model,
                createdAt = now,
                updatedAt = now,
                errorCode = null,
                errorMessage = null,
            ),
        )
        touchConversation(conversationId, now)
        return id
    }

    suspend fun insertSystemEvent(
        conversationId: String,
        content: String,
    ): String {
        val now = timeProvider.nowMillis()
        val id = UUID.randomUUID().toString()
        messageDao.insert(
            MessageEntity(
                id = id,
                conversationId = conversationId,
                role = MessageRole.SYSTEM.name,
                content = content,
                status = MessageStatus.SUCCEEDED.name,
                providerId = null,
                model = null,
                createdAt = now,
                updatedAt = now,
                errorCode = null,
                errorMessage = null,
            ),
        )
        touchConversation(conversationId, now)
        return id
    }

    suspend fun appendAssistantText(messageId: String, delta: String) {
        val current = messageDao.findById(messageId) ?: return
        if (MessageStatus.valueOf(current.status) != MessageStatus.PENDING &&
            MessageStatus.valueOf(current.status) != MessageStatus.STREAMING
        ) {
            return
        }
        val now = timeProvider.nowMillis()
        messageDao.update(
            current.copy(
                content = current.content + delta,
                status = MessageStatus.STREAMING.name,
                updatedAt = now,
            ),
        )
        touchConversation(current.conversationId, now)
    }

    suspend fun markAssistantSucceeded(messageId: String) {
        updateMessageStatus(messageId, MessageStatus.SUCCEEDED, null, onlyIfActive = true)
    }

    suspend fun markAssistantFailed(messageId: String, message: String) {
        updateMessageStatus(messageId, MessageStatus.FAILED, message)
    }

    suspend fun markAssistantCancelled(messageId: String) {
        updateMessageStatus(messageId, MessageStatus.CANCELLED, null)
    }

    suspend fun cancelActiveAssistantMessages(conversationId: String): Int {
        val activeMessages = messageDao.listForConversation(conversationId).filter {
            it.role == MessageRole.ASSISTANT.name &&
                (it.status == MessageStatus.PENDING.name || it.status == MessageStatus.STREAMING.name)
        }
        activeMessages.forEach { markAssistantCancelled(it.id) }
        return activeMessages.size
    }

    suspend fun listMessages(conversationId: String): List<ChatMessage> =
        messageDao.listForConversation(conversationId).map { it.toDomain() }

    suspend fun listAttachments(messageId: String): List<ChatAttachment> =
        attachmentDao.listForMessage(messageId).map { it.toDomain() }

    suspend fun memoryForConversation(conversationId: String): ConversationMemory? =
        memoryDao.findByConversationId(conversationId)?.toDomain()

    suspend fun upsertMemory(memory: ConversationMemory) {
        memoryDao.upsert(memory.toEntity())
    }

    private suspend fun updateMessageStatus(
        messageId: String,
        status: MessageStatus,
        errorMessage: String?,
        onlyIfActive: Boolean = false,
    ) {
        val current = messageDao.findById(messageId) ?: return
        if (onlyIfActive &&
            current.status != MessageStatus.PENDING.name &&
            current.status != MessageStatus.STREAMING.name
        ) {
            return
        }
        val now = timeProvider.nowMillis()
        messageDao.update(
            current.copy(
                status = status.name,
                errorCode = if (errorMessage == null) null else "CHAT_ERROR",
                errorMessage = errorMessage,
                updatedAt = now,
            ),
        )
        touchConversation(current.conversationId, now)
    }

    private suspend fun touchConversation(
        id: String,
        updatedAt: Long,
        titleSuggestion: String? = null,
    ) {
        val conversation = conversationDao.findById(id) ?: return
        val title = if (conversation.title == DefaultConversationTitle && titleSuggestion != null) {
            titleSuggestion
        } else {
            conversation.title
        }
        conversationDao.update(conversation.copy(title = title, updatedAt = updatedAt))
    }
}

private const val DefaultConversationTitle = "新会话"
private const val MaxGeneratedTitleLength = 20

internal fun smartConversationTitle(content: String): String {
    val normalized = content
        .replace(Regex("```[\\s\\S]*?```"), "代码片段")
        .replace(Regex("[#>*_`\\[\\]()]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('。', '，', ',', '.', '!', '！', '?', '？', ':', '：')

    return normalized
        .ifBlank { "截图对话" }
        .take(MaxGeneratedTitleLength)
}

private fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    title = title,
    updatedAt = updatedAt,
    projectId = projectId,
    promptOriginal = promptOriginal,
    promptOptimized = promptOptimized,
    promptFinal = promptFinal,
)

private fun MessageEntity.toDomain(): ChatMessage = ChatMessage(
    id = id,
    conversationId = conversationId,
    role = MessageRole.valueOf(role),
    content = content,
    status = MessageStatus.valueOf(status),
    providerId = providerId,
    model = model,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun MessageAttachmentEntity.toDomain(): ChatAttachment = ChatAttachment(
    id = id,
    messageId = messageId,
    type = type,
    uri = uri,
    mimeType = mimeType,
)

private fun ConversationMemoryEntity.toDomain(): ConversationMemory = ConversationMemory(
    conversationId = conversationId,
    summary = summary,
    coveredThroughMessageId = coveredThroughMessageId,
    coveredThroughCreatedAt = coveredThroughCreatedAt,
    compressedMessageCount = compressedMessageCount,
    updatedAt = updatedAt,
)

private fun ConversationMemory.toEntity(): ConversationMemoryEntity = ConversationMemoryEntity(
    conversationId = conversationId,
    summary = summary,
    coveredThroughMessageId = coveredThroughMessageId,
    coveredThroughCreatedAt = coveredThroughCreatedAt,
    compressedMessageCount = compressedMessageCount,
    updatedAt = updatedAt,
)
