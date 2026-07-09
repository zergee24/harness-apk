package com.harnessapk.provider

data class ProviderCapabilityCatalog(
    val catalogVersion: String,
    val providers: List<ProviderCapabilityTemplate>,
)

data class ProviderCapabilityTemplate(
    val providerId: String,
    val displayName: String,
    val defaultModelId: String,
    val models: List<ModelCapability>,
)

data class ModelCapability(
    val id: String,
    val contextWindowTokens: Int,
    val compressionThresholdPercent: Int = DEFAULT_COMPRESSION_THRESHOLD_PERCENT,
    val maxOutputTokens: Int? = null,
    val inputModalities: List<String> = listOf("text"),
    val outputModalities: List<String> = listOf("text"),
    val reasoningEffortOptions: List<String> = emptyList(),
    val defaultReasoningEffort: String? = null,
    val webSearchMode: NativeWebSearchMode = NativeWebSearchMode.DISABLED,
    val readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS,
) {
    val supportsReasoningEffort: Boolean = reasoningEffortOptions.isNotEmpty()
}

data class ModelCapabilityOverride(
    val providerProfileId: String,
    val modelId: String,
    val contextWindowTokens: Int? = null,
    val compressionThresholdPercent: Int? = null,
    val maxOutputTokens: Int? = null,
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
    val reasoningEffortOptions: List<String>? = null,
    val defaultReasoningEffort: String? = null,
    val webSearchMode: NativeWebSearchMode? = null,
    val readTimeoutMillis: Long? = null,
    val hidden: Boolean = false,
)

data class ResolvedModelCapability(
    val providerProfileId: String?,
    val providerName: String,
    val modelId: String,
    val contextWindowTokens: Int,
    val compressionThresholdPercent: Int,
    val maxOutputTokens: Int?,
    val inputModalities: List<String>,
    val outputModalities: List<String>,
    val reasoningEffortOptions: List<String>,
    val defaultReasoningEffort: String?,
    val webSearchMode: NativeWebSearchMode,
    val readTimeoutMillis: Long,
    val source: CapabilitySource,
    val catalogVersion: String?,
) {
    val supportsReasoningEffort: Boolean = reasoningEffortOptions.isNotEmpty()
}

enum class CapabilitySource {
    BUNDLED,
    REMOTE,
    PROVIDER_PROFILE,
    LOCAL_OVERRIDE,
    FALLBACK,
}

