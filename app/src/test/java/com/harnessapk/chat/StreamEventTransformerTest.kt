package com.harnessapk.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamEventTransformerTest {
    @Test
    fun thinkTagTransformerConvertsCompleteThinkBlockToReasoningPart() {
        val transformer = ThinkTagStreamTransformer()

        val events = transformer.transform(StreamEvent.TextDelta("<think>先分析</think>最终答案"))

        assertEquals(
            listOf(
                StreamEvent.ReasoningDelta("先分析"),
                StreamEvent.TextDelta("最终答案"),
            ),
            events,
        )
    }

    @Test
    fun thinkTagTransformerHandlesTagsSplitAcrossChunks() {
        val transformer = ThinkTagStreamTransformer()

        val events = listOf(
            StreamEvent.TextDelta("<thi"),
            StreamEvent.TextDelta("nk>先"),
            StreamEvent.TextDelta("分析</think>答案"),
        ).flatMap(transformer::transform)

        assertEquals(
            listOf(
                StreamEvent.ReasoningDelta("先"),
                StreamEvent.ReasoningDelta("分析"),
                StreamEvent.TextDelta("答案"),
            ),
            events,
        )
    }

    @Test
    fun thinkTagTransformerPassesNormalTextAndNativeReasoningThrough() {
        val transformer = ThinkTagStreamTransformer()

        val events = listOf(
            StreamEvent.TextDelta("普通回答"),
            StreamEvent.ReasoningDelta("provider reasoning"),
            StreamEvent.Finished("stop"),
        ).flatMap(transformer::transform)

        assertEquals(
            listOf(
                StreamEvent.TextDelta("普通回答"),
                StreamEvent.ReasoningDelta("provider reasoning"),
                StreamEvent.Finished("stop"),
            ),
            events,
        )
    }

    @Test
    fun thinkTagTransformerFlushesPartialTagBeforeTerminalEvent() {
        val transformer = ThinkTagStreamTransformer()

        val events = listOf(
            StreamEvent.TextDelta("答案 <thi"),
            StreamEvent.Finished("stop"),
        ).flatMap(transformer::transform)

        assertEquals(
            listOf(
                StreamEvent.TextDelta("答案 "),
                StreamEvent.TextDelta("<thi"),
                StreamEvent.Finished("stop"),
            ),
            events,
        )
    }

    @Test
    fun streamEventTransformerPipelineAppliesStatefulTransformersInOrder() {
        val pipeline = StreamEventTransformerPipeline(listOf(ThinkTagStreamTransformer()))

        val events = listOf(
            StreamEvent.TextDelta("开头<think>内部"),
            StreamEvent.TextDelta("</think>结尾"),
        ).flatMap(pipeline::transform)

        assertEquals(
            listOf(
                StreamEvent.TextDelta("开头"),
                StreamEvent.ReasoningDelta("内部"),
                StreamEvent.TextDelta("结尾"),
            ),
            events,
        )
    }
}
