package com.harnessapk.chat

import com.harnessapk.agent.ConversationIdentityRepository
import com.harnessapk.agent.ConversationIdentitySelection
import com.harnessapk.agent.InitialConversationIdentity

class NewConversationUseCase(
    private val chatRepository: ChatRepository,
    private val identityRepository: ConversationIdentityRepository,
) {
    suspend fun create(
        title: String = "新会话",
        projectId: String? = null,
        identity: InitialConversationIdentity = InitialConversationIdentity.Suggested,
    ): String {
        val selected = when (identity) {
            InitialConversationIdentity.Suggested -> identityRepository.suggest(projectId)
            InitialConversationIdentity.Assistant -> ConversationIdentitySelection(null, null, null, false)
            is InitialConversationIdentity.Agent -> identityRepository.selectionForNewConversation(identity.agentId)
        }
        return chatRepository.createConversation(
            title = title,
            projectId = projectId,
            agentId = selected.agentId,
            agentVersion = selected.agentVersion,
        )
    }
}
