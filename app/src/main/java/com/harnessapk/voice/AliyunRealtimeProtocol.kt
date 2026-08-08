package com.harnessapk.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class AliyunRealtimeRequest(
    val apiKey: String,
    val model: String = DEFAULT_ALIYUN_SPEECH_MODEL,
    val language: String = "system",
    val autoPunctuation: Boolean = true,
    val webSocketUrl: String = ALIYUN_REALTIME_SPEECH_URL,
)

sealed interface AliyunRealtimeEvent {
    data object TaskStarted : AliyunRealtimeEvent
    data class Transcript(val text: String, val sentenceEnd: Boolean) : AliyunRealtimeEvent
    data object Heartbeat : AliyunRealtimeEvent
    data object TaskFinished : AliyunRealtimeEvent
    data class Failed(val code: String, val message: String) : AliyunRealtimeEvent
    data object Unknown : AliyunRealtimeEvent
}

internal fun aliyunRunTaskMessage(request: AliyunRealtimeRequest, taskId: String): String = buildJsonObject {
    put(
        "header",
        buildJsonObject {
            put("action", "run-task")
            put("task_id", taskId)
            put("streaming", "duplex")
        },
    )
    put(
        "payload",
        buildJsonObject {
            put("task_group", "audio")
            put("task", "asr")
            put("function", "recognition")
            put("model", request.model)
            put(
                "parameters",
                buildJsonObject {
                    put("format", "pcm")
                    put("sample_rate", 16_000)
                    put("punctuation_prediction_enabled", request.autoPunctuation)
                    normalizeAliyunLanguage(request.language)?.let { language ->
                        put("language_hints", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(language)) })
                    }
                },
            )
            put("input", buildJsonObject {})
        },
    )
}.toString()

internal fun aliyunFinishTaskMessage(taskId: String): String = buildJsonObject {
    put(
        "header",
        buildJsonObject {
            put("action", "finish-task")
            put("task_id", taskId)
            put("streaming", "duplex")
        },
    )
    put("payload", buildJsonObject { put("input", buildJsonObject {}) })
}.toString()

internal fun parseAliyunRealtimeEvent(raw: String, json: Json): AliyunRealtimeEvent {
    val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return AliyunRealtimeEvent.Unknown
    val header = root.objectOrNull("header") ?: return AliyunRealtimeEvent.Unknown
    return when (header.stringOrNull("event")) {
        "task-started" -> AliyunRealtimeEvent.TaskStarted
        "result-generated" -> {
            val sentence = root.objectOrNull("payload")
                ?.objectOrNull("output")
                ?.objectOrNull("sentence")
                ?: return AliyunRealtimeEvent.Unknown
            if (sentence.booleanOrNull("heartbeat") == true) {
                AliyunRealtimeEvent.Heartbeat
            } else {
                AliyunRealtimeEvent.Transcript(
                    text = sentence.stringOrNull("text").orEmpty(),
                    sentenceEnd = sentence.booleanOrNull("sentence_end") == true,
                )
            }
        }
        "task-finished" -> AliyunRealtimeEvent.TaskFinished
        "task-failed" -> AliyunRealtimeEvent.Failed(
            code = header.stringOrNull("error_code").orEmpty(),
            message = header.stringOrNull("error_message").orEmpty(),
        )
        else -> AliyunRealtimeEvent.Unknown
    }
}

internal class AliyunTranscriptAssembler {
    private var completedText = ""
    private var partialText = ""

    val finalText: String
        get() = completedText + partialText

    fun accept(text: String, sentenceEnd: Boolean): String {
        val normalized = text.trim()
        if (sentenceEnd) {
            completedText += normalized
            partialText = ""
        } else {
            partialText = normalized
        }
        return finalText
    }
}

private fun normalizeAliyunLanguage(language: String): String? = when (language.trim().lowercase()) {
    "zh", "zh-cn" -> "zh"
    "en", "en-us" -> "en"
    else -> null
}

private fun JsonObject.objectOrNull(name: String): JsonObject? = get(name)?.runCatching { jsonObject }?.getOrNull()
private fun JsonObject.stringOrNull(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull
private fun JsonObject.booleanOrNull(name: String): Boolean? = get(name)?.jsonPrimitive?.booleanOrNull
