package com.harnessapk.chat

import com.harnessapk.provider.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRequestOptionsTest {
    @Test
    fun kimiK2ModelsUseRequiredTemperature() {
        assertEquals(1.0, temperatureForModel("kimi-k2.7-code"), 0.0)
        assertEquals(1.0, temperatureForModel("kimi-k2.6"), 0.0)
    }

    @Test
    fun nonKimiK2ModelsUseDefaultTemperature() {
        assertEquals(0.2, temperatureForModel("gpt-4.1-mini"), 0.0)
        assertEquals(0.2, temperatureForModel("deepseekv4pro"), 0.0)
    }

    @Test
    fun legacyKimiK2ModelNamesUseAvailableKimiModel() {
        assertEquals("kimi-k2.7-code", modelForRequest("kimi-k2"))
        assertEquals("kimi-k2.7-code", modelForRequest("kimi-k2.7"))
        assertEquals("kimi-k2.7-code", modelForRequest(" kimi-k2.7-code "))
    }

    @Test
    fun nonKimiModelNamesAreTrimmedOnly() {
        assertEquals("gpt-5.5", modelForRequest(" gpt-5.5 "))
    }

    @Test
    fun defaultReasoningEffortIsExtraHigh() {
        assertEquals(ReasoningEffort.XHIGH, defaultReasoningEffort())
    }

    @Test
    fun reasoningEffortSupportsAllSixOpenAiLevels() {
        assertEquals(
            listOf(
                "minimal",
                "low",
                "medium",
                "high",
                "xhigh",
                "max",
            ),
            ReasoningEffort.entries.map { it.wireValue },
        )
    }

    @Test
    fun openAiProviderUsesSelectedReasoningEffort() {
        val provider = providerProfile(name = "OpenAI", defaultModel = "gpt-5.6-terra")

        assertEquals("medium", reasoningEffortForRequest(provider, "gpt-5.6-terra", ReasoningEffort.MEDIUM))
    }

    @Test
    fun openAiProviderCanUseExtraHighReasoningEffort() {
        val provider = providerProfile(name = "OpenAI", defaultModel = "gpt-5.6-terra")

        assertEquals("xhigh", reasoningEffortForRequest(provider, "gpt-5.6-terra", ReasoningEffort.XHIGH))
    }

    @Test
    fun gptModelUsesSelectedReasoningEffortForOpenAiCompatibleProxy() {
        val provider = providerProfile(name = "HappyCode", defaultModel = "gpt-5.6-sol")

        assertEquals("low", reasoningEffortForRequest(provider, "gpt-5.6-sol", ReasoningEffort.LOW))
    }

    @Test
    fun nonOpenAiProviderDoesNotUseReasoningEffort() {
        val provider = providerProfile(name = "Kimi", defaultModel = "kimi-k2.7-code")

        assertNull(reasoningEffortForRequest(provider, "kimi-k2.7-code", ReasoningEffort.HIGH))
    }

    private fun providerProfile(
        name: String,
        defaultModel: String,
    ): ProviderProfile = ProviderProfile(
        id = name.lowercase(),
        name = name,
        baseUrl = "https://example.com/v1",
        defaultModel = defaultModel,
        defaultVisionModel = null,
        supportsVision = false,
        enabled = true,
        hasApiKey = true,
        availableModels = listOf(defaultModel),
    )
}
