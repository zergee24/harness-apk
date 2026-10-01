package com.harnessapk.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.harnessapk.skills.BundledSkills
import com.harnessapk.skills.SkillActivationSettings
import com.harnessapk.voice.VoiceSettings
import com.harnessapk.voice.VoiceProviderType
import com.harnessapk.voice.DEFAULT_SILICON_FLOW_SPEECH_MODEL
import com.harnessapk.voice.DEFAULT_ALIYUN_SPEECH_MODEL
import com.harnessapk.voice.decodeVoiceProviderType
import com.harnessapk.websearch.WebSearchSettings
import com.harnessapk.websearch.normalizeWebSearchMaxResults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore by preferencesDataStore("app_settings")

/**
 * simpleMode 的 SharedPreferences 镜像位置。
 *
 * DataStore 是冷流，冷启动第一帧读不到值；而 mainMode 走的是 SharedPreferences 同步读。
 * 两者不同源会让首帧按"非简洁模式"组合渲染：底部先出现工作 Tab，若上次停在过工作页
 * 还会先画一整屏工作页再被踢回生活页；"我的"里的开关也会短暂显示为关。
 */
private const val SIMPLE_MODE_MIRROR_PREFERENCES = "app_settings_mirror"
private const val SIMPLE_MODE_MIRROR_KEY = "simple_mode"

data class DefaultModelPreference(
    val providerId: String? = null,
    val model: String? = null,
)

data class ProviderCapabilityCatalogSnapshot(
    val rawJson: String? = null,
    val catalogVersion: String? = null,
    val sha256: String? = null,
    val fetchedAt: Long = 0L,
    val errorMessage: String? = null,
)

class AppSettingsStore(private val context: Context) {
    val hasSeenImagePrivacyNotice: Flow<Boolean> = context.appSettingsDataStore.data.map {
        it[HAS_SEEN_IMAGE_PRIVACY_NOTICE] ?: false
    }

    private val simpleModeMirror = context.applicationContext
        .getSharedPreferences(SIMPLE_MODE_MIRROR_PREFERENCES, Context.MODE_PRIVATE)
    private val _simpleMode = MutableStateFlow(
        simpleModeMirror.getBoolean(SIMPLE_MODE_MIRROR_KEY, false),
    )

    /**
     * 首帧就有值的 simpleMode，和 mainMode 一样是同步可读的。
     *
     * 消费方一律用它配合 `collectAsState()`；**不要**再用 `collectAsState(initial = ...)`，
     * 那样第一帧一定是 initial 里猜的那个值，正是首帧模式错乱的来源。
     *
     * 唯一的写入点是 [setSimpleMode]，所以镜像不会漂移；升级安装（镜像尚不存在）时由
     * [reconcileSimpleModeMirror] 用 DataStore 真值校正一次。
     */
    val simpleModeState: StateFlow<Boolean> = _simpleMode.asStateFlow()

    val defaultModelPreference: Flow<DefaultModelPreference> = context.appSettingsDataStore.data.map {
        DefaultModelPreference(
            providerId = it[DEFAULT_PROVIDER_ID]?.takeIf(String::isNotBlank),
            model = it[DEFAULT_MODEL]?.takeIf(String::isNotBlank),
        )
    }

    val webSearchSettings: Flow<WebSearchSettings> = context.appSettingsDataStore.data.map {
        WebSearchSettings(
            enabled = it[WEB_SEARCH_ENABLED] ?: false,
            maxResults = normalizeWebSearchMaxResults(it[WEB_SEARCH_MAX_RESULTS] ?: 5),
        )
    }

    val skillActivationSettings: Flow<SkillActivationSettings> = context.appSettingsDataStore.data.map {
        SkillActivationSettings(
            enabledSkillIds = it[ENABLED_SKILL_IDS].orEmpty(),
        ).sanitizedFor(BundledSkills.defaults)
    }

    val voiceSettings: Flow<VoiceSettings> = context.appSettingsDataStore.data.map {
        VoiceSettings(
            speechInputEnabled = it[VOICE_SPEECH_INPUT_ENABLED] ?: false,
            defaultSpeechProvider = decodeVoiceProviderType(it[VOICE_SPEECH_PROVIDER]),
            siliconFlowSpeechModel = it[VOICE_SILICON_FLOW_SPEECH_MODEL]
                ?.takeIf(String::isNotBlank)
                ?: DEFAULT_SILICON_FLOW_SPEECH_MODEL,
            aliyunSpeechModel = it[VOICE_ALIYUN_SPEECH_MODEL]
                ?.takeIf(String::isNotBlank)
                ?: DEFAULT_ALIYUN_SPEECH_MODEL,
            defaultTranscriptionLanguage = it[VOICE_TRANSCRIPTION_LANGUAGE] ?: "system",
            autoPunctuation = it[VOICE_AUTO_PUNCTUATION] ?: true,
            autoFillInput = it[VOICE_AUTO_FILL_INPUT] ?: true,
            autoSendAfterTranscription = it[VOICE_AUTO_SEND_AFTER_TRANSCRIPTION] ?: false,
            saveOriginalAudio = it[VOICE_SAVE_ORIGINAL_AUDIO] ?: false,
            ttsEnabled = it[VOICE_TTS_ENABLED] ?: false,
            ttsAutoRead = it[VOICE_TTS_AUTO_READ] ?: false,
            ttsSpeechRate = (it[VOICE_TTS_SPEECH_RATE] ?: 1.0f).coerceIn(0.6f, 1.4f),
        )
    }

