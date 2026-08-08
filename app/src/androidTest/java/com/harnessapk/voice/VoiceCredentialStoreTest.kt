package com.harnessapk.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.security.ApiKeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceCredentialStoreTest {
    @Test
    fun apiKeyIsPersistedAsCipherTextAndCanBeCleared() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferencesName = "voice_credentials_test"
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        val store = VoiceCredentialStore(
            context = context,
            cipher = ApiKeyCipher("harness_apk_voice_keys_test"),
            preferencesName = preferencesName,
        )

        store.saveSiliconFlowApiKey("sk-private")

        assertTrue(store.state.value.hasSiliconFlowApiKey)
        assertEquals("sk-private", store.siliconFlowApiKey())
        val raw = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw.contains("sk-private"))
        val reloaded = VoiceCredentialStore(
            context = context,
            cipher = ApiKeyCipher("harness_apk_voice_keys_test"),
            preferencesName = preferencesName,
        )
        assertTrue(reloaded.state.value.hasSiliconFlowApiKey)
        assertEquals("sk-private", reloaded.siliconFlowApiKey())

        store.clearSiliconFlowApiKey()
        assertFalse(store.state.value.hasSiliconFlowApiKey)
        assertEquals(null, store.siliconFlowApiKey())
    }
}
