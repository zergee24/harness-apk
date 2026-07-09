package com.harnessapk.ui.model

import com.harnessapk.provider.ProviderProfile

data class ModelSelection(
    val providerId: String?,
    val model: String,
)

fun selectableModelsForProvider(provider: ProviderProfile): List<String> =
    provider.availableModels.ifEmpty { listOf(provider.defaultModel) }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

fun resolveModelSelection(
    providers: List<ProviderProfile>,
    currentProviderId: String?,
    currentModel: String,
    preferredProviderId: String?,
    preferredModel: String?,
    selectableModelsForProvider: (ProviderProfile) -> List<String> = ::selectableModelsForProvider,
): ModelSelection {
    if (providers.isEmpty()) return ModelSelection(providerId = null, model = "")

    val currentProvider = providers.firstOrNull { it.id == currentProviderId }
    if (currentProvider != null) {
        return ModelSelection(
            providerId = currentProvider.id,
            model = modelOrFallback(currentProvider, currentModel, selectableModelsForProvider),
        )
    }

    val preferredProvider = providers.firstOrNull { it.id == preferredProviderId }
    val provider = preferredProvider ?: providers.first()
    val model = if (preferredProvider != null) {
        modelOrFallback(provider, preferredModel.orEmpty(), selectableModelsForProvider)
    } else {
        modelOrFallback(provider, "", selectableModelsForProvider)
    }

    return ModelSelection(providerId = provider.id, model = model)
}

private fun modelOrFallback(
    provider: ProviderProfile,
    model: String,
    selectableModelsForProvider: (ProviderProfile) -> List<String>,
): String {
    val selectableModels = selectableModelsForProvider(provider)
    return model.trim().takeIf { it in selectableModels }
        ?: selectableModels.firstOrNull().orEmpty()
}
