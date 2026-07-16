package com.harnessapk.provider

object ProviderTemplates {
    val defaults = listOf(
        ProviderTemplate(
            name = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            defaultModel = "kimi-k2.7-code",
            modelConfigs = listOf(
                ModelConfig("kimi-k2.7-code", contextWindowTokens = 256_000),
                ModelConfig("kimi-k2.7-code-highspeed", contextWindowTokens = 256_000),
                ModelConfig("kimi-k2.6", contextWindowTokens = 256_000),
            ),
            defaultVisionModel = "kimi-k2.7-code",
            supportsVision = true,
            nativeWebSearchMode = NativeWebSearchMode.ENABLE_SEARCH_BOOLEAN,
        ),
        ProviderTemplate(
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-pro",
            modelConfigs = listOf(
                ModelConfig("deepseek-v4-pro", contextWindowTokens = 1_000_000),
                ModelConfig("deepseek-v4-flash", contextWindowTokens = 1_000_000),
            ),
            defaultVisionModel = null,
            supportsVision = false,
            nativeWebSearchMode = NativeWebSearchMode.DISABLED,
        ),
        ProviderTemplate(
            name = "OpenAI",
            baseUrl = "https://happycode.vip/v1",
            defaultModel = "gpt-5.6-terra",
            modelConfigs = listOf(
                openAiModelConfig("gpt-5.6-terra"),
                openAiModelConfig("gpt-5.6-sol"),
            ),
            defaultVisionModel = "gpt-5.6-terra",
            supportsVision = true,
            nativeWebSearchMode = NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS,
        ),
        ProviderTemplate(
            name = "GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-5.2",
            modelConfigs = listOf(
                ModelConfig("glm-5.2", contextWindowTokens = 1_000_000),
                ModelConfig("glm-5-turbo", contextWindowTokens = 200_000),
                ModelConfig("glm-4.7", contextWindowTokens = 128_000),
                ModelConfig("glm-5v-turbo", contextWindowTokens = 128_000),
            ),
            defaultVisionModel = "glm-5v-turbo",
            supportsVision = true,
            nativeWebSearchMode = NativeWebSearchMode.GLM_WEB_SEARCH_TOOL,
        ),
    )

    val default: ProviderTemplate = defaults.first()
}

data class ProviderTemplate(
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val modelConfigs: List<ModelConfig>,
    val defaultVisionModel: String?,
    val supportsVision: Boolean,
    val nativeWebSearchMode: NativeWebSearchMode,
) {
    val availableModels: List<String> = modelConfigs.map { it.id }
}

private fun openAiModelConfig(id: String): ModelConfig = ModelConfig(
    id = id,
    contextWindowTokens = 200_000,
    maxOutputTokens = 32_000,
    inputModalities = listOf("text", "image"),
    outputModalities = listOf("text"),
    reasoningEffortOptions = OPEN_AI_REASONING_EFFORT_OPTIONS,
    defaultReasoningEffort = DEFAULT_OPEN_AI_REASONING_EFFORT,
    webSearchMode = NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS,
    supportsToolCalling = false,
    readTimeoutMillis = 180_000L,
)