    val providerCapabilityCatalogSnapshot: Flow<ProviderCapabilityCatalogSnapshot> =
        context.appSettingsDataStore.data.map {
            ProviderCapabilityCatalogSnapshot(
                rawJson = it[PROVIDER_CATALOG_RAW_JSON]?.takeIf(String::isNotBlank),
                catalogVersion = it[PROVIDER_CATALOG_VERSION]?.takeIf(String::isNotBlank),
                sha256 = it[PROVIDER_CATALOG_SHA256]?.takeIf(String::isNotBlank),
                fetchedAt = it[PROVIDER_CATALOG_FETCHED_AT] ?: 0L,
                errorMessage = it[PROVIDER_CATALOG_ERROR]?.takeIf(String::isNotBlank),
            )
        }

    suspend fun setHasSeenImagePrivacyNotice(value: Boolean) {
        context.appSettingsDataStore.edit {
            it[HAS_SEEN_IMAGE_PRIVACY_NOTICE] = value
        }
    }

    suspend fun setSimpleMode(value: Boolean) {
        // 先写镜像再写 DataStore：镜像要在下一次冷启动的第一帧就能读到。
        simpleModeMirror.edit().putBoolean(SIMPLE_MODE_MIRROR_KEY, value).apply()
        _simpleMode.value = value
        context.appSettingsDataStore.edit { it[SIMPLE_MODE] = value }
    }

    /**
     * 用 DataStore 的真值校正镜像。
     *
     * 正常路径不需要它（[setSimpleMode] 两边都写）；它只处理"镜像还不存在"的升级安装，
     * 以及 DataStore 被外部恢复/迁移导致与镜像不一致的情况。冷启动时调用一次即可。
     */
    suspend fun reconcileSimpleModeMirror() {
        val stored = context.appSettingsDataStore.data.first()[SIMPLE_MODE] ?: false
        if (stored == _simpleMode.value) return
        simpleModeMirror.edit().putBoolean(SIMPLE_MODE_MIRROR_KEY, stored).apply()
        _simpleMode.value = stored
    }

    suspend fun setDefaultModelPreference(providerId: String, model: String) {
        context.appSettingsDataStore.edit {
            it[DEFAULT_PROVIDER_ID] = providerId.trim()
            it[DEFAULT_MODEL] = model.trim()
        }
    }

    suspend fun clearDefaultModelPreference() {
        context.appSettingsDataStore.edit {
            it.remove(DEFAULT_PROVIDER_ID)
            it.remove(DEFAULT_MODEL)
        }
    }

    suspend fun setWebSearchEnabled(value: Boolean) {
        context.appSettingsDataStore.edit {
            it[WEB_SEARCH_ENABLED] = value
        }
    }

    suspend fun setWebSearchMaxResults(value: Int) {
        context.appSettingsDataStore.edit {
            it[WEB_SEARCH_MAX_RESULTS] = normalizeWebSearchMaxResults(value)
        }
    }

    suspend fun setSkillEnabled(skillId: String, enabled: Boolean) {
        context.appSettingsDataStore.edit {
            val nextSettings = SkillActivationSettings(
                enabledSkillIds = it[ENABLED_SKILL_IDS].orEmpty(),
            ).withSkillEnabled(
                skillId = skillId,
                enabled = enabled,
                availableSkills = BundledSkills.defaults,
            )
            if (nextSettings.enabledSkillIds.isEmpty()) {
                it.remove(ENABLED_SKILL_IDS)
            } else {
                it[ENABLED_SKILL_IDS] = nextSettings.enabledSkillIds
            }
        }
    }

    suspend fun setSpeechInputEnabled(value: Boolean) {
        context.appSettingsDataStore.edit { it[VOICE_SPEECH_INPUT_ENABLED] = value }
    }

    suspend fun setDefaultSpeechProvider(value: VoiceProviderType) {
        context.appSettingsDataStore.edit { it[VOICE_SPEECH_PROVIDER] = value.name }
    }

    suspend fun setSiliconFlowSpeechModel(model: String) {
        context.appSettingsDataStore.edit {
            val supportedModel = model.trim().takeIf { candidate ->
                candidate == DEFAULT_SILICON_FLOW_SPEECH_MODEL || candidate == "TeleAI/TeleSpeechASR"
            } ?: DEFAULT_SILICON_FLOW_SPEECH_MODEL
            it[VOICE_SILICON_FLOW_SPEECH_MODEL] = supportedModel
        }
    }

    suspend fun setAliyunSpeechModel(model: String) {
        context.appSettingsDataStore.edit {
            it[VOICE_ALIYUN_SPEECH_MODEL] = model.trim()
                .takeIf { candidate -> candidate == DEFAULT_ALIYUN_SPEECH_MODEL }
                ?: DEFAULT_ALIYUN_SPEECH_MODEL
        }
    }

