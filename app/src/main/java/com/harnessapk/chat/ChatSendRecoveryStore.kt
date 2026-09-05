package com.harnessapk.chat

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

data class ChatSendRequestState(
    val requestId: String,
    val submittedText: String,
    val submittedAttachments: List<PendingImageAttachment>,
    val isFirstUserMessage: Boolean,
    val currentDraftText: String = submittedText,
    val currentDraftAttachments: List<PendingImageAttachment> = submittedAttachments,
    val phase: ChatSendRequestPhase = ChatSendRequestPhase.IN_FLIGHT,
    val originalFailure: Throwable? = null,
    val cancellation: CancellationException? = null,
    val lookupFailure: Throwable? = null,
    /** Immutable request data captured before the idempotent enqueue starts. */
    val intent: ChatSendIntent? = null,
    /** Includes typed document snapshots when the caller has them available. */
    val currentDraftAttachmentSnapshots: List<ChatSendAttachmentSnapshot> = emptyList(),
    /** Automatic recovery attempts already spent for this UNKNOWN episode. */
    val automaticRecheckAttempts: Int = 0,
    /** The owning conversation is carried for observeAll/recovery callers. */
    val conversationId: String? = null,
    /**
     * Process-local diagnostic when a terminal transition could not be
     * journaled. It is intentionally excluded from [ChatSendRecoveryRecord]
     * so a failed write never looks like a durable terminal state.
     */
    val transientPersistenceFailure: Throwable? = null,
) {
    @Deprecated("Use submittedAttachments")
    constructor(
        requestId: String,
        submittedText: String,
        submittedImage: Uri?,
        submittedMimeType: String,
        isFirstUserMessage: Boolean,
        currentDraftText: String = submittedText,
        currentDraftImage: Uri? = submittedImage,
        currentDraftMimeType: String = submittedMimeType,
        phase: ChatSendRequestPhase = ChatSendRequestPhase.IN_FLIGHT,
        originalFailure: Throwable? = null,
        cancellation: CancellationException? = null,
        lookupFailure: Throwable? = null,
    ) : this(
        requestId = requestId,
        submittedText = submittedText,
        submittedAttachments = submittedImage?.let { listOf(PendingImageAttachment(it, submittedMimeType)) }.orEmpty(),
        isFirstUserMessage = isFirstUserMessage,
        currentDraftText = currentDraftText,
        currentDraftAttachments = currentDraftImage?.let {
            listOf(PendingImageAttachment(it, currentDraftMimeType))
        }.orEmpty(),
        phase = phase,
        originalFailure = originalFailure,
        cancellation = cancellation,
        lookupFailure = lookupFailure,
    )
}

enum class ChatSendRequestPhase {
    IN_FLIGHT,
    UNKNOWN,
    LANDED,
    NOT_LANDED,
}

sealed interface ChatSendStoreStartResult {
    data object Started : ChatSendStoreStartResult
    data object AlreadyPending : ChatSendStoreStartResult
    data class PersistenceFailed(val failure: Throwable) : ChatSendStoreStartResult
}

/**
 * Process-local state backed by an optional durable intent journal.
 *
 * Every mutation writes the complete next snapshot before publishing it to the
 * StateFlow. This gives callers a durable-first boundary: a failed journal write
 * cannot accidentally start enqueue or discard a draft. The no-argument
 * constructor deliberately remains an in-memory store for existing unit tests.
 */
