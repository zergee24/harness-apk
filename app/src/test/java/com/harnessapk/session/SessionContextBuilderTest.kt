package com.harnessapk.session

import com.harnessapk.network.OutgoingChatMessage
import com.harnessapk.websearch.WebSearchCapability
import com.harnessapk.websearch.WebSearchContext
import com.harnessapk.websearch.WebSearchResult
import com.harnessapk.websearch.WebSearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionContextBuilderTest {
    @Test
    fun promptOptimizationRequestKeepsOriginalPromptAndAsksForStructuredOutput() {
        val messages = buildPromptOptimizationMessages(
            rawPrompt = "帮我整理需求",
            projectContext = "项目目标：家庭 LLM 工具",
            deliverableMarkdown = "# PRD\n旧内容",
        )

        assertEquals("system", messages.first().role)
        assertTrue(messages.last().text.contains("帮我整理需求"))
        assertTrue(messages.last().text.contains("角色"))
        assertTrue(messages.last().text.contains("目标"))
        assertTrue(messages.last().text.contains("约束"))
        assertTrue(messages.last().text.contains("输出格式"))
        assertTrue(messages.last().text.contains("项目目标：家庭 LLM 工具"))
        assertTrue(messages.last().text.contains("# PRD"))
    }

    @Test
    fun sessionContextIsPrependedWithoutChangingVisibleUserMessage() {
        val requestMessages = buildSessionOutgoingMessages(
            context = SessionRequestContext(
                finalPrompt = "你是需求整理助手",
                projectName = "Harness",
                deliverableTitle = "PRD.md",
                projectContext = "目标：移动端项目工作台",
                deliverableMarkdown = "# PRD\n内容",
            ),
            baseMessages = listOf(OutgoingChatMessage(role = "user", text = "帮我补全验收标准")),
        )

        assertEquals("system", requestMessages.first().role)
        assertTrue(requestMessages.first().text.contains("你是需求整理助手"))
        assertTrue(requestMessages.first().text.contains("Harness"))
        assertTrue(requestMessages.first().text.contains("PRD.md"))
        assertTrue(requestMessages.first().text.contains("目标：移动端项目工作台"))
        assertTrue(requestMessages.first().text.contains("# PRD"))
        assertEquals("帮我补全验收标准", requestMessages.last().text)
    }

    @Test
    fun webSearchContextIsPrependedWithStableSourceNumbers() {
        val requestMessages = buildSessionOutgoingMessages(
            context = null,
            baseMessages = listOf(OutgoingChatMessage(role = "user", text = "联网查一下")),
            webSearchContext = WebSearchContext(
                results = WebSearchResult(
                    query = "联网查一下",
                    providerId = "jina",
                    capability = WebSearchCapability.SEARCH_KEYWORDS,
                    inputs = listOf("联网查一下"),
                    results = listOf(
                        WebSearchSource(
                            id = "1",
                            title = "官方文档",
                            url = "https://example.com/docs",
                            domain = "example.com",
                            content = "完整内容",
                            snippet = "摘要",
                            accessedAt = 1000L,
                            sourceInput = "联网查一下",
                        ),
                    ),
                ),
            ),
        )

        assertEquals("system", requestMessages.first().role)
        assertTrue(requestMessages.first().text.contains("[1] 官方文档"))
        assertTrue(requestMessages.first().text.contains("使用 [1]"))
        assertEquals("联网查一下", requestMessages.last().text)
    }

    @Test
    fun agentContextPrecedesProjectContextAndHistory() {
        val requestMessages = buildSessionOutgoingMessages(
            context = SessionRequestContext(
                finalPrompt = "整理项目内容",
                projectName = "Harness",
                deliverableTitle = null,
                projectContext = "项目上下文",
                deliverableMarkdown = "",
            ),
            baseMessages = listOf(OutgoingChatMessage(role = "user", text = "为什么先调查")),
            agentSystemContext = "严格资料上下文",
        )

        assertEquals("system", requestMessages.first().role)
        assertTrue(requestMessages.first().text.startsWith("严格资料上下文"))
        assertTrue(requestMessages.first().text.indexOf("严格资料上下文") < requestMessages.first().text.indexOf("会话提示词"))
        assertEquals("为什么先调查", requestMessages.last().text)
    }

    @Test
    fun wikiContextIsPlacedAfterAgentAndBeforeProjectAndWebContext() {
        val requestMessages = buildSessionOutgoingMessages(
            context = SessionRequestContext(
                finalPrompt = "整理项目内容",
                projectName = "Harness",
                deliverableTitle = null,
                projectContext = "项目上下文",
                deliverableMarkdown = "",
            ),
            baseMessages = listOf(OutgoingChatMessage(role = "user", text = "查证")),
            agentSystemContext = "人格资料",
            wikiSystemContext = "Wiki 原文证据",
            webSearchContext = WebSearchContext(
                results = WebSearchResult(
                    query = "查证",
                    providerId = "jina",
                    capability = WebSearchCapability.SEARCH_KEYWORDS,
                    inputs = listOf("查证"),
                    results = listOf(
                        WebSearchSource(
                            id = "1",
                            title = "官方来源",
                            url = "https://example.com/source",
                            domain = "example.com",
                            content = "内容",
                            snippet = "摘要",
                            accessedAt = 1L,
                            sourceInput = "查证",
                        ),
                    ),
                ),
            ),
        )

        val system = requestMessages.first().text
        assertTrue(system.indexOf("人格资料") < system.indexOf("Wiki 原文证据"))
        assertTrue(system.indexOf("Wiki 原文证据") < system.indexOf("会话提示词"))
        assertTrue(system.indexOf("会话提示词") < system.indexOf("联网搜索来源"))
    }

    @Test
    fun writeBackRequiresProjectAndAssistantMarkdownButNotExistingDeliverable() {
        assertTrue(canWriteBackMarkdown("project", "deliverable", "# 建议"))
        assertTrue(canWriteBackMarkdown("project", null, "# 新沉淀"))
        assertFalse(canWriteBackMarkdown(null, null, "# 建议"))
        assertFalse(canWriteBackMarkdown("project", null, "  "))
    }
}
