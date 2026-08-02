package com.harnessapk.ui.conversation

import com.harnessapk.chat.Conversation
import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationListUiStateTest {
    @Test
    fun agentConversationUsesUnifiedSimulationDisclosure() {
        val conversation = conversation("agent", "人物会话", 1L, null).copy(agentId = "a1")
        val label = conversationIdentityLabel(conversation, mapOf("a1" to agent("a1", "李德胜")))

        assertEquals("李德胜 · 基于资料模拟", label)
    }

    @Test
    fun missingAgentNameUsesInstalledPersonFallbackAndAssistantHasNoDisclosure() {
        assertEquals(
            "已安装人物 · 基于资料模拟",
            conversationIdentityLabel(conversation("agent", "人物会话", 1L, null).copy(agentId = "missing"), emptyMap()),
        )
        assertEquals(null, conversationIdentityLabel(conversation("assistant", "普通会话", 1L, null), emptyMap()))
    }

    @Test
    fun conversationMetadataShowsPersonForCombinedConversation() {
        val agents = mapOf("a1" to agent("a1", "李德胜"))

        assertEquals(
            "李德胜 · 基于资料模拟",
            conversationMetadataLabel(
                conversation("combined", "人物会话", 1L, null).copy(agentId = "a1"),
                agents,
            ),
        )
        assertEquals(
            null,
            conversationMetadataLabel(conversation("assistant", "普通会话", 1L, null), agents),
        )
    }

    @Test
    fun lifeConversationsKeepsOnlyNonProjectConversations() {
        val list = listOf(
            conversation(id = "c1", title = "普通", updatedAt = 1L, projectId = null),
            conversation(id = "c2", title = "项目", updatedAt = 2L, projectId = "p1"),
            conversation(id = "c3", title = "普通", updatedAt = 3L, projectId = null),
        )
        assertEquals(listOf("c1", "c3"), lifeConversations(list).map { it.id })
    }

    private fun conversation(
        id: String,
        title: String,
        updatedAt: Long,
        projectId: String?,
    ): Conversation = Conversation(
        id = id,
        title = title,
        updatedAt = updatedAt,
        projectId = projectId,
        promptOriginal = "",
        promptOptimized = "",
        promptFinal = "",
    )

    private fun agent(id: String, name: String): Agent = Agent(
        id = id,
        name = name,
        summary = "",
        activeVersion = 1,
        publisherFingerprint = "fingerprint",
        status = AgentStatus.READY,
        requiredCorpusCount = 0,
        installedCorpusCount = 0,
    )
}
