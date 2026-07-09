package com.harnessapk.chat

import com.harnessapk.provider.ProviderProfile
import com.harnessapk.provider.CapabilitySource
import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatErrorLogTest {
    @Test
    fun buildChatErrorLogIncludesProviderModelAndBaseUrlForTimeout() {
        val log = buildChatErrorLog(
            provider = providerProfile(),
            requestModel = "gpt-5.5",
            conversationId = "conversation-1",
            error = SocketTimeoutException("timeout"),
            nowMillis = 1234L,
        )

        assertTrue(log.contains("LLM 请求失败：timeout"))
        assertTrue(log.contains("Provider: OpenAI"))
        assertTrue(log.contains("Base URL: https://happycode.vip/v1"))
        assertTrue(log.contains("Model: gpt-5.5"))
        assertTrue(log.contains("Conversation: conversation-1"))
        assertTrue(log.contains("Exception: java.net.SocketTimeoutException"))
    }

    @Test
    fun buildChatErrorLogDoesNotIncludeApiKeyOrPrompt() {
        val log = buildChatErrorLog(
            provider = providerProfile(),
            requestModel = "gpt-5.5",
            conversationId = "conversation-1",
            error = IllegalStateException("bad sk-secret user prompt"),
            nowMillis = 1234L,
            sensitiveTerms = listOf("sk-secret", "user prompt"),
        )

        assertFalse(log.contains("sk-secret"))
        assertFalse(log.contains("user prompt"))
        assertTrue(log.contains("[已隐藏]"))
    }

    @Test
    fun buildChatErrorLogIncludesCapabilityDiagnostics() {
        val log = buildChatErrorLog(
            provider = providerProfile(),
            requestModel = "gpt-5.5",
            conversationId = "conversation-1",
            error = SocketTimeoutException("timeout"),
            nowMillis = 1234L,
            requestDiagnostics = ModelAwareRequestDiagnostics(
                capabilitySource = CapabilitySource.REMOTE,
                catalogVersion = "2026.07.09.1",
                readTimeoutMillis = 180_000L,
                droppedOptions = listOf("web_search"),
            ),
        )

        assertTrue(log.contains("Capability Source: REMOTE"))
        assertTrue(log.contains("Catalog Version: 2026.07.09.1"))
        assertTrue(log.contains("Read Timeout Ms: 180000"))
        assertTrue(log.contains("Dropped Options: web_search"))
    }

    @Test
    fun buildChatErrorLogIncludesRuntimeDiagnosticsForStreamingFailures() {
        val log = buildChatErrorLog(
            provider = providerProfile(),
            requestModel = "gpt-5.5",
            conversationId = "conversation-1",
            error = SocketTimeoutException("timeout"),
            nowMillis = 1_300L,
            runtimeDiagnostics = ChatRuntimeDiagnostics(
                traceId = "trace-1",
                startedAtMillis = 1_000L,
                failedAtMillis = 1_300L,
                flushCount = 4,
                receivedChars = 2048,
            ),
        )

        assertTrue(log.contains("Trace ID: trace-1"))
        assertTrue(log.contains("Started At: 1000"))
        assertTrue(log.contains("Failed At: 1300"))
        assertTrue(log.contains("Elapsed Ms: 300"))
        assertTrue(log.contains("Flush Count: 4"))
        assertTrue(log.contains("Received Chars: 2048"))
    }

    private fun providerProfile(): ProviderProfile = ProviderProfile(
        id = "provider-1",
        name = "OpenAI",
        baseUrl = "https://happycode.vip/v1",
        defaultModel = "gpt-5.5",
        defaultVisionModel = null,
        supportsVision = false,
        enabled = true,
        hasApiKey = true,
        availableModels = listOf("gpt-5.5"),
    )
}
