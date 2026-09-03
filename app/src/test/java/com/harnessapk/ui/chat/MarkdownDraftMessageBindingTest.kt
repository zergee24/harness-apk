package com.harnessapk.ui.chat

import com.harnessapk.chat.ChatMessage
import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownDraftMessageBindingTest {
    @Test
    fun oldAssistantDraftUsesItsPrecedingUserInsteadOfLatestConversationUser() {
        val messages = listOf(
            message("user-1", MessageRole.USER),
            message("assistant-1", MessageRole.ASSISTANT),
            message("user-2", MessageRole.USER),
            message("assistant-2", MessageRole.ASSISTANT),
        )

        assertEquals("user-1", sourceUserMessageIdForAssistant(messages, "assistant-1"))
        assertEquals("user-2", sourceUserMessageIdForAssistant(messages, "assistant-2"))
    }

    private fun message(id: String, role: MessageRole) = ChatMessage(
        id = id,
        conversationId = "conversation",
        role = role,
        content = id,
        status = MessageStatus.SUCCEEDED,
        providerId = null,
        model = null,
        errorMessage = null,
    )
}
