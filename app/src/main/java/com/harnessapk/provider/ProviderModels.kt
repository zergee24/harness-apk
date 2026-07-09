package com.harnessapk.provider

enum class NativeWebSearchMode {
    DISABLED,
    OPENAI_WEB_SEARCH_OPTIONS,
    ENABLE_SEARCH_BOOLEAN,
    GLM_WEB_SEARCH_TOOL,
    EXTERNAL_BING,
}

data class ProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val defaultVisionModel: String?,
    val supportsVision: Boolean,
    val nativeWebSearchMode: NativeWebSearchMode = NativeWebSearchMode.DISABLED,
    val enabled: Boolean,
    val hasApiKey: Boolean,
    val availableModels: List<String> = emptyList(),
    val modelConfigs: List<ModelConfig> = emptyList(),
)

data class ProviderDraft(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val defaultModel: String,
    val defaultVisionModel: String?,
    val supportsVision: Boolean,
    val nativeWebSearchMode: NativeWebSearchMode = NativeWebSearchMode.DISABLED,
    val availableModels: List<String> = emptyList(),
    val modelConfigs: List<ModelConfig> = emptyList(),
)

data class ModelConfig(
    val id: String,
    val contextWindowTokens: Int = DEFAULT_CONTEXT_WINDOW_TOKENS,
    val compressionThresholdPercent: Int = DEFAULT_COMPRESSION_THRESHOLD_PERCENT,
)

const val DEFAULT_CONTEXT_WINDOW_TOKENS = 200_000
const val DEFAULT_COMPRESSION_THRESHOLD_PERCENT = 70

fun modelConfigForProvider(provider: ProviderProfile?, model: String): ModelConfig {
    if (provider == null || model.isBlank()) return defaultModelConfig("", model)
    return provider.modelConfigs.firstOrNull { it.id == model }
        ?: defaultModelConfig(provider.name, model)
}
