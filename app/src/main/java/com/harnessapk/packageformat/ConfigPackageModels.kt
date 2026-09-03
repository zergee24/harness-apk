package com.harnessapk.packageformat

const val CONFIG_PACKAGE_MIME_TYPE = "application/vnd.harness.hconfig"
const val CONFIG_PACKAGE_FILE_EXTENSION = ".hconfig"

const val CONFIG_PACKAGE_PASSPHRASE_OR_CORRUPT_MESSAGE = "口令不正确或文件已损坏"
const val CONFIG_PACKAGE_EXPIRED_MESSAGE = "配置包已过期。请检查手机系统时间是否正确；若时间正确，请让家人重新发一份"
const val CONFIG_PACKAGE_UNSUPPORTED_VERSION_MESSAGE = "配置包版本过新，请先更新 Harness 后再导入"
const val CONFIG_PACKAGE_PASSPHRASE_MIN_LENGTH = 8

internal const val CONFIG_PACKAGE_KIND = "harness.hconfig"
internal const val CONFIG_PACKAGE_VERSION = 1
internal const val CONFIG_PACKAGE_PBKDF2_ITERATIONS = 310_000

class ConfigPackageException(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause) {
    companion object {
        fun passphraseOrCorrupt(cause: Throwable? = null) =
            ConfigPackageException(CONFIG_PACKAGE_PASSPHRASE_OR_CORRUPT_MESSAGE, cause)

        fun expired() = ConfigPackageException(CONFIG_PACKAGE_EXPIRED_MESSAGE)

        fun unsupportedVersion() = ConfigPackageException(CONFIG_PACKAGE_UNSUPPORTED_VERSION_MESSAGE)

        fun malformed(detail: String) = ConfigPackageException("配置包格式不正确：$detail")
    }
}

data class ConfigPackageProvider(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val defaultModel: String,
    val defaultVisionModel: String? = null,
    val supportsVision: Boolean = false,
    val nativeWebSearchMode: String? = null,
    val availableModels: List<String> = emptyList(),
    val customHeaders: Map<String, String> = emptyMap(),
    val customBodyJson: String = "",
)

data class ConfigPackagePayload(
    val providers: List<ConfigPackageProvider> = emptyList(),
    val aliyunVoiceApiKey: String? = null,
    val siliconFlowVoiceApiKey: String? = null,
    val webSearchEnabled: Boolean = false,
    val simpleMode: Boolean = false,
    val ttsAutoRead: Boolean = false,
    val generatedFrom: String = "",
)

data class ConfigPackageEnvelope(
    val kdfIterations: Int,
    val salt: ByteArray,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val nonce: ByteArray,
    val cipherText: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ConfigPackageEnvelope &&
            kdfIterations == other.kdfIterations &&
            salt.contentEquals(other.salt) &&
            issuedAtMillis == other.issuedAtMillis &&
            expiresAtMillis == other.expiresAtMillis &&
            nonce.contentEquals(other.nonce) &&
            cipherText.contentEquals(other.cipherText)

    override fun hashCode(): Int = 31 * kdfIterations + issuedAtMillis.hashCode()
}
