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

    @Test
    fun pendingFirstMessageLocksIdentityBeforeMessagesFlowUpdates() {
        val state = conversationIdentityUiState(
            conversation(agentId = "a1"),
            emptyList(),
            listOf(agent("a1")),
            firstMessagePending = true,
        )

        assertFalse(state.mutable)
        assertEquals("a1", state.selectedAgentId)
    }

    @Test
    fun historicalConversationStaysLockedUntilPersistedMessageStateIsKnown() {
        val state = conversationIdentityUiState(
            conversation = conversation(agentId = "a1"),
            messages = emptyList(),
            agents = listOf(agent("a1")),
            messageStateKnown = false,
        )

        assertFalse(state.mutable)
    }

    @Test
    fun lockedIdentityKeepsPinnedAgentWhenAgentIsMissingOrNotReady() {
        val missing = conversationIdentityUiState(
            conversation(agentId = "missing"),
            listOf(userMessage("m1")),
            emptyList(),
        )
        val disabled = conversationIdentityUiState(
            conversation(agentId = "disabled"),
            listOf(userMessage("m1")),
            listOf(agent("disabled", status = AgentStatus.DRAFT)),
        )

        assertEquals("missing", missing.selectedAgentId)
        assertEquals("已安装人物", missing.selectedName)
        assertEquals("disabled", disabled.selectedAgentId)
        assertEquals("李德胜", disabled.selectedName)
    }

    @Test
    fun enqueueFailureReleasesFirstMessagePendingLock() {
        val accepted = reduceFirstMessagePending(
            pending = false,
            isFirstUserMessage = true,
            event = FirstMessagePendingEvent.SEND_ACCEPTED,
        )

        assertFalse(
            reduceFirstMessagePending(
                pending = accepted,
                isFirstUserMessage = true,
                event = FirstMessagePendingEvent.ENQUEUE_FAILED,
            ),
        )
    }

    @Test
    fun postSuccessFailureKeepsFirstMessagePendingLock() {
        assertTrue(
            reduceFirstMessagePending(
                pending = true,
                isFirstUserMessage = true,
                event = FirstMessagePendingEvent.POST_SUCCESS_FAILED,
            ),
        )
    }

    @Test
    fun userObservationReleasesFirstMessagePendingLock() {
        assertFalse(
            reduceFirstMessagePending(
                pending = true,
                isFirstUserMessage = true,
                event = FirstMessagePendingEvent.USER_OBSERVED,
            ),
        )
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
