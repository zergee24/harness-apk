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
    fun providerApiKeysAreEncryptedAndCanBeClearedIndependently() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferencesName = "voice_credentials_test"
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        val store = VoiceCredentialStore(
            context = context,
            cipher = ApiKeyCipher("harness_apk_voice_keys_test"),
            preferencesName = preferencesName,
        )

        store.saveSiliconFlowApiKey("sk-private")
        store.saveAliyunApiKey("sk-aliyun-private")

        assertTrue(store.state.value.hasSiliconFlowApiKey)
        assertTrue(store.state.value.hasAliyunApiKey)
        assertEquals("sk-private", store.siliconFlowApiKey())
        assertEquals("sk-aliyun-private", store.aliyunApiKey())
        val raw = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw.contains("sk-private"))
        assertFalse(raw.contains("sk-aliyun-private"))
        val reloaded = VoiceCredentialStore(
            context = context,
            cipher = ApiKeyCipher("harness_apk_voice_keys_test"),
            preferencesName = preferencesName,
        )
        assertTrue(reloaded.state.value.hasSiliconFlowApiKey)
        assertTrue(reloaded.state.value.hasAliyunApiKey)
        assertEquals("sk-private", reloaded.siliconFlowApiKey())
        assertEquals("sk-aliyun-private", reloaded.aliyunApiKey())

        store.clearSiliconFlowApiKey()
        assertFalse(store.state.value.hasSiliconFlowApiKey)
        assertTrue(store.state.value.hasAliyunApiKey)
        assertEquals(null, store.siliconFlowApiKey())
        assertEquals("sk-aliyun-private", store.aliyunApiKey())

        store.clearAliyunApiKey()
        assertFalse(store.state.value.hasAliyunApiKey)
        assertEquals(null, store.aliyunApiKey())
    }
}
