package com.harnessapk.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilityResolverTest {
    @Test
    fun remoteCatalogOverridesBundledCatalogForKnownModel() {
        val resolver = ModelCapabilityResolver(
            bundledCatalog = catalog(
                version = "bundled",
                provider = providerTemplate(
                    modelCapability("gpt-5.5", contextWindowTokens = 128_000),
                ),
            ),
            remoteCatalog = catalog(
                version = "remote-2026-07-09",
                provider = providerTemplate(
                    modelCapability("gpt-5.5", contextWindowTokens = 200_000),
                ),
            ),
        )

        val resolved = resolver.resolve(providerProfile("OpenAI"), "gpt-5.5")

        assertEquals(200_000, resolved.contextWindowTokens)
        assertEquals("remote-2026-07-09", resolved.catalogVersion)
        assertEquals(CapabilitySource.REMOTE, resolved.source)
    }

    @Test
    fun localOverrideWinsOverRemoteCatalog() {
        val resolver = ModelCapabilityResolver(
            bundledCatalog = catalog("bundled", providerTemplate(modelCapability("gpt-5.5", 128_000))),
            remoteCatalog = catalog("remote", providerTemplate(modelCapability("gpt-5.5", 200_000))),
            overrides = listOf(
                ModelCapabilityOverride(
                    providerProfileId = "openai-profile",
                    modelId = "gpt-5.5",
                    contextWindowTokens = 300_000,
                    compressionThresholdPercent = 60,
                    hidden = false,
                ),
            ),
        )

        val resolved = resolver.resolve(providerProfile("OpenAI", id = "openai-profile"), "gpt-5.5")

        assertEquals(300_000, resolved.contextWindowTokens)
        assertEquals(60, resolved.compressionThresholdPercent)
        assertEquals(CapabilitySource.LOCAL_OVERRIDE, resolved.source)
    }

    @Test
    fun providerProfileModelConfigBackfillsUnknownCatalogModel() {
        val provider = providerProfile(
            name = "Custom",
            modelConfigs = listOf(ModelConfig("local-model", contextWindowTokens = 256_000, compressionThresholdPercent = 65)),
        )

        val resolved = ModelCapabilityResolver().resolve(provider, "local-model")

        assertEquals(256_000, resolved.contextWindowTokens)
        assertEquals(65, resolved.compressionThresholdPercent)
        assertEquals(CapabilitySource.PROVIDER_PROFILE, resolved.source)
    }

    @Test
    fun unknownModelUsesConservativeFallback() {
        val resolved = ModelCapabilityResolver().resolve(providerProfile("Unknown"), "new-model")

        assertEquals("new-model", resolved.modelId)
        assertEquals(200_000, resolved.contextWindowTokens)
        assertEquals(70, resolved.compressionThresholdPercent)
        assertEquals(NativeWebSearchMode.DISABLED, resolved.webSearchMode)
        assertEquals(CapabilitySource.FALLBACK, resolved.source)
    }

    @Test
    fun hiddenLocalOverrideRemovesModelFromSelectableList() {
        val resolver = ModelCapabilityResolver(
            bundledCatalog = catalog(
                "bundled",
                providerTemplate(
                    modelCapability("visible", 200_000),
                    modelCapability("hidden", 200_000),
                ),
            ),
            overrides = listOf(
                ModelCapabilityOverride(
                    providerProfileId = "provider",
                    modelId = "hidden",
                    hidden = true,
                ),
            ),
        )

        val models = resolver.selectableModels(providerProfile("OpenAI", id = "provider", defaultModel = "visible")).map { it.modelId }

        assertEquals(listOf("visible"), models)
    }

    @Test
    fun gptModelCapabilityCarriesReasoningAndSearchSettings() {
        val resolver = ModelCapabilityResolver(
            bundledCatalog = catalog(
                "bundled",
                providerTemplate(
                    modelCapability(
                        id = "gpt-5.5",
                        contextWindowTokens = 200_000,
                        reasoningEffortOptions = listOf("low", "medium", "high", "xhigh"),
                        defaultReasoningEffort = "high",
                        webSearchMode = NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS,
                    ),
                ),
            ),
        )

        val resolved = resolver.resolve(providerProfile("OpenAI"), "gpt-5.5")

        assertEquals(listOf("low", "medium", "high", "xhigh"), resolved.reasoningEffortOptions)
        assertEquals("high", resolved.defaultReasoningEffort)
        assertEquals(NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS, resolved.webSearchMode)
        assertTrue(resolved.supportsReasoningEffort)
    }

    private fun catalog(
        version: String,
        provider: ProviderCapabilityTemplate,
    ): ProviderCapabilityCatalog = ProviderCapabilityCatalog(
        catalogVersion = version,
        providers = listOf(provider),
    )

    private fun providerTemplate(
        vararg model: ModelCapability,
        providerId: String = "openai",
        displayName: String = "OpenAI",
    ): ProviderCapabilityTemplate = ProviderCapabilityTemplate(
        providerId = providerId,
        displayName = displayName,
        defaultModelId = model.first().id,
        models = model.toList(),
    )

    private fun modelCapability(
        id: String,
        contextWindowTokens: Int,
        reasoningEffortOptions: List<String> = emptyList(),
        defaultReasoningEffort: String? = null,
        webSearchMode: NativeWebSearchMode = NativeWebSearchMode.DISABLED,
    ): ModelCapability = ModelCapability(
        id = id,
        contextWindowTokens = contextWindowTokens,
        compressionThresholdPercent = 70,
        reasoningEffortOptions = reasoningEffortOptions,
        defaultReasoningEffort = defaultReasoningEffort,
        webSearchMode = webSearchMode,
    )

    private fun providerProfile(
        name: String,
        id: String = name.lowercase(),
        modelConfigs: List<ModelConfig> = emptyList(),
        defaultModel: String = modelConfigs.firstOrNull()?.id ?: "gpt-5.5",
    ): ProviderProfile = ProviderProfile(
        id = id,
        name = name,
        baseUrl = "https://example.com/v1",
        defaultModel = defaultModel,
        defaultVisionModel = null,
        supportsVision = false,
        enabled = true,
        hasApiKey = true,
        availableModels = modelConfigs.map { it.id },
        modelConfigs = modelConfigs,
    )
}