class ChatSendRecoveryStore private constructor(
    private val persistence: ChatSendRecoveryPersistence?,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Boolean,
) {
    constructor() : this(null, true)

    constructor(context: Context) : this(FileChatSendRecoveryPersistence(context), true)

    constructor(persistence: ChatSendRecoveryPersistence) : this(persistence, true)

    private val lock = Any()
    private val states = MutableStateFlow<Map<String, ChatSendRequestState>>(emptyMap())
    /** Terminal records handed to the UI remain durable until acknowledged. */
    private val consumedTerminals = mutableMapOf<String, ChatSendRecoveryRecord>()
    /**
     * Conversations currently inside the archive transaction. This marker is
     * process-local by design: it closes the read-recovery/DAO-write race
     * without holding [lock] across a suspending database operation.
     */
    private val archiveInProgress = mutableSetOf<String>()
    private var loadFailure: Throwable? = null
    private var lastPersistenceFailure: Throwable? = null

    init {
        restorePersisted()
    }

    fun current(conversationId: String): ChatSendRequestState? = synchronized(lock) {
        states.value[conversationId]
    }

    fun snapshotAll(): List<ChatSendRequestState> = synchronized(lock) {
        states.value.values.toList()
    }

    fun observe(conversationId: String): Flow<ChatSendRequestState?> =
        states.map { it[conversationId] }

    fun observeAll(): Flow<List<ChatSendRequestState>> =
        states.map { stateMap -> orderedStates(stateMap.values) }

    /** The last journal error is diagnostic; a non-null load error blocks new sends. */
    fun persistenceFailure(): Throwable? = synchronized(lock) {
        loadFailure ?: lastPersistenceFailure
    }

    fun start(
        conversationId: String,
        request: ChatSendRequestState,
        intent: ChatSendIntent? = null,
    ): Boolean = startInternal(
        conversationId = conversationId,
        request = request,
        intent = intent,
        blockOnConsumedHandoff = false,
    ) is ChatSendStoreStartResult.Started

    fun startPersisted(
        conversationId: String,
        request: ChatSendRequestState,
        intent: ChatSendIntent? = null,
    ): ChatSendStoreStartResult = startInternal(
        // A durable terminal handoff is still a pending UI acknowledgement;
        // the manager path must not let it race a new user message.
        conversationId = conversationId,
        request = request,
        intent = intent,
        blockOnConsumedHandoff = true,
    )

    /**
     * Runs one archive transaction while preventing a new persisted send from
     * entering the conversation between recovery inspection and the archive
     * write. The JVM lock is held only while inspecting/registering and while
     * releasing the marker; [action] itself may safely suspend.
     *
     * A null result means the conversation already has an active recovery
     * state, a transient terminal-journal failure, a durable terminal handoff,
     * or another archive transaction. Known LANDED/NOT_LANDED states may be
     * archived.
     */
    suspend fun <T : Any> withArchiveGuard(
        conversationId: String,
        action: suspend () -> T,
    ): T? {
        val acquired = synchronized(lock) {
            if (archiveInProgress.contains(conversationId)) {
                false
            } else if (
                states.value[conversationId]?.let { state ->
                    state.phase in setOf(ChatSendRequestPhase.IN_FLIGHT, ChatSendRequestPhase.UNKNOWN) ||
                        state.transientPersistenceFailure != null
                } == true ||
                consumedTerminals.containsKey(conversationId)
            ) {
                false
            } else {
                archiveInProgress += conversationId
                true
            }
        }
        if (!acquired) return null
        return try {
            action()
        } finally {
            synchronized(lock) {
                archiveInProgress.remove(conversationId)
            }
        }
    }

    /**
     * Publishes a journal failure without changing the durable phase. The
     * diagnostic is process-local and is cleared by the next successful state
     * transition, allowing callers to retry the original request ID.
     */
    fun recordTransientPersistenceFailure(
        conversationId: String,
        expectedRequestId: String,
        failure: Throwable,
    ): Boolean = synchronized(lock) {
        val current = states.value[conversationId] ?: return@synchronized false
        if (current.requestId != expectedRequestId) return@synchronized false
        if (current.phase !in setOf(ChatSendRequestPhase.IN_FLIGHT, ChatSendRequestPhase.UNKNOWN)) {
            return@synchronized false
        }
        states.value = states.value + (
            conversationId to current.copy(transientPersistenceFailure = failure)
        )
        true
    }

    private fun startInternal(
        conversationId: String,
        request: ChatSendRequestState,
        intent: ChatSendIntent?,
        blockOnConsumedHandoff: Boolean,
    ): ChatSendStoreStartResult = synchronized(lock) {
        if (blockOnConsumedHandoff && archiveInProgress.contains(conversationId)) {
            return@synchronized ChatSendStoreStartResult.PersistenceFailed(
                IllegalStateException("会话正在归档，暂不能发送"),
            )
        }
        if (
            states.value.containsKey(conversationId) ||
            (blockOnConsumedHandoff && consumedTerminals.containsKey(conversationId))
        ) return@synchronized ChatSendStoreStartResult.AlreadyPending
        loadFailure?.let { failure ->
            return@synchronized ChatSendStoreStartResult.PersistenceFailed(failure)
        }
        val canonicalIntent = (intent ?: request.intent ?: ChatSendIntent.legacy(request)).let { candidate ->
            if (candidate.conversationId == conversationId) candidate
            else candidate.copy(conversationId = conversationId)
        }
        if (canonicalIntent.requestId != request.requestId) {
            return@synchronized ChatSendStoreStartResult.PersistenceFailed(
                IllegalArgumentException("发送意图与请求 ID 不一致"),
            )
        }
        val canonicalState = request.copy(
            intent = canonicalIntent,
            currentDraftAttachmentSnapshots = request.currentDraftAttachmentSnapshots.ifEmpty {
                canonicalIntent.originalAttachments
            },
            conversationId = conversationId,
            transientPersistenceFailure = null,
        )
        val nextStates = states.value + (conversationId to canonicalState)
        val nextConsumed = consumedTerminals - conversationId
        if (!persistLocked(nextStates, nextConsumed)) {
            return@synchronized ChatSendStoreStartResult.PersistenceFailed(
                requireNotNull(lastPersistenceFailure) { "发送意图未能落盘" },
            )
        }
        consumedTerminals.remove(conversationId)
        states.value = nextStates
        ChatSendStoreStartResult.Started
    }

    fun markUnknown(
        conversationId: String,
        expectedRequestId: String,
        originalFailure: Throwable,
        cancellation: CancellationException?,
        lookupFailure: Throwable,
    ): Boolean = transitionIfRequest(
        conversationId = conversationId,
        expectedRequestId = expectedRequestId,
        allowedPhases = setOf(ChatSendRequestPhase.IN_FLIGHT, ChatSendRequestPhase.UNKNOWN),
    ) { current ->
        current.copy(
            phase = ChatSendRequestPhase.UNKNOWN,
            originalFailure = originalFailure,
            cancellation = cancellation,
            lookupFailure = lookupFailure,
            transientPersistenceFailure = null,
        )
    }

    fun markLanded(
        conversationId: String,
        expectedRequestId: String,
        originalFailure: Throwable? = null,
        cancellation: CancellationException? = null,
    ): Boolean = transitionIfRequest(
        conversationId = conversationId,
        expectedRequestId = expectedRequestId,
        allowedPhases = setOf(ChatSendRequestPhase.IN_FLIGHT, ChatSendRequestPhase.UNKNOWN),
    ) { current ->
        current.copy(
            phase = ChatSendRequestPhase.LANDED,
            originalFailure = originalFailure,
            cancellation = cancellation,
            transientPersistenceFailure = null,
        )
    }

    fun markNotLanded(
        conversationId: String,
        expectedRequestId: String,
        originalFailure: Throwable,
        cancellation: CancellationException?,
    ): Boolean = transitionIfRequest(
        conversationId = conversationId,
        expectedRequestId = expectedRequestId,
        allowedPhases = setOf(ChatSendRequestPhase.IN_FLIGHT, ChatSendRequestPhase.UNKNOWN),
    ) { current ->
        current.copy(
            phase = ChatSendRequestPhase.NOT_LANDED,
            originalFailure = originalFailure,
            cancellation = cancellation,
            transientPersistenceFailure = null,
        )
    }

    /** Atomically spends one bounded automatic retry slot. */
    fun beginAutomaticRecheck(
        conversationId: String,
        expectedRequestId: String,
        maxAttempts: Int = DEFAULT_MAX_AUTOMATIC_RECHECKS,
    ): Boolean {
        var incremented = false
        val changed = transitionIfRequest(
            conversationId = conversationId,
            expectedRequestId = expectedRequestId,
            allowedPhases = setOf(ChatSendRequestPhase.UNKNOWN),
        ) { current ->
            if (current.automaticRecheckAttempts >= maxAttempts) current
            else {
                incremented = true
                current.copy(automaticRecheckAttempts = current.automaticRecheckAttempts + 1)
            }
        }
        return changed && incremented
    }

    fun updateCurrentDraft(
        conversationId: String,
        expectedRequestId: String,
        text: String,
        attachments: List<PendingImageAttachment>,
        attachmentSnapshots: List<ChatSendAttachmentSnapshot>? = null,
    ): Boolean = transitionIfRequest(
        conversationId = conversationId,
        expectedRequestId = expectedRequestId,
        allowedPhases = ChatSendRequestPhase.entries.toSet(),
    ) { current ->
        val nextSnapshots = attachmentSnapshots ?: mergeImageSnapshots(
            existing = current.currentDraftAttachmentSnapshots,
            attachments = attachments,
        )
        current.copy(
            currentDraftText = text,
            currentDraftAttachments = attachments.toList(),
            currentDraftAttachmentSnapshots = nextSnapshots,
        )
    }

    @Deprecated("Use the attachment-list overload")
    fun updateCurrentDraft(
        conversationId: String,
        expectedRequestId: String,
        text: String,
        image: Uri?,
        mimeType: String,
    ): Boolean = updateCurrentDraft(
        conversationId = conversationId,
        expectedRequestId = expectedRequestId,
        text = text,
        attachments = image?.let { listOf(PendingImageAttachment(it, mimeType)) }.orEmpty(),
    )

    /**
     * Marks a terminal record handed to the UI before removing it from the live
     * state. The durable consumed marker is retained until [acknowledgeTerminal]
     * so a process death between this call and draft restoration can show the
     * exact same snapshot again without enqueueing a second user message.
     */
    fun consumeTerminal(conversationId: String, expectedRequestId: String): ChatSendRequestState? = synchronized(lock) {
        val current = states.value[conversationId] ?: return@synchronized null
        if (
            current.requestId != expectedRequestId ||
            current.phase !in setOf(ChatSendRequestPhase.LANDED, ChatSendRequestPhase.NOT_LANDED)
        ) return@synchronized null
        val consumedRecord = ChatSendRecoveryRecord.fromState(
            state = current,
            consumed = true,
        )
        val nextStates = states.value - conversationId
        val nextConsumed = consumedTerminals + (conversationId to consumedRecord)
        if (!persistLocked(nextStates, nextConsumed)) return@synchronized null
        consumedTerminals[conversationId] = consumedRecord
        states.value = nextStates
        current
    }

    /**
     * Atomically removes a terminal record after the caller has durably saved
     * its restored draft. A failed write leaves the terminal state visible, so
     * the conversation remains blocked from starting a second send.
     *
     * This is the preferred completion boundary for new UI code. The older
     * consume/acknowledge pair remains available for existing callers.
     */
    fun finishTerminal(conversationId: String, expectedRequestId: String): Boolean = synchronized(lock) {
        val visible = states.value[conversationId]
        val candidate = visible ?: consumedTerminals[conversationId]?.toState() ?: return@synchronized false
        if (
            candidate.requestId != expectedRequestId ||
            candidate.phase !in setOf(ChatSendRequestPhase.LANDED, ChatSendRequestPhase.NOT_LANDED)
        ) return@synchronized false
        val nextStates = states.value - conversationId
        val nextConsumed = consumedTerminals - conversationId
        if (!persistLocked(nextStates, nextConsumed)) {
            if (visible == null) states.value = states.value + (conversationId to candidate)
            return@synchronized false
        }
        consumedTerminals.remove(conversationId)
        states.value = nextStates
        true
    }

    /** Removes the durable terminal handoff after the caller has restored its draft. */
    fun acknowledgeTerminal(conversationId: String, expectedRequestId: String): Boolean = synchronized(lock) {
        val record = consumedTerminals[conversationId] ?: return@synchronized false
        if (record.intent.requestId != expectedRequestId) return@synchronized false
        val nextConsumed = consumedTerminals - conversationId
        val visible = states.value[conversationId]
        val nextStates = if (visible?.requestId == expectedRequestId) {
            states.value - conversationId
        } else {
            states.value
        }
        if (!persistLocked(nextStates, nextConsumed)) {
            // consumeTerminal intentionally removes the live state only after
            // its tombstone is durable. If this legacy second step fails, put
            // the terminal back into the live view so canSend remains blocked.
            if (visible == null) states.value = states.value + (conversationId to record.toState())
            return@synchronized false
        }
        consumedTerminals.remove(conversationId)
        states.value = nextStates
        true
    }

    private fun transitionIfRequest(
        conversationId: String,
        expectedRequestId: String,
        allowedPhases: Set<ChatSendRequestPhase>,
        update: (ChatSendRequestState) -> ChatSendRequestState,
    ): Boolean = synchronized(lock) {
        val current = states.value[conversationId] ?: return@synchronized false
        if (current.requestId != expectedRequestId || current.phase !in allowedPhases) {
            return@synchronized false
        }
        // Any successful journal write clears the process-local diagnostic.
        val updated = update(current).copy(transientPersistenceFailure = null)
        val nextConsumed = consumedTerminals - conversationId
        if (!persistLocked(states.value + (conversationId to updated), nextConsumed)) return@synchronized false
        consumedTerminals.remove(conversationId)
        states.value = states.value + (conversationId to updated)
        true
    }

    private fun persistLocked(
        nextStates: Map<String, ChatSendRequestState>,
        nextConsumed: Map<String, ChatSendRecoveryRecord>,
    ): Boolean {
        val journal = persistence ?: return true
        return try {
            journal.save(
                nextStates.values.map { ChatSendRecoveryRecord.fromState(it) } + nextConsumed.values,
            )
            lastPersistenceFailure = null
            true
        } catch (failure: Throwable) {
            lastPersistenceFailure = failure
            false
        }
    }

    private fun restorePersisted() = synchronized(lock) {
        val journal = persistence ?: return@synchronized
        val records = try {
            journal.load()
        } catch (failure: Throwable) {
            loadFailure = failure
            return@synchronized
        }
        if (records.isEmpty()) return@synchronized
        val latestByConversation = records
            .filter { it.intent.conversationId.isNotBlank() }
            .groupBy { it.intent.conversationId }
            .mapValues { (_, candidates) -> candidates.maxByOrNull { it.updatedAtMillis }!! }
        val normalized = latestByConversation.values.map { record ->
            if (record.phase == ChatSendRequestPhase.IN_FLIGHT) {
                record.copy(
                    phase = ChatSendRequestPhase.UNKNOWN,
                    lookupFailureMessage = record.lookupFailureMessage ?: "进程重启后待确认",
                )
            } else {
                record
            }
        }
        states.value = normalized.associate { record ->
            record.intent.conversationId to record.copy(consumed = false).toState()
        }
        normalized.filter { it.consumed }.forEach { record ->
            consumedTerminals[record.intent.conversationId] = record
        }
        val normalizedByConversation = normalized.associateBy { it.intent.conversationId }
        val wasChanged = latestByConversation.any { (conversationId, previous) ->
            normalizedByConversation[conversationId] != previous
        }
        if (wasChanged) {
            runCatching {
                journal.save(normalized)
            }.onFailure { lastPersistenceFailure = it }
        }
    }

    private fun orderedStates(values: Collection<ChatSendRequestState>): List<ChatSendRequestState> =
        values.sortedWith(
            compareBy<ChatSendRequestState> { it.intent?.createdAtMillis ?: 0L }
                .thenBy { it.requestId },
        )

    private companion object {
        const val DEFAULT_MAX_AUTOMATIC_RECHECKS = 5

        fun mergeImageSnapshots(
            existing: List<ChatSendAttachmentSnapshot>,
            attachments: List<PendingImageAttachment>,
        ): List<ChatSendAttachmentSnapshot> {
            val documents = existing.filter { it.kind != ChatSendAttachmentKind.IMAGE }
            val images = attachments.mapIndexed { index, attachment ->
                ChatSendAttachmentSnapshot(
                    id = "image-$index-${attachment.uri}",
                    kind = ChatSendAttachmentKind.IMAGE,
                    uri = attachment.uri.toString(),
                    mimeType = attachment.mimeType,
                )
            }
            return documents + images
        }
    }
}

fun identityLockedForPendingSend(request: ChatSendRequestState?): Boolean =
    request?.isFirstUserMessage == true
