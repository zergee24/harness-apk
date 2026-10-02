package com.harnessapk.configpackage

import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 配置包中继传输：一次性加密保管箱。领取码既是中继下载地址也是解密口令
 * （KDF 在两端），中继全程只见 envelope 密文——端到端，领取即焚 + TTL。
 */
object ConfigTransfer {
    /** 家庭系统唯一中继部署；未配对设备（无 remote profile）也用它领取。 */
    const val DEFAULT_RELAY_URL = "https://relay.zerg.work"

    private const val CLAIM_CODE_LENGTH = 8
    // 纯大写 + 数字、去易混（I/O/0/1）：领取码大小写不敏感
    private val CLAIM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray()

    private val random = SecureRandom()

    data class Receipt(
        val code: String,
        val expiresAtMillis: Long,
        val adminToken: String,
    )

    /** 34 表大写、8 位、必含字母与数字；码同时是 envelope 的加密口令。 */
    fun generateClaimCode(): String {
        repeat(16) {
            val candidate = buildString {
                repeat(CLAIM_CODE_LENGTH) { append(CLAIM_CODE_ALPHABET[random.nextInt(CLAIM_CODE_ALPHABET.size)]) }
            }
            if (candidate.any { it.isLetter() } && candidate.any { it.isDigit() }) return candidate
        }
        error("领取码生成失败，请重试")
    }

    fun normalizeCode(raw: String): String =
        raw.trim().replace("-", "").replace(" ", "").uppercase()

    fun formatCode(code: String): String =
        if (code.length == CLAIM_CODE_LENGTH) {
            code.substring(0, 4) + "-" + code.substring(4)
        } else {
            code
        }

    fun resolveRelayUrl(profileRelayUrl: String?): String {
        // 本机调试 override（gradle.properties 的 configRelayOverride，如 http://127.0.0.1:8080）优先
        com.harnessapk.BuildConfig.CONFIG_RELAY_OVERRIDE.trim().takeIf(String::isNotBlank)?.let { return it.removeSuffix("/") }
        return profileRelayUrl?.trim()?.takeIf(String::isNotBlank)?.removeSuffix("/") ?: DEFAULT_RELAY_URL
    }

    suspend fun upload(
        relayUrl: String,
        code: String,
        envelope: ByteArray,
        ttlMinutes: Int,
    ): Receipt = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("code", normalizeCode(code))
            put("envelopeB64", base64(envelope))
            put("ttlMinutes", ttlMinutes)
        }
        val response = postJson("$relayUrl/v1/config-blobs", body)
        if (response.status == 409) error("该领取码刚被占用，请重试")
        requireOk(response)
        val json = JSONObject(response.body)
        Receipt(
            code = normalizeCode(json.getString("code")),
            expiresAtMillis = parseRfc3339(json.getString("expiresAt")),
            adminToken = json.getString("adminToken"),
        )
    }

    /** 领取即焚：成功返回 envelope 字节；未找到/过期/已领返回 null。 */
    suspend fun claim(relayUrl: String, code: String): ByteArray? = withContext(Dispatchers.IO) {
        val response = getJson("$relayUrl/v1/config-blobs/${normalizeCode(code)}")
        when (response.status) {
            200 -> base64Decode(JSONObject(response.body).getString("envelopeB64"))
            404 -> null
            else -> error("中继响应异常（${response.status}），请稍后重试")
        }
    }

    suspend fun revoke(relayUrl: String, code: String, adminToken: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("adminToken", adminToken)
            val connection = URL("$relayUrl/v1/config-blobs/${normalizeCode(code)}").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "DELETE"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().encodeToByteArray()) }
                connection.responseCode == 204
            } finally {
                connection.disconnect()
            }
        }

    private class Response(val status: Int, val body: String)

    private fun postJson(url: String, body: JSONObject): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().encodeToByteArray()) }
            return Response(connection.responseCode, readBody(connection))
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(url: String): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            return Response(connection.responseCode, readBody(connection))
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun requireOk(response: Response) {
        if (response.status !in 200..299) {
            throw IllegalStateException("中继响应异常（${response.status}），请稍后重试")
        }
    }

    private fun base64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun base64Decode(text: String): ByteArray =
        android.util.Base64.decode(text, android.util.Base64.DEFAULT)

    private fun parseRfc3339(text: String): Long =
        java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
}
