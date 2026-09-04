package com.harnessapk.network

import com.harnessapk.provider.ProviderApiProtocol
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

class ProviderApiClientTest {
    @Test
    fun listModelsParsesOpenAiStyleDataArray() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {"object":"list","data":[{"id":"gpt-5.6-terra"},{"id":"gpt-5.6-sol"},{"id":"gpt-5.6-terra"}]}
                    """.trimIndent(),
                ),
        )
        server.start()

        val client = ProviderApiClient(OKHTTP, Json { ignoreUnknownKeys = true })
        val models = client.listModels(
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-test",
            apiProtocol = ProviderApiProtocol.OPENAI_COMPATIBLE,
        )

        assertEquals(listOf("gpt-5.6-terra", "gpt-5.6-sol"), models)
        val recorded = server.takeRequest()
        assertEquals("/v1/models", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))

        server.shutdown()
    }

    @Test
    fun listModelsUsesAnthropicHeadersAndEndpoint() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"id":"claude-sonnet-4-5"},{"id":"claude-haiku-4-5"}]}"""),
        )
        server.start()

        val client = ProviderApiClient(OKHTTP, Json { ignoreUnknownKeys = true })
        val models = client.listModels(
            baseUrl = server.url("/api/anthropic").toString(),
            apiKey = "sk-ant-test",
            apiProtocol = ProviderApiProtocol.ANTHROPIC_MESSAGES,
        )

        assertEquals(listOf("claude-sonnet-4-5", "claude-haiku-4-5"), models)
        val recorded = server.takeRequest()
        assertEquals("/api/anthropic/v1/models", recorded.path)
        assertEquals("sk-ant-test", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))

        server.shutdown()
    }

    @Test
    fun testConnectionSendsMinimalNonStreamRequestForOpenAiProtocol() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"role":"assistant","content":""}}],"model":"gpt-5.6-terra"}"""),
        )
        server.start()

        val client = ProviderApiClient(OKHTTP, Json { ignoreUnknownKeys = true })
        val result = client.testConnection(
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-test",
            model = "gpt-5.6-terra",
            apiProtocol = ProviderApiProtocol.OPENAI_COMPATIBLE,
        )

        assertEquals("gpt-5.6-terra", result.replyModel)
        assertTrue(result.latencyMillis >= 0)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(1, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(false, body["stream"]!!.jsonPrimitive.content.toBoolean())

        server.shutdown()
    }

    @Test
    fun testConnectionPostsToAnthropicMessagesEndpoint() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"msg_1","model":"claude-sonnet-4-5","content":[{"type":"text","text":"ok"}]}"""),
        )
        server.start()

        val client = ProviderApiClient(OKHTTP, Json { ignoreUnknownKeys = true })
        val result = client.testConnection(
            baseUrl = server.url("/api/anthropic").toString(),
            apiKey = "sk-ant-test",
            model = "claude-sonnet-4-5",
            apiProtocol = ProviderApiProtocol.ANTHROPIC_MESSAGES,
        )

        assertEquals("claude-sonnet-4-5", result.replyModel)
        val recorded = server.takeRequest()
        assertEquals("/api/anthropic/v1/messages", recorded.path)
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("claude-sonnet-4-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(1, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["messages"]!!.jsonArray.size == 1)

        server.shutdown()
    }

    @Test
    fun httpErrorSurfacesProviderErrorMessage() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":{"message":"令牌无效","type":"invalid_request_error"}}"""),
        )
        server.start()

        val client = ProviderApiClient(OKHTTP, Json { ignoreUnknownKeys = true })
        val error = runCatching {
            client.listModels(
                baseUrl = server.url("/v1").toString(),
                apiKey = "sk-bad",
                apiProtocol = ProviderApiProtocol.OPENAI_COMPATIBLE,
            )
        }.exceptionOrNull()

        assertTrue(error is ChatHttpException)
        assertTrue(error!!.message!!.contains("令牌无效"))
        assertTrue(error.message!!.contains("403"))

        server.shutdown()
    }

    private companion object {
        private val OKHTTP = okhttp3.OkHttpClient()
    }
}
