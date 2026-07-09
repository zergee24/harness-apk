package com.harnessapk.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMessageAccumulatorTest {
    @Test
    fun firstTextDeltaFlushesImmediatelySoUserSeesProgress() {
        val accumulator = StreamingMessageAccumulator(
            flushIntervalMillis = 300L,
            maxBufferedChars = 320,
        )

        val flush = accumulator.onEvent(StreamEvent.TextDelta("你"), nowMillis = 0L)

        val snapshot = flush!!.snapshot
        assertEquals(StreamingFlushReason.FIRST_DELTA, flush.reason)
        assertEquals(MessageStatus.STREAMING, snapshot.status)
        assertEquals(1, snapshot.parts.size)
        assertEquals(UiMessagePartType.TEXT, snapshot.parts.single().type)
        assertEquals("你", snapshot.parts.single().content)
        assertFalse(snapshot.parts.single().stable)
    }

    @Test
    fun textDeltasFlushByIntervalAfterFirstChunk() {
        val accumulator = StreamingMessageAccumulator(
            flushIntervalMillis = 300L,
            maxBufferedChars = 320,
        )

        accumulator.onEvent(StreamEvent.TextDelta("你"), nowMillis = 0L)
        assertNull(accumulator.onEvent(StreamEvent.TextDelta("好"), nowMillis = 100L))

        val flush = accumulator.onEvent(StreamEvent.TextDelta("啊"), nowMillis = 300L)

        assertEquals(StreamingFlushReason.INTERVAL, flush!!.reason)
        assertEquals("你好啊", flush.snapshot.parts.single().content)
    }

    @Test
    fun textDeltasFlushByBufferedSize() {
        val accumulator = StreamingMessageAccumulator(
            flushIntervalMillis = 1_000L,
            maxBufferedChars = 4,
        )

        accumulator.onEvent(StreamEvent.TextDelta("a"), nowMillis = 0L)

        val flush = accumulator.onEvent(StreamEvent.TextDelta("bcd"), nowMillis = 10L)

        assertEquals(StreamingFlushReason.SIZE, flush!!.reason)
        assertEquals("abcd", flush.snapshot.parts.single().content)
    }

    @Test
    fun switchingFromReasoningToTextClosesReasoningPart() {
        val accumulator = StreamingMessageAccumulator()

        accumulator.onEvent(StreamEvent.ReasoningDelta("先分析"), nowMillis = 0L)
        val flush = accumulator.onEvent(StreamEvent.TextDelta("结论"), nowMillis = 20L)

        val parts = flush!!.snapshot.parts
        assertEquals(StreamingFlushReason.STRUCTURE, flush.reason)
        assertEquals(2, parts.size)
        assertEquals(UiMessagePartType.REASONING, parts[0].type)
        assertEquals("先分析", parts[0].content)
        assertTrue(parts[0].stable)
        assertEquals(UiMessagePartType.TEXT, parts[1].type)
        assertEquals("结论", parts[1].content)
        assertFalse(parts[1].stable)
    }

    @Test
    fun toolCallDeltasMergeByToolIdAndClosePreviousTextPart() {
        val accumulator = StreamingMessageAccumulator()

        accumulator.onEvent(StreamEvent.TextDelta("我来查"), nowMillis = 0L)
        accumulator.onEvent(
            StreamEvent.ToolCallDelta(id = "tool-1", name = "search", argumentsDelta = "{\"q\""),
            nowMillis = 10L,
        )
        val flush = accumulator.onEvent(
            StreamEvent.ToolCallDelta(id = "tool-1", name = "search", argumentsDelta = ":\"天气\"}"),
            nowMillis = 20L,
        )

        val parts = flush!!.snapshot.parts
        assertEquals(2, parts.size)
        assertEquals(UiMessagePartType.TEXT, parts[0].type)
        assertTrue(parts[0].stable)
        assertEquals(UiMessagePartType.TOOL_CALL, parts[1].type)
        assertEquals("{\"q\":\"天气\"}", parts[1].content)
        assertEquals("tool-1", parts[1].metadata["id"])
        assertEquals("search", parts[1].metadata["name"])
    }

    @Test
    fun cancelStabilizesPartsAndIgnoresLateDeltas() {
        val accumulator = StreamingMessageAccumulator()
        accumulator.onEvent(StreamEvent.TextDelta("已经生成"), nowMillis = 0L)

        val cancelled = accumulator.cancel(nowMillis = 50L)
        val late = accumulator.onEvent(StreamEvent.TextDelta("不应出现"), nowMillis = 60L)

        assertEquals(StreamingFlushReason.CANCELLED, cancelled.reason)
        assertEquals(MessageStatus.CANCELLED, cancelled.snapshot.status)
        assertEquals("已经生成", cancelled.snapshot.parts.single().content)
        assertTrue(cancelled.snapshot.parts.single().stable)
        assertNull(late)
        assertEquals("已经生成", accumulator.snapshot().parts.single().content)
    }

    @Test
    fun finishStabilizesPartsAndIgnoresLateDeltas() {
        val accumulator = StreamingMessageAccumulator()
        accumulator.onEvent(StreamEvent.TextDelta("完成"), nowMillis = 0L)

        val finished = accumulator.onEvent(StreamEvent.Finished(reason = "stop"), nowMillis = 100L)
        val late = accumulator.onEvent(StreamEvent.TextDelta("late"), nowMillis = 120L)

        assertEquals(StreamingFlushReason.FINISHED, finished!!.reason)
        assertEquals(MessageStatus.SUCCEEDED, finished.snapshot.status)
        assertEquals("stop", finished.snapshot.finishReason)
        assertTrue(finished.snapshot.parts.single().stable)
        assertNull(late)
        assertEquals("完成", accumulator.snapshot().parts.single().content)
    }

    @Test
    fun legacyVisibleTextUsesTextPartsOnlyUntilPartRendererIsReady() {
        val accumulator = StreamingMessageAccumulator()
        accumulator.onEvent(StreamEvent.ReasoningDelta("隐藏思考"), nowMillis = 0L)
        accumulator.onEvent(StreamEvent.TextDelta("可见答案"), nowMillis = 10L)
        accumulator.onEvent(
            StreamEvent.ToolCallDelta(id = "tool-1", name = "search", argumentsDelta = "{}"),
            nowMillis = 20L,
        )

        assertEquals("可见答案", accumulator.snapshot().legacyVisibleText())
    }

    @Test
    fun legacyVisibleDeltaReturnsOnlyNewSuffixWhenSnapshotExtendsPreviousText() {
        val accumulator = StreamingMessageAccumulator()
        accumulator.onEvent(StreamEvent.TextDelta("你好"), nowMillis = 0L)
        accumulator.onEvent(StreamEvent.TextDelta("世界"), nowMillis = 400L)

        assertEquals("世界", accumulator.snapshot().legacyVisibleDelta(previousVisibleText = "你好"))
    }

    @Test
    fun legacyVisibleDeltaReturnsReplacementWhenSnapshotNoLongerExtendsPreviousText() {
        val snapshot = StreamingMessageSnapshot(
            status = MessageStatus.STREAMING,
            parts = listOf(
                UiMessagePartDraft(
                    index = 0,
                    type = UiMessagePartType.TEXT,
                    content = "新的完整文本",
                    stable = false,
                ),
            ),
        )

        assertEquals("新的完整文本", snapshot.legacyVisibleDelta(previousVisibleText = "旧文本"))
    }
}