class ModelCapabilityResolver(
    private val bundledCatalog: ProviderCapabilityCatalog = bundledProviderCapabilityCatalog(),
    private val remoteCatalog: ProviderCapabilityCatalog? = null,
    overrides: List<ModelCapabilityOverride> = emptyList(),
) {
    private val overridesByProviderAndModel = overrides.associateBy {
        it.providerProfileId to it.modelId
    }

    fun resolve(provider: ProviderProfile?, modelId: String): ResolvedModelCapability {
        val normalizedModelId = modelId.trim()
        val base = findCatalogCapability(remoteCatalog, provider, normalizedModelId)
            ?: findCatalogCapability(bundledCatalog, provider, normalizedModelId)
            ?: provider?.modelConfigs
                ?.firstOrNull { it.id == normalizedModelId }
                ?.let { config ->
                    CatalogResolution(
                        capability = ModelCapability(
                            id = config.id,
                            contextWindowTokens = config.contextWindowTokens,
                            compressionThresholdPercent = config.compressionThresholdPercent,
                        ),
                        source = CapabilitySource.PROVIDER_PROFILE,
                        catalogVersion = null,
                    )
                }
            ?: CatalogResolution(
                capability = fallbackModelCapability(normalizedModelId),
                source = CapabilitySource.FALLBACK,
                catalogVersion = null,
            )
        val savedConfigOverride = if (base.source == CapabilitySource.PROVIDER_PROFILE) {
            null
        } else {
            provider?.modelConfigs
                ?.firstOrNull { it.id == normalizedModelId }
                ?.let { config ->
                    ModelCapabilityOverride(
                        providerProfileId = provider.id,
                        modelId = normalizedModelId,
                        contextWindowTokens = config.contextWindowTokens,
                        compressionThresholdPercent = config.compressionThresholdPercent,
                    )
                }
        }
        val explicitOverride = provider?.let {
            overridesByProviderAndModel[it.id to normalizedModelId]
        }?.takeIf { !it.hidden }
        return base.toResolved(provider, explicitOverride ?: savedConfigOverride)
    }

    fun selectableModels(provider: ProviderProfile): List<ResolvedModelCapability> {
        val hiddenModels = overridesByProviderAndModel
            .filter { (key, override) -> key.first == provider.id && override.hidden }
            .keys
            .mapTo(mutableSetOf()) { it.second }
        val modelIds = buildList {
            providerTemplate(remoteCatalog, provider)?.models?.mapTo(this) { it.id }
            providerTemplate(bundledCatalog, provider)?.models?.mapTo(this) { it.id }
            provider.modelConfigs.mapTo(this) { it.id }
            provider.availableModels.mapTo(this) { it }
            add(provider.defaultModel)
            provider.defaultVisionModel?.let(::add)
        }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .filterNot { it in hiddenModels }
        return modelIds.map { resolve(provider, it) }
    }

    private fun findCatalogCapability(
        catalog: ProviderCapabilityCatalog?,
        provider: ProviderProfile?,
        modelId: String,
    ): CatalogResolution? {
        val currentCatalog = catalog ?: return null
        val template = providerTemplate(currentCatalog, provider) ?: return null
        val capability = template.models.firstOrNull { model ->
            model.id.equals(modelId, ignoreCase = true)
        } ?: return null
        val source = if (currentCatalog === remoteCatalog) CapabilitySource.REMOTE else CapabilitySource.BUNDLED
        return CatalogResolution(capability, source, currentCatalog.catalogVersion)
    }

    private fun providerTemplate(
        catalog: ProviderCapabilityCatalog?,
        provider: ProviderProfile?,
    ): ProviderCapabilityTemplate? {
        if (catalog == null || provider == null) return null
        val normalizedName = provider.name.lowercase()
        return catalog.providers.firstOrNull { template ->
            template.providerId.equals(normalizedName, ignoreCase = true) ||
                template.displayName.equals(provider.name, ignoreCase = true)
        }
    }

    private fun CatalogResolution.toResolved(
        provider: ProviderProfile?,
        override: ModelCapabilityOverride?,
    ): ResolvedModelCapability {
        val nextSource = if (override != null) CapabilitySource.LOCAL_OVERRIDE else source
        return ResolvedModelCapability(
            providerProfileId = provider?.id,
            providerName = provider?.name.orEmpty(),
            modelId = capability.id,
            contextWindowTokens = override?.contextWindowTokens
                ?: capability.contextWindowTokens,
            compressionThresholdPercent = override?.compressionThresholdPercent
                ?: capability.compressionThresholdPercent,
            maxOutputTokens = override?.maxOutputTokens ?: capability.maxOutputTokens,
            inputModalities = override?.inputModalities ?: capability.inputModalities,
            outputModalities = override?.outputModalities ?: capability.outputModalities,
            reasoningEffortOptions = override?.reasoningEffortOptions ?: capability.reasoningEffortOptions,
            defaultReasoningEffort = override?.defaultReasoningEffort ?: capability.defaultReasoningEffort,
            webSearchMode = override?.webSearchMode ?: capability.webSearchMode,
            readTimeoutMillis = override?.readTimeoutMillis ?: capability.readTimeoutMillis,
            source = nextSource,
            catalogVersion = catalogVersion,
        )
    }

    private data class CatalogResolution(
        val capability: ModelCapability,
        val source: CapabilitySource,
        val catalogVersion: String?,
    )
}

private fun bundledProviderCapabilityCatalog(): ProviderCapabilityCatalog =
    ProviderCapabilityCatalog(
        catalogVersion = "bundled",
        providers = ProviderTemplates.defaults.map { template ->
            ProviderCapabilityTemplate(
                providerId = template.name.lowercase(),
                displayName = template.name,
                defaultModelId = template.defaultModel,
                models = template.modelConfigs.map { config ->
                    ModelCapability(
                        id = config.id,
                        contextWindowTokens = config.contextWindowTokens,
                        compressionThresholdPercent = config.compressionThresholdPercent,
                        inputModalities = if (template.supportsVision) listOf("text", "image") else listOf("text"),
                        outputModalities = listOf("text"),
                        reasoningEffortOptions = if (config.id.lowercase().startsWith("gpt-")) {
                            listOf("low", "medium", "high", "xhigh")
                        } else {
                            emptyList()
                        },
                        defaultReasoningEffort = if (config.id.lowercase().startsWith("gpt-")) "high" else null,
                        webSearchMode = template.nativeWebSearchMode,
                    )
                },
            )
        },
    )

private fun fallbackModelCapability(modelId: String): ModelCapability =
    modelId.trim().lowercase().let { normalizedModel ->
        ModelCapability(
            id = modelId.trim(),
            contextWindowTokens = DEFAULT_CONTEXT_WINDOW_TOKENS,
            compressionThresholdPercent = DEFAULT_COMPRESSION_THRESHOLD_PERCENT,
            reasoningEffortOptions = if (normalizedModel.startsWith("gpt-")) {
                listOf("low", "medium", "high", "xhigh")
            } else {
                emptyList()
            },
            defaultReasoningEffort = if (normalizedModel.startsWith("gpt-")) "high" else null,
            webSearchMode = NativeWebSearchMode.DISABLED,
        )
    }

private const val DEFAULT_READ_TIMEOUT_MILLIS = 180_000L
