package com.harnessapk.chat

/**
 * 一条流式累积完成的工具调用（OpenAI Chat Completions 协议）。
 *
 * [argumentsJson] 是各分片 `function.arguments` 增量按顺序拼接后的完整 JSON 字符串；
 * 可能为空、可能非法，由调用方在解析参数时统一处理。
 */
data class CollectedToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/**
 * 按 OpenAI 流式协议累积 `choices[0].delta.tool_calls[]`：
 * - 同一次响应里可能同时有多个调用，用 [StreamEvent.ToolCallDelta.index] 区分；
 * - `id` 与 `function.name` 通常只在第一片出现，后续分片只带 arguments 增量；
 * - 参数必须按到达顺序拼接，不能用最后一片覆盖前面的内容。
 */
class ToolCallCollector {
    private val calls = linkedMapOf<Int, MutableCall>()

    fun accept(event: StreamEvent) {
        if (event !is StreamEvent.ToolCallDelta) return
        // 空分片（既没 id / name 也没参数增量）不建立调用，避免把心跳帧算成一个空工具调用。
        if (event.id.isBlank() && event.name.isNullOrBlank() && event.argumentsDelta.isEmpty()) return
        val call = calls.getOrPut(event.index) { MutableCall() }
        if (event.id.isNotBlank()) call.id = event.id
        event.name?.takeIf { it.isNotBlank() }?.let { call.name = it }
        call.arguments.append(event.argumentsDelta)
    }

    fun isEmpty(): Boolean = calls.isEmpty()

    /**
     * 导出一轮流结束后的调用列表。
     * 个别代理不回传 id，这里按 index 生成稳定的兜底 id，保证后续 tool 回执能对上。
     */
    fun completedCalls(): List<CollectedToolCall> = calls.map { (index, call) ->
        CollectedToolCall(
            id = call.id.ifBlank { "tool-call-$index" },
            name = call.name,
            argumentsJson = call.arguments.toString(),
        )
    }

    private class MutableCall {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }
}
