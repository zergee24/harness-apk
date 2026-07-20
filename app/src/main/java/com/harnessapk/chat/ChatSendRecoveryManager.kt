package com.harnessapk.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    suspend fun awaitLanding(
        requestId: String,
        retryDelayMillis: Long = REQUEST_LOOKUP_RETRY_DELAY_MILLIS,
    ): ChatRequestLanding {
        while (currentCoroutineContext().isActive) {
            when (val landing = landingFor(requestId)) {
                ChatRequestLanding.Landed,
                ChatRequestLanding.NotLanded,
                -> return landing
                is ChatRequestLanding.Unknown -> delay(retryDelayMillis)
            }
        }
        throw CancellationException("请求落地确认已取消")
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
    }
}

class ChatSendRecoveryManager(
    private val scope: CoroutineScope,
    private val store: ChatSendRecoveryStore,
    private val controller: ChatSendController,
    private val retryDelayMillis: Long = 250L,
) {
    fun start(
        conversationId: String,
        state: ChatSendRequestState,
        enqueueRequest: EnqueueChatRequest,
    ): Job? {
        check(state.requestId == enqueueRequest.requestId) { "发送状态与请求 ID 不一致" }
        check(conversationId == enqueueRequest.conversationId) { "发送状态与会话 ID 不一致" }
        if (!store.start(conversationId, state)) return null
        return scope.launch { settle(conversationId, state.requestId, enqueueRequest) }
    }

    private suspend fun settle(conversationId: String, requestId: String, request: EnqueueChatRequest) {
        when (val settlement = controller.submit(request)) {
            is ChatSendSettlement.Accepted -> store.markLanded(conversationId, requestId)
            is ChatSendSettlement.AcceptedAfterFailure -> store.markLanded(
                conversationId,
                requestId,
                originalFailure = settlement.failure,
            )
            is ChatSendSettlement.Failed -> store.markNotLanded(
                conversationId,
                requestId,
                originalFailure = settlement.failure,
                cancellation = null,
            )
            is ChatSendSettlement.Cancelled -> {
                if (settlement.persisted) {
                    store.markLanded(conversationId, requestId, settlement.cancellation, settlement.cancellation)
                } else {
                    store.markNotLanded(conversationId, requestId, settlement.cancellation, settlement.cancellation)
                }
                throw settlement.cancellation
            }
            is ChatSendSettlement.Unknown -> {
                store.markUnknown(
                    conversationId = conversationId,
                    expectedRequestId = requestId,
                    originalFailure = settlement.originalFailure,
                    cancellation = settlement.cancellation,
                    lookupFailure = settlement.lookupFailure,
                )
                when (controller.awaitLanding(requestId, retryDelayMillis)) {
                    ChatRequestLanding.Landed -> store.markLanded(
                        conversationId,
                        requestId,
                        settlement.originalFailure,
                        settlement.cancellation,
                    )
                    ChatRequestLanding.NotLanded -> store.markNotLanded(
                        conversationId,
                        requestId,
                        settlement.originalFailure,
                        settlement.cancellation,
                    )
                    is ChatRequestLanding.Unknown -> error("awaitLanding must not return UNKNOWN")
                }
                settlement.cancellation?.let { throw it }
            }
        }
    }
}
