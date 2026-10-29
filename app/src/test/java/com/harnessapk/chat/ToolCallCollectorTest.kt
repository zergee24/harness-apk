package com.harnessapk.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallCollectorTest {
    @Test
    fun `同一调用的参数分片按到达顺序拼接`() {
        val collector = ToolCallCollector()
        collector.accept(StreamEvent.ToolCallDelta("call_a", "image_generate", "{\"prompt\":"))
        collector.accept(StreamEvent.ToolCallDelta("", null, "\"一只橘猫\","))
        collector.accept(StreamEvent.ToolCallDelta("", null, "\"size\":\"1024x1536\"}"))

        assertEquals(
            listOf(CollectedToolCall("call_a", "image_generate", "{\"prompt\":\"一只橘猫\",\"size\":\"1024x1536\"}")),
            collector.completedCalls(),
        )
    }

    @Test
    fun `多个 index 的调用互不干扰`() {
        val collector = ToolCallCollector()
        collector.accept(StreamEvent.ToolCallDelta("call_a", "image_generate", "{\"prompt\":\"猫\"}", index = 0))
        collector.accept(StreamEvent.ToolCallDelta("call_b", "image_generate", "{\"prompt\":", index = 1))
        collector.accept(StreamEvent.ToolCallDelta("", null, "\"狗\"}", index = 1))

        assertEquals(
            listOf(
                CollectedToolCall("call_a", "image_generate", "{\"prompt\":\"猫\"}"),
                CollectedToolCall("call_b", "image_generate", "{\"prompt\":\"狗\"}"),
            ),
            collector.completedCalls(),
        )
    }

    @Test
    fun `缺少 id 时按 index 生成稳定兜底 id`() {
        val collector = ToolCallCollector()
        collector.accept(StreamEvent.ToolCallDelta("", "image_generate", "{}", index = 3))

        assertEquals(
            listOf(CollectedToolCall("tool-call-3", "image_generate", "{}")),
            collector.completedCalls(),
        )
    }

    @Test
    fun `非工具事件与空增量被忽略`() {
        val collector = ToolCallCollector()
        collector.accept(StreamEvent.TextDelta("你好"))
        collector.accept(StreamEvent.ToolCallDelta("", null, "", index = 0))

        assertTrue(collector.isEmpty())
        assertEquals(emptyList<CollectedToolCall>(), collector.completedCalls())
    }
}
