package com.harnessapk.configpackage

import com.harnessapk.packageformat.ConfigPackagePayload
import com.harnessapk.packageformat.ConfigPackageProvider
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.provider.ProviderDraft
import com.harnessapk.provider.ProviderRepository
import com.harnessapk.storage.AppSettingsStore
import com.harnessapk.voice.VoiceCredentialStore
import com.harnessapk.voice.VoiceProviderType

/**
 * `.hconfig` 导入落地：provider 按 name + baseUrl upsert、第一个成为默认，
 * 语音凭证写入本地保护存储；容器等无系统识别服务的环境自动切阿里云语音。
 */
class ConfigPackageApplier(
    private val providerRepository: ProviderRepository,
    private val voiceCredentialStore: VoiceCredentialStore,
    private val settingsStore: AppSettingsStore,
    private val systemRecognitionAvailable: () -> Boolean,
) {
    data class AppliedSummary(
        val providerNames: List<String> = emptyList(),
        val aliyunVoiceApplied: Boolean = false,
        val siliconFlowVoiceApplied: Boolean = false,
        val speechProviderForcedToAliyun: Boolean = false,
        val webSearchApplied: Boolean = false,
        val simpleModeEnabled: Boolean = false,
    )

    suspend fun apply(payload: ConfigPackagePayload): AppliedSummary {
        var firstProviderId: String? = null
        var firstProviderModel: String? = null
        val names = mutableListOf<String>()
        payload.providers.forEach { config ->
            val result = providerRepository.upsertProvider(config.toDraft())
            if (firstProviderId == null) {
                firstProviderId = result.providerId
                firstProviderModel = config.defaultModel.trim()
            }
            names += config.name.trim()
        }
        if (firstProviderId != null) {
            settingsStore.setDefaultModelPreference(firstProviderId!!, firstProviderModel.orEmpty())
        }

        var aliyunApplied = false
        var siliconFlowApplied = false
        val aliyunKey = payload.aliyunVoiceApiKey?.takeIf(String::isNotBlank)
        if (aliyunKey != null) {
            voiceCredentialStore.saveAliyunApiKey(aliyunKey)
            aliyunApplied = true
        }
        val siliconFlowKey = payload.siliconFlowVoiceApiKey?.takeIf(String::isNotBlank)
        if (siliconFlowKey != null) {
            voiceCredentialStore.saveSiliconFlowApiKey(siliconFlowKey)
            siliconFlowApplied = true
        }

        var speechProviderForcedToAliyun = false
        if (aliyunApplied && !systemRecognitionAvailable()) {
            settingsStore.setDefaultSpeechProvider(VoiceProviderType.ALIYUN)
            settingsStore.setSpeechInputEnabled(true)
            speechProviderForcedToAliyun = true
        }

        settingsStore.setWebSearchEnabled(payload.webSearchEnabled)
        settingsStore.setSimpleMode(payload.simpleMode)

        return AppliedSummary(
            providerNames = names,
            aliyunVoiceApplied = aliyunApplied,
            siliconFlowVoiceApplied = siliconFlowApplied,
            speechProviderForcedToAliyun = speechProviderForcedToAliyun,
            webSearchApplied = payload.webSearchEnabled,
            simpleModeEnabled = payload.simpleMode,
        )
    }

    private fun ConfigPackageProvider.toDraft(): ProviderDraft = ProviderDraft(
        name = name.trim(),
        baseUrl = baseUrl.trim(),
        apiKey = apiKey,
        defaultModel = defaultModel.trim(),
        defaultVisionModel = defaultVisionModel?.trim()?.takeIf(String::isNotBlank),
        supportsVision = supportsVision,
        nativeWebSearchMode = nativeWebSearchMode?.let { mode ->
            runCatching { NativeWebSearchMode.valueOf(mode) }.getOrNull()
        } ?: NativeWebSearchMode.DISABLED,
        availableModels = availableModels,
        customHeaders = customHeaders,
        customBodyJson = customBodyJson,
    )
}
