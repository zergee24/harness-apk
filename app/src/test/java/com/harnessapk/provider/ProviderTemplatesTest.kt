package com.harnessapk.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTemplatesTest {
    @Test
    fun defaultTemplateUsesFirstProviderTemplate() {
        assertEquals("Kimi", ProviderTemplates.default.name)
        assertEquals("kimi-k2.7-code", ProviderTemplates.default.defaultModel)
    }

    @Test
    fun kimiTemplateUsesConfiguredDefaultModels() {
        val template = ProviderTemplates.defaults.first { it.name == "Kimi" }

        assertEquals("kimi-k2.7-code", template.defaultModel)
        assertEquals("kimi-k2.7-code", template.defaultVisionModel)
        assertEquals(
            listOf("kimi-k2.7-code", "kimi-k2.7-code-highspeed", "kimi-k2.6"),
            template.availableModels,
        )
        assertEquals(NativeWebSearchMode.ENABLE_SEARCH_BOOLEAN, template.nativeWebSearchMode)
        assertEquals(256_000, template.modelConfigs.first { it.id == "kimi-k2.7-code" }.contextWindowTokens)
    }

    @Test
    fun deepSeekTemplateUsesConfiguredDefaultModel() {
        val template = ProviderTemplates.defaults.first { it.name == "DeepSeek" }

        assertEquals("https://api.deepseek.com", template.baseUrl)
        assertEquals("deepseek-v4-pro", template.defaultModel)
        assertEquals(listOf("deepseek-v4-pro", "deepseek-v4-flash"), template.availableModels)
        assertEquals(1_000_000, template.modelConfigs.first { it.id == "deepseek-v4-pro" }.contextWindowTokens)
    }

    @Test
    fun openAiTemplateUsesConfiguredDefaultBaseUrl() {
        val template = ProviderTemplates.defaults.first { it.name == "OpenAI" }

        assertEquals("https://happycode.vip/v1", template.baseUrl)
        assertEquals("gpt-5.5", template.defaultModel)
        assertEquals("gpt-5.5", template.defaultVisionModel)
        assertEquals(listOf("gpt-5.5"), template.availableModels)
        assertEquals(NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS, template.nativeWebSearchMode)
        assertEquals(200_000, template.modelConfigs.first { it.id == "gpt-5.5" }.contextWindowTokens)
    }

    @Test
    fun glmTemplateUsesOpenAiCompatibleDefaults() {
        val template = ProviderTemplates.defaults.first { it.name == "GLM" }

        assertEquals("https://open.bigmodel.cn/api/paas/v4", template.baseUrl)
        assertEquals("glm-5.2", template.defaultModel)
        assertEquals("glm-5v-turbo", template.defaultVisionModel)
        assertEquals(
            listOf("glm-5.2", "glm-5-turbo", "glm-4.7", "glm-5v-turbo"),
            template.availableModels,
        )
        assertTrue(template.supportsVision)
        assertEquals(NativeWebSearchMode.GLM_WEB_SEARCH_TOOL, template.nativeWebSearchMode)
        assertEquals(1_000_000, template.modelConfigs.first { it.id == "glm-5.2" }.contextWindowTokens)
        assertEquals(200_000, template.modelConfigs.first { it.id == "glm-5-turbo" }.contextWindowTokens)
    }

    @Test
    fun eachTemplateIncludesDefaultModelInSelectableModels() {
        ProviderTemplates.defaults.forEach { template ->
            check(template.defaultModel in template.availableModels) {
                "${template.name} default model must be selectable"
            }
        }
    }

    @Test
    fun eachTemplateModelUsesEditableCompressionDefaults() {
        ProviderTemplates.defaults.flatMap { it.modelConfigs }.forEach { config ->
            assertTrue("${config.id} context should be realistic", config.contextWindowTokens >= 128_000)
            assertEquals(70, config.compressionThresholdPercent)
        }
    }
}
