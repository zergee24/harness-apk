package com.harnessapk.chat

import com.harnessapk.provider.ProviderProfile
import com.harnessapk.provider.ModelConfig
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
    fun defaultReasoningEffortIsHigh() {
        assertEquals(ReasoningEffort.HIGH, defaultReasoningEffort())
    }

    @Test
    fun openAiProviderUsesSelectedReasoningEffort() {
        val provider = providerProfile(name = "OpenAI", defaultModel = "gpt-5.5")

        assertEquals("medium", reasoningEffortForRequest(provider, "gpt-5.5", ReasoningEffort.MEDIUM))
    }

    @Test
    fun openAiProviderCanUseExtraHighReasoningEffort() {
        val provider = providerProfile(name = "OpenAI", defaultModel = "gpt-5.5")

        assertEquals("xhigh", reasoningEffortForRequest(provider, "gpt-5.5", ReasoningEffort.XHIGH))
    }

    @Test
    fun gptModelUsesSelectedReasoningEffortForOpenAiCompatibleProxy() {
        val provider = providerProfile(name = "HappyCode", defaultModel = "gpt-5.5")

        assertEquals("low", reasoningEffortForRequest(provider, "gpt-5.5", ReasoningEffort.LOW))
    }

    @Test
    fun reasoningEffortUsesModelConfigSwitch() {
        val provider = providerProfile(
            name = "HappyCode",
            defaultModel = "custom-reasoning",
            modelConfigs = listOf(ModelConfig("custom-reasoning", supportsReasoningEffort = true)),
        )

        assertEquals("high", reasoningEffortForRequest(provider, "custom-reasoning", ReasoningEffort.HIGH))
    }

    @Test
    fun modelConfigSwitchCanDisableReasoningEffortForGptModel() {
        val provider = providerProfile(
            name = "OpenAI",
            defaultModel = "gpt-5.5",
            modelConfigs = listOf(ModelConfig("gpt-5.5", supportsReasoningEffort = false)),
        )

        assertNull(reasoningEffortForRequest(provider, "gpt-5.5", ReasoningEffort.HIGH))
    }

    @Test
    fun nonOpenAiProviderDoesNotUseReasoningEffort() {
        val provider = providerProfile(name = "Kimi", defaultModel = "kimi-k2.7-code")

        assertNull(reasoningEffortForRequest(provider, "kimi-k2.7-code", ReasoningEffort.HIGH))
    }

    private fun providerProfile(
        name: String,
        defaultModel: String,
        modelConfigs: List<ModelConfig> = emptyList(),
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
        modelConfigs = modelConfigs,
    )
}
