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
    val apiProtocol: String? = null,
    val availableModels: List<String> = emptyList(),
    val customHeaders: Map<String, String> = emptyMap(),
    val customBodyJson: String = "",
)

data class ConfigPackagePayload(
    val providers: List<ConfigPackageProvider> = emptyList(),
    val aliyunVoiceApiKey: String? = null,
    val siliconFlowVoiceApiKey: String? = null,
    val webSearchEnabled: Boolean = false,
    /**
     * 三态语义：null = 包未包含该项，导入时保持接收方现有设置不变；
     * true = 显式开启；false = 显式关闭。
     * 旧版本导出的包不会写这个字段，因此一律落到 null（不动接收方设置），
     * 不会再把"没带这一项"误当成"要求关闭"。
     */
    val simpleMode: Boolean? = null,
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
