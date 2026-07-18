package com.harnessapk.ui.chat

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
}

class ChatSendController(
    private val enqueue: suspend (EnqueueChatRequest) -> ChatExecutionEntry,
    private val requestExists: suspend (String) -> Boolean,
) {
    suspend fun submit(request: EnqueueChatRequest): ChatSendSettlement = try {
        ChatSendSettlement.Accepted(enqueue(request))
    } catch (cancelled: CancellationException) {
        ChatSendSettlement.Cancelled(
            requestId = request.requestId,
            cancellation = cancelled,
            persisted = withContext(NonCancellable) { requestExists(request.requestId) },
        )
    } catch (failure: Throwable) {
        if (requestExists(request.requestId)) {
            ChatSendSettlement.AcceptedAfterFailure(request.requestId, failure)
        } else {
            ChatSendSettlement.Failed(request.requestId, failure)
        }
    }

    fun settleText(currentText: String, submittedText: String): String =
        if (currentText == submittedText) "" else currentText
}
