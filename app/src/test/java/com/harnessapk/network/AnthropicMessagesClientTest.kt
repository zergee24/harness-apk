package com.harnessapk.network

import com.harnessapk.chat.StreamEvent
import com.harnessapk.provider.ProviderApiProtocol
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicMessagesClientTest {
    @Test
    fun streamChatEventsParsesTextThinkingUsageAndStop() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    event: message_start
                    data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":25,"output_tokens":1}}}

                    event: content_block_start
                    data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"想一想"}}

                    event: message_delta
                    data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":15}}

                    event: message_stop
                    data: {"type":"message_stop"}
                    """.trimIndent(),
                ),
        )
        server.start()

        val client = AnthropicMessagesClient(OKHTTP, Json { ignoreUnknownKeys = true })
        val events = client.streamChatEvents(
            ChatRequest(
                baseUrl = server.url("/").toString(),
                apiKey = "sk-ant-test",
                model = "claude-sonnet-4-5",
                messages = listOf(
                    OutgoingChatMessage(role = "system", text = "系统提示"),
                    OutgoingChatMessage(role = "user", text = "打个招呼"),
                ),
                apiProtocol = ProviderApiProtocol.ANTHROPIC_MESSAGES,
            ),
        ).toList()

        assertEquals(listOf("你好"), events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
        assertEquals(listOf("想一想"), events.filterIsInstance<StreamEvent.ReasoningDelta>().map { it.text })
        val usage = events.filterIsInstance<StreamEvent.Usage>().single()
        assertEquals(25, usage.inputTokens)
        assertEquals(15, usage.outputTokens)
        assertEquals("end_turn", events.filterIsInstance<StreamEvent.Finished>().single().reason)

        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("sk-ant-test", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("claude-sonnet-4-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(8192, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("系统提示", body["system"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages.first().jsonObject["role"]!!.jsonPrimitive.content)

        server.shutdown()
    }

    @Test
    fun imageDataUrlsAreConvertedToBase64SourceBlocks() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""data: {"type":"message_stop"}"""),
        )
        server.start()

        val client = AnthropicMessagesClient(OKHTTP, Json { ignoreUnknownKeys = true })
        runCatching {
            client.streamChatEvents(
                ChatRequest(
                    baseUrl = server.url("/").toString(),
                    apiKey = "sk-ant-test",
                    model = "claude-sonnet-4-5",
                    messages = listOf(
                        OutgoingChatMessage(
                            role = "user",
                            text = "看图",
                            imageDataUrls = listOf("data:image/png;base64,aGVsbG8="),
                        ),
                    ),
                    apiProtocol = ProviderApiProtocol.ANTHROPIC_MESSAGES,
                ),
            ).toList()
        }

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val content = body["messages"]!!.jsonArray
            .first().jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        val imageBlock = content[1].jsonObject
        assertEquals("image", imageBlock["type"]!!.jsonPrimitive.content)
        val source = imageBlock["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("aGVsbG8=", source["data"]!!.jsonPrimitive.content)

        server.shutdown()
    }

    @Test
    fun errorEventInsideStreamThrowsChatHttpException() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    event: error
                    data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}
                    """.trimIndent(),
                ),
        )
        server.start()

        val client = AnthropicMessagesClient(OKHTTP, Json { ignoreUnknownKeys = true })
        val error = runCatching {
            client.streamChatEvents(
                ChatRequest(
                    baseUrl = server.url("/").toString(),
                    apiKey = "sk-ant-test",
                    model = "claude-sonnet-4-5",
                    messages = listOf(OutgoingChatMessage(role = "user", text = "hi")),
                    apiProtocol = ProviderApiProtocol.ANTHROPIC_MESSAGES,
                ),
            ).toList()
        }.exceptionOrNull()

        assertTrue(error is ChatHttpException)
        assertTrue(error!!.message!!.contains("Overloaded"))

        server.shutdown()
    }

    @Test
    fun httpErrorSurfacesProviderMessage() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""),
        )
        server.start()

        val client = AnthropicMessagesClient(OKHTTP, Json { ignoreUnknownKeys = true })
        val error = runCatching {
            client.streamChat(
                ChatRequest(
                    baseUrl = server.url("/").toString(),
                    apiKey = "sk-ant-bad",
                    model = "claude-sonnet-4-5",
                    messages = listOf(OutgoingChatMessage(role = "user", text = "hi")),
                    apiProtocol = ProviderApiProtocol.ANTHROPIC_MESSAGES,
                ),
            ).toList()
        }.exceptionOrNull()

        assertTrue(error is ChatHttpException)
        assertTrue(error!!.message!!.contains("invalid x-api-key"))

        server.shutdown()
    }

    @Test
    fun messagesUrlNormalizesKnownBaseShapes() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            anthropicMessagesUrl("https://api.anthropic.com"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            anthropicMessagesUrl("https://api.anthropic.com/v1"),
        )
        assertEquals(
            "https://open.bigmodel.cn/api/anthropic/v1/messages",
            anthropicMessagesUrl("https://open.bigmodel.cn/api/anthropic"),
        )
        assertEquals(
            "https://proxy.example.com/api/anthropic/v1/messages",
            anthropicMessagesUrl("https://proxy.example.com/api/anthropic/v1/messages"),
        )
    }

    private companion object {
        private val OKHTTP = okhttp3.OkHttpClient.Builder()
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
}
