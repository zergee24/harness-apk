package com.harnessapk.ui.chat

import com.harnessapk.chat.ChatMessage
import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import com.harnessapk.chat.UiMessagePartDraft
import com.harnessapk.chat.UiMessagePartType
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatConversationSearchTest {
    @Test
    fun searchCoversContentProcessAndSourcesWithFilters() {
        val message = ChatMessage(
            id = "m1",
            conversationId = "c1",
            role = MessageRole.ASSISTANT,
            content = "最终答案",
            status = MessageStatus.SUCCEEDED,
            providerId = null,
            model = null,
            errorMessage = null,
        )
        val documents = buildConversationSearchDocuments(
            messages = listOf(message),
            partsByMessageId = mapOf(
                "m1" to listOf(
                    part(0, UiMessagePartType.TEXT, "正文命中"),
                    part(1, UiMessagePartType.REASONING, "过程命中"),
                    part(2, UiMessagePartType.AGENT_SOURCES, "来源命中"),
                ),
            ),
            citationsByMessageId = emptyMap(),
        )

        assertEquals(1, searchConversationDocuments(documents, "正文命中", ConversationSearchFilter.CONTENT).size)
        assertEquals(1, searchConversationDocuments(documents, "过程命中", ConversationSearchFilter.PROCESS).size)
        assertEquals(1, searchConversationDocuments(documents, "来源命中", ConversationSearchFilter.SOURCES).size)
        assertEquals(0, searchConversationDocuments(documents, "来源命中", ConversationSearchFilter.CONTENT).size)
        assertEquals(1, searchConversationDocuments(documents, "命中", ConversationSearchFilter.ALL).size)
    }

    private fun part(index: Int, type: UiMessagePartType, content: String) = UiMessagePartDraft(
        index = index,
        type = type,
        content = content,
        stable = true,
    )
}
