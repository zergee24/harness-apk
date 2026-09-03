package com.harnessapk.packageformat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * `.hconfig` 配置包编解码：口令 PBKDF2 派生密钥 + AES-256-GCM，
 * kind / version / 签发与过期时间进 AAD 防篡改，过期由导入端时钟判定。
 */
object ConfigPackageCodec {
    private val json = Json

    private const val GCM_TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val NONCE_BYTES = 12
    private const val SALT_BYTES = 16

    private val passphraseRandom = SecureRandom()

    private const val PASSPHRASE_ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val PASSPHRASE_LENGTH = 8

    fun exportPackage(
        payload: ConfigPackagePayload,
        passphrase: String,
        issuedAtMillis: Long,
        expiresAtMillis: Long,
        random: SecureRandom = passphraseRandom,
    ): ByteArray {
        require(passphrase.length >= CONFIG_PACKAGE_PASSPHRASE_MIN_LENGTH) {
            "口令至少 $CONFIG_PACKAGE_PASSPHRASE_MIN_LENGTH 位"
        }
        require(expiresAtMillis > issuedAtMillis) { "有效期必须晚于签发时间" }
        val salt = randomBytes(SALT_BYTES, random)
        val nonce = randomBytes(NONCE_BYTES, random)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            derivedKey(passphrase, salt, CONFIG_PACKAGE_PBKDF2_ITERATIONS),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(aad(CONFIG_PACKAGE_VERSION, issuedAtMillis, expiresAtMillis))
        val cipherText = cipher.doFinal(encodePayload(payload))
        return serializeEnvelope(
            ConfigPackageEnvelope(
                kdfIterations = CONFIG_PACKAGE_PBKDF2_ITERATIONS,
                salt = salt,
                issuedAtMillis = issuedAtMillis,
                expiresAtMillis = expiresAtMillis,
                nonce = nonce,
                cipherText = cipherText,
            ),
        )
    }

    fun parseEnvelope(bytes: ByteArray): ConfigPackageEnvelope {
        val root = runCatching { json.parseToJsonElement(bytes.decodeToString()).jsonObject }
            .getOrElse { throw ConfigPackageException.malformed("不是有效的配置包 JSON") }
        val kind = root.stringField("kind")
        if (kind != CONFIG_PACKAGE_KIND) throw ConfigPackageException.malformed("kind 不匹配")
        val version = root.intField("version") ?: throw ConfigPackageException.malformed("缺少 version")
        if (version > CONFIG_PACKAGE_VERSION) throw ConfigPackageException.unsupportedVersion()
        if (version < CONFIG_PACKAGE_VERSION) throw ConfigPackageException.malformed("version 过旧")
        val kdf = root.objectField("kdf") ?: throw ConfigPackageException.malformed("缺少 kdf")
        val algo = kdf.stringField("algo")
        if (algo != "PBKDF2WithHmacSHA256") throw ConfigPackageException.malformed("kdf.algo 不受支持")
        val iterations = kdf.intField("iterations") ?: throw ConfigPackageException.malformed("缺少 kdf.iterations")
        if (iterations < 1) throw ConfigPackageException.malformed("kdf.iterations 非法")
        val salt = kdf.stringField("saltB64")?.decodeBase64() ?: throw ConfigPackageException.malformed("缺少 kdf.saltB64")
        val issuedAt = root.longField("issuedAtMs") ?: throw ConfigPackageException.malformed("缺少 issuedAtMs")
        val expiresAt = root.longField("expiresAtMs") ?: throw ConfigPackageException.malformed("缺少 expiresAtMs")
        if (expiresAt <= issuedAt) throw ConfigPackageException.malformed("有效期早于签发时间")
        val nonce = root.stringField("nonceB64")?.decodeBase64() ?: throw ConfigPackageException.malformed("缺少 nonceB64")
        val cipherText = root.stringField("cipherTextB64")?.decodeBase64()
            ?: throw ConfigPackageException.malformed("缺少 cipherTextB64")
        if (nonce.size != NONCE_BYTES) throw ConfigPackageException.malformed("nonce 长度非法")
        return ConfigPackageEnvelope(
            kdfIterations = iterations,
            salt = salt,
            issuedAtMillis = issuedAt,
            expiresAtMillis = expiresAt,
            nonce = nonce,
            cipherText = cipherText,
        )
    }

