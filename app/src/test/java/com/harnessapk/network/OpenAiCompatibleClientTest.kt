package com.harnessapk.network

import com.harnessapk.chat.StreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleClientTest {
    @Test
    fun streamChatEventsEmitsReasoningTextUsageAndFinishEvents() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    data: {"choices":[{"delta":{"reasoning_content":"先想"}}]}
                    data: {"choices":[{"delta":{"content":"答案"}}]}
                    data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":7,"total_tokens":12}}
                    data: [DONE]
                    """.trimIndent(),
                ),
        )
        server.start()

        val client = OpenAiCompatibleClient(OKHTTP, Json { ignoreUnknownKeys = true })
        val result = client.streamChatEvents(
            ChatRequest(
                baseUrl = server.url("/v1").toString(),
                apiKey = "secret-key",
                model = "test-model",
                messages = listOf(OutgoingChatMessage(role = "user", text = "hello")),
            ),
        ).toList()

        assertEquals(
            listOf(
                StreamEvent.ReasoningDelta("先想"),
                StreamEvent.TextDelta("答案"),
                StreamEvent.Usage(inputTokens = 5, outputTokens = 7, totalTokens = 12),
                StreamEvent.Finished("stop"),
            ),
            result,
        )
        server.shutdown()
    }

    @Test
    fun streamChatPostsToChatCompletionsAndEmitsDeltas() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    data: {"choices":[{"delta":{"content":"你"}}]}
                    data: {"choices":[{"delta":{"content":"好"}}]}
                    data: [DONE]
                    """.trimIndent(),
                ),
        )
        server.start()

        val client = OpenAiCompatibleClient(OKHTTP, Json { ignoreUnknownKeys = true })
        val result = client.streamChat(
            ChatRequest(
                baseUrl = server.url("/v1").toString(),
                apiKey = "secret-key",
                model = "test-model",
                messages = listOf(OutgoingChatMessage(role = "user", text = "hello")),
            ),
        ).toList()

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer secret-key", recorded.getHeader("Authorization"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertFalse(body.containsKey("reasoning_effort"))
        assertEquals(listOf(ChatDelta("你"), ChatDelta("好")), result)
        server.shutdown()
    }

    @Test
    fun streamChatIncludesReasoningEffortWhenProvided() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    data: {"choices":[{"delta":{"content":"ok"}}]}
                    data: [DONE]
                    """.trimIndent(),
                ),
        )
        server.start()

        val client = OpenAiCompatibleClient(OKHTTP, Json { ignoreUnknownKeys = true })
        client.streamChat(
            ChatRequest(
                baseUrl = server.url("/v1").toString(),
                apiKey = "secret-key",
                model = "gpt-5.5",
                messages = listOf(OutgoingChatMessage(role = "user", text = "hello")),
                reasoningEffort = "high",
            ),
        ).toList()

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.contentOrNull)
        server.shutdown()
    }

    @Test
    fun streamChatIncludesOpenAiNativeWebSearchOptionsWhenRequested() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    data: {"choices":[{"delta":{"content":"ok"}}]}
                    data: [DONE]
                    """.trimIndent(),
                ),
        )
        server.start()

        val client = OpenAiCompatibleClient(OKHTTP, Json { ignoreUnknownKeys = true })
        client.streamChat(
            ChatRequest(
                baseUrl = server.url("/v1").toString(),
                apiKey = "secret-key",
                model = "gpt-5.5",
                messages = listOf(OutgoingChatMessage(role = "user", text = "hello")),
                nativeWebSearchMode = com.harnessapk.provider.NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS,
            ),
        ).toList()

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(body.containsKey("web_search_options"))
        server.shutdown()
    }

    @Test
    fun streamChatIncludesEnableSearchBooleanWhenRequested() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    data: {"choices":[{"delta":{"content":"ok"}}]}
                    data: [DONE]
                    """.trimIndent(),
                ),
        )
        server.start()

        val client = OpenAiCompatibleClient(OKHTTP, Json { ignoreUnknownKeys = true })
        client.streamChat(
            ChatRequest(
                baseUrl = server.url("/v1").toString(),
                apiKey = "secret-key",
                model = "kimi-k2.7-code",
                messages = listOf(OutgoingChatMessage(role = "user", text = "hello")),
                nativeWebSearchMode = com.harnessapk.provider.NativeWebSearchMode.ENABLE_SEARCH_BOOLEAN,
            ),
        ).toList()

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(true, body["enable_search"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull())
        server.shutdown()
    }

    @Test
    fun streamChatIncludesGlmWebSearchToolWhenRequested() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    data: {"choices":[{"delta":{"content":"ok"}}]}
                    data: [DONE]
                    """.trimIndent(),
                ),
        )
        server.start()

        val client = OpenAiCompatibleClient(OKHTTP, Json { ignoreUnknownKeys = true })
        client.streamChat(
            ChatRequest(
                baseUrl = server.url("/api/paas/v4").toString(),
                apiKey = "secret-key",
                model = "glm-5.2",
                messages = listOf(OutgoingChatMessage(role = "user", text = "hello")),
                nativeWebSearchMode = com.harnessapk.provider.NativeWebSearchMode.GLM_WEB_SEARCH_TOOL,
            ),
        ).toList()

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("web_search", body["tools"]!!.jsonArray.first().jsonObject["type"]!!.jsonPrimitive.contentOrNull)
        server.shutdown()
    }

    @Test
    fun errorMessageDoesNotExposeApiKeyOrPrompt() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad secret-key hello"))
        server.start()
        val client = OpenAiCompatibleClient(OKHTTP, Json { ignoreUnknownKeys = true })

        val error = runCatching {
            client.streamChat(
                ChatRequest(
                    baseUrl = server.url("/v1").toString(),
                    apiKey = "secret-key",
                    model = "test-model",
                    messages = listOf(OutgoingChatMessage(role = "user", text = "hello")),
                ),
            ).toList()
        }.exceptionOrNull()!!

        assertFalse(error.message.orEmpty().contains("secret-key"))
        assertFalse(error.message.orEmpty().contains("hello"))
        server.shutdown()
    }

    @Test
    fun errorMessageIncludesProviderJsonErrorMessage() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"model kimi-k2 is not available"}}"""),
        )
        server.start()
        val client = OpenAiCompatibleClient(OKHTTP, Json { ignoreUnknownKeys = true })

        val error = runCatching {
            client.streamChat(
                ChatRequest(
                    baseUrl = server.url("/v1").toString(),
                    apiKey = "secret-key",
                    model = "kimi-k2",
                    messages = listOf(OutgoingChatMessage(role = "user", text = "hello")),
                ),
            ).toList()
        }.exceptionOrNull()!!

        assertEquals("LLM 请求失败：HTTP 400，model kimi-k2 is not available", error.message)
        assertFalse(error.message.orEmpty().contains("secret-key"))
        assertFalse(error.message.orEmpty().contains("hello"))
        server.shutdown()
    }

    @Test
    fun chatCompletionsUrlAddsV1ForRootProxyBaseUrl() {
        assertEquals("https://happycode.vip/v1/chat/completions", chatCompletionsUrl("https://happycode.vip"))
        assertEquals("https://happycode.vip/v1/chat/completions", chatCompletionsUrl("https://happycode.vip/"))
        assertEquals("https://happycode.vip/v1/chat/completions", chatCompletionsUrl("https://happycode.vip/v1"))
        assertEquals("https://api.moonshot.cn/v1/chat/completions", chatCompletionsUrl("https://api.moonshot.cn/v1"))
    }

    @Test
    fun chatCompletionsUrlKeepsOpenAiCompatibleApiBasePath() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            chatCompletionsUrl("https://open.bigmodel.cn/api/paas/v4"),
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            chatCompletionsUrl("https://open.bigmodel.cn/api/paas/v4/"),
        )
    }

    @Test
    fun streamChatFailsWhenSuccessResponseIsNotSse() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("<!doctype html><html><body>not an api response</body></html>"),
        )
        server.start()
        val client = OpenAiCompatibleClient(OKHTTP, Json { ignoreUnknownKeys = true })

        val error = runCatching {
            client.streamChat(
                ChatRequest(
                    baseUrl = server.url("/").toString(),
                    apiKey = "secret-key",
                    model = "gpt-5.5",
                    messages = listOf(OutgoingChatMessage(role = "user", text = "hello")),
                ),
            ).toList()
        }.exceptionOrNull()!!

        assertTrue(error.message.orEmpty().contains("未收到流式数据"))
        server.shutdown()
    }

    companion object {
        private val OKHTTP = okhttp3.OkHttpClient.Builder().build()
    }
}
