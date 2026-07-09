package com.harnessapk.ui.provider

import com.harnessapk.provider.ModelConfig
import com.harnessapk.provider.ModelCapability
import com.harnessapk.provider.ProviderCapabilityCatalog
import com.harnessapk.provider.ProviderCapabilityTemplate
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.provider.CapabilitySource
import com.harnessapk.provider.ProviderTemplates
import com.harnessapk.provider.ResolvedModelCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSettingsUiStateTest {
    @Test
    fun nativeWebSearchSwitchHidesProviderSpecificModes() {
        assertFalse(isNativeWebSearchEnabled(NativeWebSearchMode.DISABLED))
        assertTrue(isNativeWebSearchEnabled(NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS))

        assertEquals(
            NativeWebSearchMode.DISABLED,
            nativeWebSearchModeForSwitch(providerName = "OpenAI", enabled = false),
        )
        assertEquals(
            NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS,
            nativeWebSearchModeForSwitch(providerName = "OpenAI", enabled = true),
        )
        assertEquals(
            NativeWebSearchMode.ENABLE_SEARCH_BOOLEAN,
            nativeWebSearchModeForSwitch(providerName = "Kimi", enabled = true),
        )
        assertEquals(
            NativeWebSearchMode.GLM_WEB_SEARCH_TOOL,
            nativeWebSearchModeForSwitch(providerName = "GLM", enabled = true),
        )
    }

    @Test
    fun modelConfigListMaintainsEachModelIndependently() {
        val configs = listOf(
            ModelConfig("gpt-5.5", contextWindowTokens = 1_000_000, compressionThresholdPercent = 70),
            ModelConfig("gpt-5.5-mini", contextWindowTokens = 400_000, compressionThresholdPercent = 65),
        )

        assertEquals(
            listOf(
                ModelConfig("gpt-5.5", contextWindowTokens = 1_000_000, compressionThresholdPercent = 80),
                ModelConfig("gpt-5.5-mini", contextWindowTokens = 400_000, compressionThresholdPercent = 65),
            ),
            updateModelConfigAt(configs, index = 0) { it.copy(compressionThresholdPercent = 80) },
        )
        assertEquals(
            listOf("gpt-5.5"),
            removeModelConfigAt(configs, index = 1).map { it.id },
        )
        assertEquals(
            listOf("gpt-5.5", "gpt-5.5-mini", "new-model"),
            appendModelConfig(configs, providerName = "OpenAI").map { it.id },
        )
    }

    @Test
    fun templateModelConfigsPreferRemoteCatalogModels() {
        val template = ProviderTemplates.defaults.first { it.name == "OpenAI" }
        val catalog = ProviderCapabilityCatalog(
            catalogVersion = "remote",
            providers = listOf(
                ProviderCapabilityTemplate(
                    providerId = "openai",
                    displayName = "OpenAI",
                    defaultModelId = "gpt-5.5",
                    models = listOf(
                        ModelCapability(
                            id = "gpt-5.5",
                            contextWindowTokens = 200_000,
                            compressionThresholdPercent = 70,
                        ),
                        ModelCapability(
                            id = "gpt-5.5-mini",
                            contextWindowTokens = 128_000,
                            compressionThresholdPercent = 65,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                ModelConfig("gpt-5.5", contextWindowTokens = 200_000, compressionThresholdPercent = 70),
                ModelConfig("gpt-5.5-mini", contextWindowTokens = 128_000, compressionThresholdPercent = 65),
            ),
            modelConfigsForTemplate(template, catalog),
        )
    }

    @Test
    fun capabilitySourceSummaryShowsRemoteVersionAndLocalOverride() {
        assertEquals(
            "remote · 2026.07.09",
            capabilitySourceSummary(
                resolvedCapability(
                    source = CapabilitySource.REMOTE,
                    catalogVersion = "2026.07.09",
                ),
            ),
        )
        assertEquals(
            "本地覆盖",
            capabilitySourceSummary(
                resolvedCapability(
                    source = CapabilitySource.LOCAL_OVERRIDE,
                    catalogVersion = "2026.07.09",
                ),
            ),
        )
    }

    private fun resolvedCapability(
        source: CapabilitySource,
        catalogVersion: String?,
    ): ResolvedModelCapability = ResolvedModelCapability(
        providerProfileId = "provider",
        providerName = "OpenAI",
        modelId = "gpt-5.5",
        contextWindowTokens = 200_000,
        compressionThresholdPercent = 70,
        maxOutputTokens = null,
        inputModalities = listOf("text"),
        outputModalities = listOf("text"),
        reasoningEffortOptions = emptyList(),
        defaultReasoningEffort = null,
        webSearchMode = NativeWebSearchMode.DISABLED,
        readTimeoutMillis = 180_000L,
        source = source,
        catalogVersion = catalogVersion,
    )
}
