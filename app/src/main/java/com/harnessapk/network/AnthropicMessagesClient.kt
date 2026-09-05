package com.harnessapk.network

import com.harnessapk.chat.StreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Anthropic Messages 协议流式客户端（Claude / Claude Code 兼容端点，如 GLM Coding
 * Plan 的 /api/anthropic 网关）。system 提升为顶层字段，鉴权走 x-api-key +
 * anthropic-version，SSE 事件按 content_block_delta / message_delta 解析。
 */
class AnthropicMessagesClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) : ChatStreamClient {
    override fun streamChat(request: ChatRequest): Flow<ChatDelta> = flow {
        streamChatEvents(request).collect { event ->
            if (event is StreamEvent.TextDelta) emit(ChatDelta(event.text))
        }
    }

    override fun streamChatEvents(request: ChatRequest): Flow<StreamEvent> = flow {
        val httpRequestBuilder = Request.Builder()
            .url(anthropicMessagesUrl(request.baseUrl))
            .addHeader("x-api-key", request.apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")

        request.customHeaders.normalizedCustomHeaders().forEach { (key, value) ->
            if (!key.isProtectedHeader()) {
                httpRequestBuilder.addHeader(key, value)
            }
        }

        val httpRequest = httpRequestBuilder
            .post(buildBody(request).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = okHttpClient.forRequest(request).newCall(httpRequest)
        val cancellation = currentCoroutineContext().job.invokeOnCompletion {
            if (it != null) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw ChatHttpException(buildErrorMessage(response.code, response.body.string()))
                }

                val source = response.body.source()
                var sawStreamData = false
                var emittedOutput = false
                var emittedFinished = false
                var inputTokens: Int? = null
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    sawStreamData = true
                    val events = parseEvents(data, inputTokens)
                    inputTokens = events.inputTokens ?: inputTokens
                    events.events.forEach { event ->
                        when (event) {
                            is StreamEvent.TextDelta,
                            is StreamEvent.ImageDelta,
                            is StreamEvent.ReasoningDelta,
                            -> emittedOutput = true
                            is StreamEvent.Finished -> emittedFinished = true
                            else -> Unit
                        }
                        emit(event)
                    }
                    if (events.isMessageStop) {
                        if (!emittedFinished) emit(StreamEvent.Finished(reason = null))
                        break
                    }
                }
                if (!sawStreamData) {
                    throw ChatHttpException(
                        "LLM 返回格式异常：未收到流式数据，请检查 Base URL 是否为 Anthropic 兼容 API 地址",
                    )
                }
                if (!emittedOutput) {
                    throw ChatHttpException("LLM 返回为空：未收到可显示内容")
                }
            }
        } finally {
            cancellation.dispose()
        }
    }.flowOn(Dispatchers.IO)

    private fun buildBody(request: ChatRequest): JsonObject {
        val systemText = request.messages
            .filter { it.role.equals("system", ignoreCase = true) }
            .joinToString("\n\n") { it.text }
            .trim()
        val baseBody = buildJsonObject {
            put("model", JsonPrimitive(request.model))
            put(
                "max_tokens",
                JsonPrimitive(request.maxOutputTokens?.takeIf { it > 0 } ?: DEFAULT_MAX_OUTPUT_TOKENS),
            )
            put("stream", JsonPrimitive(true))
            request.temperature.coerceIn(0.0, 1.0).let { put("temperature", JsonPrimitive(it)) }
            if (systemText.isNotEmpty()) put("system", JsonPrimitive(systemText))
            put("messages", buildJsonArray {
                request.messages
                    .filter { it.role.equals("user", true) || it.role.equals("assistant", true) }
                    .map { it.toAnthropicMessage() }
                    .mergeConsecutiveSameRole()
                    .forEach { add(it) }
            })
        }
        val customBody = parseCustomBody(request.customBodyJson) ?: return baseBody
        return buildJsonObject {
            baseBody.forEach { (key, value) -> put(key, value) }
            customBody.forEach { (key, value) -> put(key, value) }
        }
    }

    private fun parseCustomBody(customBodyJson: String): JsonObject? {
        val trimmed = customBodyJson.trim()
        if (trimmed.isBlank()) return null
        return runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrElse {
            throw ChatHttpException("自定义请求体不是合法 JSON 对象")
        }
    }

    private fun OutgoingChatMessage.toAnthropicMessage(): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(role.lowercase()))
        put("content", buildJsonArray {
            if (text.isNotBlank()) {
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(text))
                    },
                )
            }
            imageDataUrls.forEach { dataUrl ->
                dataUrl.toAnthropicImageSource()?.let { add(it) }
            }
        })
    }

    private fun String.toAnthropicImageSource(): JsonObject? {
        val trimmed = trim()
        if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            val metadata = trimmed.substringAfter("data:", "").substringBefore(",", missingDelimiterValue = "")
            val payload = trimmed.substringAfter(',', "")
            val mediaType = metadata.substringBefore(';').lowercase()
            if (payload.isEmpty() || !mediaType.startsWith("image/")) return null
            return buildJsonObject {
                put("type", JsonPrimitive("image"))
                put("source", buildJsonObject {
                    put("type", JsonPrimitive("base64"))
                    put("media_type", JsonPrimitive(mediaType))
                    put("data", JsonPrimitive(payload))
                })
            }
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return buildJsonObject {
                put("type", JsonPrimitive("image"))
                put("source", buildJsonObject {
                    put("type", JsonPrimitive("url"))
                    put("url", JsonPrimitive(trimmed))
                })
            }
        }
        return null
    }

    private fun List<JsonObject>.mergeConsecutiveSameRole(): List<JsonObject> {
        val merged = mutableListOf<JsonObject>()
        forEach { message ->
            val previous = merged.lastOrNull()
            if (previous != null && previous.getString("role") == message.getString("role")) {
                merged[merged.lastIndex] = buildJsonObject {
                    put("role", previous.getString("role")?.let(::JsonPrimitive) ?: JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        (previous["content"] as? JsonArray)?.forEach { add(it) }
                        (message["content"] as? JsonArray)?.forEach { add(it) }
                    })
                }
            } else {
                merged += message
            }
        }
        return merged
    }

    private data class ParsedStreamEvents(
        val events: List<StreamEvent>,
        val inputTokens: Int?,
        val isMessageStop: Boolean,
    )

    private fun parseEvents(data: String, knownInputTokens: Int?): ParsedStreamEvents {
        val root = json.parseToJsonElement(data).jsonObject
        return when (root.getString("type")) {
            "content_block_delta" -> {
                val delta = root["delta"] as? JsonObject
                val events = mutableListOf<StreamEvent>()
                when (delta?.getString("type")) {
                    "text_delta" -> delta.getString("text")?.takeIf { it.isNotBlank() }?.let {
                        events += StreamEvent.TextDelta(it)
                    }
                    "thinking_delta" -> delta.getString("thinking")?.takeIf { it.isNotBlank() }?.let {
                        events += StreamEvent.ReasoningDelta(it)
                    }
                    else -> Unit
                }
                ParsedStreamEvents(events, inputTokens = null, isMessageStop = false)
            }
            "message_start" -> {
                val usage = root["message"].asJsonObject()?.get("usage").asJsonObject()
                ParsedStreamEvents(
                    events = emptyList(),
                    inputTokens = usage?.getString("input_tokens")?.trim()?.toIntOrNull(),
                    isMessageStop = false,
                )
            }
            "message_delta" -> {
                val delta = root["delta"].asJsonObject()
                val usage = root["usage"].asJsonObject()
                val events = mutableListOf<StreamEvent>()
                val inputTokens = knownInputTokens
                    ?: usage?.getString("input_tokens")?.trim()?.toIntOrNull()
                val outputTokens = usage?.getString("output_tokens")?.trim()?.toIntOrNull()
                if (inputTokens != null || outputTokens != null) {
                    events += StreamEvent.Usage(
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        totalTokens = listOfNotNull(inputTokens, outputTokens).sum().takeIf { it > 0 },
                    )
                }
                delta?.getString("stop_reason")?.takeIf { it.isNotBlank() }?.let {
                    events += StreamEvent.Finished(it)
                }
                ParsedStreamEvents(events, inputTokens = null, isMessageStop = false)
            }
            "message_stop" -> ParsedStreamEvents(emptyList(), inputTokens = null, isMessageStop = true)
            "error" -> {
                val message = root["error"].asJsonObject()?.getString("message")
                    ?: root.getString("message")
                    ?: "Anthropic 流式返回 error 事件"
                throw ChatHttpException("LLM 请求失败：$message")
            }
            else -> ParsedStreamEvents(emptyList(), inputTokens = null, isMessageStop = false)
        }
    }

    private fun buildErrorMessage(statusCode: Int, responseBody: String?): String {
        val providerMessage = responseBody
            ?.let { runCatching { parseProviderErrorMessage(it) }.getOrNull() ?: it }
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_ERROR_DETAIL_LENGTH)
        return if (providerMessage == null) {
            "LLM 请求失败：HTTP $statusCode"
        } else {
            "LLM 请求失败：HTTP $statusCode，$providerMessage"
        }
    }

    private fun parseProviderErrorMessage(responseBody: String): String? = runCatching {
        val root = json.parseToJsonElement(responseBody).jsonObject
        root["error"].asJsonObject()?.getString("message")
            ?: root.getString("message")
    }.getOrNull()

    private fun JsonElement?.asJsonObject(): JsonObject? = this as? JsonObject

    private fun JsonObject.getString(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun OkHttpClient.forRequest(request: ChatRequest): OkHttpClient {
        val readTimeoutMillis = request.readTimeoutMillis?.takeIf { it > 0 } ?: return this
        return newBuilder()
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
    }

    companion object {
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 8_192
        private const val MAX_ERROR_DETAIL_LENGTH = 240
    }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private fun Map<String, String>.normalizedCustomHeaders(): Map<String, String> {
    val normalized = linkedMapOf<String, String>()
    forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        if (key.isNotBlank() && value.isNotBlank()) {
            normalized[key] = value
        }
    }
    return normalized
}

private fun String.isProtectedHeader(): Boolean = when (this.lowercase()) {
    "authorization", "content-type", "x-api-key", "anthropic-version" -> true
    else -> false
}

/** Anthropic 端点 URL：base 已含 /v1 则追加 /messages，否则补全 /v1/messages。 */
fun anthropicMessagesUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return when {
        trimmed.endsWith("/messages") -> trimmed
        trimmed.endsWith("/v1") -> "$trimmed/messages"
        else -> "$trimmed/v1/messages"
    }
}
