package com.harnessapk.network

import com.harnessapk.chat.StreamEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 回归：流式读取阻塞在 socket 上时，取消协程必须立即断开连接。
 * 旧实现用 invokeOnCompletion，要等阻塞读自己返回（readTimeout）才断开，「停止」和卡流检测都会干等。
 */
class CallCancellationTest {
    @Test
    fun cancellingCollectorAbortsBlockedStreamReadImmediately() = runBlocking {
        // 手写最小 HTTP 服务：立刻回一行 SSE，然后保持连接不再发送任何字节，模拟上游卡流。
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val serverThread = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    var contentLength = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toInt()
                        }
                    }
                    repeat(contentLength) { input.read() }
                    val output = socket.getOutputStream()
                    output.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n").toByteArray(),
                    )
                    val chunk = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n".toByteArray()
                    output.write("${Integer.toHexString(chunk.size)}\r\n".toByteArray())
                    output.write(chunk)
                    output.write("\r\n".toByteArray())
                    output.flush()
                    Thread.sleep(30_000)
                }
            }
        }
        try {
            val client = OpenAiCompatibleClient(
                OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build(),
                Json { ignoreUnknownKeys = true },
            )
            val firstEvent = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) {
                client.streamChatEvents(
                    ChatRequest(
                        baseUrl = "http://127.0.0.1:${server.localPort}/v1",
                        apiKey = "test-key",
                        model = "test-model",
                        messages = listOf(OutgoingChatMessage(role = "user", text = "hi")),
                    ),
                ).collect { event ->
                    if (event is StreamEvent.TextDelta) firstEvent.complete(Unit)
                }
            }
            withTimeout(5_000) { firstEvent.await() }

            val startedAt = System.nanoTime()
            withTimeout(5_000) { job.cancelAndJoin() }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue("取消耗时 ${elapsedMillis}ms，应立即断开", elapsedMillis < 2_000)
        } finally {
            server.close()
            serverThread.interrupt()
        }
    }
}
