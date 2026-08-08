package com.harnessapk.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AliyunRealtimeProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun runTaskUsesRealtimeParaformerPcmAndRequestedLanguage() {
        val message = aliyunRunTaskMessage(
            request = AliyunRealtimeRequest(
                apiKey = "secret",
                model = DEFAULT_ALIYUN_SPEECH_MODEL,
                language = "zh-CN",
                autoPunctuation = true,
            ),
            taskId = "task-1",
        )
        val root = json.parseToJsonElement(message).jsonObject
        val header = root.getValue("header").jsonObject
        val payload = root.getValue("payload").jsonObject
        val parameters = payload.getValue("parameters").jsonObject

        assertEquals("run-task", header.getValue("action").jsonPrimitive.content)
        assertEquals("duplex", header.getValue("streaming").jsonPrimitive.content)
        assertEquals(DEFAULT_ALIYUN_SPEECH_MODEL, payload.getValue("model").jsonPrimitive.content)
        assertEquals("pcm", parameters.getValue("format").jsonPrimitive.content)
        assertEquals("16000", parameters.getValue("sample_rate").jsonPrimitive.content)
        assertTrue(parameters.getValue("punctuation_prediction_enabled").jsonPrimitive.content.toBoolean())
        assertEquals("zh", parameters.getValue("language_hints").toString().trim('[', ']', '"'))
    }

    @Test
    fun serverEventsExposePartialFinalAndFailureWithoutHeartbeatText() {
        val partial = parseAliyunRealtimeEvent(
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"正在输入","sentence_end":false,"heartbeat":false}}}}""",
            json,
        )
        val heartbeat = parseAliyunRealtimeEvent(
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"忽略","sentence_end":false,"heartbeat":true}}}}""",
            json,
        )
        val failure = parseAliyunRealtimeEvent(
            """{"header":{"event":"task-failed","error_code":"AUTHENTICATION_FAILURE","error_message":"denied"},"payload":{}}""",
            json,
        )

        assertEquals(AliyunRealtimeEvent.Transcript("正在输入", sentenceEnd = false), partial)
        assertEquals(AliyunRealtimeEvent.Heartbeat, heartbeat)
        assertEquals(AliyunRealtimeEvent.Failed("AUTHENTICATION_FAILURE", "denied"), failure)
    }

    @Test
    fun transcriptAssemblerKeepsCompletedSentencesWhileReplacingCurrentPartial() {
        val assembler = AliyunTranscriptAssembler()

        assertEquals("第一句", assembler.accept("第一句", sentenceEnd = false))
        assertEquals("第一句话。", assembler.accept("第一句话。", sentenceEnd = true))
        assertEquals("第一句话。第二句", assembler.accept("第二句", sentenceEnd = false))
        assertFalse(assembler.finalText.isBlank())
        assertEquals("第一句话。第二句话。", assembler.accept("第二句话。", sentenceEnd = true))
        assertEquals("第一句话。第二句话。", assembler.finalText)
    }
}
