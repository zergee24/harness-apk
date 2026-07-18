package com.harnessapk.agent

sealed interface InitialConversationIdentity {
    data object Suggested : InitialConversationIdentity
    data object Assistant : InitialConversationIdentity
    data class Agent(val agentId: String) : InitialConversationIdentity
}

data class ConversationIdentitySelection(
    val agentId: String?,
    val agentVersion: Int?,
    val name: String?,
    val locked: Boolean,
)
