package com.harnessapk.ui.chat

import com.harnessapk.chat.ChatExecutionEntry
import com.harnessapk.chat.Conversation
import com.harnessapk.chat.EnqueueChatRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val selectionMutex = Mutex()
    private var latestGeneration = 0L
    private val _state = MutableStateFlow(ConversationIdentityControllerState())

    val state: StateFlow<ConversationIdentityControllerState> = _state.asStateFlow()

    fun canSend(): Boolean = !state.value.selectionPending

    fun selectIdentity(agentId: String?) {
        val generation = synchronized(this) {
            ++latestGeneration
        }
        _state.value = _state.value.copy(selectionPending = true, failure = null)
        scope.launch {
            val result = selectionMutex.withLock {
                runCatching {
                    selectDraft(agentId)
                    reloadConversation()
                }
            }
            if (generation == synchronized(this@ConversationIdentityController) { latestGeneration }) {
                _state.value = result.fold(
                    onSuccess = { refreshed ->
                        ConversationIdentityControllerState(
                            refreshedConversation = refreshed,
                            settledGeneration = generation,
                        )
                    },
                    onFailure = { failure ->
                        ConversationIdentityControllerState(
                            failure = failure,
                            settledGeneration = generation,
                        )
                    },
                )
            }
        }
    }
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
