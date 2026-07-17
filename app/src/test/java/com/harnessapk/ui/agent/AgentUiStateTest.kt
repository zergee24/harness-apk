package com.harnessapk.ui.agent

import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentStatus
import com.harnessapk.chat.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentUiStateTest {
    @Test
    fun readyAgentCanStartAndShowsCorpusCoverage() {
        val agent = agent(status = AgentStatus.READY, installed = 2, required = 2)

        assertTrue(canStartAgent(agent))
        assertEquals("可用", agentStatusLabel(agent.status))
        assertEquals("资料 2/2", agentCorpusCoverage(agent))
    }

    @Test
    fun waitingAgentCannotStart() {
        val agent = agent(status = AgentStatus.WAITING_FOR_CORPUS, installed = 1, required = 2)

        assertFalse(canStartAgent(agent))
        assertEquals("缺少资料", agentStatusLabel(agent.status))
    }

    @Test
    fun formatsPackageSizeWithoutExcessPrecision() {
        assertEquals("900 B", formatAgentPackageSize(900))
        assertEquals("1.5 MB", formatAgentPackageSize(1_572_864))
    }

    @Test
    fun agentTopicsOnlyIncludeThatAgentAndKeepTheMostRecentFirst() {
        val topics = agentTopicConversations(
            agent = agent(status = AgentStatus.READY, installed = 1, required = 1),
            conversations = listOf(
                conversation(id = "older", agentId = "agent-1", updatedAt = 100),
                conversation(id = "other-agent", agentId = "agent-2", updatedAt = 300),
                conversation(id = "latest", agentId = "agent-1", updatedAt = 200),
                conversation(id = "regular", agentId = null, updatedAt = 400),
            ),
        )

        assertEquals(listOf("latest", "older"), topics.map(Conversation::id))
    }

    private fun agent(status: AgentStatus, installed: Int, required: Int): Agent = Agent(
        id = "agent-1",
        name = "资料研究代理",
        summary = "基于资料模拟",
        activeVersion = 1,
        publisherFingerprint = "fingerprint",
        status = status,
        requiredCorpusCount = required,
        installedCorpusCount = installed,
    )

    private fun conversation(id: String, agentId: String?, updatedAt: Long) = Conversation(
        id = id,
        title = id,
        updatedAt = updatedAt,
        promptOriginal = "",
        promptOptimized = "",
        promptFinal = "",
        agentId = agentId,
        agentVersion = agentId?.let { 1 },
    )
}
