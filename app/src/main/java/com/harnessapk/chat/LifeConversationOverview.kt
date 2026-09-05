package com.harnessapk.chat

import com.harnessapk.storage.ConversationDao
import com.harnessapk.storage.LifeConversationMetadata
import com.harnessapk.storage.LifeConversationMetadataStorePort
import com.harnessapk.storage.LifeConversationOrigin
import com.harnessapk.storage.LifeConversationRoomRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** A batch-friendly draft contract supplied by the draft worker. */
data class LifeConversationDraftEntry(
    val conversationId: String,
    val text: String = "",
    val attachmentCount: Int = 0,
    val imageCount: Int = 0,
    val documentCount: Int = 0,
    val firstDocumentName: String? = null,
    val pendingAttachmentCount: Int = 0,
    val importingAttachmentCount: Int = 0,
    val failedAttachmentCount: Int = 0,
    val missingAttachmentCount: Int = 0,
    val isPreparing: Boolean = false,
    val updatedAt: Long = 0L,
) {
    val totalAttachmentCount: Int
        get() = maxOf(attachmentCount, imageCount + documentCount)

    val hasContent: Boolean
        get() = text.isNotBlank() || totalAttachmentCount > 0 || pendingAttachmentCount > 0 || importingAttachmentCount > 0

    val hasPendingAttachments: Boolean
        get() = pendingAttachmentCount > 0 || importingAttachmentCount > 0

    val hasPendingWork: Boolean
        get() = hasPendingAttachments || isPreparing

    val hasUnavailableAttachments: Boolean
        get() = failedAttachmentCount > 0 || missingAttachmentCount > 0

    companion object {
        /** Adapter for the durable draft store used by the app container. */
        fun fromConversationDraft(
            conversationId: String,
            draft: ConversationDraft,
            isPreparing: Boolean = false,
        ): LifeConversationDraftEntry {
            val imageRecords = draft.imageMetadata
            val imageCount = if (imageRecords.isNotEmpty()) imageRecords.size else draft.attachments.size
            val allRecords = imageRecords + draft.documents
            val importingCount = allRecords.count { it.state == DraftAttachmentState.IMPORTING }
            val failedCount = allRecords.count { it.state == DraftAttachmentState.FAILED }
            val missingCount = allRecords.count { it.state == DraftAttachmentState.MISSING }
            return LifeConversationDraftEntry(
                conversationId = conversationId,
                text = draft.text,
                attachmentCount = imageCount + draft.documents.size,
                imageCount = imageCount,
                documentCount = draft.documents.size,
                firstDocumentName = draft.documents.firstOrNull()?.displayName,
                importingAttachmentCount = importingCount,
                failedAttachmentCount = failedCount,
                missingAttachmentCount = missingCount,
                isPreparing = isPreparing,
                updatedAt = draft.updatedAt,
            )
        }
    }
}

/** The worker can expose a single observeAll() query/flow rather than row reads. */
fun interface LifeConversationDraftStore {
    fun observeAll(): Flow<List<LifeConversationDraftEntry>>
}

/** A batch-friendly view of in-memory send-recovery facts. */
enum class LifeConversationRecoveryPhase {
    IN_FLIGHT,
    UNKNOWN,
    LANDED,
    NOT_LANDED,
}

