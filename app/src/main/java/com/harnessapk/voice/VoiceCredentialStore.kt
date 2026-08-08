package com.harnessapk.voice

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.harnessapk.security.EncryptedValue
import com.harnessapk.security.StringCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceCredentialState(
    val hasSiliconFlowApiKey: Boolean = false,
)

class VoiceCredentialStore internal constructor(
    context: Context,
    private val cipher: StringCipher,
    preferencesName: String,
) {
    constructor(context: Context, cipher: StringCipher) : this(
        context = context,
        cipher = cipher,
        preferencesName = "voice_credentials",
    )

    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(loadState())
    val state: StateFlow<VoiceCredentialState> = mutableState.asStateFlow()

    fun saveSiliconFlowApiKey(apiKey: String) {
        val normalized = apiKey.trim()
        require(normalized.isNotBlank()) { "硅基流动 API Key 不能为空" }
        val encrypted = cipher.encrypt(normalized)
        preferences.edit {
            putString(KEY_CIPHER_TEXT, encrypted.cipherText.encodeBase64())
            putString(KEY_INITIALIZATION_VECTOR, encrypted.initializationVector.encodeBase64())
        }
        mutableState.value = VoiceCredentialState(hasSiliconFlowApiKey = true)
    }

    fun clearSiliconFlowApiKey() {
        preferences.edit {
            remove(KEY_CIPHER_TEXT)
            remove(KEY_INITIALIZATION_VECTOR)
        }
        mutableState.value = VoiceCredentialState()
    }

    fun siliconFlowApiKey(): String? {
        val cipherText = preferences.getString(KEY_CIPHER_TEXT, null)?.decodeBase64() ?: return null
        val initializationVector = preferences.getString(KEY_INITIALIZATION_VECTOR, null)?.decodeBase64() ?: return null
        return runCatching {
            cipher.decrypt(
                EncryptedValue(
                    cipherText = cipherText,
                    initializationVector = initializationVector,
                ),
            ).takeIf(String::isNotBlank)
        }.getOrNull()
    }

    private fun loadState(): VoiceCredentialState = VoiceCredentialState(
        hasSiliconFlowApiKey = preferences.contains(KEY_CIPHER_TEXT) &&
            preferences.contains(KEY_INITIALIZATION_VECTOR) &&
            siliconFlowApiKey() != null,
    )

    private fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val KEY_CIPHER_TEXT = "silicon_flow_api_key_cipher_text"
        const val KEY_INITIALIZATION_VECTOR = "silicon_flow_api_key_iv"
    }
}
