package com.harnessapk.network

import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.provider.ProviderApiProtocol
import kotlinx.serialization.json.JsonObject

/**
 * function calling 的工具声明（OpenAI Chat Completions 协议）。
 * 只在工具确实可用时放进 [ChatRequest.tools]，避免模型空调用。
 */
data class ChatToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** assistant 消息携带的一条工具调用；arguments 是 JSON 字符串，按协议原样回传。 */
data class OutgoingToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class ChatRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val messages: List<OutgoingChatMessage>,
    val temperature: Double = 0.2,
    val reasoningEffort: String? = null,
    val nativeWebSearchMode: NativeWebSearchMode? = null,
    val readTimeoutMillis: Long? = null,
    val customHeaders: Map<String, String> = emptyMap(),
    val customBodyJson: String = "",
    val apiProtocol: ProviderApiProtocol = ProviderApiProtocol.OPENAI_COMPATIBLE,
    val maxOutputTokens: Int? = null,
    /** 本轮声明的 function tools；空表示不使用 function calling。 */
    val tools: List<ChatToolDefinition> = emptyList(),
    /** 工具选择策略，仅在有 [tools] 时下发（如 "auto"）。 */
    val toolChoice: String? = null,
)

data class OutgoingChatMessage(
    val role: String,
    val text: String,
    val imageDataUrls: List<String> = emptyList(),
    /** role=assistant 时随消息回传的工具调用（OpenAI 协议要求 assistant 的 tool_calls 原样出现在历史里）。 */
    val toolCalls: List<OutgoingToolCall> = emptyList(),
    /** role=tool 时对应 assistant tool_calls 的 id。 */
    val toolCallId: String? = null,
)

data class ChatDelta(
    val text: String,
)

class ChatHttpException(message: String) : Exception(message)
