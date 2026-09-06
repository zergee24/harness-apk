package com.harnessapk.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

sealed interface ChatSendSettlement {
    val requestId: String

    data class Accepted(val entry: ChatExecutionEntry) : ChatSendSettlement {
        override val requestId: String = entry.id
    }

    data class AcceptedAfterFailure(
        override val requestId: String,
        val failure: Throwable,
    ) : ChatSendSettlement

    data class Failed(
        override val requestId: String,
        val failure: Throwable,
    ) : ChatSendSettlement

    data class Cancelled(
        override val requestId: String,
        val cancellation: CancellationException,
        val persisted: Boolean,
    ) : ChatSendSettlement

    data class Unknown(
        override val requestId: String,
        val originalFailure: Throwable,
        val lookupFailure: Throwable,
        val cancellation: CancellationException?,
    ) : ChatSendSettlement
}

sealed interface ChatRequestLanding {
    data object Landed : ChatRequestLanding
    data object NotLanded : ChatRequestLanding
    data class Unknown(val lookupFailure: Throwable) : ChatRequestLanding
}

class ChatSendController(
    private val enqueue: suspend (EnqueueChatRequest) -> ChatExecutionEntry,
    private val requestExists: suspend (String) -> Boolean,
) {
    suspend fun submit(request: EnqueueChatRequest): ChatSendSettlement = try {
        ChatSendSettlement.Accepted(enqueue(request))
    } catch (cancelled: CancellationException) {
        when (val landing = landingFor(request.requestId)) {
            ChatRequestLanding.Landed -> ChatSendSettlement.Cancelled(request.requestId, cancelled, persisted = true)
            ChatRequestLanding.NotLanded -> ChatSendSettlement.Cancelled(request.requestId, cancelled, persisted = false)
            is ChatRequestLanding.Unknown -> ChatSendSettlement.Unknown(
                requestId = request.requestId,
                originalFailure = cancelled,
                lookupFailure = landing.lookupFailure,
                cancellation = cancelled,
            )
        }
    } catch (failure: Throwable) {
        when (val landing = landingFor(request.requestId)) {
            ChatRequestLanding.Landed -> ChatSendSettlement.AcceptedAfterFailure(request.requestId, failure)
            ChatRequestLanding.NotLanded -> ChatSendSettlement.Failed(request.requestId, failure)
            is ChatRequestLanding.Unknown -> ChatSendSettlement.Unknown(
                requestId = request.requestId,
                originalFailure = failure,
                lookupFailure = landing.lookupFailure,
                cancellation = null,
            )
        }
    }

    /** Performs one authoritative lookup. It never turns a lookup exception into NotLanded. */
    suspend fun check(requestId: String): ChatRequestLanding = landingFor(requestId)

    /**
     * Kept for legacy controller callers, but bounded so no caller can reopen
     * the old infinite 250 ms polling loop. New recovery uses [check] and its
     * manager-owned 1/2/4/8/15 second schedule.
     */
    suspend fun awaitLanding(
        requestId: String,
        retryDelayMillis: Long = REQUEST_LOOKUP_RETRY_DELAY_MILLIS,
    ): ChatRequestLanding {
        var lastFailure: Throwable = IllegalStateException("请求落地状态待确认")
        repeat(MAX_COMPATIBILITY_LOOKUPS) { attempt ->
            when (val landing = landingFor(requestId)) {
                ChatRequestLanding.Landed,
                ChatRequestLanding.NotLanded,
                -> return landing
                is ChatRequestLanding.Unknown -> {
                    lastFailure = landing.lookupFailure
                    if (attempt + 1 < MAX_COMPATIBILITY_LOOKUPS) {
                        delay(retryDelayMillis.coerceAtLeast(0L))
                    }
                }
            }
        }
        return ChatRequestLanding.Unknown(lastFailure)
    }

    fun settleText(currentText: String, submittedText: String): String =
        if (currentText == submittedText) "" else currentText

    private suspend fun landingFor(requestId: String): ChatRequestLanding = withContext(NonCancellable) {
        try {
            if (requestExists(requestId)) ChatRequestLanding.Landed else ChatRequestLanding.NotLanded
        } catch (failure: Throwable) {
            ChatRequestLanding.Unknown(failure)
        }
    }

    private companion object {
        const val REQUEST_LOOKUP_RETRY_DELAY_MILLIS = 250L
        const val MAX_COMPATIBILITY_LOOKUPS = 5
    }
}

