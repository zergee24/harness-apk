package com.harnessapk.ui.chat

import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentStatus
import com.harnessapk.chat.ChatMessage
import com.harnessapk.chat.Conversation
import com.harnessapk.chat.MessageRole
import kotlinx.coroutines.CancellationException

data class ConversationIdentityOption(
    val agentId: String?,
    val name: String,
    val version: Int?,
)

data class ConversationIdentityUiState(
    val selectedAgentId: String?,
    val selectedName: String,
    val mutable: Boolean,
    val options: List<ConversationIdentityOption>,
)

internal enum class FirstMessagePendingEvent {
    SEND_ACCEPTED,
    ENQUEUE_FAILED,
    POST_SUCCESS_FAILED,
    USER_OBSERVED,
}

internal fun reduceFirstMessagePending(
    pending: Boolean,
    isFirstUserMessage: Boolean,
    event: FirstMessagePendingEvent,
    firstUserMessageLanded: Boolean = false,
): Boolean = when (event) {
    FirstMessagePendingEvent.SEND_ACCEPTED -> pending || isFirstUserMessage
    FirstMessagePendingEvent.ENQUEUE_FAILED ->
        if (isFirstUserMessage && !firstUserMessageLanded) false else pending
    FirstMessagePendingEvent.POST_SUCCESS_FAILED -> pending
    FirstMessagePendingEvent.USER_OBSERVED -> false
}

internal enum class FirstUserMessageLanding {
    NOT_LANDED,
    LANDED,
    UNKNOWN,
}

internal enum class EnqueueExceptionPresentation {
    SEND_FAILURE,
    QUEUED_WARNING,
    UNKNOWN_WARNING,
}

internal data class EnqueueExceptionDisposition(
    val pending: Boolean,
    val settlePersistedSend: Boolean,
    val clearDraft: Boolean,
    val presentation: EnqueueExceptionPresentation,
)

internal fun enqueueExceptionDisposition(
    pending: Boolean,
    isFirstUserMessage: Boolean,
    landing: FirstUserMessageLanding,
): EnqueueExceptionDisposition = when (landing) {
    FirstUserMessageLanding.NOT_LANDED -> EnqueueExceptionDisposition(
        pending = reduceFirstMessagePending(
            pending = pending,
            isFirstUserMessage = isFirstUserMessage,
            event = FirstMessagePendingEvent.ENQUEUE_FAILED,
        ),
        settlePersistedSend = false,
        clearDraft = false,
        presentation = EnqueueExceptionPresentation.SEND_FAILURE,
    )
    FirstUserMessageLanding.LANDED -> EnqueueExceptionDisposition(
        pending = reduceFirstMessagePending(
            pending = pending,
            isFirstUserMessage = isFirstUserMessage,
            event = FirstMessagePendingEvent.ENQUEUE_FAILED,
            firstUserMessageLanded = true,
        ),
        settlePersistedSend = true,
        clearDraft = true,
        presentation = EnqueueExceptionPresentation.QUEUED_WARNING,
    )
    FirstUserMessageLanding.UNKNOWN -> EnqueueExceptionDisposition(
        pending = pending || isFirstUserMessage,
        settlePersistedSend = false,
        clearDraft = false,
        presentation = EnqueueExceptionPresentation.UNKNOWN_WARNING,
    )
}

internal suspend fun settlePersistedSendThenRethrowCancellation(
    cancelled: CancellationException = CancellationException(),
    settle: suspend () -> Unit,
): Nothing {
    settle()
    throw cancelled
}

internal fun conversationIdentityUiState(
    conversation: Conversation?,
    messages: List<ChatMessage>,
    agents: List<Agent>,
    firstMessagePending: Boolean = false,
): ConversationIdentityUiState {
    val options = listOf(ConversationIdentityOption(null, "普通助手", null)) +
        agents.filter { it.status == AgentStatus.READY }.map {
            ConversationIdentityOption(it.id, it.name, it.activeVersion)
        }
    val mutable = !firstMessagePending && messages.none { it.role == MessageRole.USER }
    val selected = if (!mutable && conversation?.agentId != null) {
        val installedAgent = agents.firstOrNull { it.id == conversation.agentId }
        ConversationIdentityOption(
            agentId = conversation.agentId,
            name = installedAgent?.name ?: "已安装人物",
            version = conversation.agentVersion,
        )
    } else {
        options.firstOrNull { it.agentId == conversation?.agentId } ?: options.first()
    }
    return ConversationIdentityUiState(
        selectedAgentId = selected.agentId,
        selectedName = selected.name,
        mutable = mutable,
        options = options,
    )
}
