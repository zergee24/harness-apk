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
    val hasAliyunApiKey: Boolean = false,
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
        saveApiKey(apiKey, SILICON_FLOW_KEY_PREFIX, "硅基流动 API Key 不能为空")
    }

    fun clearSiliconFlowApiKey() {
        preferences.edit {
            remove(cipherTextKey(SILICON_FLOW_KEY_PREFIX))
            remove(initializationVectorKey(SILICON_FLOW_KEY_PREFIX))
        }
        mutableState.value = loadState()
    }

    fun siliconFlowApiKey(): String? = apiKey(SILICON_FLOW_KEY_PREFIX)

    fun saveAliyunApiKey(apiKey: String) {
        saveApiKey(apiKey, ALIYUN_KEY_PREFIX, "阿里云百炼 API Key 不能为空")
    }

    fun clearAliyunApiKey() {
        preferences.edit {
            remove(cipherTextKey(ALIYUN_KEY_PREFIX))
            remove(initializationVectorKey(ALIYUN_KEY_PREFIX))
        }
        mutableState.value = loadState()
    }

    fun aliyunApiKey(): String? = apiKey(ALIYUN_KEY_PREFIX)

    private fun saveApiKey(apiKey: String, prefix: String, blankMessage: String) {
        val normalized = apiKey.trim()
        require(normalized.isNotBlank()) { blankMessage }
        val encrypted = cipher.encrypt(normalized)
        preferences.edit {
            putString(cipherTextKey(prefix), encrypted.cipherText.encodeBase64())
            putString(initializationVectorKey(prefix), encrypted.initializationVector.encodeBase64())
        }
        mutableState.value = loadState()
    }

    private fun apiKey(prefix: String): String? {
        val cipherText = preferences.getString(cipherTextKey(prefix), null)?.decodeBase64() ?: return null
        val initializationVector = preferences.getString(initializationVectorKey(prefix), null)?.decodeBase64()
            ?: return null
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
        hasSiliconFlowApiKey = preferences.contains(cipherTextKey(SILICON_FLOW_KEY_PREFIX)) &&
            preferences.contains(initializationVectorKey(SILICON_FLOW_KEY_PREFIX)) &&
            siliconFlowApiKey() != null,
        hasAliyunApiKey = preferences.contains(cipherTextKey(ALIYUN_KEY_PREFIX)) &&
            preferences.contains(initializationVectorKey(ALIYUN_KEY_PREFIX)) &&
            aliyunApiKey() != null,
    )

    private fun cipherTextKey(prefix: String): String = "${prefix}_api_key_cipher_text"
    private fun initializationVectorKey(prefix: String): String = "${prefix}_api_key_iv"

    private fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val SILICON_FLOW_KEY_PREFIX = "silicon_flow"
        const val ALIYUN_KEY_PREFIX = "aliyun"
    }
}
