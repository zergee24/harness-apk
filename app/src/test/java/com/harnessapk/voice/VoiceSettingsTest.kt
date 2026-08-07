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
    fun defaultSystemVoiceSettingsDoNotRequireCloudConfiguration() {
        assertFalse(VoiceSettings().requiresCloudConfiguration())
        assertTrue(
            VoiceSettings(defaultSpeechProvider = VoiceProviderType.CLOUD)
                .requiresCloudConfiguration(),
        )
        assertTrue(
            VoiceSettings(defaultTtsProvider = VoiceProviderType.CLOUD)
                .requiresCloudConfiguration(),
        )
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
