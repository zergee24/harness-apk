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
