package com.harnessapk.chat

import com.harnessapk.agent.AgentRuntimeContext
import com.harnessapk.agent.AgentEvidence
import com.harnessapk.agent.AgentContextRequest
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.websearch.WebSearchCapability
import com.harnessapk.websearch.WebSearchContext
import com.harnessapk.websearch.WebSearchResult
import com.harnessapk.session.SessionRequestContext
import com.harnessapk.ui.chat.emptyChatPrimaryText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException

class SendMessageUseCaseSupportTest {
    @Test
    fun ordinaryConversationDoesNotInvokeAssemblerAndAgentConversationInvokesItOnce() = runTest {
        var calls = 0
        val provider: suspend (AgentContextRequest) -> AgentRuntimeContext? = {
            calls += 1
            AgentRuntimeContext("agent-1", 7, "prompt", emptyList())
        }

        assertEquals(
            null,
            assembleAgentContextForConversation(
                agentId = null,
                agentVersion = null,
                request = AgentContextRequest("", 0, "普通问题"),
                assembler = provider,
            ),
        )
        assertEquals(0, calls)

        val context = assembleAgentContextForConversation(
            agentId = "agent-1",
            agentVersion = 7,
            request = AgentContextRequest("agent-1", 7, "人格问题"),
            assembler = provider,
        )

        assertEquals("agent-1", context?.agentId)
        assertEquals(1, calls)
    }

    @Test
    fun agentRequestCarriesOnlyCurrentConversationMemoryAndSessionContext() {
        val session = SessionRequestContext(
            finalPrompt = "本会话目标",
            projectName = "Harness",
            deliverableTitle = "B8",
            projectContext = "仅当前会话项目资料",
            deliverableMarkdown = "当前文档",
        )

        val request = agentContextRequestForSend(
            query = "如何实现？",
            conversationMemory = "此前讨论过检索预算",
            sessionContext = session,
        )

        assertEquals("此前讨论过检索预算", request.conversationMemory)
        assertEquals("仅当前会话项目资料", request.projectContext)
        assertTrue(request.sessionContext.contains("本会话目标"))
        assertTrue(request.sessionContext.contains("Harness"))
        assertTrue(request.sessionContext.contains("B8"))
        assertTrue(request.sessionContext.contains("当前文档"))
        assertEquals(null, sessionContextOutsideAgentPrompt(session, AgentRuntimeContext("agent-1", 7, "p", emptyList())))
        assertEquals(session, sessionContextOutsideAgentPrompt(session, null))
    }

    @Test
    fun emptyAgentChatUsesNonPersistentOpenerWhileV1FallbackStaysUnchanged() {
        assertEquals("固定版本开场", emptyChatPrimaryText("固定版本开场"))
        assertEquals("开始一段对话", emptyChatPrimaryText(null))
        assertEquals("开始一段对话", emptyChatPrimaryText("  "))
    }

    @Test
    fun agentRequestDisablesExternalAndNativeWebSearch() {
        val web = WebSearchContext(
            WebSearchResult(
                query = "调查",
                providerId = "jina",
                capability = WebSearchCapability.SEARCH_KEYWORDS,
                inputs = listOf("调查"),
                results = emptyList(),
            ),
        )
        val contexts = effectiveAgentSearchContexts(
            agentContext = AgentRuntimeContext("agent-1", 1, "严格资料", emptyList()),
            webSearchContext = web,
            nativeWebSearchMode = NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS,
        )

        assertEquals(null, contexts.webSearchContext)
        assertEquals(null, contexts.nativeWebSearchMode)
        assertFalse(webSearchAllowedForAgentConversation(agentId = "agent-1"))
        assertTrue(webSearchAllowedForAgentConversation(agentId = null))
    }