data class LifeConversationRecoveryEntry(
    val conversationId: String,
    val phase: LifeConversationRecoveryPhase,
    val updatedAt: Long = 0L,
    val submittedText: String = "",
    val currentDraftText: String = "",
    val draftProjection: LifeConversationDraftEntry? = null,
) {
    companion object {
        /** Adapter for ChatSendRecoveryStore.observeAll(). */
        fun fromRecoveryState(state: ChatSendRequestState): LifeConversationRecoveryEntry? {
            val id = state.conversationId ?: state.intent?.conversationId
            if (id.isNullOrBlank()) return null
            val original = state.intent?.originalAttachments.orEmpty()
            val current = state.currentDraftAttachmentSnapshots.ifEmpty { original }
            val landed = state.phase == ChatSendRequestPhase.LANDED
            val unsent = if (landed) current.filterNot { item ->
                original.any { it.id == item.id && it.uri == item.uri && it.sha256 == item.sha256 }
            } else current
            val rawQuestion = state.intent?.originalText ?: state.submittedText
            val draftText = if (landed && state.currentDraftText == rawQuestion) "" else state.currentDraftText
            val documents = unsent.filter { it.kind == ChatSendAttachmentKind.DOCUMENT }
            val imageCount = unsent.count { it.kind == ChatSendAttachmentKind.IMAGE }.takeIf { current.isNotEmpty() }
                ?: state.currentDraftAttachments.count { !landed || it !in state.submittedAttachments }
            return LifeConversationRecoveryEntry(
                conversationId = id,
                phase = if (state.transientPersistenceFailure != null) LifeConversationRecoveryPhase.UNKNOWN else when (state.phase) {
                    ChatSendRequestPhase.IN_FLIGHT -> LifeConversationRecoveryPhase.IN_FLIGHT
                    ChatSendRequestPhase.UNKNOWN -> LifeConversationRecoveryPhase.UNKNOWN
                    ChatSendRequestPhase.LANDED -> LifeConversationRecoveryPhase.LANDED
                    ChatSendRequestPhase.NOT_LANDED -> LifeConversationRecoveryPhase.NOT_LANDED
                },
                updatedAt = state.intent?.createdAtMillis ?: 0L,
                submittedText = rawQuestion,
                currentDraftText = draftText,
                draftProjection = LifeConversationDraftEntry(
                    conversationId = id, text = draftText, imageCount = imageCount,
                    documentCount = documents.size, firstDocumentName = documents.firstOrNull()?.displayName,
                    updatedAt = state.intent?.createdAtMillis ?: 0L,
                ),
            )
        }
    }
}

fun interface LifeConversationRecoveryStore {
    fun observeAll(): Flow<List<LifeConversationRecoveryEntry>>
}

object EmptyLifeConversationDraftStore : LifeConversationDraftStore {
    override fun observeAll(): Flow<List<LifeConversationDraftEntry>> = MutableStateFlow(emptyList())
}

object EmptyLifeConversationRecoveryStore : LifeConversationRecoveryStore {
    override fun observeAll(): Flow<List<LifeConversationRecoveryEntry>> = MutableStateFlow(emptyList())
}

/** Production adapter for the durable draft worker owned by AppContainer. */
class ConversationDraftOverviewAdapter(
    private val source: ConversationDraftStore,
) : LifeConversationDraftStore {
    override fun observeAll(): Flow<List<LifeConversationDraftEntry>> = source.observeAll().map { entries ->
        entries.map { entry ->
            LifeConversationDraftEntry.fromConversationDraft(entry.conversationId, entry.draft)
        }
    }
}

/** Production adapter for the durable send-recovery journal. */
class ChatSendRecoveryOverviewAdapter(
    private val source: ChatSendRecoveryStore,
) : LifeConversationRecoveryStore {
    override fun observeAll(): Flow<List<LifeConversationRecoveryEntry>> = source.observeAll().mapNotNullEntries()
}

private fun Flow<List<ChatSendRequestState>>.mapNotNullEntries(): Flow<List<LifeConversationRecoveryEntry>> =
    map { states -> states.mapNotNull(LifeConversationRecoveryEntry::fromRecoveryState) }

class InMemoryLifeConversationDraftStore(
    initial: List<LifeConversationDraftEntry> = emptyList(),
) : LifeConversationDraftStore {
    private val _entries = MutableStateFlow(initial)
    val entries: StateFlow<List<LifeConversationDraftEntry>> = _entries.asStateFlow()
    override fun observeAll(): Flow<List<LifeConversationDraftEntry>> = entries

    fun replace(entries: List<LifeConversationDraftEntry>) {
        _entries.value = entries
    }
}

