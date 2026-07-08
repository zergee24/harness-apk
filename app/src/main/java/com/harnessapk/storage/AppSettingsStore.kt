package com.harnessapk.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.harnessapk.skills.BundledSkills
import com.harnessapk.skills.SkillActivationSettings
import com.harnessapk.voice.VoiceSettings
import com.harnessapk.websearch.WebSearchSettings
import com.harnessapk.websearch.normalizeWebSearchMaxResults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore by preferencesDataStore("app_settings")

data class DefaultModelPreference(
    val providerId: String? = null,
    val model: String? = null,
)

class AppSettingsStore(private val context: Context) {
    val hasSeenImagePrivacyNotice: Flow<Boolean> = context.appSettingsDataStore.data.map {
        it[HAS_SEEN_IMAGE_PRIVACY_NOTICE] ?: false
    }

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
            defaultTranscriptionLanguage = it[VOICE_TRANSCRIPTION_LANGUAGE] ?: "zh-CN",
            autoPunctuation = it[VOICE_AUTO_PUNCTUATION] ?: true,
            autoFillInput = it[VOICE_AUTO_FILL_INPUT] ?: true,
            autoSendAfterTranscription = it[VOICE_AUTO_SEND_AFTER_TRANSCRIPTION] ?: false,
            saveOriginalAudio = it[VOICE_SAVE_ORIGINAL_AUDIO] ?: false,
            ttsEnabled = it[VOICE_TTS_ENABLED] ?: false,
            ttsSpeechRate = (it[VOICE_TTS_SPEECH_RATE] ?: 1.0f).coerceIn(0.6f, 1.4f),
        )
    }

    suspend fun setHasSeenImagePrivacyNotice(value: Boolean) {
        context.appSettingsDataStore.edit {
            it[HAS_SEEN_IMAGE_PRIVACY_NOTICE] = value
        }
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

    suspend fun setDefaultTranscriptionLanguage(value: String) {
        context.appSettingsDataStore.edit { it[VOICE_TRANSCRIPTION_LANGUAGE] = value.trim().ifBlank { "zh-CN" } }
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

    suspend fun setTtsSpeechRate(value: Float) {
        context.appSettingsDataStore.edit { it[VOICE_TTS_SPEECH_RATE] = value.coerceIn(0.6f, 1.4f) }
    }

    companion object {
        private val HAS_SEEN_IMAGE_PRIVACY_NOTICE = booleanPreferencesKey("has_seen_image_privacy_notice")
        private val DEFAULT_PROVIDER_ID = stringPreferencesKey("default_provider_id")
        private val DEFAULT_MODEL = stringPreferencesKey("default_model")
        private val WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
        private val WEB_SEARCH_MAX_RESULTS = intPreferencesKey("web_search_max_results")
        private val ENABLED_SKILL_IDS = stringSetPreferencesKey("enabled_skill_ids")
        private val VOICE_SPEECH_INPUT_ENABLED = booleanPreferencesKey("voice_speech_input_enabled")
        private val VOICE_TRANSCRIPTION_LANGUAGE = stringPreferencesKey("voice_transcription_language")
        private val VOICE_AUTO_PUNCTUATION = booleanPreferencesKey("voice_auto_punctuation")
        private val VOICE_AUTO_FILL_INPUT = booleanPreferencesKey("voice_auto_fill_input")
        private val VOICE_AUTO_SEND_AFTER_TRANSCRIPTION = booleanPreferencesKey("voice_auto_send_after_transcription")
        private val VOICE_SAVE_ORIGINAL_AUDIO = booleanPreferencesKey("voice_save_original_audio")
        private val VOICE_TTS_ENABLED = booleanPreferencesKey("voice_tts_enabled")
        private val VOICE_TTS_SPEECH_RATE = floatPreferencesKey("voice_tts_speech_rate")
    }
}
