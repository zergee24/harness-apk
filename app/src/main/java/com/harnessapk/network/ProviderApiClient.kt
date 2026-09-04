package com.harnessapk.network

import com.harnessapk.provider.ProviderApiProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 供应商录入辅助：按协议拉取模型列表（GET /models）与连通性测试（发一条 max_tokens=1
 * 的最小请求）。同步 OkHttp 调用统一放在 IO 线程执行，失败抛 [ChatHttpException]。
 */
class ProviderApiClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    data class ConnectionTestResult(
        val latencyMillis: Long,
        val replyModel: String?,
    )

    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        apiProtocol: ProviderApiProtocol,
    ): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(modelsUrlFor(baseUrl, apiProtocol))
            .authHeaders(apiKey, apiProtocol)
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw ChatHttpException(buildErrorMessage(response.code, body))
            }
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                throw ChatHttpException("模型列表返回格式异常：不是 JSON 对象")
            }
            parseModelIds(root)
        }
    }

    suspend fun testConnection(
        baseUrl: String,
        apiKey: String,
        model: String,
        apiProtocol: ProviderApiProtocol,
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val body = buildJsonObject {
            put("model", JsonPrimitive(model))
            if (apiProtocol == ProviderApiProtocol.ANTHROPIC_MESSAGES) {
                put("max_tokens", JsonPrimitive(1))
            } else {
                put("max_tokens", JsonPrimitive(1))
                put("stream", JsonPrimitive(false))
            }
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive("ping"))
                })
            })
        }
        val url = when (apiProtocol) {
            ProviderApiProtocol.OPENAI_COMPATIBLE -> chatCompletionsUrl(baseUrl)
            ProviderApiProtocol.ANTHROPIC_MESSAGES -> anthropicMessagesUrl(baseUrl)
        }
        val request = Request.Builder()
            .url(url)
            .authHeaders(apiKey, apiProtocol)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                throw ChatHttpException(buildErrorMessage(response.code, responseBody))
            }
            val replyModel = runCatching {
                (json.parseToJsonElement(responseBody) as? JsonObject)
                    ?.let { root -> root["model"]?.let { it as? JsonPrimitive }?.contentOrNull }
            }.getOrNull()
            ConnectionTestResult(
                latencyMillis = System.currentTimeMillis() - startedAt,
                replyModel = replyModel,
            )
        }
    }

    private fun parseModelIds(root: JsonObject): List<String> = buildList {
        val data = root["data"] as? JsonArray ?: return@buildList
        data.forEach { element ->
            val id = (element as? JsonObject)
                ?.get("id")?.let { it as? JsonPrimitive }?.contentOrNull
            if (!id.isNullOrBlank()) add(id.trim())
        }
    }.distinct()

    private fun buildErrorMessage(statusCode: Int, responseBody: String?): String {
        val providerMessage = responseBody
            ?.let { runCatching { parseProviderErrorMessage(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_ERROR_DETAIL_LENGTH)
        return if (providerMessage == null) {
            "请求失败：HTTP $statusCode"
        } else {
            "请求失败：HTTP $statusCode，$providerMessage"
        }
    }

    private fun parseProviderErrorMessage(responseBody: String): String? {
        val root = runCatching { json.parseToJsonElement(responseBody) }.getOrNull() ?: return null
        if (root !is JsonObject) return null
        return root.errorObjectMessage() ?: root.primitiveField("message")
    }

    private fun JsonObject.errorObjectMessage(): String? =
        (this["error"] as? JsonObject)?.primitiveField("message")
            ?: (this["error"] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.primitiveField(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val MAX_ERROR_DETAIL_LENGTH = 240
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** /models 列表端点，与 chatCompletionsUrl 的 base 归一化规则保持一致。 */
        fun modelsUrlFor(baseUrl: String, apiProtocol: ProviderApiProtocol): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            return when {
                trimmed.endsWith("/models") -> trimmed
                trimmed.endsWith("/v1") || trimmed.endsWith("/api/paas/v4") -> "$trimmed/models"
                else -> "$trimmed/v1/models"
            }
        }

        private fun Request.Builder.authHeaders(apiKey: String, apiProtocol: ProviderApiProtocol): Request.Builder {
            addHeader("Content-Type", "application/json")
            return when (apiProtocol) {
                ProviderApiProtocol.OPENAI_COMPATIBLE -> addHeader("Authorization", "Bearer $apiKey")
                ProviderApiProtocol.ANTHROPIC_MESSAGES -> {
                    addHeader("x-api-key", apiKey)
                    addHeader("anthropic-version", "2023-06-01")
                }
            }
        }
    }
}