class InMemoryLifeConversationRecoveryStore(
    initial: List<LifeConversationRecoveryEntry> = emptyList(),
) : LifeConversationRecoveryStore {
    private val _entries = MutableStateFlow(initial)
    val entries: StateFlow<List<LifeConversationRecoveryEntry>> = _entries.asStateFlow()
    override fun observeAll(): Flow<List<LifeConversationRecoveryEntry>> = entries

    fun replace(entries: List<LifeConversationRecoveryEntry>) {
        _entries.value = entries
    }
}

enum class LifeConversationDisplayStatus {
    WAITING_CONFIRMATION,
    FAILED,
    GENERATING,
    WAITING,
    STOPPED,
    DRAFT,
    COMPLETED,
    NONE,
}

val LifeConversationDisplayStatus.label: String
    get() = when (this) {
        LifeConversationDisplayStatus.WAITING_CONFIRMATION -> "待确认"
        LifeConversationDisplayStatus.FAILED -> "失败"
        LifeConversationDisplayStatus.GENERATING -> "正在生成"
        LifeConversationDisplayStatus.WAITING -> "等待处理"
        LifeConversationDisplayStatus.STOPPED -> "已停止"
        LifeConversationDisplayStatus.DRAFT -> "草稿"
        LifeConversationDisplayStatus.COMPLETED -> "已完成"
        LifeConversationDisplayStatus.NONE -> ""
    }

data class LifeConversationOverviewItem(
    val conversationId: String,
    val title: String,
    val summary: String,
    val updatedAt: Long,
    val status: LifeConversationDisplayStatus,
    val hasDraft: Boolean,
    val isArchived: Boolean,
    val origin: LifeConversationOrigin,
    val agentId: String?,
    val agentVersion: Int?,
    val canArchive: Boolean,
    val archiveBlockedReason: String? = null,
    val statusLabel: String = status.label,
)

sealed interface LifeConversationOverviewState {
    data object Loading : LifeConversationOverviewState

    data class Content(
        val items: List<LifeConversationOverviewItem>,
        val fromCache: Boolean = false,
    ) : LifeConversationOverviewState

    data class Error(
        val message: String,
        val cachedItems: List<LifeConversationOverviewItem> = emptyList(),
    ) : LifeConversationOverviewState
}

sealed interface LifeConversationArchiveResult {
    data class Archived(val undoDeadlineMillis: Long) : LifeConversationArchiveResult
    data object AlreadyArchived : LifeConversationArchiveResult
    data object NotFound : LifeConversationArchiveResult
    data class Blocked(val reason: String) : LifeConversationArchiveResult
}

/**
 * Durable conversation rows plus batched draft/recovery facts become one
 * presentation stream. The repository intentionally owns only life projection
 * rules; ChatRepository and its send/recovery semantics remain untouched.
 */
