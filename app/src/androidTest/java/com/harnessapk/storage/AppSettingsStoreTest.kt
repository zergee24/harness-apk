package com.harnessapk.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.voice.VoiceProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSettingsStoreTest {
    @Test
    fun persistsDefaultModelPreference() = runBlocking {
        val store = AppSettingsStore(ApplicationProvider.getApplicationContext<Context>())

        store.clearDefaultModelPreference()
        store.setDefaultModelPreference(providerId = "openai", model = "gpt-5.5")

        assertEquals(
            DefaultModelPreference(providerId = "openai", model = "gpt-5.5"),
            store.defaultModelPreference.first(),
        )

        store.clearDefaultModelPreference()
    }

    @Test
    fun persistsSiliconFlowSpeechProviderAndModel() = runBlocking {
        val store = AppSettingsStore(ApplicationProvider.getApplicationContext<Context>())

        store.setDefaultSpeechProvider(VoiceProviderType.SILICON_FLOW)
        store.setSiliconFlowSpeechModel("TeleAI/TeleSpeechASR")

        val settings = store.voiceSettings.first()
        assertEquals(VoiceProviderType.SILICON_FLOW, settings.defaultSpeechProvider)
        assertEquals("TeleAI/TeleSpeechASR", settings.siliconFlowSpeechModel)

        store.setDefaultSpeechProvider(VoiceProviderType.ANDROID_SYSTEM)
        store.setSiliconFlowSpeechModel("FunAudioLLM/SenseVoiceSmall")
    }

    @Test
    fun persistsAliyunRealtimeSpeechProviderAndModel() = runBlocking {
        val store = AppSettingsStore(ApplicationProvider.getApplicationContext<Context>())

        store.setDefaultSpeechProvider(VoiceProviderType.ALIYUN)
        store.setAliyunSpeechModel("paraformer-realtime-v2")

        val settings = store.voiceSettings.first()
        assertEquals(VoiceProviderType.ALIYUN, settings.defaultSpeechProvider)
        assertEquals("paraformer-realtime-v2", settings.aliyunSpeechModel)

        store.setDefaultSpeechProvider(VoiceProviderType.ANDROID_SYSTEM)
    }
}
