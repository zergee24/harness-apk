package com.harnessapk.voice

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleTranscriptionClientTest {
    @Test
    fun transcribeUploadsTemporaryAudioToOpenAiCompatibleEndpoint() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"荣耀手机语音"}"""))
        server.start()
        val audio = File.createTempFile("voice-", ".m4a").apply { writeBytes("audio".encodeToByteArray()) }
        try {
            val result = OpenAiCompatibleTranscriptionClient(
                httpClient = OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
            ).transcribe(
                request = CloudTranscriptionRequest(
                    baseUrl = server.url("/v1").toString().trimEnd('/'),
                    apiKey = "voice-secret",
                    model = "whisper-1",
                    language = "zh-CN",
                    customHeaders = mapOf(
                        "X-Tenant" to "mobile",
                        "Authorization" to "Bearer wrong-key",
                        "Content-Type" to "application/json",
                    ),
                ),
                audioFile = audio,
            )

            assertEquals("荣耀手机语音", result)
            val recorded = server.takeRequest()
            assertEquals("/v1/audio/transcriptions", recorded.path)
            assertEquals("Bearer voice-secret", recorded.getHeader("Authorization"))
            assertEquals("mobile", recorded.getHeader("X-Tenant"))
            assertTrue(recorded.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("name=\"model\""))
            assertTrue(body.contains("whisper-1"))
            assertTrue(body.contains("name=\"language\""))
            assertTrue(body.contains("zh"))
            assertTrue(body.contains("filename=\"voice-input.m4a\""))
        } finally {
            audio.delete()
            server.shutdown()
        }
    }

    @Test
    fun providerFailureDoesNotExposeApiKey() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid key"}}"""))
        server.start()
        val audio = File.createTempFile("voice-", ".m4a").apply { writeBytes("audio".encodeToByteArray()) }
        try {
            val failure = runCatching {
                OpenAiCompatibleTranscriptionClient(OkHttpClient(), Json { ignoreUnknownKeys = true })
                    .transcribe(
                        CloudTranscriptionRequest(
                            baseUrl = server.url("/v1").toString().trimEnd('/'),
                            apiKey = "voice-secret",
                            model = "whisper-1",
                            language = "system",
                        ),
                        audio,
                    )
            }.exceptionOrNull()

            assertTrue(failure is CloudTranscriptionException)
            assertFalse(failure?.message.orEmpty().contains("voice-secret"))
        } finally {
            audio.delete()
            server.shutdown()
        }
    }
}
