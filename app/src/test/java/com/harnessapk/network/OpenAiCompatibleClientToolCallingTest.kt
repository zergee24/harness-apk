package com.harnessapk.network

import com.harnessapk.chat.StreamEvent
import com.harnessapk.chat.imageGenerateToolDefinition
import com.harnessapk.provider.NativeWebSearchMode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

/**
 * function calling 的请求体形状与流式 tool_calls 解析（image_generate 工具）。
 */
class OpenAiCompatibleClientToolCallingTest {
    @Test
    fun `声明工具时请求体带 function tools 与 tool_choice`() = runTest {
        val server = MockWebServer()
        server.enqueue(streamResponse("data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\ndata: [DONE]"))
        server.start()
        try {
            val client = OpenAiCompatibleClient(OKHTTP, JSON)
            client.streamChatEvents(
                ChatRequest(
                    baseUrl = server.url("/v1").toString(),
                    apiKey = "secret-key",
                    model = "gpt-5.5",
                    messages = listOf(OutgoingChatMessage(role = "user", text = "画只猫")),
                    tools = listOf(imageGenerateToolDefinition()),
                    toolChoice = "auto",
                ),
            ).toList()

            val body = requestBody(server)
            assertEquals("auto", body["tool_choice"]?.jsonPrimitive?.contentOrNull)
            val tool = body["tools"]!!.jsonArray.single().jsonObject
            assertEquals("function", tool["type"]?.jsonPrimitive?.contentOrNull)
            val function = tool["function"]!!.jsonObject
            assertEquals("image_generate", function["name"]?.jsonPrimitive?.contentOrNull)
            assertTrue(function["description"]!!.jsonPrimitive.contentOrNull!!.contains("生成图片"))
            val parameters = function["parameters"]!!.jsonObject
            assertEquals("object", parameters["type"]?.jsonPrimitive?.contentOrNull)
            assertEquals(
                listOf("prompt"),
                parameters["required"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull },
            )
            val properties = parameters["properties"]!!.jsonObject
            assertEquals("string", properties["prompt"]!!.jsonObject["type"]?.jsonPrimitive?.contentOrNull)
            assertEquals(
                listOf("1024x1024", "1024x1536", "1536x1024"),
                properties["size"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull },
            )
            assertEquals(
                listOf("low", "medium", "high"),
                properties["quality"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull },
            )
            assertEquals(
                listOf("gpt-image-2.5-flare", "gpt-image-2.5-sunburst", "gpt-image-2"),
                properties["model"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull },
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `未声明工具时请求体不含 tools 与 tool_choice`() = runTest {
        val server = MockWebServer()
        server.enqueue(streamResponse("data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\ndata: [DONE]"))
        server.start()
        try {
            val client = OpenAiCompatibleClient(OKHTTP, JSON)
            client.streamChatEvents(
                ChatRequest(
                    baseUrl = server.url("/v1").toString(),
                    apiKey = "secret-key",
                    model = "gpt-5.5",
                    messages = listOf(OutgoingChatMessage(role = "user", text = "画只猫")),
                ),
            ).toList()

            val body = requestBody(server)
            assertFalse(body.containsKey("tools"))
            assertFalse(body.containsKey("tool_choice"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `function tools 与 GLM 服务端搜索共用同一个 tools 数组`() = runTest {
        val server = MockWebServer()
        server.enqueue(streamResponse("data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\ndata: [DONE]"))
        server.start()
        try {
            val client = OpenAiCompatibleClient(OKHTTP, JSON)
            client.streamChatEvents(
                ChatRequest(
                    baseUrl = server.url("/v1").toString(),
                    apiKey = "secret-key",
                    model = "glm-5",
                    messages = listOf(OutgoingChatMessage(role = "user", text = "画只猫")),
                    nativeWebSearchMode = NativeWebSearchMode.GLM_WEB_SEARCH_TOOL,
                    tools = listOf(imageGenerateToolDefinition()),
                    toolChoice = "auto",
                ),
            ).toList()

            val tools = requestBody(server)["tools"]!!.jsonArray.map { it.jsonObject }
            assertEquals(2, tools.size)
            assertEquals("function", tools[0]["type"]?.jsonPrimitive?.contentOrNull)
            assertEquals("image_generate", tools[0]["function"]!!.jsonObject["name"]?.jsonPrimitive?.contentOrNull)
            assertEquals("web_search", tools[1]["type"]?.jsonPrimitive?.contentOrNull)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `流式 tool_calls 按 index 分片解析`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            streamResponse(
                """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"image_generate","arguments":"{\"prompt\":\"一只"}}]}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"橘猫\"}"}}]}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"image_generate","arguments":"{\"prompt\":\"柴犬\"}"}}]}}]}
                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
                data: [DONE]
                """.trimIndent(),
            ),
        )
        server.start()
        try {
            val client = OpenAiCompatibleClient(OKHTTP, JSON)
            val events = client.streamChatEvents(
                ChatRequest(
                    baseUrl = server.url("/v1").toString(),
                    apiKey = "secret-key",
                    model = "gpt-5.5",
                    messages = listOf(OutgoingChatMessage(role = "user", text = "画图")),
                    tools = listOf(imageGenerateToolDefinition()),
                    toolChoice = "auto",
                ),
            ).toList()

            assertEquals(
                listOf(
                    StreamEvent.ToolCallDelta("call_a", "image_generate", "{\"prompt\":\"一只", 0),
                    StreamEvent.ToolCallDelta("", null, "橘猫\"}", 0),
                    StreamEvent.ToolCallDelta("call_b", "image_generate", "{\"prompt\":\"柴犬\"}", 1),
                    StreamEvent.Finished("tool_calls"),
                ),
                events,
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `assistant tool_calls 与 tool 回执按 OpenAI 协议回传`() = runTest {
        val server = MockWebServer()
        server.enqueue(streamResponse("data: {\"choices\":[{\"delta\":{\"content\":\"好了\"}}]}\n\ndata: [DONE]"))
        server.start()
        try {
            val client = OpenAiCompatibleClient(OKHTTP, JSON)
            client.streamChatEvents(
                ChatRequest(
                    baseUrl = server.url("/v1").toString(),
                    apiKey = "secret-key",
                    model = "gpt-5.5",
                    messages = listOf(
                        OutgoingChatMessage(role = "system", text = "系统提示"),
                        OutgoingChatMessage(role = "user", text = "画只猫"),
                        OutgoingChatMessage(
                            role = "assistant",
                            text = "",
                            toolCalls = listOf(
                                OutgoingToolCall("call_a", "image_generate", "{\"prompt\":\"橘猫\"}"),
                            ),
                        ),
                        OutgoingChatMessage(role = "tool", text = "图片已生成", toolCallId = "call_a"),
                    ),
                ),
            ).toList()

            val messages = requestBody(server)["messages"]!!.jsonArray.map { it.jsonObject }
            val assistant = messages[2]
            assertEquals("assistant", assistant["role"]?.jsonPrimitive?.contentOrNull)
            assertEquals("", assistant["content"]?.jsonPrimitive?.contentOrNull)
            val toolCall = assistant["tool_calls"]!!.jsonArray.single().jsonObject
            assertEquals("call_a", toolCall["id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("function", toolCall["type"]?.jsonPrimitive?.contentOrNull)
            val function = toolCall["function"]!!.jsonObject
            assertEquals("image_generate", function["name"]?.jsonPrimitive?.contentOrNull)
            assertEquals("{\"prompt\":\"橘猫\"}", function["arguments"]?.jsonPrimitive?.contentOrNull)

            val tool = messages[3]
            assertEquals("tool", tool["role"]?.jsonPrimitive?.contentOrNull)
            assertEquals("图片已生成", tool["content"]?.jsonPrimitive?.contentOrNull)
            assertEquals("call_a", tool["tool_call_id"]?.jsonPrimitive?.contentOrNull)
            assertFalse(tool.containsKey("tool_calls"))
        } finally {
            server.shutdown()
        }
    }

    private fun requestBody(server: MockWebServer): JsonObject =
        JSON.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    private fun streamResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setBody(body)

    companion object {
        private val OKHTTP = okhttp3.OkHttpClient.Builder().build()
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
