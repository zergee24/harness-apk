package com.harnessapk.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface StringCipher {
    fun encrypt(plainText: String): EncryptedValue
    fun decrypt(value: EncryptedValue): String
}

class ApiKeyCipher(
    private val keyAlias: String = "harness_apk_provider_keys",
) : StringCipher {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun encrypt(plainText: String): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return EncryptedValue(
            cipherText = cipher.doFinal(plainText.encodeToByteArray()),
            initializationVector = cipher.iv,
        )
    }

    override fun decrypt(value: EncryptedValue): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, value.initializationVector),
        )
        return cipher.doFinal(value.cipherText).decodeToString()
    }

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

data class EncryptedValue(
    val cipherText: ByteArray,
    val initializationVector: ByteArray,
)