    fun decryptPayload(
        envelope: ConfigPackageEnvelope,
        passphrase: String,
        nowMillis: Long,
    ): ConfigPackagePayload {
        if (nowMillis > envelope.expiresAtMillis) throw ConfigPackageException.expired()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            derivedKey(passphrase, envelope.salt, envelope.kdfIterations),
            GCMParameterSpec(GCM_TAG_BITS, envelope.nonce),
        )
        cipher.updateAAD(aad(CONFIG_PACKAGE_VERSION, envelope.issuedAtMillis, envelope.expiresAtMillis))
        val plainBytes = runCatching {
            cipher.doFinal(envelope.cipherText)
        }.getOrElse { throw ConfigPackageException.passphraseOrCorrupt(it) }
        return runCatching { decodePayload(plainBytes) }
            .getOrElse { throw ConfigPackageException.malformed("解密后的内容无法解析") }
    }

    private fun encodePayload(payload: ConfigPackagePayload): ByteArray = buildJsonObject {
        put("providers", buildJsonArray {
            payload.providers.forEach { provider ->
                add(
                    buildJsonObject {
                        put("name", provider.name)
                        put("baseUrl", provider.baseUrl)
                        put("apiKey", provider.apiKey)
                        put("defaultModel", provider.defaultModel)
                        provider.defaultVisionModel?.let { put("defaultVisionModel", it) }
                        put("supportsVision", provider.supportsVision)
                        provider.nativeWebSearchMode?.let { put("nativeWebSearchMode", it) }
                        put("availableModels", buildJsonArray {
                            provider.availableModels.forEach { model -> add(JsonPrimitive(model)) }
                        })
                        if (provider.customHeaders.isNotEmpty()) {
                            put("customHeaders", buildJsonObject {
                                provider.customHeaders.forEach { (key, value) -> put(key, value) }
                            })
                        }
                        if (provider.customBodyJson.isNotBlank()) put("customBodyJson", provider.customBodyJson)
                    },
                )
            }
        })
        payload.aliyunVoiceApiKey?.let { put("aliyunVoiceApiKey", it) }
        payload.siliconFlowVoiceApiKey?.let { put("siliconFlowVoiceApiKey", it) }
        if (payload.webSearchEnabled) put("webSearchEnabled", true)
        if (payload.simpleMode) put("simpleMode", true)
        if (payload.generatedFrom.isNotBlank()) put("generatedFrom", payload.generatedFrom)
    }.toString().encodeToByteArray()

    private fun decodePayload(bytes: ByteArray): ConfigPackagePayload {
        val root = runCatching { json.parseToJsonElement(bytes.decodeToString()).jsonObject }
            .getOrElse { throw ConfigPackageException.malformed("解密后的内容无法解析") }
        val providers = root.arrayField("providers")?.map { element ->
            val provider = runCatching { element.jsonObject }.getOrElse {
                throw ConfigPackageException.malformed("providers 元素非法")
            }
            ConfigPackageProvider(
                name = provider.stringField("name") ?: throw ConfigPackageException.malformed("provider 缺少 name"),
                baseUrl = provider.stringField("baseUrl")
                    ?: throw ConfigPackageException.malformed("provider 缺少 baseUrl"),
                apiKey = provider.stringField("apiKey")
                    ?: throw ConfigPackageException.malformed("provider 缺少 apiKey"),
                defaultModel = provider.stringField("defaultModel")
                    ?: throw ConfigPackageException.malformed("provider 缺少 defaultModel"),
                defaultVisionModel = provider.stringField("defaultVisionModel"),
                supportsVision = provider.booleanField("supportsVision") ?: false,
                nativeWebSearchMode = provider.stringField("nativeWebSearchMode"),
                availableModels = provider.arrayField("availableModels")
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: emptyList(),
                customHeaders = provider.objectField("customHeaders")?.let { headers ->
                    headers.entries.associate { (key, value) -> key to (value.jsonPrimitive.contentOrNull ?: "") }
                } ?: emptyMap(),
                customBodyJson = provider.stringField("customBodyJson") ?: "",
            )
        } ?: emptyList()
        return ConfigPackagePayload(
            providers = providers,
            aliyunVoiceApiKey = root.stringField("aliyunVoiceApiKey"),
            siliconFlowVoiceApiKey = root.stringField("siliconFlowVoiceApiKey"),
            webSearchEnabled = root.booleanField("webSearchEnabled") ?: false,
            simpleMode = root.booleanField("simpleMode") ?: false,
            generatedFrom = root.stringField("generatedFrom") ?: "",
        )
    }

    internal fun serializeEnvelope(envelope: ConfigPackageEnvelope): ByteArray = buildJsonObject {
        put("kind", CONFIG_PACKAGE_KIND)
        put("version", CONFIG_PACKAGE_VERSION)
        put(
            "kdf",
            buildJsonObject {
                put("algo", "PBKDF2WithHmacSHA256")
                put("iterations", envelope.kdfIterations)
                put("saltB64", envelope.salt.encodeBase64())
            },
        )
        put("issuedAtMs", envelope.issuedAtMillis)
        put("expiresAtMs", envelope.expiresAtMillis)
        put("nonceB64", envelope.nonce.encodeBase64())
        put("cipherTextB64", envelope.cipherText.encodeBase64())
    }.toString().encodeToByteArray()

    fun generatePassphrase(random: SecureRandom = passphraseRandom): String {
        repeat(PASSPHRASE_GENERATION_ATTEMPTS) {
            val candidate = buildString {
                repeat(PASSPHRASE_LENGTH) { append(PASSPHRASE_ALPHABET[random.nextInt(PASSPHRASE_ALPHABET.length)]) }
            }
            val hasLetter = candidate.any { it.isLetter() }
            val hasDigit = candidate.any { it.isDigit() }
            if (hasLetter && hasDigit) return candidate
        }
        // 54 个字符的字母表下 8 位全数字的概率约 1e-8，兜底替换保证规则成立
        return "h${random.randomIndex(PASSPHRASE_ALPHABET)}${random.randomIndex(PASSPHRASE_ALPHABET)}2345678"
            .take(PASSPHRASE_LENGTH)
    }

    private const val PASSPHRASE_GENERATION_ATTEMPTS = 16
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun SecureRandom.randomIndex(alphabet: String): Char = alphabet[nextInt(alphabet.length)]

    private fun derivedKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        val key = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(key, "AES")
    }

    private fun aad(version: Int, issuedAtMillis: Long, expiresAtMillis: Long): ByteArray =
        "$CONFIG_PACKAGE_KIND\n$version\n$issuedAtMillis\n$expiresAtMillis".encodeToByteArray()

    private fun randomBytes(size: Int, random: SecureRandom): ByteArray =
        ByteArray(size).also(random::nextBytes)

    private fun ByteArray.encodeBase64(): String = Base64.getEncoder().encodeToString(this)

    private fun String.decodeBase64(): ByteArray = runCatching {
        Base64.getDecoder().decode(this)
    }.getOrElse { throw ConfigPackageException.malformed("base64 字段非法") }

    private fun JsonObject.stringField(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.intField(name: String): Int? =
        this[name]?.jsonPrimitive?.intOrNull

    private fun JsonObject.longField(name: String): Long? =
        this[name]?.jsonPrimitive?.longOrNull

    private fun JsonObject.objectField(name: String): JsonObject? =
        runCatching { this[name]?.jsonObject }.getOrNull()

    private fun JsonObject.arrayField(name: String): kotlinx.serialization.json.JsonArray? =
        runCatching { this[name]?.jsonArray }.getOrNull()

    private fun JsonObject.booleanField(name: String): Boolean? =
        this[name]?.jsonPrimitive?.contentOrNull?.let { value ->
            when (value) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
}
