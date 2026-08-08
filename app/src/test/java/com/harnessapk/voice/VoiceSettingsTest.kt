package com.harnessapk.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSettingsTest {
    @Test
    fun defaultVoiceSettingsUseAndroidSystemProvidersWithoutAutoSendOrAudioRetention() {
        val settings = VoiceSettings()

        assertFalse(settings.speechInputEnabled)
        assertEquals(VoiceProviderType.ANDROID_SYSTEM, settings.defaultSpeechProvider)
        assertEquals("system", settings.defaultTranscriptionLanguage)
        assertTrue(settings.autoPunctuation)
        assertTrue(settings.autoFillInput)
        assertFalse(settings.autoSendAfterTranscription)
        assertFalse(settings.saveOriginalAudio)
        assertFalse(settings.ttsEnabled)
        assertEquals(VoiceProviderType.ANDROID_SYSTEM, settings.defaultTtsProvider)
        assertEquals(1.0f, settings.ttsSpeechRate)
    }

    @Test
    fun siliconFlowSpeechDefaultsToSenseVoiceSmall() {
        val settings = VoiceSettings()

        assertEquals("FunAudioLLM/SenseVoiceSmall", settings.siliconFlowSpeechModel)
        assertEquals(
            listOf(
                SiliconFlowSpeechModel(
                    id = "FunAudioLLM/SenseVoiceSmall",
                    label = "SenseVoice Small",
                    recommended = true,
                ),
                SiliconFlowSpeechModel(
                    id = "TeleAI/TeleSpeechASR",
                    label = "TeleSpeech ASR",
                    recommended = false,
                ),
            ),
            siliconFlowSpeechModels(),
        )
    }

    @Test
    fun systemVoiceDoesNotRequireApiConfiguration() {
        assertFalse(VoiceSettings().requiresApiConfiguration())
        assertTrue(
            VoiceSettings(defaultSpeechProvider = VoiceProviderType.SILICON_FLOW)
                .requiresApiConfiguration(),
        )
        assertTrue(
            VoiceSettings(defaultTtsProvider = VoiceProviderType.SILICON_FLOW)
                .requiresApiConfiguration(),
        )
    }

    @Test
    fun siliconFlowSpeechRequiresSavedKeyAndExplicitModel() {
        val settings = VoiceSettings(
            defaultSpeechProvider = VoiceProviderType.SILICON_FLOW,
            siliconFlowSpeechModel = "TeleAI/TeleSpeechASR",
        )

        assertTrue(settings.siliconFlowSpeechReady(hasApiKey = true))
        assertFalse(settings.siliconFlowSpeechReady(hasApiKey = false))
        assertEquals("TeleAI/TeleSpeechASR", settings.siliconFlowSpeechModel)
    }

    @Test
    fun aliyunSpeechDefaultsToRealtimeParaformerAndRequiresSavedKey() {
        val settings = VoiceSettings(defaultSpeechProvider = VoiceProviderType.ALIYUN)

        assertEquals("paraformer-realtime-v2", settings.aliyunSpeechModel)
        assertTrue(settings.requiresApiConfiguration())
        assertTrue(settings.aliyunSpeechReady(hasApiKey = true))
        assertFalse(settings.aliyunSpeechReady(hasApiKey = false))
    }

    @Test
    fun legacyCloudProviderMigratesToSiliconFlow() {
        assertEquals(VoiceProviderType.SILICON_FLOW, decodeVoiceProviderType("CLOUD"))
        assertEquals(VoiceProviderType.SILICON_FLOW, decodeVoiceProviderType("SILICON_FLOW"))
        assertEquals(VoiceProviderType.ALIYUN, decodeVoiceProviderType("ALIYUN"))
        assertEquals(VoiceProviderType.ANDROID_SYSTEM, decodeVoiceProviderType("unknown"))
    }

    @Test
    fun transcriptMergePreservesExistingInputAndIgnoresBlankTranscript() {
        assertEquals("帮我总结这段话", mergeTranscriptIntoInput("", "帮我总结这段话"))
        assertEquals("已有草稿\n补充语音", mergeTranscriptIntoInput("已有草稿", "补充语音"))
        assertEquals("已有草稿", mergeTranscriptIntoInput("已有草稿", "   "))
    }

    @Test
    fun transcriptionLanguageOptionsStayLimitedToSystemChineseAndEnglish() {
        assertEquals(
            listOf(
                TranscriptionLanguageOption("system", "跟随系统"),
                TranscriptionLanguageOption("zh-CN", "中文"),
                TranscriptionLanguageOption("en-US", "English"),
            ),
            transcriptionLanguageOptions(),
        )
    }
}
