package com.harnessapk.chat

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.ChatExecutionEntryDao
import com.harnessapk.storage.ChatExecutionEntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

data class EnqueueChatRequest(
    val conversationId: String,
    val content: String,
    val attachments: List<PendingImageAttachment>,
    val providerId: String?,
    val model: String?,
    val reasoningEffort: ReasoningEffort,
    val requestContext: ChatExecutionRequestContext,
)

class ChatExecutionRepository(
    private val database: RoomDatabase,
    private val dao: ChatExecutionEntryDao,
    private val chatRepository: ChatRepository,
    private val timeProvider: TimeProvider,
) {
    fun observeForConversation(conversationId: String): Flow<List<ChatExecutionEntry>> =
        dao.observeForConversation(conversationId).map { rows -> rows.map(ChatExecutionEntryEntity::toDomain) }

    suspend fun enqueue(request: EnqueueChatRequest): ChatExecutionEntry = database.withTransaction {
        val now = timeProvider.nowMillis()
        val userMessageId = chatRepository.insertUserMessage(
            conversationId = request.conversationId,
            content = request.content,
            attachments = request.attachments,
        )
        val entity = ChatExecutionEntryEntity(
            id = UUID.randomUUID().toString(),
            conversationId = request.conversationId,
            userMessageId = userMessageId,
            assistantMessageId = null,
            targetAssistantMessageId = null,
            sequence = dao.maxSequence(request.conversationId) + 1L,
            type = ChatExecutionType.NORMAL.name,
            status = ChatExecutionStatus.QUEUED.name,
            providerId = request.providerId,
            model = request.model,
            reasoningEffort = request.reasoningEffort.name,
            requestContextJson = encodeExecutionRequestContext(request.requestContext),
            errorMessage = null,
            createdAt = now,
            updatedAt = now,
        )
        dao.insert(entity)
        entity.toDomain()
    }

    suspend fun entry(id: String): ChatExecutionEntry? = dao.findById(id)?.toDomain()

    suspend fun nextQueued(conversationId: String): ChatExecutionEntry? =
        dao.listForConversation(conversationId)
            .firstOrNull { it.status == ChatExecutionStatus.QUEUED.name }
            ?.toDomain()

    suspend fun queuedConversationIds(): Set<String> =
        dao.listByStatus(ChatExecutionStatus.QUEUED.name).mapTo(linkedSetOf()) { it.conversationId }

    suspend fun requestHistory(entryId: String): List<ChatMessage> {
        val entry = requireNotNull(entry(entryId)) { "队列任务不存在" }
        val entries = dao.listForConversation(entry.conversationId).map(ChatExecutionEntryEntity::toDomain)
        return executionRequestHistory(
            messages = chatRepository.listMessages(entry.conversationId),
            entries = entries,
            currentEntryId = entry.id,
        )
    }

    suspend fun markRunning(entryId: String, assistantMessageId: String?): ChatExecutionEntry =
        updateEntry(entryId) { entry ->
            entry.copy(
                status = ChatExecutionStatus.RUNNING,
                assistantMessageId = assistantMessageId ?: entry.assistantMessageId,
                errorMessage = null,
            )
        }

    suspend fun markTerminal(
        entryId: String,
        status: ChatExecutionStatus,
        assistantMessageId: String? = null,
        errorMessage: String? = null,
    ): ChatExecutionEntry {
        require(status != ChatExecutionStatus.QUEUED && status != ChatExecutionStatus.RUNNING) {
            "终态不能是排队或运行中"
        }
        return updateEntry(entryId) { entry ->
            entry.copy(
                status = status,
                assistantMessageId = assistantMessageId ?: entry.assistantMessageId,
                errorMessage = errorMessage,
            )
        }
    }

    suspend fun recoverAfterProcessDeath() = database.withTransaction {
        dao.listByStatus(ChatExecutionStatus.RUNNING.name).forEach { entity ->
            val now = timeProvider.nowMillis()
            dao.update(
                entity.copy(
                    status = ChatExecutionStatus.INTERRUPTED.name,
                    updatedAt = now,
                ),
            )
            entity.assistantMessageId?.let { assistantMessageId ->
                chatRepository.markAssistantCancelled(assistantMessageId)
            }
        }
    }

    private suspend fun updateEntry(
        entryId: String,
        transform: (ChatExecutionEntry) -> ChatExecutionEntry,
    ): ChatExecutionEntry = database.withTransaction {
        val current = requireNotNull(dao.findById(entryId)) { "队列任务不存在" }.toDomain()
        val updated = transform(current).copy(updatedAt = timeProvider.nowMillis())
        dao.update(updated.toEntity())
        updated
    }
}

private fun ChatExecutionEntryEntity.toDomain(): ChatExecutionEntry = ChatExecutionEntry(
    id = id,
    conversationId = conversationId,
    userMessageId = userMessageId,
    assistantMessageId = assistantMessageId,
    targetAssistantMessageId = targetAssistantMessageId,
    sequence = sequence,
    type = runCatching { ChatExecutionType.valueOf(type) }.getOrDefault(ChatExecutionType.NORMAL),
    status = runCatching { ChatExecutionStatus.valueOf(status) }.getOrDefault(ChatExecutionStatus.FAILED),
    providerId = providerId,
    model = model,
    reasoningEffort = runCatching { ReasoningEffort.valueOf(reasoningEffort) }.getOrDefault(defaultReasoningEffort()),
    requestContext = decodeExecutionRequestContext(requestContextJson),
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun ChatExecutionEntry.toEntity(): ChatExecutionEntryEntity = ChatExecutionEntryEntity(
    id = id,
    conversationId = conversationId,
    userMessageId = userMessageId,
    assistantMessageId = assistantMessageId,
    targetAssistantMessageId = targetAssistantMessageId,
    sequence = sequence,
    type = type.name,
    status = status.name,
    providerId = providerId,
    model = model,
    reasoningEffort = reasoningEffort.name,
    requestContextJson = encodeExecutionRequestContext(requestContext),
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
