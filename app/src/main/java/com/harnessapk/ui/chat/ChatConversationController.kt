package com.harnessapk.ui.chat

import com.harnessapk.chat.Conversation
import com.harnessapk.chat.ChatSendRequestPhase
import com.harnessapk.chat.ChatSendRequestState
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

internal fun canAcceptChatSend(
    identityMessageStateKnown: Boolean,
    request: ChatSendRequestState?,
): Boolean = identityMessageStateKnown && request == null

data class ChatDraftUiState<T>(
    val text: String,
    val image: T?,
    val mimeType: String,
)

internal fun <T> reduceTerminalDraft(
    phase: ChatSendRequestPhase,
    submittedText: String,
    submittedImage: T?,
    submittedMimeType: String,
    currentText: String,
    currentImage: T?,
    currentMimeType: String,
): ChatDraftUiState<T> = when (phase) {
    ChatSendRequestPhase.LANDED -> ChatDraftUiState(
        text = if (currentText == submittedText) "" else currentText,
        image = if (
            currentImage == submittedImage && currentMimeType == submittedMimeType
        ) null else currentImage,
        mimeType = if (
            currentImage == submittedImage && currentMimeType == submittedMimeType
        ) "image/png" else currentMimeType,
    )
    ChatSendRequestPhase.NOT_LANDED -> ChatDraftUiState(
        text = currentText,
        image = currentImage,
        mimeType = currentMimeType,
    )
    ChatSendRequestPhase.IN_FLIGHT,
    ChatSendRequestPhase.UNKNOWN,
    -> error("仅能结算终态发送请求")
}
