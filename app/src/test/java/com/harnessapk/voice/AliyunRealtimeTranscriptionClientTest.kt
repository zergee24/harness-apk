package com.harnessapk.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AliyunRealtimeTranscriptionClientTest {
    @Test
    fun websocketStreamsAudioAndReturnsPartialThenFinalTranscript() {
        val server = MockWebServer()
        val receivedAudio = AtomicReference<ByteArray>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        when {
                            text.contains("\"run-task\"") -> webSocket.send(
                                """{"header":{"event":"task-started"},"payload":{}}""",
                            )
                            text.contains("\"finish-task\"") -> {
                                webSocket.send(
                                    """{"header":{"event":"task-finished"},"payload":{}}""",
                                )
                                webSocket.close(1000, null)
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        receivedAudio.set(bytes.toByteArray())
                        webSocket.send(
                            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"你","sentence_end":false,"heartbeat":false}}}}""",
                        )
                        webSocket.send(
                            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"你好。","sentence_end":true,"heartbeat":false}}}}""",
                        )
                    }
                },
            ),
        )
        server.start()
        val ready = CountDownLatch(1)
        val partialReceived = CountDownLatch(2)
        val finished = CountDownLatch(1)
        val latestPartial = AtomicReference("")
        val finalTranscript = AtomicReference("")
        try {
            val session = AliyunRealtimeTranscriptionClient(
                httpClient = okhttp3.OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
            ).start(
                request = AliyunRealtimeRequest(
                    apiKey = "aliyun-secret",
                    webSocketUrl = server.url("/api-ws/v1/inference").toString()
                        .replaceFirst("http://", "ws://"),
                ),
                listener = object : AliyunRealtimeTranscriptionListener {
                    override fun onReady() {
                        ready.countDown()
                    }

                    override fun onPartialResult(transcript: String) {
                        latestPartial.set(transcript)
                        partialReceived.countDown()
                    }

                    override fun onFinalResult(transcript: String) {
                        finalTranscript.set(transcript)
                        finished.countDown()
                    }

                    override fun onFailure(error: Throwable) {
                        throw AssertionError(error)
                    }
                },
            )

            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertTrue(session.sendAudio(byteArrayOf(1, 2, 3, 4)))
            assertTrue(partialReceived.await(2, TimeUnit.SECONDS))
            assertEquals("你好。", latestPartial.get())
            session.finish()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals("你好。", finalTranscript.get())
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), receivedAudio.get())

            val handshake = server.takeRequest(2, TimeUnit.SECONDS)
            assertEquals("Bearer aliyun-secret", handshake?.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}
