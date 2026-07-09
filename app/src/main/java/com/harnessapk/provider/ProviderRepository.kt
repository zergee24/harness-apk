package com.harnessapk.provider

import com.harnessapk.common.AppError
import com.harnessapk.common.TimeProvider
import com.harnessapk.security.EncryptedValue
import com.harnessapk.security.StringCipher
import com.harnessapk.storage.ProviderProfileDao
import com.harnessapk.storage.ProviderProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class ProviderRepository(
    private val dao: ProviderProfileDao,
    private val cipher: StringCipher,
    private val timeProvider: TimeProvider,
) {
    fun observeEnabled(): Flow<List<ProviderProfile>> = dao.observeEnabled().map { rows ->
        rows.map { it.toDomain() }
    }

    suspend fun firstEnabled(): ProviderProfile? = dao.firstEnabled()?.toDomain()

    suspend fun saveProvider(draft: ProviderDraft): String {
        requireHttpsBaseUrl(draft.baseUrl)
        requireProviderFields(draft)
        require(draft.apiKey.isNotBlank()) { "API Key 不能为空" }

        val now = timeProvider.nowMillis()
        val encrypted = cipher.encrypt(draft.apiKey)
        val id = UUID.randomUUID().toString()
        val defaultModel = draft.defaultModel.trim()
        val defaultVisionModel = draft.defaultVisionModel?.trim()?.takeIf { it.isNotBlank() }
        dao.insert(
            ProviderProfileEntity(
                id = id,
                name = draft.name.trim(),
                baseUrl = draft.baseUrl.trim().trimEnd('/'),
                apiKeyAlias = "provider:$id",
                encryptedApiKey = encrypted.cipherText,
                apiKeyIv = encrypted.initializationVector,
                defaultModel = defaultModel,
                availableModels = normalizeAvailableModels(
                    defaultModel = defaultModel,
                    defaultVisionModel = defaultVisionModel,
                    models = draft.availableModels,
                    modelConfigs = draft.modelConfigs,
                    providerName = draft.name,
                ).encodeModelConfigs(),
                defaultVisionModel = defaultVisionModel,
                supportsVision = draft.supportsVision,
                nativeWebSearchMode = draft.nativeWebSearchMode.name,
                enabled = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    suspend fun updateProvider(providerId: String, draft: ProviderDraft) {
        requireHttpsBaseUrl(draft.baseUrl)
        requireProviderFields(draft)
        val current = dao.findById(providerId) ?: throw AppError.ProviderMissing()
        val now = timeProvider.nowMillis()
        val encrypted = draft.apiKey.trim().takeIf { it.isNotBlank() }?.let { cipher.encrypt(it) }
        val encryptedApiKey = encrypted?.cipherText ?: current.encryptedApiKey ?: throw AppError.ApiKeyMissing()
        val apiKeyIv = encrypted?.initializationVector ?: current.apiKeyIv ?: throw AppError.ApiKeyMissing()
        val defaultModel = draft.defaultModel.trim()
        val defaultVisionModel = draft.defaultVisionModel?.trim()?.takeIf { it.isNotBlank() }

        dao.update(
            current.copy(
                name = draft.name.trim(),
                baseUrl = draft.baseUrl.trim().trimEnd('/'),
                encryptedApiKey = encryptedApiKey,
                apiKeyIv = apiKeyIv,
                defaultModel = defaultModel,
                availableModels = normalizeAvailableModels(
                    defaultModel = defaultModel,
                    defaultVisionModel = defaultVisionModel,
                    models = draft.availableModels,
                    modelConfigs = draft.modelConfigs,
                    providerName = draft.name,
                ).encodeModelConfigs(),
                defaultVisionModel = defaultVisionModel,
                supportsVision = draft.supportsVision,
                nativeWebSearchMode = draft.nativeWebSearchMode.name,
                enabled = true,
                updatedAt = now,
            ),
        )
    }

    suspend fun deleteProvider(providerId: String) {
        val current = dao.findById(providerId) ?: throw AppError.ProviderMissing()
        dao.delete(current)
    }

    suspend fun getApiKey(providerId: String): String {
        val entity = dao.findById(providerId) ?: throw AppError.ProviderMissing()
        val cipherText = entity.encryptedApiKey ?: throw AppError.ApiKeyMissing()
        val iv = entity.apiKeyIv ?: throw AppError.ApiKeyMissing()
        return cipher.decrypt(EncryptedValue(cipherText, iv))
    }

    suspend fun findById(providerId: String): ProviderProfile? = dao.findById(providerId)?.toDomain()

    suspend fun defaultProviderForText(): ProviderWithKey {
        val entity = dao.firstEnabled() ?: throw AppError.ProviderMissing()
        return entity.toProviderWithKey()
    }

    suspend fun providerWithKey(providerId: String): ProviderWithKey {
        val entity = dao.findById(providerId) ?: throw AppError.ProviderMissing()
        return entity.toProviderWithKey()
    }

    private fun ProviderProfileEntity.toProviderWithKey(): ProviderWithKey {
        val cipherText = encryptedApiKey ?: throw AppError.ApiKeyMissing()
        val iv = apiKeyIv ?: throw AppError.ApiKeyMissing()
        return ProviderWithKey(
            profile = toDomain(),
            apiKey = cipher.decrypt(EncryptedValue(cipherText, iv)),
        )
    }

    private fun requireHttpsBaseUrl(baseUrl: String) {
        require(baseUrl.trim().startsWith("https://")) {
            "Provider Base URL 必须使用 HTTPS"
        }
    }

    private fun requireProviderFields(draft: ProviderDraft) {
        require(draft.name.isNotBlank()) { "Provider 名称不能为空" }
        require(draft.defaultModel.isNotBlank()) { "模型不能为空" }
    }
}

data class ProviderWithKey(
    val profile: ProviderProfile,
    val apiKey: String,
)

private fun ProviderProfileEntity.toDomain(): ProviderProfile = ProviderProfile(
    id = id,
    name = name,
    baseUrl = baseUrl,
    defaultModel = defaultModel,
    defaultVisionModel = defaultVisionModel,
    supportsVision = supportsVision,
    nativeWebSearchMode = nativeWebSearchMode.decodeNativeWebSearchMode(),
    enabled = enabled,
    hasApiKey = encryptedApiKey != null && apiKeyIv != null,
    availableModels = normalizeAvailableModels(
        defaultModel = defaultModel,
        defaultVisionModel = defaultVisionModel,
        models = availableModels.decodeModelConfigs().map { it.id },
        modelConfigs = availableModels.decodeModelConfigs(),
        providerName = name,
    ).map { it.id },
    modelConfigs = normalizeAvailableModels(
        defaultModel = defaultModel,
        defaultVisionModel = defaultVisionModel,
        models = availableModels.decodeModelConfigs().map { it.id },
        modelConfigs = availableModels.decodeModelConfigs(),
        providerName = name,
    ),
)

private fun normalizeAvailableModels(
    defaultModel: String,
    defaultVisionModel: String?,
    models: List<String>,
    modelConfigs: List<ModelConfig>,
    providerName: String,
): List<ModelConfig> {
    val explicitConfigs = modelConfigs
        .filter { it.id.isNotBlank() }
        .associateBy { it.id.trim() }
    return (listOf(defaultModel) + models + listOfNotNull(defaultVisionModel))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .map { modelId ->
            val fallback = defaultModelConfig(providerName, modelId)
            explicitConfigs[modelId]?.let { explicit ->
                ModelConfig(
                    id = modelId,
                    contextWindowTokens = explicit.contextWindowTokens.takeIf { it > 0 }
                        ?: fallback.contextWindowTokens,
                    compressionThresholdPercent = explicit.compressionThresholdPercent.takeIf { it > 0 }
                        ?: fallback.compressionThresholdPercent,
                    maxOutputTokens = explicit.maxOutputTokens?.takeIf { it > 0 },
                    inputModalities = explicit.inputModalities.sanitizedModalities(),
                    outputModalities = explicit.outputModalities.sanitizedModalities(),
                    reasoningEffortOptions = explicit.reasoningEffortOptions.sanitizedReasoningOptions(),
                    defaultReasoningEffort = explicit.defaultReasoningEffort?.trim()?.takeIf { it.isNotBlank() },
                    webSearchMode = explicit.webSearchMode,
                    supportsToolCalling = explicit.supportsToolCalling,
                    readTimeoutMillis = explicit.readTimeoutMillis?.takeIf { it > 0 },
                ).normalized()
            } ?: fallback
        }
}

private fun ModelConfig.normalized(): ModelConfig = copy(
    id = id.trim(),
    contextWindowTokens = contextWindowTokens.coerceAtLeast(1),
    compressionThresholdPercent = compressionThresholdPercent.coerceIn(1, 95),
    maxOutputTokens = maxOutputTokens?.coerceAtLeast(1),
    inputModalities = inputModalities.sanitizedModalities(),
    outputModalities = outputModalities.sanitizedModalities(),
    reasoningEffortOptions = reasoningEffortOptions.sanitizedReasoningOptions(),
    defaultReasoningEffort = defaultReasoningEffort?.trim()?.takeIf { it.isNotBlank() },
    readTimeoutMillis = readTimeoutMillis?.coerceAtLeast(1L),
)

private fun List<ModelConfig>.encodeModelConfigs(): String = joinToString("\n") {
    if (!it.hasAdvancedCapabilityOverrides()) {
        "${it.id}|${it.contextWindowTokens}|${it.compressionThresholdPercent}"
    } else {
        listOf(
            it.id,
            it.contextWindowTokens.toString(),
            it.compressionThresholdPercent.toString(),
            it.maxOutputTokens.orEmptyField(),
            it.inputModalities.encodeStringListField(),
            it.outputModalities.encodeStringListField(),
            it.reasoningEffortOptions.encodeStringListField(),
            it.defaultReasoningEffort.orEmpty(),
            it.webSearchMode?.name.orEmpty(),
            it.supportsToolCalling?.toString().orEmpty(),
            it.readTimeoutMillis?.toString().orEmpty(),
        ).joinToString("|")
    }
}

private fun String.decodeModelConfigs(): List<ModelConfig> = lines()
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .map { line ->
        val parts = line.split("|").map { it.trim() }
        ModelConfig(
            id = parts.first(),
            contextWindowTokens = parts.getOrNull(1)?.toIntOrNull() ?: -1,
            compressionThresholdPercent = parts.getOrNull(2)?.toIntOrNull() ?: -1,
            maxOutputTokens = parts.getOrNull(3)?.toIntOrNull(),
            inputModalities = parts.getOrNull(4).decodeStringListField(),
            outputModalities = parts.getOrNull(5).decodeStringListField(),
            reasoningEffortOptions = parts.getOrNull(6).decodeStringListField(),
            defaultReasoningEffort = parts.getOrNull(7)?.takeIf { it.isNotBlank() },
            webSearchMode = parts.getOrNull(8)
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { NativeWebSearchMode.valueOf(it) }.getOrNull() },
            supportsToolCalling = parts.getOrNull(9)?.toBooleanStrictOrNull(),
            readTimeoutMillis = parts.getOrNull(10)?.toLongOrNull(),
        )
    }

private fun ModelConfig.hasAdvancedCapabilityOverrides(): Boolean =
    maxOutputTokens != null ||
        !inputModalities.isNullOrEmpty() ||
        !outputModalities.isNullOrEmpty() ||
        !reasoningEffortOptions.isNullOrEmpty() ||
        !defaultReasoningEffort.isNullOrBlank() ||
        webSearchMode != null ||
        supportsToolCalling != null ||
        readTimeoutMillis != null

private fun Int?.orEmptyField(): String = this?.toString().orEmpty()

private fun List<String>?.encodeStringListField(): String =
    this.orEmpty()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(",")

private fun String?.decodeStringListField(): List<String>? =
    this?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.distinct()
        ?.takeIf { it.isNotEmpty() }

private fun List<String>?.sanitizedModalities(): List<String>? =
    this?.map { it.trim().lowercase() }
        ?.filter { it.isNotBlank() }
        ?.distinct()
        ?.takeIf { it.isNotEmpty() }

private fun List<String>?.sanitizedReasoningOptions(): List<String>? =
    this?.map { it.trim().lowercase() }
        ?.filter { it.isNotBlank() }
        ?.distinct()
        ?.takeIf { it.isNotEmpty() }

private fun String.decodeNativeWebSearchMode(): NativeWebSearchMode =
    runCatching { NativeWebSearchMode.valueOf(this) }.getOrDefault(NativeWebSearchMode.DISABLED)

fun defaultModelConfig(providerName: String, modelId: String): ModelConfig {
    val normalizedProvider = providerName.lowercase()
    val normalizedModel = modelId.trim().lowercase()
    val catalogConfig = ProviderTemplates.defaults
        .firstOrNull { it.name.lowercase() == normalizedProvider }
        ?.modelConfigs
        ?.firstOrNull { it.id.equals(modelId.trim(), ignoreCase = true) }
    if (catalogConfig != null) return catalogConfig.copy(id = modelId.trim()).normalized()

    val contextWindow = when {
        normalizedModel.startsWith("deepseek-v4") -> 1_000_000
        normalizedModel.startsWith("glm-5-turbo") -> 200_000
        normalizedModel.startsWith("glm-5.2") -> 1_000_000
        normalizedModel.startsWith("glm-") -> 128_000
        normalizedModel.startsWith("kimi-") -> 256_000
        normalizedModel.startsWith("gpt-5.5") -> 200_000
        normalizedModel.startsWith("gpt-5") -> 400_000
        else -> DEFAULT_CONTEXT_WINDOW_TOKENS
    }
    return ModelConfig(
        id = modelId.trim(),
        contextWindowTokens = contextWindow,
        compressionThresholdPercent = DEFAULT_COMPRESSION_THRESHOLD_PERCENT,
    )
}
