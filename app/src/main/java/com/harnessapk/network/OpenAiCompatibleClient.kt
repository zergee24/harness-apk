package com.harnessapk.network

import com.harnessapk.provider.NativeWebSearchMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAiCompatibleClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    fun streamChat(request: ChatRequest): Flow<ChatDelta> = flow {
        val httpRequest = Request.Builder()
            .url(chatCompletionsUrl(request.baseUrl))
            .addHeader("Authorization", "Bearer ${request.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(buildBody(request).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = okHttpClient.newCall(httpRequest)
        val cancellation = currentCoroutineContext().job.invokeOnCompletion {
            if (it != null) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw ChatHttpException(buildErrorMessage(response.code, response.body.string(), request))
                }

                val source = response.body.source()
                var sawStreamData = false
                var emittedText = false
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    sawStreamData = true
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    parseDelta(data)?.takeIf { it.isNotBlank() }?.let {
                        emittedText = true
                        emit(ChatDelta(it))
                    }
                }
                if (!sawStreamData) {
                    throw ChatHttpException("LLM 返回格式异常：未收到流式数据，请检查 Base URL 是否为 OpenAI-compatible API 地址")
                }
                if (!emittedText) {
                    throw ChatHttpException("LLM 返回为空：未收到可显示内容")
                }
            }
        } finally {
            cancellation.dispose()
        }
    }.flowOn(Dispatchers.IO)

    private fun buildBody(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(request.model))
        put("stream", JsonPrimitive(true))
        put("temperature", JsonPrimitive(request.temperature))
        request.reasoningEffort?.let { put("reasoning_effort", JsonPrimitive(it)) }
        addNativeWebSearch(request.nativeWebSearchMode)
        put("messages", buildJsonArray {
            request.messages.forEach { message ->
                add(buildMessage(message))
            }
        })
    }

    private fun JsonObjectBuilder.addNativeWebSearch(mode: NativeWebSearchMode?) {
        when (mode) {
            NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS -> {
                put("web_search_options", buildJsonObject {})
            }
            NativeWebSearchMode.ENABLE_SEARCH_BOOLEAN -> {
                put("enable_search", JsonPrimitive(true))
            }
            NativeWebSearchMode.GLM_WEB_SEARCH_TOOL -> {
                put("tools", buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("web_search"))
                            put(
                                "web_search",
                                buildJsonObject {
                                    put("enable", JsonPrimitive(true))
                                    put("search_engine", JsonPrimitive("search_std"))
                                },
                            )
                        },
                    )
                })
            }
            NativeWebSearchMode.DISABLED, null -> Unit
        }
    }

    private fun buildMessage(message: OutgoingChatMessage): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(message.role))
        if (message.imageDataUrls.isEmpty()) {
            put("content", JsonPrimitive(message.text))
        } else {
            put("content", buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(message.text))
                    },
                )
                message.imageDataUrls.forEach { dataUrl ->
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("image_url"))
                            put(
                                "image_url",
                                buildJsonObject {
                                    put("url", JsonPrimitive(dataUrl))
                                },
                            )
                        },
                    )
                }
            })
        }
    }

    private fun parseDelta(data: String): String? {
        val root = json.parseToJsonElement(data).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val delta = choice["delta"]?.jsonObject
        return delta?.get("content")?.jsonPrimitive?.contentOrNull
    }

    private fun buildErrorMessage(
        statusCode: Int,
        responseBody: String?,
        request: ChatRequest,
    ): String {
        val providerMessage = responseBody
            ?.let { parseProviderErrorMessage(it) ?: it }
            ?.sanitizeForDisplay(request)
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_ERROR_DETAIL_LENGTH)
        return if (providerMessage == null) {
            "LLM 请求失败：HTTP $statusCode"
        } else {
            "LLM 请求失败：HTTP $statusCode，$providerMessage"
        }
    }

    private fun parseProviderErrorMessage(responseBody: String): String? = runCatching {
        val root = json.parseToJsonElement(responseBody)
        root.findString("message") ?: root.findString("error")
    }.getOrNull()

    private fun JsonElement.findString(key: String): String? = when (this) {
        is JsonObject -> {
            this[key]?.jsonPrimitive?.contentOrNull
                ?: values.firstNotNullOfOrNull { it.findString(key) }
        }
        is JsonArray -> firstNotNullOfOrNull { it.findString(key) }
        else -> null
    }

    private fun String.sanitizeForDisplay(request: ChatRequest): String {
        val sensitiveTerms = buildList {
            add(request.apiKey)
            request.messages.forEach { add(it.text) }
        }.filter { it.isNotBlank() }

        return sensitiveTerms.fold(this) { current, term ->
            current.replace(term, "[已隐藏]")
        }.replace(Regex("\\s+"), " ").trim()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_ERROR_DETAIL_LENGTH = 240
    }
}

fun chatCompletionsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return when {
        trimmed.endsWith("/chat/completions") -> trimmed
        trimmed.endsWith("/v1") || trimmed.endsWith("/api/paas/v4") -> "$trimmed/chat/completions"
        else -> "$trimmed/v1/chat/completions"
    }
}
