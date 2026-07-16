package com.harnessapk.chat

import com.harnessapk.agent.AgentRuntimeContext
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.websearch.WebSearchCapability
import com.harnessapk.websearch.WebSearchContext
import com.harnessapk.websearch.WebSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException

class SendMessageUseCaseSupportTest {
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
