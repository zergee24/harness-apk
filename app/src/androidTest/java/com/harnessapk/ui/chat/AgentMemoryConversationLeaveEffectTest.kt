package com.harnessapk.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AgentMemoryConversationLeaveEffectTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun conversationChangeAndRemovalNotifyTheConversationBeingLeft() {
        val visible = mutableStateOf(true)
        val conversationId = mutableStateOf("conversation-1")
        val notifications = mutableListOf<String>()
        composeRule.setContent {
            if (visible.value) {
                AgentMemoryConversationLeaveEffect(
                    conversationId = conversationId.value,
                    onConversationLeft = notifications::add,
                )
            }
        }

        composeRule.runOnIdle {
            assertEquals(emptyList<String>(), notifications)
            conversationId.value = "conversation-2"
        }
        composeRule.runOnIdle {
            assertEquals(listOf("conversation-1"), notifications)
            visible.value = false
        }
        composeRule.runOnIdle {
            assertEquals(listOf("conversation-1", "conversation-2"), notifications)
        }
    }
}
