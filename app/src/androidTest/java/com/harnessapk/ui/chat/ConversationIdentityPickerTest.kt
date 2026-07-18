package com.harnessapk.ui.chat

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentStatus
import com.harnessapk.chat.ChatMessage
import com.harnessapk.chat.Conversation
import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConversationIdentityPickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun draftPickerSelectsReadyPersonAndLockedPickerOnlyShowsDisclosure() {
        var selectedAgentId: String? = null
        var detailsShown = 0
        val draft = ConversationIdentityUiState(
            selectedAgentId = null,
            selectedName = "普通助手",
            mutable = true,
            options = listOf(
                ConversationIdentityOption(null, "普通助手", null),
                ConversationIdentityOption("a1", "李德胜", 3),
            ),
        )

        var state by mutableStateOf(draft)
        composeRule.setContent {
            HarnessApkTheme {
                ConversationIdentityPicker(
                    state = state,
                    onSelectAgentId = { selectedAgentId = it },
                    onShowDetails = { detailsShown += 1 },
                )
            }
        }

        composeRule.onNodeWithText("普通助手").performClick()
        composeRule.onNodeWithText("李德胜").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals("a1", selectedAgentId) }

        composeRule.runOnIdle {
            state = draft.copy(selectedAgentId = "a1", selectedName = "李德胜", mutable = false)
        }

        composeRule.onNodeWithText("李德胜 · 基于资料模拟").assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.runOnIdle { assertEquals(1, detailsShown) }
    }

    @Test
    fun pendingAndUserLockedStatesRemovePickerMenuAndAssistantDisclosure() {
        val agent = agent()
        val mutable = conversationIdentityUiState(conversation(), emptyList(), listOf(agent))
        var state by mutableStateOf(mutable)
        composeRule.setContent {
            HarnessApkTheme {
                ConversationIdentityPicker(state, onSelectAgentId = {}, onShowDetails = {})
            }
        }

        composeRule.onNodeWithText("普通助手").performClick()
        composeRule.onNodeWithText("李德胜").assertIsDisplayed()
        composeRule.runOnIdle {
            state = conversationIdentityUiState(
                conversation(agentId = agent.id),
                emptyList(),
                listOf(agent),
                firstMessagePending = true,
            )
        }
        composeRule.onNodeWithText("李德胜").assertDoesNotExist()
        composeRule.onNodeWithText("李德胜 · 基于资料模拟").assertIsDisplayed()

        composeRule.runOnIdle {
            state = conversationIdentityUiState(conversation(), listOf(userMessage()), listOf(agent))
        }
        composeRule.onNodeWithText("普通助手").assertDoesNotExist()
        composeRule.onNodeWithText("普通助手 · 基于资料模拟").assertDoesNotExist()
    }

    private fun conversation(agentId: String? = null): Conversation = Conversation(
        id = "c1",
        title = "会话",
        updatedAt = 1L,
        promptOriginal = "",
        promptOptimized = "",
        promptFinal = "",
        agentId = agentId,
        agentVersion = agentId?.let { 3 },
    )

    private fun agent(): Agent = Agent(
        id = "a1",
        name = "李德胜",
        summary = "",
        activeVersion = 3,
        publisherFingerprint = "fingerprint",
        status = AgentStatus.READY,
        requiredCorpusCount = 1,
        installedCorpusCount = 1,
    )

    private fun userMessage(): ChatMessage = ChatMessage(
        id = "m1",
        conversationId = "c1",
        role = MessageRole.USER,
        content = "你好",
        status = MessageStatus.SUCCEEDED,
        providerId = null,
        model = null,
        errorMessage = null,
    )
}
