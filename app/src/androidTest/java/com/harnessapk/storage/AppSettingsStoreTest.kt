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
    fun persistsCloudSpeechProviderAndModel() = runBlocking {
        val store = AppSettingsStore(ApplicationProvider.getApplicationContext<Context>())

        store.setDefaultSpeechProvider(VoiceProviderType.CLOUD)
        store.setCloudSpeechConfiguration(providerId = "provider-voice", model = "whisper-1")

        val settings = store.voiceSettings.first()
        assertEquals(VoiceProviderType.CLOUD, settings.defaultSpeechProvider)
        assertEquals("provider-voice", settings.cloudSpeechProviderId)
        assertEquals("whisper-1", settings.cloudSpeechModel)

        store.setDefaultSpeechProvider(VoiceProviderType.ANDROID_SYSTEM)
        store.setCloudSpeechConfiguration(providerId = null, model = "whisper-1")
    }
}
