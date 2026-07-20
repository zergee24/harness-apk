package com.harnessapk.ui.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.harnessapk.chat.ChatMessage
import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Rule
import org.junit.Test

class ChatOpenerVisibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun openerWaitsForAnEmptyPersistedHistoryAndHidesWhenAnyMessageAppears() {
        var messageState by mutableStateOf<PersistedMessagesState>(PersistedMessagesState.Loading)

        composeRule.setContent {
            HarnessApkTheme {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    emptyChatStateItem(
                        messageState = messageState,
                        contentMaxWidth = 360.dp,
                        showProviderHint = false,
                        agentOpening = "固定版本开场",
                    )
                }
            }
        }

        composeRule.onNodeWithText("固定版本开场").assertDoesNotExist()

        composeRule.runOnIdle {
            messageState = PersistedMessagesState.Loaded(listOf(historicalMessage()))
        }
        composeRule.onNodeWithText("固定版本开场").assertDoesNotExist()

        composeRule.runOnIdle {
            messageState = PersistedMessagesState.Loaded(emptyList())
        }
        composeRule.onNodeWithText("固定版本开场").assertIsDisplayed()

        composeRule.runOnIdle {
            messageState = PersistedMessagesState.Loaded(listOf(historicalMessage(id = "sent-message")))
        }
        composeRule.onNodeWithText("固定版本开场").assertDoesNotExist()
    }

    @Test
    fun emptyV1ConversationKeepsTheDefaultEmptyState() {
        composeRule.setContent {
            HarnessApkTheme {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    emptyChatStateItem(
                        messageState = PersistedMessagesState.Loaded(emptyList()),
                        contentMaxWidth = 360.dp,
                        showProviderHint = false,
                        agentOpening = null,
                    )
                }
            }
        }

        composeRule.onNodeWithText("开始一段对话").assertIsDisplayed()
    }

    private fun historicalMessage(id: String = "historical-message") = ChatMessage(
        id = id,
        conversationId = "conversation",
        role = MessageRole.ASSISTANT,
        content = "历史回复",
        status = MessageStatus.SUCCEEDED,
        providerId = null,
        model = null,
        errorMessage = null,
    )
}
