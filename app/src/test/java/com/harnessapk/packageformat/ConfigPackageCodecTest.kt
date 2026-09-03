package com.harnessapk.packageformat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class ConfigPackageCodecTest {
    private val payload = ConfigPackagePayload(
        providers = listOf(
            ConfigPackageProvider(
                name = "家庭主力",
                baseUrl = "https://api.example.com/v1",
                apiKey = "sk-test-key",
                defaultModel = "glm-5.3",
                defaultVisionModel = "glm-5.3v",
                supportsVision = true,
                nativeWebSearchMode = "GLM_WEB_SEARCH_TOOL",
                availableModels = listOf("glm-5.3", "glm-5.3v"),
                customHeaders = mapOf("X-Custom" to "v"),
                customBodyJson = "",
            ),
        ),
        aliyunVoiceApiKey = "aliyun-key",
        webSearchEnabled = true,
        simpleMode = true,
        generatedFrom = "0.2.5",
    )

    @Test
    fun exportThenDecryptRoundTripsPayload() {
        val bytes = ConfigPackageCodec.exportPackage(
            payload = payload,
            passphrase = "ab234567",
            issuedAtMillis = 1_000_000L,
            expiresAtMillis = 1_000_000L + VALIDITY_MILLIS,
        )

        val decrypted = ConfigPackageCodec.decryptPayload(
            envelope = ConfigPackageCodec.parseEnvelope(bytes),
            passphrase = "ab234567",
            nowMillis = 1_000_000L,
        )

        assertEquals(payload, decrypted)
    }

    @Test
    fun tamperedAadFieldFailsWithPassphraseOrCorruptMessage() {
        val bytes = ConfigPackageCodec.exportPackage(
            payload = payload,
            passphrase = "ab234567",
            issuedAtMillis = 1_000_000L,
            expiresAtMillis = 1_000_000L + VALIDITY_MILLIS,
        )
        val envelope = ConfigPackageCodec.parseEnvelope(bytes)
        val tampered = ConfigPackageCodec.serializeEnvelope(envelope.copy(issuedAtMillis = 1_000_001L))

        val error = decryptError(tampered, "ab234567", nowMillis = 1_000_050L)

        assertEquals(CONFIG_PACKAGE_PASSPHRASE_OR_CORRUPT_MESSAGE, error.userMessage)
    }

    @Test
    fun wrongPassphraseIsIndistinguishableFromCorruption() {
        val bytes = ConfigPackageCodec.exportPackage(
            payload = payload,
            passphrase = "ab234567",
            issuedAtMillis = 1_000_000L,
            expiresAtMillis = 1_000_000L + VALIDITY_MILLIS,
        )

        val wrongPassphraseError = decryptError(bytes, "xy234567", nowMillis = 1_000_000L)
        val wrongLengthPassphraseError = decryptError(bytes, "ab234", nowMillis = 1_000_000L)

        assertEquals(CONFIG_PACKAGE_PASSPHRASE_OR_CORRUPT_MESSAGE, wrongPassphraseError.userMessage)
        assertEquals(CONFIG_PACKAGE_PASSPHRASE_OR_CORRUPT_MESSAGE, wrongLengthPassphraseError.userMessage)
    }

    @Test
    fun expiredPackageIsRejectedAndBoundaryIsAccepted() {
        val bytes = ConfigPackageCodec.exportPackage(
            payload = payload,
            passphrase = "ab234567",
            issuedAtMillis = 1_000_000L,
            expiresAtMillis = 2_000_000L,
        )

        val expiredError = decryptError(bytes, "ab234567", nowMillis = 2_000_001L)
        assertEquals(CONFIG_PACKAGE_EXPIRED_MESSAGE, expiredError.userMessage)

        val decrypted = ConfigPackageCodec.decryptPayload(
            envelope = ConfigPackageCodec.parseEnvelope(bytes),
            passphrase = "ab234567",
            nowMillis = 2_000_000L,
        )
        assertEquals(payload, decrypted)
    }

    @Test
    fun newerVersionIsRejectedWithUpgradeMessage() {
        val bytes = ConfigPackageCodec.exportPackage(
            payload = payload,
            passphrase = "ab234567",
            issuedAtMillis = 1_000_000L,
            expiresAtMillis = 2_000_000L,
        )
        val newer = bytes.decodeToString()
            .replace("\"version\":1", "\"version\":2")
            .encodeToByteArray()

        val error = runCatching { ConfigPackageCodec.parseEnvelope(newer) }.exceptionOrNull()

        assertTrue(error is ConfigPackageException)
        assertEquals(CONFIG_PACKAGE_UNSUPPORTED_VERSION_MESSAGE, (error as ConfigPackageException).userMessage)
    }

    @Test
    fun unknownKdfIterationsIsRejected() {
        val bytes = ConfigPackageCodec.exportPackage(
            payload = payload,
            passphrase = "ab234567",
            issuedAtMillis = 1_000_000L,
            expiresAtMillis = 2_000_000L,
        )
        val badIterations = bytes.decodeToString()
            .replace("\"iterations\":310000", "\"iterations\":0")
            .encodeToByteArray()

        val error = runCatching { ConfigPackageCodec.parseEnvelope(badIterations) }.exceptionOrNull()

        assertTrue(error is ConfigPackageException)
        assertTrue((error as ConfigPackageException).userMessage.startsWith("配置包格式不正确"))
    }

    @Test
    fun nonJsonBytesAreRejectedAsMalformed() {
        val error = runCatching { ConfigPackageCodec.parseEnvelope("not json".encodeToByteArray()) }
            .exceptionOrNull()

        assertTrue(error is ConfigPackageException)
        assertTrue((error as ConfigPackageException).userMessage.startsWith("配置包格式不正确"))
    }

    @Test
    fun generatedPassphrasesAreEightCharsWithoutAmbiguousCharsAndNeverPureDigits() {
        val random = SecureRandom()
        repeat(64) {
            val passphrase = ConfigPackageCodec.generatePassphrase(random)

            assertEquals(8, passphrase.length)
            passphrase.forEach { char ->
                assertTrue("易混字符不应出现: $passphrase", char !in "0O1lI")
            }
            assertTrue("应包含字母: $passphrase", passphrase.any(Char::isLetter))
            assertTrue("应包含数字: $passphrase", passphrase.any(Char::isDigit))
        }
    }

    private fun decryptError(bytes: ByteArray, passphrase: String, nowMillis: Long): ConfigPackageException {
        val envelope = ConfigPackageCodec.parseEnvelope(bytes)
        return runCatching {
            ConfigPackageCodec.decryptPayload(envelope, passphrase, nowMillis)
        }.exceptionOrNull() as ConfigPackageException
    }

    private companion object {
        private const val VALIDITY_MILLIS = 12L * 60 * 60 * 1000
    }
}