sealed interface ChatSendStartResult {
    data class Started(
        val job: Job,
        val intent: ChatSendIntent,
    ) : ChatSendStartResult

    data object AlreadyPending : ChatSendStartResult

    data class PersistenceFailed(val failure: Throwable) : ChatSendStartResult
}

class ChatSendEnqueueStillInFlightException(message: String) : IllegalStateException(message)

/**
 * Owns one send attempt after its intent has been durably journaled.
 *
 * Recovery never calls enqueue again. It only checks the original request ID,
 * and all checks for one request share one Deferred. The actual enqueue job is
 * tracked separately so a lookup cannot classify a still-running enqueue as
 * NotLanded.
 */
class ChatSendRecoveryManager(
    private val scope: CoroutineScope,
    private val store: ChatSendRecoveryStore,
    private val controller: ChatSendController,
    private val retryDelayMillis: Long = LEGACY_RETRY_DELAY_MILLIS,
    private val automaticRetryDelaysMillis: List<Long> = DEFAULT_AUTOMATIC_RETRY_DELAYS_MILLIS,
) {
    private val lock = Any()
    private val submissionFinished = mutableMapOf<String, CompletableDeferred<Unit>>()
    /** Marks that the enqueue body actually entered controller.submit. */
    private val submissionStarted = mutableSetOf<String>()
    /** Bounded retries for a completed enqueue whose terminal journal write failed. */
    private val completedSubmissionRecoveryAttempts = mutableMapOf<String, Int>()
    private val checks = mutableMapOf<String, CompletableDeferred<ChatRequestLanding>>()
    private val checkingRequestIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Legacy entry point retained for ChatScreen. A null result means either an
     * existing pending send or a journal failure; use [startWithResult] when the
     * caller needs to distinguish those cases.
     */
    fun start(
        conversationId: String,
        state: ChatSendRequestState,
        enqueueRequest: EnqueueChatRequest,
        intent: ChatSendIntent? = null,
    ): Job? = (startWithResult(conversationId, state, enqueueRequest, intent) as? ChatSendStartResult.Started)?.job

    fun startWithResult(
        conversationId: String,
        state: ChatSendRequestState,
        enqueueRequest: EnqueueChatRequest,
        intent: ChatSendIntent? = null,
    ): ChatSendStartResult {
        check(state.requestId == enqueueRequest.requestId) { "发送状态与请求 ID 不一致" }
        check(conversationId == enqueueRequest.conversationId) { "发送状态与会话 ID 不一致" }
        val capturedIntent = try {
            intent ?: ChatSendIntent.from(state, enqueueRequest)
        } catch (failure: Throwable) {
            return ChatSendStartResult.PersistenceFailed(failure)
        }
        return when (val startResult = store.startPersisted(conversationId, state, capturedIntent)) {
            ChatSendStoreStartResult.AlreadyPending -> ChatSendStartResult.AlreadyPending
            is ChatSendStoreStartResult.PersistenceFailed ->
                ChatSendStartResult.PersistenceFailed(startResult.failure)
            ChatSendStoreStartResult.Started -> {
                val finished = CompletableDeferred<Unit>()
                synchronized(lock) {
                    submissionFinished[enqueueRequest.requestId] = finished
                    submissionStarted.remove(enqueueRequest.requestId)
                    completedSubmissionRecoveryAttempts.remove(enqueueRequest.requestId)
                }
                val job = scope.launch {
                    try {
                        settle(conversationId, enqueueRequest.requestId, enqueueRequest)
                    } finally {
                        finished.complete(Unit)
                        retainOrRemoveSubmissionProof(
                            conversationId = conversationId,
                            requestId = enqueueRequest.requestId,
                            finished = finished,
                        )
                    }
                }
                // A cancelled scope may cancel the Job before its body starts,
                // so the completion proof must follow the Job as well as the
                // normal settle/finally path.
                job.invokeOnCompletion {
                    finished.complete(Unit)
                    retainOrRemoveSubmissionProof(
                        conversationId = conversationId,
                        requestId = enqueueRequest.requestId,
                        finished = finished,
                    )
                }
                ChatSendStartResult.Started(job, capturedIntent)
            }
        }
    }

    /** Alias for callers that name the boundary after its durable side effect. */
    fun startPersisted(
        conversationId: String,
        state: ChatSendRequestState,
        enqueueRequest: EnqueueChatRequest,
        intent: ChatSendIntent? = null,
    ): ChatSendStartResult = startWithResult(conversationId, state, enqueueRequest, intent)

    private suspend fun settle(conversationId: String, requestId: String, request: EnqueueChatRequest) {
        synchronized(lock) {
            // A completed Job alone is insufficient: a cancelled coroutine may
            // finish before its body reaches the actual enqueue call.
            if (submissionFinished.containsKey(requestId)) submissionStarted += requestId
        }
        val settlement = try {
            controller.submit(request)
        } finally {
            // From this point on the enqueue attempt has returned or thrown. A
            // later NotLanded result therefore has an actual completion proof.
            synchronized(lock) {
                submissionFinished[requestId]?.complete(Unit)
            }
        }
        when (settlement) {
            is ChatSendSettlement.Accepted -> settleTerminalOrRecover(
                conversationId = conversationId,
                requestId = requestId,
                terminalWrite = { store.markLanded(conversationId, requestId) },
                failureMessage = "无法持久化已落地状态",
            )
            is ChatSendSettlement.AcceptedAfterFailure -> settleTerminalOrRecover(
                conversationId = conversationId,
                requestId = requestId,
                terminalWrite = {
                    store.markLanded(
                        conversationId,
                        requestId,
                        originalFailure = settlement.failure,
                    )
                },
                failureMessage = "无法持久化已落地状态",
            )
            is ChatSendSettlement.Failed -> settleTerminalOrRecover(
                conversationId = conversationId,
                requestId = requestId,
                terminalWrite = {
                    store.markNotLanded(
                        conversationId,
                        requestId,
                        originalFailure = settlement.failure,
                        cancellation = null,
                    )
                },
                failureMessage = "无法持久化未落地状态",
            )
            is ChatSendSettlement.Cancelled -> {
                val marked = if (settlement.persisted) {
                    store.markLanded(conversationId, requestId, settlement.cancellation, settlement.cancellation)
                } else {
                    store.markNotLanded(
                        conversationId,
                        requestId,
                        settlement.cancellation,
                        settlement.cancellation,
                    )
                }
                if (!marked) {
                    val failure = persistenceFailure(
                        if (settlement.persisted) "无法持久化已落地状态" else "无法持久化未落地状态",
                    )
                    store.recordTransientPersistenceFailure(conversationId, requestId, failure)
                    withContext(NonCancellable) {
                        recoverCompletedSubmission(conversationId, requestId)
                    }
                } else {
                    clearSubmissionProofIfTerminal(conversationId, requestId)
                }
                throw settlement.cancellation
            }
            is ChatSendSettlement.Unknown -> {
                val markedUnknown = store.markUnknown(
                    conversationId = conversationId,
                    expectedRequestId = requestId,
                    originalFailure = settlement.originalFailure,
                    cancellation = settlement.cancellation,
                    lookupFailure = settlement.lookupFailure,
                )
                if (markedUnknown) {
                    recoverUnknown(conversationId, requestId)
                } else {
                    val failure = persistenceFailure("无法持久化待确认状态")
                    store.recordTransientPersistenceFailure(conversationId, requestId, failure)
                    recoverCompletedSubmission(conversationId, requestId)
                }
                settlement.cancellation?.let { throw it }
            }
        }
    }

    private suspend fun settleTerminalOrRecover(
        conversationId: String,
        requestId: String,
        terminalWrite: () -> Boolean,
        failureMessage: String,
    ) {
        if (terminalWrite()) {
            clearSubmissionProofIfTerminal(conversationId, requestId)
            return
        }
        val failure = persistenceFailure(failureMessage)
        store.recordTransientPersistenceFailure(conversationId, requestId, failure)
        // The enqueue is already finished, but the phase intentionally stays
        // IN_FLIGHT until this exact terminal transition is durable. Retry the
        // authoritative lookup with the same request ID; never enqueue again.
        recoverCompletedSubmission(conversationId, requestId)
    }

    private fun persistenceFailure(fallbackMessage: String): Throwable =
        store.persistenceFailure() ?: IllegalStateException(fallbackMessage)

    private fun retainOrRemoveSubmissionProof(
        conversationId: String,
        requestId: String,
        finished: CompletableDeferred<Unit>,
    ) {
        val current = store.current(conversationId)
        synchronized(lock) {
            if (submissionFinished[requestId] !== finished) return@synchronized
            val retain = submissionStarted.contains(requestId) &&
                current?.requestId == requestId &&
                current.phase == ChatSendRequestPhase.IN_FLIGHT
            if (!retain) {
                submissionFinished.remove(requestId)
                submissionStarted.remove(requestId)
                completedSubmissionRecoveryAttempts.remove(requestId)
            }
        }
    }

    private fun hasCompletedSubmissionProof(requestId: String): Boolean = synchronized(lock) {
        submissionStarted.contains(requestId) && submissionFinished[requestId]?.isCompleted == true
    }

    private fun clearSubmissionProofIfTerminal(conversationId: String, requestId: String) {
        val current = store.current(conversationId)
        if (current?.requestId != requestId || current.phase !in TERMINAL_PHASES) return
        synchronized(lock) {
            submissionFinished.remove(requestId)
            submissionStarted.remove(requestId)
            completedSubmissionRecoveryAttempts.remove(requestId)
        }
    }

    /**
     * Manually confirms one request. Calls made while a lookup is running share
     * the same requestId and result. An IN_FLIGHT request is never queried until
     * its tracked enqueue job has completed.
     */
    suspend fun recheck(
        conversationId: String,
        expectedRequestId: String? = null,
    ): ChatRequestLanding? {
        var state = store.current(conversationId) ?: return null
        if (expectedRequestId != null && state.requestId != expectedRequestId) return null
        if (state.phase == ChatSendRequestPhase.IN_FLIGHT) {
            if (!hasCompletedSubmissionProof(state.requestId)) {
                return ChatRequestLanding.Unknown(
                    ChatSendEnqueueStillInFlightException("原发送仍未结束，暂不能判定未落地"),
                )
            }
            state = store.current(conversationId) ?: return null
        }
        return when (state.phase) {
            ChatSendRequestPhase.LANDED -> ChatRequestLanding.Landed
            ChatSendRequestPhase.NOT_LANDED -> ChatRequestLanding.NotLanded
            ChatSendRequestPhase.UNKNOWN,
            ChatSendRequestPhase.IN_FLIGHT,
            -> {
                awaitSubmissionIfNeeded(state.requestId)
                checkAndSettle(conversationId, state.requestId)
            }
        }
    }

    /** Rechecks every currently unresolved request once, as a foreground/startup action. */
    suspend fun recheckAll(): Map<String, ChatRequestLanding> {
        val pending = store.snapshotAll().filter {
            it.phase == ChatSendRequestPhase.UNKNOWN ||
                (it.phase == ChatSendRequestPhase.IN_FLIGHT && hasCompletedSubmissionProof(it.requestId))
        }
        val result = linkedMapOf<String, ChatRequestLanding>()
        pending.forEach { state ->
            val conversationId = state.conversationId ?: state.intent?.conversationId
            if (conversationId == null) return@forEach
            val landing = recheck(conversationId, state.requestId)
                ?: ChatRequestLanding.Unknown(IllegalStateException("发送恢复记录已变化"))
            result[state.requestId] = landing
        }
        return result
    }

    /** Schedules one startup/foreground pass without reopening an unbounded loop. */
    fun recoverPending(): Job = scope.launch { recheckAll() }

    /** Alias used by lifecycle owners when the app returns to the foreground. */
    fun onForeground(): Job = recoverPending()

    fun isChecking(conversationId: String): Boolean = synchronized(lock) {
        store.current(conversationId)?.requestId?.let { it in checkingRequestIds.value } == true
    }

    fun observeChecking(conversationId: String): Flow<Boolean> =
        checkingRequestIds.map { requestIds ->
            store.current(conversationId)?.requestId?.let { it in requestIds } == true
        }

    /** Convenience delegation for lifecycle/UI owners that depend on the manager. */
    fun observeAll(): Flow<List<ChatSendRequestState>> = store.observeAll()

    fun observeCheckingAll(): Flow<Set<String>> = checkingRequestIds

    private suspend fun recoverUnknown(conversationId: String, requestId: String) {
        val delays = effectiveAutomaticRetryDelays()
        for (delayMillis in delays) {
            delay(delayMillis)
            val current = store.current(conversationId) ?: return
            if (current.requestId != requestId || current.phase != ChatSendRequestPhase.UNKNOWN) return
            if (!store.beginAutomaticRecheck(conversationId, requestId)) return
            when (checkAndSettle(conversationId, requestId)) {
                ChatRequestLanding.Landed,
                ChatRequestLanding.NotLanded,
                -> return
                is ChatRequestLanding.Unknown -> Unit
            }
        }
    }

    /**
     * Retries lookup after enqueue completed but its terminal journal write
     * failed. This path deliberately accepts IN_FLIGHT only with the retained
     * completion proof; an active enqueue is never classified from a lookup.
     */
    private suspend fun recoverCompletedSubmission(conversationId: String, requestId: String) {
        for (delayMillis in effectiveAutomaticRetryDelays()) {
            delay(delayMillis)
            val current = store.current(conversationId) ?: return
            if (current.requestId != requestId || current.phase in TERMINAL_PHASES) return
            if (!hasCompletedSubmissionProof(requestId)) return
            if (!beginCompletedSubmissionRecoveryAttempt(requestId)) return
            when (checkAndSettle(conversationId, requestId)) {
                ChatRequestLanding.Landed,
                ChatRequestLanding.NotLanded,
                -> return
                is ChatRequestLanding.Unknown -> Unit
            }
        }
    }

    private fun beginCompletedSubmissionRecoveryAttempt(requestId: String): Boolean = synchronized(lock) {
        val attempts = completedSubmissionRecoveryAttempts[requestId] ?: 0
        if (attempts >= MAX_AUTOMATIC_RECHECKS) return@synchronized false
        completedSubmissionRecoveryAttempts[requestId] = attempts + 1
        true
    }

    private suspend fun awaitSubmissionIfNeeded(requestId: String) {
        val finished = synchronized(lock) { submissionFinished[requestId] } ?: return
        if (!finished.isCompleted) {
            withContext(NonCancellable) { finished.await() }
        }
    }

    private suspend fun checkAndSettle(
        conversationId: String,
        requestId: String,
    ): ChatRequestLanding {
        var launchCheck = false
        val deferred = synchronized(lock) {
            checks[requestId] ?: CompletableDeferred<ChatRequestLanding>().also {
                checks[requestId] = it
                checkingRequestIds.value = checkingRequestIds.value + requestId
                launchCheck = true
            }
        }
        if (launchCheck) {
            scope.launch {
                val result = try {
                    val landing = controller.check(requestId)
                    settleCheckedResult(conversationId, requestId, landing)
                } catch (failure: Throwable) {
                    ChatRequestLanding.Unknown(failure)
                }
                synchronized(lock) {
                    // Publish the UI flag before completing the Deferred, but
                    // keep the completed request in [checks] until after the
                    // completion. A caller arriving in either tiny window
                    // still joins this lookup instead of starting a duplicate.
                    if (checks[requestId] === deferred) {
                        checkingRequestIds.value = checkingRequestIds.value - requestId
                    }
                }
                deferred.complete(result)
                synchronized(lock) {
                    if (checks[requestId] === deferred) checks.remove(requestId)
                }
            }
        }
        return deferred.await()
    }

    private fun settleCheckedResult(
        conversationId: String,
        requestId: String,
        landing: ChatRequestLanding,
    ): ChatRequestLanding {
        val current = store.current(conversationId)
        if (current == null || current.requestId != requestId) return landing
        return when (landing) {
            ChatRequestLanding.Landed -> {
                if (store.markLanded(
                        conversationId = conversationId,
                        expectedRequestId = requestId,
                        originalFailure = current.originalFailure,
                        cancellation = current.cancellation,
                    )
                ) {
                    clearSubmissionProofIfTerminal(conversationId, requestId)
                    ChatRequestLanding.Landed
                } else {
                    val failure = persistenceFailure("无法持久化已落地状态")
                    store.recordTransientPersistenceFailure(conversationId, requestId, failure)
                    ChatRequestLanding.Unknown(
                        failure,
                    )
                }
            }
            ChatRequestLanding.NotLanded -> {
                if (store.markNotLanded(
                        conversationId = conversationId,
                        expectedRequestId = requestId,
                        originalFailure = current.originalFailure
                            ?: IllegalStateException("发送失败后未能确认请求"),
                        cancellation = current.cancellation,
                    )
                ) {
                    clearSubmissionProofIfTerminal(conversationId, requestId)
                    ChatRequestLanding.NotLanded
                } else {
                    val failure = persistenceFailure("无法持久化未落地状态")
                    store.recordTransientPersistenceFailure(conversationId, requestId, failure)
                    ChatRequestLanding.Unknown(
                        failure,
                    )
                }
            }
            is ChatRequestLanding.Unknown -> {
                if (!store.markUnknown(
                    conversationId = conversationId,
                    expectedRequestId = requestId,
                    originalFailure = current.originalFailure
                        ?: IllegalStateException("发送请求待确认"),
                    cancellation = current.cancellation,
                    lookupFailure = landing.lookupFailure,
                )) {
                    val failure = persistenceFailure("无法持久化待确认状态")
                    store.recordTransientPersistenceFailure(conversationId, requestId, failure)
                    ChatRequestLanding.Unknown(failure)
                } else {
                    landing
                }
            }
        }
    }

    private fun effectiveAutomaticRetryDelays(): List<Long> {
        if (retryDelayMillis != LEGACY_RETRY_DELAY_MILLIS) {
            return List(MAX_AUTOMATIC_RECHECKS) { retryDelayMillis.coerceAtLeast(0L) }
        }
        return automaticRetryDelaysMillis
            .take(MAX_AUTOMATIC_RECHECKS)
            .map { it.coerceAtLeast(0L) }
    }

    private companion object {
        const val MAX_AUTOMATIC_RECHECKS = 5
        const val LEGACY_RETRY_DELAY_MILLIS = 250L
        val DEFAULT_AUTOMATIC_RETRY_DELAYS_MILLIS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
        val TERMINAL_PHASES = setOf(ChatSendRequestPhase.LANDED, ChatSendRequestPhase.NOT_LANDED)
    }
}
