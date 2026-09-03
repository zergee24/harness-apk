package com.harnessapk.security

import android.content.Context
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 无 AndroidKeyStore 环境下的软件密钥回退：随机 AES-256 密钥存应用私有
 * SharedPreferences，静态保护强度 = 应用沙箱。仅在 Keystore 真实失败时使用。
 */
class SoftwareStringCipher(
    context: Context,
    preferencesName: String,
) : StringCipher {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    @Volatile
    private var cachedKey: SecretKey? = null

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

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        val stored = preferences.getString(KEY_PREF, null)?.let(::decodeStoredKey)
        val key = stored ?: generateAndStoreKey()
        cachedKey = key
        return key
    }

    private fun generateAndStoreKey(): SecretKey {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(KEY_BITS)
        val key = generator.generateKey()
        preferences.edit()
            .putString(KEY_PREF, Base64.getEncoder().encodeToString(key.encoded))
            .apply()
        return key
    }

    private fun decodeStoredKey(encoded: String): SecretKey? = runCatching {
        SecretKeySpec(Base64.getDecoder().decode(encoded), "AES")
    }.getOrNull()

    private companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_PREF = "software_aes_key"
        private const val KEY_BITS = 256
    }
}

/**
 * AndroidKeyStore 优先、软件密钥兜底的组合加密器：加密一旦失败即永久切换软件
 * 路径（[usingSoftwareFallback]），解密先试 Keystore 再试软件密钥，两条路径都
 * 失败时抛出 Keystore 路径的原始错误。
 *
 * @param context 仅软件回退路径需要；注入 factory 的测试场景可为 null。
 */
class ResilientStringCipher(
    private val context: Context?,
    private val keyAlias: String,
    preferencesName: String? = null,
    primaryFactory: (() -> StringCipher?)? = null,
    softwareFactory: ((Context?, String) -> StringCipher)? = null,
) : StringCipher {
    private val resolvedPreferencesName = preferencesName ?: "resilient_cipher_keys_$keyAlias"
    private val primary: Lazy<StringCipher?> = lazy {
        primaryFactory?.invoke() ?: runCatching { ApiKeyCipher(keyAlias) }.getOrNull()
    }
    private val software: Lazy<StringCipher> = lazy {
        softwareFactory?.invoke(context, resolvedPreferencesName)
            ?: SoftwareStringCipher(requireContext(), resolvedPreferencesName)
    }

    @Volatile
    private var softwareFallback = false

    val usingSoftwareFallback: Boolean get() = softwareFallback

    override fun encrypt(plainText: String): EncryptedValue {
        if (!softwareFallback) {
            val target = primary.value
            if (target != null) {
                try {
                    return target.encrypt(plainText)
                } catch (_: Throwable) {
                    softwareFallback = true
                }
            } else {
                softwareFallback = true
            }
        }
        return software.value.encrypt(plainText)
    }

    override fun decrypt(value: EncryptedValue): String {
        val keystoreFailure = runCatching {
            val target = primary.value
            if (!softwareFallback && target != null) {
                return target.decrypt(value)
            }
            null
        }.exceptionOrNull()
        return try {
            software.value.decrypt(value)
        } catch (softwareFailure: Throwable) {
            throw keystoreFailure ?: softwareFailure
        }
    }

    private fun requireContext(): Context =
        context ?: throw IllegalStateException("SoftwareStringCipher 回退需要 Context")
}
