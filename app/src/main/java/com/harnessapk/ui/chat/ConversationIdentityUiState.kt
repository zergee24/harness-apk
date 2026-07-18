package com.harnessapk.ui.chat

import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentStatus
import com.harnessapk.chat.ChatMessage
import com.harnessapk.chat.Conversation
import com.harnessapk.chat.MessageRole

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

internal fun conversationIdentityUiState(
    conversation: Conversation?,
    messages: List<ChatMessage>,
    agents: List<Agent>,
    firstMessagePending: Boolean = false,
    messageStateKnown: Boolean = true,
    persistedUserMessage: Boolean = false,
): ConversationIdentityUiState {
    val options = listOf(ConversationIdentityOption(null, "普通助手", null)) +
        agents.filter { it.status == AgentStatus.READY }.map {
            ConversationIdentityOption(it.id, it.name, it.activeVersion)
        }
    val mutable = messageStateKnown &&
        !persistedUserMessage &&
        !firstMessagePending &&
        messages.none { it.role == MessageRole.USER }
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