class LifeConversationOverviewRepository(
    private val conversationDao: ConversationDao,
    private val metadataStore: LifeConversationMetadataStorePort,
    private val draftStore: LifeConversationDraftStore,
    private val recoveryStore: LifeConversationRecoveryStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val undoWindowMillis: Long = DEFAULT_UNDO_WINDOW_MILLIS,
    private val archiveGuard: suspend (
        String,
        suspend () -> LifeConversationArchiveResult,
    ) -> LifeConversationArchiveResult = { _, action -> action() },
) {
    private val refreshSignal = MutableStateFlow(0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(includeArchived: Boolean = false): Flow<LifeConversationOverviewState> {
        var cachedItems = emptyList<LifeConversationOverviewItem>()
        val source: Flow<LifeConversationOverviewState> = combine(
            conversationDao.observeLifeOverviewRows(includeArchived),
            draftStore.observeAll(),
            recoveryStore.observeAll(),
            metadataStore.entries,
        ) { rows, drafts, recoveries, metadata ->
            val items = aggregateLifeConversationItems(rows, drafts, recoveries, metadata)
                .let { values ->
                    if (includeArchived) values else values.filterNot { isHiddenEmptyShell(it, rows, metadata) }
                }
                .sortedWith(compareByDescending<LifeConversationOverviewItem> { it.updatedAt }.thenByDescending { it.conversationId })
            cachedItems = items
            LifeConversationOverviewState.Content(items)
        }
        return refreshSignal.flatMapLatest {
            source.catch { failure ->
                emit(
                    LifeConversationOverviewState.Error(
                        message = failure.message?.trim().takeUnless { it.isNullOrEmpty() }
                            ?: "暂时没能读取记录",
                        cachedItems = cachedItems,
                    ),
                )
            }
        }
    }

    /** A retry gives the DAO a fresh collector after a transient read failure. */
    fun refresh() {
        refreshSignal.update { it + 1L }
    }

    suspend fun archive(conversationId: String): LifeConversationArchiveResult = archiveGuard(conversationId) {
        archiveInternal(conversationId)
    }

    private suspend fun archiveInternal(conversationId: String): LifeConversationArchiveResult {
        val conversation = conversationDao.findById(conversationId) ?: return LifeConversationArchiveResult.NotFound
        if (conversation.isArchived) return LifeConversationArchiveResult.AlreadyArchived
        if (conversation.projectId != null) {
            return LifeConversationArchiveResult.Blocked("工作会话请在工作入口整理")
        }
        val recovery = recoveryStore.observeAll().first().firstOrNull { it.conversationId == conversationId }
        if (recovery?.phase in setOf(
                LifeConversationRecoveryPhase.IN_FLIGHT,
                LifeConversationRecoveryPhase.UNKNOWN,
            )
        ) {
            return LifeConversationArchiveResult.Blocked("问题仍在处理，结束后再整理")
        }
        val draft = draftStore.observeAll().first().firstOrNull { it.conversationId == conversationId }
        if (draft?.hasPendingWork == true) {
            return LifeConversationArchiveResult.Blocked("附件或问题仍在准备，完成后再整理")
        }
        // Make the undo promise durable before the Room mutation. A crash here
        // leaves only an unused deadline, never a falsely promised undo action.
        val deadline = nowMillis() + undoWindowMillis
        try {
            metadataStore.setUndoDeadline(conversationId, deadline)
        } catch (_: Exception) {
            return LifeConversationArchiveResult.Blocked("归档信息未能保存，请重试")
        }
        val changed = try {
            conversationDao.archiveLifeConversationIfIdle(conversationId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            runCatching { metadataStore.clearUndoDeadline(conversationId) }
            return LifeConversationArchiveResult.Blocked("暂时未能归档，请重试")
        }
        if (changed == 0) {
            runCatching { metadataStore.clearUndoDeadline(conversationId) }
            return LifeConversationArchiveResult.Blocked("问题仍在处理，结束后再整理")
        }
        return LifeConversationArchiveResult.Archived(deadline)
    }

    suspend fun undoArchive(conversationId: String): Boolean {
        val deadline = metadataStore.get(conversationId)?.undoDeadlineMillis ?: return false
        if (deadline <= nowMillis()) return false
        val restored = conversationDao.restoreLifeConversation(conversationId) > 0
        if (restored) {
            runCatching { metadataStore.clearUndoDeadline(conversationId) }
            refresh()
        }
        return restored
    }

    /**
     * Gives assistive technology users the recommended extra time to act on
     * the transient undo action while keeping the deadline durable. The
     * caller supplies a duration from the platform accessibility manager.
     */
    fun extendUndoDeadline(conversationId: String, durationMillis: Long): Long? {
        if (durationMillis <= 0L) return metadataStore.get(conversationId)?.undoDeadlineMillis
        val current = metadataStore.get(conversationId)?.undoDeadlineMillis ?: return null
        val now = nowMillis()
        if (current <= now) return null
        val requested = now + durationMillis
        val extended = maxOf(current, requested)
        if (extended != current) metadataStore.setUndoDeadline(conversationId, extended)
        return extended
    }

    /** Long-lived archive-list restoration; it is independent of the 5s undo. */
    suspend fun restoreFromArchiveList(conversationId: String): Boolean {
        val restored = conversationDao.restoreLifeConversation(conversationId) > 0
        if (restored) {
            runCatching { metadataStore.clearUndoDeadline(conversationId) }
            refresh()
        }
        return restored
    }

    fun recordOrigin(conversationId: String, origin: LifeConversationOrigin) =
        metadataStore.recordOrigin(conversationId, origin)

    fun markUserRetained(conversationId: String) = metadataStore.markUserRetained(conversationId)

    fun setCustomTitle(conversationId: String, title: String) =
        metadataStore.setCustomTitle(conversationId, title)

    companion object {
        const val DEFAULT_UNDO_WINDOW_MILLIS = 5_000L
    }
}

internal fun aggregateLifeConversationItems(
    rows: List<LifeConversationRoomRow>,
    drafts: List<LifeConversationDraftEntry>,
    recoveries: List<LifeConversationRecoveryEntry>,
    metadata: Map<String, LifeConversationMetadata>,
): List<LifeConversationOverviewItem> {
    val draftsByConversation = drafts.associateBy { it.conversationId }
    val recoveryByConversation = recoveries.associateBy { it.conversationId }
    return rows.map { row ->
        val recovery = recoveryByConversation[row.id]
        val storedDraft = draftsByConversation[row.id]
        val draft = if (recovery?.phase == LifeConversationRecoveryPhase.LANDED && recovery.draftProjection != null) {
            recovery.draftProjection
        } else storedDraft?.takeIf { it.hasContent || it.hasUnavailableAttachments }
            ?: recovery?.draftProjection ?: storedDraft
        val facts = metadata[row.id]
        val requestContext = row.latestRequestContextJson
            ?.let(::decodeExecutionRequestContext)
        val firstRequestContext = row.firstUserRequestContextJson
            ?.let(::decodeExecutionRequestContext)
        val status = lifeDisplayStatus(row.latestExecutionStatus, recovery, draft)
        val active = row.openExecutionCount > 0L || recovery?.phase == LifeConversationRecoveryPhase.IN_FLIGHT
        val unknown = recovery?.phase == LifeConversationRecoveryPhase.UNKNOWN
        val pendingDraft = draft?.hasPendingWork == true
        val blocked = active || unknown || pendingDraft
        LifeConversationOverviewItem(
            conversationId = row.id,
            title = lifeConversationTitle(row, draft, facts, recovery, requestContext, firstRequestContext),
            summary = lifeConversationSummary(row, draft, recovery, requestContext),
            updatedAt = maxOf(
                row.updatedAt,
                row.lastMessageAt ?: 0L,
                draft?.updatedAt ?: 0L,
                recovery?.updatedAt ?: 0L,
            ),
            status = status,
            hasDraft = draft?.hasContent == true || recovery?.currentDraftText?.isNotBlank() == true,
            isArchived = row.isArchived,
            origin = facts?.origin ?: LifeConversationOrigin.UNKNOWN,
            agentId = row.agentId,
            agentVersion = row.agentVersion,
            canArchive = !row.isArchived && !blocked,
            archiveBlockedReason = when {
                unknown || active -> "问题仍在处理，结束后再整理"
                pendingDraft -> "附件或问题仍在准备，完成后再整理"
                else -> null
            },
            statusLabel = when {
                status != LifeConversationDisplayStatus.FAILED -> status.label
                recovery?.phase == LifeConversationRecoveryPhase.NOT_LANDED -> "未发出"
                row.latestExecutionStatus in setOf("FAILED", "INTERRUPTED") -> "回答失败"
                draft?.hasUnavailableAttachments == true -> "附件不可用"
                else -> status.label
            },
        )
    }
}

internal fun isHiddenEmptyShell(
    item: LifeConversationOverviewItem,
    rows: List<LifeConversationRoomRow>,
    metadata: Map<String, LifeConversationMetadata>,
): Boolean {
    val row = rows.firstOrNull { it.id == item.conversationId } ?: return false
    val facts = metadata[item.conversationId] ?: return false
    val isLifeOrigin = facts.origin in setOf(
        LifeConversationOrigin.LIFE_TEXT,
        LifeConversationOrigin.LIFE_PHOTO,
        LifeConversationOrigin.LIFE_VOICE,
        LifeConversationOrigin.STANDARD,
    )
    val hasStoredConfiguration = row.agentId != null ||
        row.agentVersion != null ||
        !row.defaultProviderId.isNullOrBlank() ||
        !row.defaultModel.isNullOrBlank() ||
        row.promptOriginal.isNotBlank() ||
        row.promptOptimized.isNotBlank() ||
        row.promptFinal.isNotBlank()
    // Any non-empty request snapshot is evidence that the row was used for a
    // request. Keep it visible when decoding is unavailable or incomplete.
    val hasRequestContent = !row.latestRequestContextJson.isNullOrBlank() ||
        !row.firstUserRequestContextJson.isNullOrBlank()
    return !item.isArchived &&
        row.projectId == null &&
        isLifeOrigin &&
        item.status == LifeConversationDisplayStatus.NONE &&
        row.messageCount == 0L &&
        !item.hasDraft &&
        row.openExecutionCount == 0L &&
        !hasStoredConfiguration &&
        !hasRequestContent &&
        (row.title.isBlank() || row.title in setOf("新会话", "新问题")) &&
        !facts.userRetained &&
        facts.customTitle.isNullOrBlank()
}

internal fun lifeDisplayStatus(
    executionStatus: String?,
    recovery: LifeConversationRecoveryEntry?,
    draft: LifeConversationDraftEntry?,
): LifeConversationDisplayStatus = when {
    recovery?.phase == LifeConversationRecoveryPhase.UNKNOWN -> LifeConversationDisplayStatus.WAITING_CONFIRMATION
    recovery?.phase == LifeConversationRecoveryPhase.IN_FLIGHT -> LifeConversationDisplayStatus.WAITING
    recovery?.phase == LifeConversationRecoveryPhase.NOT_LANDED -> LifeConversationDisplayStatus.FAILED
    executionStatus == "FAILED" -> LifeConversationDisplayStatus.FAILED
    executionStatus == "RUNNING" -> LifeConversationDisplayStatus.GENERATING
    executionStatus == "QUEUED" -> LifeConversationDisplayStatus.WAITING
    executionStatus == "CANCELLED" -> LifeConversationDisplayStatus.STOPPED
    executionStatus == "INTERRUPTED" -> LifeConversationDisplayStatus.FAILED
    draft?.hasUnavailableAttachments == true -> LifeConversationDisplayStatus.FAILED
    draft?.hasPendingWork == true -> LifeConversationDisplayStatus.WAITING
    draft?.hasContent == true -> LifeConversationDisplayStatus.DRAFT
    executionStatus == "SUCCEEDED" -> LifeConversationDisplayStatus.COMPLETED
    else -> LifeConversationDisplayStatus.NONE
}

internal fun lifeConversationTitle(
    row: LifeConversationRoomRow,
    draft: LifeConversationDraftEntry?,
    metadata: LifeConversationMetadata?,
    recovery: LifeConversationRecoveryEntry? = null,
    requestContext: ChatExecutionRequestContext? = null,
    firstRequestContext: ChatExecutionRequestContext? = null,
): String {
    metadata?.customTitle?.trim()?.takeUnless { it.isNullOrBlank() }?.let { return it }

    val rowTitle = row.title.trim()
    // Older document messages predate typed request context. Only replace a
    // title that exactly matches our automatic generator and a complete known
    // attachment envelope; the stored title and message remain untouched.
    row.firstUserText?.takeIf { rowTitle == smartConversationTitle(it).trim() }?.let { firstMessage ->
        val header = Regex("^【附件：([^\\n】]+)】\\n").find(firstMessage)
        if (header != null && firstMessage.contains("\n【附件结束】")) {
            return header.groupValues[1].removeSuffix("，已截断").truncateForOverview()
        }
    }
    val contexts = listOfNotNull(firstRequestContext, requestContext)
    val isUnnamed = rowTitle.isBlank() || rowTitle == "新会话" || rowTitle == "新问题"
    val legacyAttachmentTitleMatched = isConfirmedLegacyAttachmentTitle(
        rowTitle = rowTitle,
        contexts = contexts,
    )
    if (!isUnnamed && !legacyAttachmentTitleMatched) return rowTitle

    val explicitQuestion = listOfNotNull(
        firstRequestContext?.userInputText?.firstMeaningfulLine(),
        requestContext?.userInputText?.firstMeaningfulLine(),
    ).firstOrNull()
    explicitQuestion?.let { return it.truncateForOverview() }

    row.firstUserText
        ?.takeUnless {
            (legacyAttachmentTitleMatched && it.looksLikeAttachmentProtocol()) ||
                isConfirmedLegacyAttachmentPayload(it, contexts)
        }
        ?.firstMeaningfulLine()
        ?.let { return it.truncateForOverview() }
    row.lastUserText
        ?.takeUnless {
            (legacyAttachmentTitleMatched && it.looksLikeAttachmentProtocol()) ||
                isConfirmedLegacyAttachmentPayload(it, contexts)
        }
        ?.firstMeaningfulLine()
        ?.let { return it.truncateForOverview() }
    draft?.text?.firstMeaningfulLine()?.let { return it.truncateForOverview() }
    recovery?.currentDraftText?.firstMeaningfulLine()?.let { return it.truncateForOverview() }
    recovery?.submittedText?.firstMeaningfulLine()?.let { return it.truncateForOverview() }

    val documents = contexts
        .asSequence()
        .map { it.documents }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
    if (documents.size == 1) {
        documents.first().fileName.trim().takeUnless { it.isBlank() }?.let { return it }
    }
    if (documents.size > 1) return "关于 ${documents.size} 个文件的提问"

    return when {
        (draft?.documentCount ?: 0) == 1 -> {
            draft?.firstDocumentName?.trim()?.takeUnless { it.isBlank() } ?: "文件提问"
        }
        (draft?.documentCount ?: 0) > 1 -> "关于 ${draft?.documentCount} 个文件的提问"
        (draft?.imageCount ?: 0) > 0 -> "照片提问"
        (draft?.totalAttachmentCount ?: 0) > 0 -> "照片或文件提问"
        else -> "未发送的问题"
    }
}

private fun isConfirmedLegacyAttachmentTitle(
    rowTitle: String,
    contexts: List<ChatExecutionRequestContext>,
): Boolean {
    if (!rowTitle.startsWith("【附件：")) return false
    return contexts.any { context ->
        if (context.documents.isEmpty()) return@any false
        val content = DocumentTextExtractor.withDocumentBlocks(
            userText = context.userInputText.orEmpty(),
            documents = context.documents.map { document ->
                ExtractedDocument(
                    uri = document.uri,
                    fileName = document.fileName,
                    mimeType = document.mimeType,
                    text = document.extractedText,
                    truncated = document.truncated,
                    originalCharCount = document.extractedText.length,
                )
            },
        )
        smartConversationTitle(content) == rowTitle
    }
}

private fun isConfirmedLegacyAttachmentPayload(
    userText: String,
    contexts: List<ChatExecutionRequestContext>,
): Boolean {
    val normalizedUserText = userText.trim()
    if (normalizedUserText.isBlank()) return false
    return contexts.any { context ->
        if (context.documents.isEmpty()) return@any false
        val content = DocumentTextExtractor.withDocumentBlocks(
            userText = context.userInputText.orEmpty(),
            documents = context.documents.map { document ->
                ExtractedDocument(
                    uri = document.uri,
                    fileName = document.fileName,
                    mimeType = document.mimeType,
                    text = document.extractedText,
                    truncated = document.truncated,
                    originalCharCount = document.extractedText.length,
                )
            },
        )
        content.trim() == normalizedUserText
    }
}

private fun String.looksLikeAttachmentProtocol(): Boolean =
    startsWith("【附件：") || startsWith("【附件结束】")

internal fun lifeConversationSummary(
    row: LifeConversationRoomRow,
    draft: LifeConversationDraftEntry?,
    recovery: LifeConversationRecoveryEntry? = null,
    requestContext: ChatExecutionRequestContext? = null,
): String {
    val draftSummary = draft?.text?.firstMeaningfulLine()?.truncateForSummary()
    val recoverySummary = recovery?.takeUnless { it.phase == LifeConversationRecoveryPhase.LANDED }
        ?.let { it.currentDraftText.firstMeaningfulLine() ?: it.submittedText.firstMeaningfulLine() }?.truncateForSummary()
    val requestSummary = requestContext?.userInputText?.firstMeaningfulLine()?.truncateForSummary()
    val requestDocumentName = requestContext?.documents?.firstOrNull()?.fileName?.trim()
    val draftImageCount = draft?.imageCount ?: 0
    val draftDocumentCount = draft?.documentCount ?: 0
    val firstDraftDocumentName = draft?.firstDocumentName?.trim()
    val unavailableSummary = when {
        (draft?.missingAttachmentCount ?: 0) > 0 -> "附件不可用，请重选"
        (draft?.failedAttachmentCount ?: 0) > 0 -> "附件读取失败，请重选"
        else -> null
    }
    return when {
        draftSummary != null && unavailableSummary != null ->
            "$draftSummary · $unavailableSummary".truncateForSummary()
        draftSummary != null -> draftSummary
        recoverySummary != null -> recoverySummary
        draft?.hasContent != true && row.lastAssistantText?.firstMeaningfulLine() != null ->
            row.lastAssistantText.firstMeaningfulLine()!!.truncateForSummary()
        requestSummary != null -> requestSummary
        unavailableSummary != null -> unavailableSummary
        (draft?.pendingAttachmentCount ?: 0) > 0 -> "正在读取附件"
        (draft?.importingAttachmentCount ?: 0) > 0 -> "正在读取附件"
        draft?.isPreparing == true -> "问题正在准备"
        draftImageCount > 0 && draftDocumentCount == 0 -> "已选 $draftImageCount 张照片"
        draftDocumentCount > 0 && draftImageCount == 0 -> {
            val name = firstDraftDocumentName
            if (!name.isNullOrBlank()) {
                "已选《$name》"
            } else {
                "已选 $draftDocumentCount 个文件"
            }
        }
        (draft?.totalAttachmentCount ?: 0) > 0 -> "已选 ${draft?.totalAttachmentCount ?: 0} 个附件"
        !requestDocumentName.isNullOrBlank() -> "已选《$requestDocumentName》"
        requestContext?.documents?.isNotEmpty() == true -> "已选 ${requestContext.documents.size} 个文件"
        row.lastAssistantText?.firstMeaningfulLine() != null -> row.lastAssistantText.firstMeaningfulLine()!!.truncateForSummary()
        row.lastUserText?.firstMeaningfulLine() != null -> row.lastUserText.firstMeaningfulLine()!!.truncateForSummary()
        else -> "还没有内容"
    }
}

private fun String.firstMeaningfulLine(): String? = lineSequence()
    .map(String::trim)
    .firstOrNull(String::isNotBlank)

private fun String.truncateForOverview(maxChars: Int = 28): String =
    if (length <= maxChars) this else take(maxChars - 1).trimEnd() + "…"

private fun String.truncateForSummary(maxChars: Int = 52): String =
    if (length <= maxChars) this else take(maxChars - 1).trimEnd() + "…"
