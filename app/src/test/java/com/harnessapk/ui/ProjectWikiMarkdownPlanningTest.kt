package com.harnessapk.ui

import com.harnessapk.chat.ChatMessage
import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import com.harnessapk.session.MarkdownFileChangePlanningException
import com.harnessapk.session.WikiMarkdownCitation
import com.harnessapk.session.WikiMarkdownCitationSet
import com.harnessapk.session.WikiMarkdownSourceContext
import com.harnessapk.ui.chat.loadProjectMarkdownWikiContext
import com.harnessapk.ui.chat.markdownFileChangeConversationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectWikiMarkdownPlanningTest {
    @Test
    fun assistantPlanningLoadsOnlyTheExactAssistantMessageCitationContext() = runBlocking {
        val expected = WikiMarkdownSourceContext(
            citations = WikiMarkdownCitationSet(listOf(citation("assistant-exact"))),
            coverage = com.harnessapk.session.WikiEvidenceCoverage.NONE,
        )
        var requestedMessageIds: List<String>? = null

        val result = loadProjectMarkdownWikiContext(listOf("assistant-exact")) { messageIds ->
            requestedMessageIds = messageIds
            expected
        }

        assertEquals(listOf("assistant-exact"), requestedMessageIds)
        assertEquals(expected.citations.citations, result.citations.citations)
    }

    @Test
    fun userFileChangeUsesTheSameBoundedMessageIdsAsItsConversationText() = runBlocking {
        val messages = listOf(
            message("user-old", MessageRole.USER, "先讨论背景"),
            message("assistant-evidence", MessageRole.ASSISTANT, "这是带引用的结论"),
            message("system-event", MessageRole.SYSTEM, "不应进入上下文"),
            message("assistant-new", MessageRole.ASSISTANT, "这是最近结论"),
        )
        val history = markdownFileChangeConversationContext(messages)
        var requestedMessageIds: List<String>? = null

        loadProjectMarkdownWikiContext(history.messageIds) { messageIds ->
            requestedMessageIds = messageIds
            WikiMarkdownSourceContext(
                citations = WikiMarkdownCitationSet.EMPTY,
                coverage = com.harnessapk.session.WikiEvidenceCoverage.NONE,
            )
        }

        assertEquals(listOf("user-old", "assistant-evidence", "assistant-new"), history.messageIds)
        assertEquals(history.messageIds, requestedMessageIds)
        assertTrue(history.text.contains("用户：先讨论背景"))
        assertTrue(history.text.contains("助手：这是最近结论"))
    }

    @Test
    fun citationReadFailureStopsPlanningWithAConciseDraftError() {
        val error = assertThrows(MarkdownFileChangePlanningException::class.java) {
            runBlocking {
                loadProjectMarkdownWikiContext(listOf("assistant-1")) {
                    error("stored citation row is invalid")
                }
            }
        }

        assertEquals("无法读取本轮引用，未生成文件变更", error.message)
    }

    private fun citation(sourceMessageId: String): WikiMarkdownCitation = WikiMarkdownCitation(
        citationId = "00000000-0000-0000-0000-000000000001",
        sourceMessageId = sourceMessageId,
        displayOrdinal = 1,
        wikiId = "history.24",
        wikiVersion = 2,
        wikiTitle = "二十四史",
        sourceTitle = "汉书",
        sectionPath = "汉书 / 卷一",
        locatorLabel = "第 1 节",
        originalTextSnapshot = "可核对原文",
        originalTextSha256 = "a".repeat(64),
    )

    private fun message(id: String, role: MessageRole, content: String): ChatMessage = ChatMessage(
        id = id,
        conversationId = "conversation",
        role = role,
        content = content,
        status = MessageStatus.SUCCEEDED,
        providerId = null,
        model = null,
        errorMessage = null,
    )
}
