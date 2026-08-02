package com.harnessapk.remote

import android.util.Base64
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object RemoteCrypto {
    private val random = SecureRandom()

    fun encrypt(secretEncoded: String, message: RemoteWireMessage, payload: JsonObject): RemoteWireMessage {
        val secret = decode(secretEncoded)
        require(secret.size == 32) { "远程配对密钥无效" }
        val nonce = ByteArray(12).also(random::nextBytes)
        val withNonce = message.copy(nonce = encode(nonce), ciphertext = "")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(withNonce.aad().toByteArray(StandardCharsets.UTF_8))
        val sealed = cipher.doFinal(payload.toString().toByteArray(StandardCharsets.UTF_8))
        return withNonce.copy(ciphertext = encode(sealed))
    }

    fun decrypt(secretEncoded: String, message: RemoteWireMessage): String {
        require(message.version == REMOTE_PROTOCOL_VERSION) { "不支持的远程协议版本" }
        require(message.expiresAt >= System.currentTimeMillis()) { "远程消息已过期" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(decode(secretEncoded), "AES"), GCMParameterSpec(128, decode(message.nonce)))
        cipher.updateAAD(message.aad().toByteArray(StandardCharsets.UTF_8))
        return cipher.doFinal(decode(message.ciphertext)).toString(StandardCharsets.UTF_8)
    }

    private fun RemoteWireMessage.aad(): String = listOf(
        version, messageId, hostId, deviceId, pairingTicket.orEmpty(), sequence, expiresAt, pushKind.orEmpty(), ackOf.orEmpty(),
    ).joinToString("\u0000")

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
