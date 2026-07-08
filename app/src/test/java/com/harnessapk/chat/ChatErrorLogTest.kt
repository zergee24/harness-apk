package com.harnessapk.chat

import com.harnessapk.provider.ProviderProfile
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
