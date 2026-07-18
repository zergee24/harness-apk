package com.harnessapk.ui.chat

import android.net.Uri
import com.harnessapk.chat.ChatExecutionEntry
import com.harnessapk.chat.Conversation
import com.harnessapk.chat.EnqueueChatRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class ConversationIdentityControllerState(
    val selectionPending: Boolean = false,
    val refreshedConversation: Conversation? = null,
    val failure: Throwable? = null,
    val settledGeneration: Long = 0L,
)

class ConversationIdentityController(
    private val scope: CoroutineScope,
    private val selectDraft: suspend (String?) -> Unit,
    private val reloadConversation: suspend () -> Conversation?,
) {
    private var latestGeneration = 0L
    private val _state = MutableStateFlow(ConversationIdentityControllerState())
    private val requests = Channel<IdentitySelectionRequest>(Channel.UNLIMITED)

    val state: StateFlow<ConversationIdentityControllerState> = _state.asStateFlow()

    init {
        scope.launch {
            for (request in requests) {
                process(request)
            }
        }
    }

    fun canSend(): Boolean = !state.value.selectionPending

    fun selectIdentity(agentId: String?) {
        val generation = synchronized(this) {
            ++latestGeneration
        }
        _state.value = _state.value.copy(selectionPending = true, failure = null)
        check(requests.trySend(IdentitySelectionRequest(generation, agentId)).isSuccess) {
            "身份选择队列已关闭"
        }
    }

    private suspend fun process(request: IdentitySelectionRequest) {
        try {
            selectDraft(request.agentId)
            val refreshed = reloadConversation()
            if (isLatest(request.generation)) {
                _state.value = ConversationIdentityControllerState(
                    refreshedConversation = refreshed,
                    settledGeneration = request.generation,
                )
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            if (!isLatest(request.generation)) return
            val refreshed = withContext(NonCancellable) {
                runCatching { reloadConversation() }
            }
            if (!currentCoroutineContext().isActive) throw cancelled
            if (isLatest(request.generation)) {
                _state.value = refreshed.fold(
                    onSuccess = { conversation ->
                        ConversationIdentityControllerState(
                            refreshedConversation = conversation,
                            settledGeneration = request.generation,
                        )
                    },
                    onFailure = { failure ->
                        ConversationIdentityControllerState(
                            failure = failure,
                            settledGeneration = request.generation,
                        )
                    },
                )
            }
        } catch (failure: Throwable) {
            if (isLatest(request.generation)) {
                _state.value = ConversationIdentityControllerState(
                    failure = failure,
                    settledGeneration = request.generation,
                )
            }
        }
    }

    private fun isLatest(generation: Long): Boolean =
        generation == synchronized(this) { latestGeneration }

    private data class IdentitySelectionRequest(
        val generation: Long,
        val agentId: String?,
    )
}

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

data class ChatSendRequestState(
    val requestId: String,
    val submittedText: String,
    val draftImage: Uri?,
    val isFirstUserMessage: Boolean,
    val originalFailure: Throwable? = null,
    val cancellation: CancellationException? = null,
    val lookupFailure: Throwable? = null,
)

internal fun canAcceptChatSend(
    identityMessageStateKnown: Boolean,
    request: ChatSendRequestState?,
): Boolean = identityMessageStateKnown && request == null

class ChatSendController(
    private val enqueue: suspend (EnqueueChatRequest) -> ChatExecutionEntry,
    private val requestExists: suspend (String) -> Boolean,
) {
    suspend fun submit(request: EnqueueChatRequest): ChatSendSettlement = try {
        ChatSendSettlement.Accepted(enqueue(request))
    } catch (cancelled: CancellationException) {
        when (val landing = landingFor(request.requestId)) {
            ChatRequestLanding.Landed -> ChatSendSettlement.Cancelled(
                requestId = request.requestId,
                cancellation = cancelled,
                persisted = true,
            )
            ChatRequestLanding.NotLanded -> ChatSendSettlement.Cancelled(
                requestId = request.requestId,
                cancellation = cancelled,
                persisted = false,
            )
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

    private suspend fun landingFor(requestId: String): ChatRequestLanding = withContext(NonCancellable) {
        try {
            if (requestExists(requestId)) ChatRequestLanding.Landed else ChatRequestLanding.NotLanded
        } catch (failure: Throwable) {
            ChatRequestLanding.Unknown(failure)
        }
    }

    fun settleText(currentText: String, submittedText: String): String =
        if (currentText == submittedText) "" else currentText

    private companion object {
        const val REQUEST_LOOKUP_RETRY_DELAY_MILLIS = 250L
    }
}
