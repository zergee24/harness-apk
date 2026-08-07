package com.harnessapk.chat

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.harnessapk.agent.ConversationIdentityRepository
import com.harnessapk.agent.AgentLifecycleCoordinator
import com.harnessapk.common.TimeProvider
import com.harnessapk.common.toUserMessage
import com.harnessapk.storage.ChatExecutionEntryDao
import com.harnessapk.storage.ChatExecutionEntryEntity
import com.harnessapk.wiki.WikiRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

data class EnqueueChatRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val content: String,
    val attachments: List<PendingImageAttachment>,
    val providerId: String?,
    val model: String?,
    val reasoningEffort: ReasoningEffort,
    val requestContext: ChatExecutionRequestContext,
    val contextSnapshotDraft: ContextSnapshotDraftV2? = null,
)

data class ChatExecutionEnqueueOutcome(
    val entry: ChatExecutionEntry,
    val insertedByThisCall: Boolean,
)

class ChatExecutionRepository(
    private val database: RoomDatabase,
    private val dao: ChatExecutionEntryDao,
    private val chatRepository: ChatRepository,
    private val identityRepository: ConversationIdentityRepository,
    private val timeProvider: TimeProvider,
    private val lifecycleCoordinator: AgentLifecycleCoordinator = AgentLifecycleCoordinator(),
    private val wikiScopeSnapshotProvider: suspend (conversationId: String) -> List<WikiRef> = { emptyList() },
    private val requestContextEncoder: (ChatExecutionRequestContext) -> String = ::encodeExecutionRequestContext,
) {
    fun observeForConversation(conversationId: String): Flow<List<ChatExecutionEntry>> =
        dao.observeForConversation(conversationId).map { rows -> rows.map(ChatExecutionEntryEntity::toDomain) }

    suspend fun enqueue(request: EnqueueChatRequest): ChatExecutionEntry = enqueueWithOutcome(request).entry

    suspend fun enqueueWithOutcome(request: EnqueueChatRequest): ChatExecutionEnqueueOutcome =
        lifecycleCoordinator.serialized {
            database.withTransaction {
        dao.findById(request.requestId)?.toDomain()?.let { existing ->
            return@withTransaction ChatExecutionEnqueueOutcome(existing, insertedByThisCall = false)
        }
        identityRepository.pinForFirstMessage(request.conversationId)
        val pinnedConversation = chatRepository.conversation(request.conversationId)
        val requestContext = request.requestContext.copy(
            contextSnapshot = request.requestContext.contextSnapshot?.copy(
                agentId = pinnedConversation?.agentId,
                agentVersion = pinnedConversation?.agentVersion,
            ),
        )
        val now = timeProvider.nowMillis()
        val userMessageId = chatRepository.insertUserMessage(
            conversationId = request.conversationId,
            content = request.content,
            attachments = request.attachments,
        )
        val entity = ChatExecutionEntryEntity(
            id = request.requestId,
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
            requestContextJson = requestContextEncoder(requestContext),
            phase = null,
            automaticRetryCount = 0,
            interruptionReason = null,
            errorMessage = null,
            createdAt = now,
            updatedAt = now,
        )
        dao.insert(entity)
        ChatExecutionEnqueueOutcome(entity.toDomain(), insertedByThisCall = true)
            }
        }

    suspend fun entry(id: String): ChatExecutionEntry? = dao.findById(id)?.toDomain()

    suspend fun isAttachmentBatchReferenced(
        requestId: String,
        attachments: List<PendingImageAttachment>,
    ): Boolean = database.withTransaction {
        if (attachments.isEmpty()) return@withTransaction false
        val entry = dao.findById(requestId) ?: return@withTransaction false
        val candidateUris = attachments.mapTo(hashSetOf()) { it.uri.toString() }
        chatRepository.listAttachments(entry.userMessageId).any { it.uri in candidateUris }
    }

    suspend fun nextQueued(conversationId: String): ChatExecutionEntry? =
        dao.listForConversation(conversationId)
            .firstOrNull { it.status == ChatExecutionStatus.QUEUED.name }
            ?.toDomain()

    suspend fun runningEntry(conversationId: String): ChatExecutionEntry? =
        dao.findByConversationAndStatus(conversationId, ChatExecutionStatus.RUNNING.name)?.toDomain()

    suspend fun entryForUserMessage(userMessageId: String): ChatExecutionEntry? =
        dao.findByUserMessageId(userMessageId)?.toDomain()

    suspend fun queuedConversationIds(): Set<String> =
        dao.listByStatus(ChatExecutionStatus.QUEUED.name).mapTo(linkedSetOf()) { it.conversationId }

    suspend fun runningConversationIds(): Set<String> =
        dao.listByStatus(ChatExecutionStatus.RUNNING.name).mapTo(linkedSetOf()) { it.conversationId }

    suspend fun openConversationIds(): Set<String> =
        dao.listByStatuses(openExecutionStatusNames).mapTo(linkedSetOf()) { it.conversationId }

    suspend fun hasOpenWork(): Boolean = dao.listByStatuses(openExecutionStatusNames).isNotEmpty()

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
            val requestContext = captureLegacyWikiScopeSnapshot(entry.requestContext) {
                wikiScopeSnapshotProvider(entry.conversationId)
            }
            entry.copy(
                status = ChatExecutionStatus.RUNNING,
                assistantMessageId = assistantMessageId ?: entry.assistantMessageId,
                phase = entry.phase ?: ChatExecutionPhase.PREPARING_CONTEXT,
                errorMessage = null,
                requestContext = requestContext,
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
                phase = if (status == ChatExecutionStatus.SUCCEEDED) ChatExecutionPhase.FINALIZING else entry.phase,
                errorMessage = errorMessage,
            )
        }
    }

    suspend fun updatePhase(entryId: String, phase: ChatExecutionPhase): ChatExecutionEntry =
        updateEntry(entryId) { entry ->
            if (entry.status == ChatExecutionStatus.RUNNING) entry.copy(phase = phase) else entry
        }

    suspend fun retryInterrupted(
        entryId: String,
        reason: ChatInterruptionReason,
    ): Boolean = database.withTransaction {
        val entity = dao.findById(entryId) ?: return@withTransaction false
        if (entity.status != ChatExecutionStatus.RUNNING.name || entity.automaticRetryCount >= MAX_AUTOMATIC_RETRIES) {
            return@withTransaction false
        }
        entity.assistantMessageId?.let { chatRepository.deleteMessage(it) }
        dao.update(
            entity.copy(
                assistantMessageId = null,
                status = ChatExecutionStatus.QUEUED.name,
                phase = null,
                automaticRetryCount = entity.automaticRetryCount + 1,
                interruptionReason = reason.name,
                errorMessage = null,
                updatedAt = timeProvider.nowMillis(),
            ),
        )
        true
    }

    suspend fun markFailedAfterRunnerFailure(entryId: String, failure: Throwable): ChatExecutionEntry = database.withTransaction {
        val entry = requireNotNull(dao.findById(entryId)) { "队列任务不存在" }
        if (entry.status != ChatExecutionStatus.RUNNING.name) return@withTransaction entry.toDomain()

        val errorMessage = failure.toUserMessage()
        val assistantMessageId = entry.assistantMessageId ?: chatRepository.insertAssistantPending(
            conversationId = entry.conversationId,
            providerId = entry.providerId,
            model = entry.model,
        )
        chatRepository.markAssistantFailed(assistantMessageId, errorMessage)
        val updated = entry.copy(
            status = ChatExecutionStatus.FAILED.name,
            assistantMessageId = assistantMessageId,
            errorMessage = errorMessage,
            updatedAt = timeProvider.nowMillis(),
        )
        dao.update(updated)
        updated.toDomain()
    }

    suspend fun recoverAfterProcessDeath(
        conversationId: String? = null,
        reason: ChatInterruptionReason = ChatInterruptionReason.PROCESS_RESTART,
    ) = database.withTransaction {
        dao.listByStatus(ChatExecutionStatus.RUNNING.name).forEach { entity ->
            if (conversationId != null && entity.conversationId != conversationId) return@forEach
            val now = timeProvider.nowMillis()
            if (entity.automaticRetryCount < MAX_AUTOMATIC_RETRIES) {
                entity.assistantMessageId?.let { chatRepository.deleteMessage(it) }
                dao.update(
                    entity.copy(
                        status = ChatExecutionStatus.QUEUED.name,
                        assistantMessageId = null,
                        phase = null,
                        automaticRetryCount = entity.automaticRetryCount + 1,
                        interruptionReason = reason.name,
                        errorMessage = null,
                        updatedAt = now,
                    ),
                )
            } else {
                val assistantMessageId = entity.assistantMessageId ?: chatRepository.insertAssistantPending(
                    conversationId = entity.conversationId,
                    providerId = entity.providerId,
                    model = entity.model,
                )
                chatRepository.markAssistantFailed(assistantMessageId, BACKGROUND_INTERRUPTED_MESSAGE)
                dao.update(
                    entity.copy(
                        status = ChatExecutionStatus.FAILED.name,
                        assistantMessageId = assistantMessageId,
                        interruptionReason = reason.name,
                        errorMessage = BACKGROUND_INTERRUPTED_MESSAGE,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    suspend fun prepareSteer(entryId: String): ChatExecutionEntry = database.withTransaction {
        val selected = requireNotNull(dao.findById(entryId)) { "队列任务不存在" }
        require(selected.status == ChatExecutionStatus.QUEUED.name) { "只有等待处理的消息可以引导当前回复" }
        val running = dao.findByConversationAndStatus(selected.conversationId, ChatExecutionStatus.RUNNING.name)
        val entries = dao.listForConversation(selected.conversationId)
        val now = timeProvider.nowMillis()
        running?.let { active ->
            dao.update(
                active.copy(
                    status = ChatExecutionStatus.STEERED.name,
                    updatedAt = now,
                ),
            )
        }
        val promoted = selected.copy(
            sequence = (entries.minOfOrNull(ChatExecutionEntryEntity::sequence) ?: 0L) - 1L,
            type = ChatExecutionType.STEER_CURRENT.name,
            targetAssistantMessageId = running?.assistantMessageId,
            updatedAt = now,
        )
        dao.update(promoted)
        promoted.toDomain()
    }

    suspend fun cancelRunning(conversationId: String): ChatExecutionEntry? = database.withTransaction {
        val active = dao.findByConversationAndStatus(conversationId, ChatExecutionStatus.RUNNING.name)
            ?: return@withTransaction null
        val now = timeProvider.nowMillis()
        val cancelled = active.copy(
            status = ChatExecutionStatus.CANCELLED.name,
            updatedAt = now,
        )
        dao.update(cancelled)
        cancelled.toDomain()
    }

    suspend fun deleteQueued(entryId: String): Boolean = database.withTransaction {
        val entry = dao.findById(entryId) ?: return@withTransaction false
        if (entry.status != ChatExecutionStatus.QUEUED.name) return@withTransaction false
        dao.deleteById(entryId)
        chatRepository.deleteMessage(entry.userMessageId)
        true
    }

    suspend fun retryFailed(entryId: String): Boolean = database.withTransaction {
        val entry = dao.findById(entryId) ?: return@withTransaction false
        if (entry.status != ChatExecutionStatus.FAILED.name) return@withTransaction false
        val latest = dao.listForConversation(entry.conversationId).maxByOrNull(ChatExecutionEntryEntity::sequence)
        if (latest?.id != entry.id) return@withTransaction false
        entry.assistantMessageId?.let { chatRepository.deleteMessage(it) }
        dao.update(
            entry.copy(
                assistantMessageId = null,
                status = ChatExecutionStatus.QUEUED.name,
                phase = null,
                automaticRetryCount = 0,
                interruptionReason = null,
                errorMessage = null,
                updatedAt = timeProvider.nowMillis(),
            ),
        )
        true
    }

    private suspend fun updateEntry(
        entryId: String,
        transform: suspend (ChatExecutionEntry) -> ChatExecutionEntry,
    ): ChatExecutionEntry = database.withTransaction {
        val current = requireNotNull(dao.findById(entryId)) { "队列任务不存在" }.toDomain()
        val updated = transform(current).copy(updatedAt = timeProvider.nowMillis())
        dao.update(updated.toEntity())
        updated
    }
}

private val openExecutionStatusNames = listOf(
    ChatExecutionStatus.QUEUED.name,
    ChatExecutionStatus.RUNNING.name,
)

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
    phase = phase?.let { runCatching { ChatExecutionPhase.valueOf(it) }.getOrNull() },
    automaticRetryCount = automaticRetryCount,
    interruptionReason = interruptionReason?.let { runCatching { ChatInterruptionReason.valueOf(it) }.getOrNull() },
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
    phase = phase?.name,
    automaticRetryCount = automaticRetryCount,
    interruptionReason = interruptionReason?.name,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal const val MAX_AUTOMATIC_RETRIES = 2
internal const val BACKGROUND_INTERRUPTED_MESSAGE = "后台生成多次中断，请重试或检查系统电池限制。"
