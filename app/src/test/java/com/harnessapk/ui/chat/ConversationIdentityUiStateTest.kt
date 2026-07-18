package com.harnessapk.ui.chat

import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentStatus
import com.harnessapk.chat.ChatMessage
import com.harnessapk.chat.Conversation
import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationIdentityUiStateTest {
    @Test
    fun identityIsMutableOnlyBeforeFirstUserMessage() {
        val draft = conversationIdentityUiState(conversation(agentId = "a1"), emptyList(), listOf(agent("a1")))
        val sent = conversationIdentityUiState(
            conversation(agentId = "a1"),
            listOf(userMessage("m1")),
            listOf(agent("a1")),
        )

        assertTrue(draft.mutable)
        assertFalse(sent.mutable)
    }

    @Test
    fun assistantIsAlwaysTheFirstOption() {
        val state = conversationIdentityUiState(conversation(), emptyList(), listOf(agent("a1")))

        assertEquals(null, state.options.first().agentId)
        assertEquals("普通助手", state.options.first().name)
    }

    @Test
    fun disabledAgentsAreExcludedAndMissingSelectionFallsBackToAssistant() {
        val state = conversationIdentityUiState(
            conversation(agentId = "disabled"),
            emptyList(),
            listOf(agent("disabled", status = AgentStatus.DRAFT), agent("ready")),
        )

        assertEquals(null, state.selectedAgentId)
        assertEquals("普通助手", state.selectedName)
        assertEquals(listOf(null, "ready"), state.options.map { it.agentId })
    }

    private fun conversation(agentId: String? = null): Conversation = Conversation(
        id = "c1",
        title = "会话",
        updatedAt = 1L,
        promptOriginal = "",
        promptOptimized = "",
        promptFinal = "",
        agentId = agentId,
    )

    private fun agent(id: String, status: AgentStatus = AgentStatus.READY): Agent = Agent(
        id = id,
        name = if (id == "ready") "王五" else "李德胜",
        summary = "",
        activeVersion = 3,
        publisherFingerprint = "fingerprint",
        status = status,
        requiredCorpusCount = 2,
        installedCorpusCount = 2,
    )

    private fun userMessage(id: String): ChatMessage = ChatMessage(
        id = id,
        conversationId = "c1",
        role = MessageRole.USER,
        content = "你好",
        status = MessageStatus.SUCCEEDED,
        providerId = null,
        model = null,
        errorMessage = null,
    )
}