    suspend fun setDefaultTranscriptionLanguage(value: String) {
        context.appSettingsDataStore.edit { it[VOICE_TRANSCRIPTION_LANGUAGE] = value.trim().ifBlank { "system" } }
    }

    suspend fun setAutoPunctuation(value: Boolean) {
        context.appSettingsDataStore.edit { it[VOICE_AUTO_PUNCTUATION] = value }
    }

    suspend fun setAutoFillTranscription(value: Boolean) {
        context.appSettingsDataStore.edit { it[VOICE_AUTO_FILL_INPUT] = value }
    }

    suspend fun setAutoSendAfterTranscription(value: Boolean) {
        context.appSettingsDataStore.edit { it[VOICE_AUTO_SEND_AFTER_TRANSCRIPTION] = value }
    }

    suspend fun setSaveOriginalAudio(value: Boolean) {
        context.appSettingsDataStore.edit { it[VOICE_SAVE_ORIGINAL_AUDIO] = value }
    }

    suspend fun setTtsEnabled(value: Boolean) {
        context.appSettingsDataStore.edit { it[VOICE_TTS_ENABLED] = value }
    }

    suspend fun setTtsAutoRead(value: Boolean) {
        context.appSettingsDataStore.edit { it[VOICE_TTS_AUTO_READ] = value }
    }

    suspend fun setTtsSpeechRate(value: Float) {
        context.appSettingsDataStore.edit { it[VOICE_TTS_SPEECH_RATE] = value.coerceIn(0.6f, 1.4f) }
    }

    suspend fun setProviderCapabilityCatalog(
        rawJson: String,
        catalogVersion: String,
        sha256: String,
        fetchedAt: Long,
    ) {
        context.appSettingsDataStore.edit {
            it[PROVIDER_CATALOG_RAW_JSON] = rawJson
            it[PROVIDER_CATALOG_VERSION] = catalogVersion
            it[PROVIDER_CATALOG_SHA256] = sha256
            it[PROVIDER_CATALOG_FETCHED_AT] = fetchedAt
            it.remove(PROVIDER_CATALOG_ERROR)
        }
    }

    suspend fun setProviderCapabilityCatalogError(errorMessage: String) {
        context.appSettingsDataStore.edit {
            it[PROVIDER_CATALOG_ERROR] = errorMessage.trim().take(300)
        }
    }

    companion object {
        private val HAS_SEEN_IMAGE_PRIVACY_NOTICE = booleanPreferencesKey("has_seen_image_privacy_notice")
        private val SIMPLE_MODE = booleanPreferencesKey("simple_mode")
        private val DEFAULT_PROVIDER_ID = stringPreferencesKey("default_provider_id")
        private val DEFAULT_MODEL = stringPreferencesKey("default_model")
        private val WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
        private val WEB_SEARCH_MAX_RESULTS = intPreferencesKey("web_search_max_results")
        private val ENABLED_SKILL_IDS = stringSetPreferencesKey("enabled_skill_ids")
        private val VOICE_SPEECH_INPUT_ENABLED = booleanPreferencesKey("voice_speech_input_enabled")
        private val VOICE_SPEECH_PROVIDER = stringPreferencesKey("voice_speech_provider")
        private val VOICE_SILICON_FLOW_SPEECH_MODEL = stringPreferencesKey("voice_silicon_flow_speech_model")
        private val VOICE_ALIYUN_SPEECH_MODEL = stringPreferencesKey("voice_aliyun_speech_model")
        private val VOICE_TRANSCRIPTION_LANGUAGE = stringPreferencesKey("voice_transcription_language")
        private val VOICE_AUTO_PUNCTUATION = booleanPreferencesKey("voice_auto_punctuation")
        private val VOICE_AUTO_FILL_INPUT = booleanPreferencesKey("voice_auto_fill_input")
        private val VOICE_AUTO_SEND_AFTER_TRANSCRIPTION = booleanPreferencesKey("voice_auto_send_after_transcription")
        private val VOICE_SAVE_ORIGINAL_AUDIO = booleanPreferencesKey("voice_save_original_audio")
        private val VOICE_TTS_ENABLED = booleanPreferencesKey("voice_tts_enabled")
        private val VOICE_TTS_AUTO_READ = booleanPreferencesKey("voice_tts_auto_read")
        private val VOICE_TTS_SPEECH_RATE = floatPreferencesKey("voice_tts_speech_rate")
        private val PROVIDER_CATALOG_RAW_JSON = stringPreferencesKey("provider_catalog_raw_json")
        private val PROVIDER_CATALOG_VERSION = stringPreferencesKey("provider_catalog_version")
        private val PROVIDER_CATALOG_SHA256 = stringPreferencesKey("provider_catalog_sha256")
        private val PROVIDER_CATALOG_FETCHED_AT = longPreferencesKey("provider_catalog_fetched_at")
        private val PROVIDER_CATALOG_ERROR = stringPreferencesKey("provider_catalog_error")
    }
}