    @Test
    fun appendVisibleTextPartKeepsExistingStableTextPartUnchanged() {
        val snapshot = StreamingMessageSnapshot(
            status = MessageStatus.SUCCEEDED,
            parts = listOf(
                UiMessagePartDraft(
                    index = 0,
                    type = UiMessagePartType.TEXT,
                    content = "正文答案",
                    stable = true,
                ),
            ),
        )

        val next = appendVisibleTextPart(snapshot, "\n\n来源：example.com")

        assertEquals(2, next.parts.size)
        assertEquals("正文答案", next.parts[0].content)
        assertTrue(next.parts[0].stable)
        assertEquals(UiMessagePartType.TEXT, next.parts[1].type)
        assertEquals("\n\n来源：example.com", next.parts[1].content)
        assertTrue(next.parts[1].stable)
        assertEquals("正文答案\n\n来源：example.com", next.legacyVisibleText())
    }

    @Test
    fun appendVisibleTextPartIgnoresBlankText() {
        val snapshot = StreamingMessageSnapshot(
            status = MessageStatus.STREAMING,
            parts = emptyList(),
        )

        assertEquals(snapshot, appendVisibleTextPart(snapshot, "   "))
    }

    @Test
    fun appendAgentSourcesPartKeepsEvidenceOutsideTheResponseText() {
        val snapshot = StreamingMessageSnapshot(
            status = MessageStatus.SUCCEEDED,
            parts = listOf(
                UiMessagePartDraft(
                    index = 0,
                    type = UiMessagePartType.TEXT,
                    content = "正文回答",
                    stable = true,
                ),
            ),
        )
        val evidence = listOf(
            AgentEvidence("chunk-1", "实践论", "第一章", "资料内容", 8),
            AgentEvidence("chunk-2", "矛盾论", "第二章", "资料内容", 6),
        )

        val next = appendAgentSourcesPart(snapshot, evidence)

        assertEquals(2, next.parts.size)
        assertEquals(UiMessagePartType.AGENT_SOURCES, next.parts.last().type)
        assertTrue(next.parts.last().content.contains("资料 1 · 实践论 · 第一章"))
        assertTrue(next.parts.last().content.contains("资料 2 · 矛盾论 · 第二章"))
        assertEquals("正文回答", next.legacyVisibleText())
    }

    @Test
    fun ordinaryChatSnapshotKeepsCitationLikeTextUntouched() {
        val snapshot = StreamingMessageSnapshot(
            status = MessageStatus.SUCCEEDED,
            parts = listOf(
                UiMessagePartDraft(0, UiMessagePartType.TEXT, "搜索结果见[资料1]。", stable = true),
            ),
        )

        val next = sanitizeAgentSnapshotIfNeeded(snapshot, agentContext = null)

        assertEquals("搜索结果见[资料1]。", next.legacyVisibleText())
    }

    @Test
    fun cancelStreamingSnapshotIncludesBufferedUnflushedText() {
        val accumulator = StreamingMessageAccumulator(
            flushIntervalMillis = 1_000L,
            maxBufferedChars = 100,
        )
        accumulator.onEvent(StreamEvent.TextDelta("已落库"), nowMillis = 0L)
        accumulator.onEvent(StreamEvent.TextDelta("未到节流窗口"), nowMillis = 20L)

        val snapshot = cancelStreamingSnapshot(accumulator, nowMillis = 30L)!!

        assertEquals(MessageStatus.CANCELLED, snapshot.status)
        assertEquals("已落库未到节流窗口", snapshot.legacyVisibleText())
        assertTrue(snapshot.parts.all { it.stable })
    }

    @Test
    fun backgroundSocketAbortGetsOneControlledStreamRetry() {
        val abort = SocketException("Software caused connection abort")

        assertTrue(shouldRetryStreamAfterTransportFailure(abort, retriesUsed = 0))
        assertFalse(shouldRetryStreamAfterTransportFailure(abort, retriesUsed = 1))
        assertFalse(shouldRetryStreamAfterTransportFailure(IllegalStateException("HTTP 401"), retriesUsed = 0))
    }

}
