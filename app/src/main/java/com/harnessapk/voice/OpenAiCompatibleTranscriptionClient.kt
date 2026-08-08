package com.harnessapk.voice

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

data class CloudTranscriptionRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val language: String,
    val customHeaders: Map<String, String> = emptyMap(),
)

class CloudTranscriptionException(
    message: String,
    val statusCode: Int? = null,
) : Exception(message)

class OpenAiCompatibleTranscriptionClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun transcribe(request: CloudTranscriptionRequest, audioFile: File): String = withContext(Dispatchers.IO) {
        require(request.baseUrl.startsWith("https://") || request.baseUrl.startsWith("http://localhost")) {
            "语音转写地址必须使用 HTTPS"
        }
        require(request.apiKey.isNotBlank()) { "语音转写 API Key 不能为空" }
        require(request.model.isNotBlank()) { "语音转写模型不能为空" }
        require(audioFile.isFile && audioFile.length() > 0L) { "没有可转写的录音" }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", request.model.trim())
            .addFormDataPart(
                "file",
                "voice-input.m4a",
                audioFile.asRequestBody("audio/mp4".toMediaType()),
            )
            .apply {
                normalizeTranscriptionLanguage(request.language)?.let { language ->
                    addFormDataPart("language", language)
                }
            }
            .build()
        val httpRequest = Request.Builder()
            .url(transcriptionEndpoint(request.baseUrl))
            .header("Authorization", "Bearer ${request.apiKey}")
            .apply {
                request.customHeaders.forEach { (name, value) ->
                    if (
                        !name.equals("Authorization", ignoreCase = true) &&
                        !name.equals("Content-Type", ignoreCase = true)
                    ) {
                        header(name, value)
                    }
                }
            }
            .post(body)
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                throw CloudTranscriptionException(
                    message = "语音转写请求失败（HTTP ${response.code}）",
                    statusCode = response.code,
                )
            }
            val transcript = runCatching {
                json.parseToJsonElement(responseBody).jsonObject["text"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.trim().orEmpty()
            if (transcript.isBlank()) throw CloudTranscriptionException("语音转写没有返回文字")
            transcript
        }
    }
}

fun siliconFlowTranscriptionError(error: Throwable): String = when (
    (error as? CloudTranscriptionException)?.statusCode
) {
    401, 403 -> "硅基流动 API Key 无效，请重新配置"
    429 -> "硅基流动请求过于频繁，请稍后重试"
    503, 504 -> "硅基流动语音服务繁忙，请稍后重试"
    else -> error.message ?: "硅基流动语音转写失败，请重试"
}

internal fun transcriptionEndpoint(baseUrl: String): String {
    val normalized = baseUrl.trim().trimEnd('/')
    return if (normalized.endsWith("/audio/transcriptions")) normalized else "$normalized/audio/transcriptions"
}

internal fun normalizeTranscriptionLanguage(language: String): String? = when (language.trim().lowercase()) {
    "zh", "zh-cn" -> "zh"
    "en", "en-us" -> "en"
    else -> null
}
