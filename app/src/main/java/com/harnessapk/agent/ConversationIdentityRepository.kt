package com.harnessapk.agent

import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AgentDao
import com.harnessapk.storage.ConversationDao
import com.harnessapk.storage.ConversationEntity
import com.harnessapk.storage.MessageDao

class ConversationIdentityRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val agentDao: AgentDao,
    private val timeProvider: TimeProvider,
) {
    suspend fun suggest(projectId: String?): ConversationIdentitySelection {
        val recent = if (projectId.isNullOrBlank()) {
            conversationDao.findLatestActive()
        } else {
            conversationDao.findLatestActiveInProject(projectId)
        }
        return selectionFor(recent?.agentId, locked = false)
    }

    suspend fun selectDraft(conversationId: String, agentId: String?): ConversationIdentitySelection {
        val conversation = requireNotNull(conversationDao.findById(conversationId)) { "会话不存在" }
        val selected = selectionFor(agentId, locked = false)
        check(
            conversationDao.updateIdentityIfNoUserMessages(
                id = conversation.id,
                agentId = selected.agentId,
                agentVersion = selected.agentVersion,
                updatedAt = timeProvider.nowMillis(),
            ) == 1,
        ) { "首条消息发送后不能切换身份" }
        return selected
    }

    suspend fun pinForFirstMessage(conversationId: String): ConversationIdentitySelection {
        val conversation = requireNotNull(conversationDao.findById(conversationId)) { "会话不存在" }
        if (messageDao.countUserMessages(conversationId) > 0) {
            return selectionForPinned(conversation)
        }
        val selected = selectionFor(conversation.agentId, locked = true)
        conversationDao.update(
            conversation.copy(
                agentId = selected.agentId,
                agentVersion = selected.agentVersion,
                updatedAt = timeProvider.nowMillis(),
            ),
        )
        return selected
    }

    suspend fun selectionForNewConversation(agentId: String): ConversationIdentitySelection {
        val agent = agentDao.findAgent(agentId)
        check(agent?.status == AgentStatus.READY.name) { "智能体资料尚未就绪" }
        return ConversationIdentitySelection(
            agentId = agent.id,
            agentVersion = agent.activeVersion,
            name = agent.name,
            locked = false,
        )
    }

    private suspend fun selectionFor(agentId: String?, locked: Boolean): ConversationIdentitySelection {
        val agent = agentId?.let { agentDao.findAgent(it) }?.takeIf { it.status == AgentStatus.READY.name }
        return ConversationIdentitySelection(
            agentId = agent?.id,
            agentVersion = agent?.activeVersion,
            name = agent?.name,
            locked = locked,
        )
    }

    private suspend fun selectionForPinned(conversation: ConversationEntity): ConversationIdentitySelection {
        val agent = conversation.agentId?.let { agentDao.findAgent(it) }
        return ConversationIdentitySelection(
            agentId = conversation.agentId,
            agentVersion = conversation.agentVersion,
            name = agent?.name,
            locked = true,
        )
    }
}
