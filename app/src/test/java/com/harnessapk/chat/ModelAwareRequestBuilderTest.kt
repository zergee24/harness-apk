package com.harnessapk.chat

import com.harnessapk.common.AppError
import com.harnessapk.network.OutgoingChatMessage
import com.harnessapk.provider.CapabilitySource
import com.harnessapk.provider.ModelCapability
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.provider.ProviderProfile
import com.harnessapk.provider.ResolvedModelCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAwareRequestBuilderTest {
    @Test
    fun buildDropsUnsupportedReasoningAndSearchWithDiagnostics() {
        val result = ModelAwareRequestBuilder().build(
            provider = providerProfile("Kimi"),
            apiKey = "secret",
            capability = resolvedCapability(
                modelId = "kimi-k2.7-code",
                reasoningEffortOptions = emptyList(),
                webSearchMode = NativeWebSearchMode.DISABLED,
                source = CapabilitySource.REMOTE,
                catalogVersion = "remote-1",
            ),
            messages = listOf(OutgoingChatMessage(role = "user", text = "联网查一下")),
            temperature = 1.0,
            selectedReasoningEffort = ReasoningEffort.XHIGH,
            webSearchRequested = true,
        )

        assertNull(result.request.reasoningEffort)
        assertNull(result.request.nativeWebSearchMode)
        assertEquals(CapabilitySource.REMOTE, result.diagnostics.capabilitySource)
        assertEquals("remote-1", result.diagnostics.catalogVersion)
        assertTrue(result.diagnostics.droppedOptions.contains("reasoning_effort:xhigh"))
        assertTrue(result.diagnostics.droppedOptions.contains("web_search"))
    }

    @Test
    fun buildUsesResolvedCapabilityForReasoningSearchAndTimeout() {
        val result = ModelAwareRequestBuilder().build(
            provider = providerProfile(
                name = "OpenAI",
                customHeaders = mapOf("X-Provider-Feature" to "beta"),
                customBodyJson = """{"metadata":{"source":"local-override"}}""",
            ),
            apiKey = "secret",
            capability = resolvedCapability(
                modelId = "gpt-5.5",
                inputModalities = listOf("text", "image"),
                reasoningEffortOptions = listOf("low", "medium", "high", "xhigh"),
                defaultReasoningEffort = "high",
                webSearchMode = NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS,
                readTimeoutMillis = 240_000L,
                source = CapabilitySource.LOCAL_OVERRIDE,
            ),
            messages = listOf(
                OutgoingChatMessage(
                    role = "user",
                    text = "看图说明",
                    imageDataUrls = listOf("data:image/jpeg;base64,abc"),
                ),
            ),
            temperature = 0.2,
            selectedReasoningEffort = ReasoningEffort.XHIGH,
            webSearchRequested = true,
        )

        assertEquals("xhigh", result.request.reasoningEffort)
        assertEquals(NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS, result.request.nativeWebSearchMode)
        assertEquals(240_000L, result.request.readTimeoutMillis)
        assertEquals(mapOf("X-Provider-Feature" to "beta"), result.request.customHeaders)
        assertEquals("""{"metadata":{"source":"local-override"}}""", result.request.customBodyJson)
        assertEquals(CapabilitySource.LOCAL_OVERRIDE, result.diagnostics.capabilitySource)
        assertTrue(result.diagnostics.droppedOptions.isEmpty())
    }

    @Test
    fun buildRejectsImagesWhenModelDoesNotSupportImageInput() {
        val error = assertThrows(AppError.VisionUnsupported::class.java) {
            ModelAwareRequestBuilder().build(
                provider = providerProfile("OpenAI"),
                apiKey = "secret",
                capability = resolvedCapability(
                    modelId = "gpt-text-only",
                    inputModalities = listOf("text"),
                ),
                messages = listOf(
                    OutgoingChatMessage(
                        role = "user",
                        text = "看图",
                        imageDataUrls = listOf("data:image/jpeg;base64,abc"),
                    ),
                ),
                temperature = 0.2,
                selectedReasoningEffort = ReasoningEffort.HIGH,
                webSearchRequested = false,
            )
        }

        assertEquals("当前供应商未开启图片输入，请切换支持图片的模型或移除图片。", error.message)
    }

    private fun providerProfile(
        name: String,
        customHeaders: Map<String, String> = emptyMap(),
        customBodyJson: String = "",
    ): ProviderProfile = ProviderProfile(
        id = name.lowercase(),
        name = name,
        baseUrl = "https://example.com/v1",
        defaultModel = "gpt-5.5",
        defaultVisionModel = null,
        supportsVision = false,
        enabled = true,
        hasApiKey = true,
        customHeaders = customHeaders,
        customBodyJson = customBodyJson,
    )

    private fun resolvedCapability(
        modelId: String,
        inputModalities: List<String> = listOf("text"),
        reasoningEffortOptions: List<String> = emptyList(),
        defaultReasoningEffort: String? = null,
        webSearchMode: NativeWebSearchMode = NativeWebSearchMode.DISABLED,
        readTimeoutMillis: Long = 180_000L,
        source: CapabilitySource = CapabilitySource.BUNDLED,
        catalogVersion: String? = null,
    ): ResolvedModelCapability {
        val capability = ModelCapability(
            id = modelId,
            contextWindowTokens = 200_000,
            inputModalities = inputModalities,
            reasoningEffortOptions = reasoningEffortOptions,
            defaultReasoningEffort = defaultReasoningEffort,
            webSearchMode = webSearchMode,
            readTimeoutMillis = readTimeoutMillis,
        )
        return ResolvedModelCapability(
            providerProfileId = "provider",
            providerName = "OpenAI",
            modelId = capability.id,
            contextWindowTokens = capability.contextWindowTokens,
            compressionThresholdPercent = capability.compressionThresholdPercent,
            maxOutputTokens = capability.maxOutputTokens,
            inputModalities = capability.inputModalities,
            outputModalities = capability.outputModalities,
            reasoningEffortOptions = capability.reasoningEffortOptions,
            defaultReasoningEffort = capability.defaultReasoningEffort,
            webSearchMode = capability.webSearchMode,
            supportsToolCalling = capability.supportsToolCalling,
            readTimeoutMillis = capability.readTimeoutMillis,
            source = source,
            catalogVersion = catalogVersion,
        )
    }
}
