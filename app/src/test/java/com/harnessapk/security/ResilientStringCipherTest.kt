package com.harnessapk.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilientStringCipherTest {
    private class FakeCipher : StringCipher {
        val encrypted = mutableMapOf<String, EncryptedValue>()
        var failing = false

        override fun encrypt(plainText: String): EncryptedValue {
            if (failing) throw IllegalStateException("keystore unavailable")
            val value = EncryptedValue(plainText.encodeToByteArray(), ByteArray(12))
            encrypted[plainText] = value
            return value
        }

        override fun decrypt(value: EncryptedValue): String {
            if (failing) throw IllegalStateException("keystore unavailable")
            return encrypted.entries.firstOrNull { it.value == value }?.key
                ?: throw IllegalArgumentException("unknown value")
        }
    }

    private val software = FakeCipher()
    private var primary: FakeCipher = FakeCipher().also { it.failing = true }

    private fun cipher(): ResilientStringCipher = ResilientStringCipher(
        context = null,
        keyAlias = "alias",
        primaryFactory = { primary },
        softwareFactory = { _, _ -> software },
    )

    @Test
    fun encryptFallsBackToSoftwareWhenKeystoreFailsAndStaysThere() {
        val cipher = cipher()
        assertFalse(cipher.usingSoftwareFallback)

        val value = cipher.encrypt("sk-secret")

        assertTrue(cipher.usingSoftwareFallback)
        assertEquals("sk-secret", software.decrypt(value))
    }

    @Test
    fun decryptTriesKeystoreFirstThenSoftware() {
        val cipher = cipher()
        val softwareValue = software.encrypt("sk-software-value")

        assertEquals("sk-software-value", cipher.decrypt(softwareValue))
        assertFalse(cipher.usingSoftwareFallback)
    }

    @Test
    fun decryptThrowsKeystoreErrorWhenBothPathsFail() {
        val cipher = cipher()
        primary.failing = false
        val keystoreValue = primary.encrypt("sk-old-keystore-value")
        primary.failing = true

        val error = runCatching { cipher.decrypt(keystoreValue) }.exceptionOrNull()

        assertEquals("keystore unavailable", error?.message)
    }

    @Test
    fun healthyKeystorePathNeverTouchesSoftware() {
        primary = FakeCipher().also { it.failing = false }
        val cipher = cipher()

        val value = cipher.encrypt("sk-primary-value")

        assertFalse(cipher.usingSoftwareFallback)
        assertEquals("sk-primary-value", primary.decrypt(value))
    }
}
