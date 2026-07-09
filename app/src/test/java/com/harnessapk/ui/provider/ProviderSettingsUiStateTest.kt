package com.harnessapk.ui.provider

import com.harnessapk.provider.ModelConfig
import com.harnessapk.provider.NativeWebSearchMode
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
    fun modelConfigListMaintainsReasoningSwitchIndependently() {
        val configs = listOf(
            ModelConfig("gpt-5.5", supportsReasoningEffort = true),
            ModelConfig("gpt-5.5-mini", supportsReasoningEffort = false),
        )

        assertEquals(
            listOf(
                ModelConfig("gpt-5.5", supportsReasoningEffort = false),
                ModelConfig("gpt-5.5-mini", supportsReasoningEffort = false),
            ),
            updateModelConfigAt(configs, index = 0) { it.copy(supportsReasoningEffort = false) },
        )
    }
}
