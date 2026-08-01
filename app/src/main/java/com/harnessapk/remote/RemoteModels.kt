package com.harnessapk.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val REMOTE_PROTOCOL_VERSION = 1

data class RemotePairingPayload(
    val relayUrl: String,
    val hostId: String,
    val hostName: String,
    val pairingTicket: String,
    val pairingSecret: String,
    val expiresAt: Long,
)

data class RemoteProfile(
    val relayUrl: String,
    val hostId: String,
    val hostName: String,
    val deviceId: String,
    val deviceToken: String,
    val pairingTicket: String,
    val pairingSecret: String,
)

data class RemoteWireMessage(
    val version: Int = REMOTE_PROTOCOL_VERSION,
    val messageId: String,
    val hostId: String,
    val deviceId: String,
    val pairingTicket: String? = null,
    val sequence: Long,
    val expiresAt: Long,
    val nonce: String,
    val ciphertext: String,
    val pushKind: String? = null,
    val ackOf: String? = null,
)

data class RemoteCommand(
    val type: String,
    val requestId: String,
    val threadId: String? = null,
    val turnId: String? = null,
    val text: String? = null,
    val cwd: String? = null,
    val expectedTurnId: String? = null,
    val serverRequestId: JsonElement? = null,
    val decision: String? = null,
    val method: String? = null,
    val params: JsonElement? = null,
)

data class RemoteEvent(
    val type: String,
    val requestId: String? = null,
    val method: String? = null,
    val threadId: String? = null,
    val turnId: String? = null,
    val message: String? = null,
    val payload: JsonElement? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class RemoteThread(
    val id: String,
    val title: String,
    val preview: String,
    val cwd: String?,
    val updatedAt: Long,
    val status: String,
)

data class RemoteTimelineItem(
    val id: String,
    val kind: String,
    val text: String,
    val status: String? = null,
)

data class RemoteApproval(
    val requestId: JsonElement,
    val method: String,
    val threadId: String?,
    val turnId: String?,
    val reason: String,
    val command: String?,
)

enum class RemoteConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class RemoteNotification(val title: String, val message: String)

data class RemoteUiState(
    val connectionStatus: RemoteConnectionStatus = RemoteConnectionStatus.DISCONNECTED,
    val errorMessage: String? = null,
    val threads: List<RemoteThread> = emptyList(),
    val selectedThreadId: String? = null,
    val activeTurnId: String? = null,
    val timeline: List<RemoteTimelineItem> = emptyList(),
    val approvals: List<RemoteApproval> = emptyList(),
    val isWorking: Boolean = false,
)

internal fun parsePairingPayload(raw: String, now: Long = System.currentTimeMillis()): RemotePairingPayload {
    val root = Json.parseToJsonElement(raw.trim()).jsonObject
    require(root.long("version") == REMOTE_PROTOCOL_VERSION.toLong()) { "不支持的远程协议版本" }
    val payload = RemotePairingPayload(
        relayUrl = requireNotNull(root.string("relayUrl")),
        hostId = requireNotNull(root.string("hostId")),
        hostName = root.string("hostName").orEmpty().ifBlank { "Mac" },
        pairingTicket = requireNotNull(root.string("pairingTicket")),
        pairingSecret = requireNotNull(root.string("pairingSecret")),
        expiresAt = root.long("expiresAt") ?: 0L,
    )
    require(payload.relayUrl.startsWith("https://") || payload.relayUrl.startsWith("http://localhost")) {
        "远程节点必须使用 HTTPS"
    }
    require(payload.expiresAt > now) { "配对二维码已过期" }
    return payload
}

internal fun RemoteCommand.toJson(): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(type)); put("requestId", JsonPrimitive(requestId))
    threadId?.let { put("threadId", JsonPrimitive(it)) }; turnId?.let { put("turnId", JsonPrimitive(it)) }
    text?.let { put("text", JsonPrimitive(it)) }; cwd?.let { put("cwd", JsonPrimitive(it)) }
    expectedTurnId?.let { put("expectedTurnId", JsonPrimitive(it)) }
    serverRequestId?.let { put("serverRequestId", it) }; decision?.let { put("decision", JsonPrimitive(it)) }
    method?.let { put("method", JsonPrimitive(it)) }; params?.let { put("params", it) }
}

internal fun parseRemoteEvent(raw: String): RemoteEvent {
    val root = Json.parseToJsonElement(raw).jsonObject
    return RemoteEvent(
        type = root.string("type").orEmpty(), requestId = root.string("requestId"), method = root.string("method"),
        threadId = root.string("threadId"), turnId = root.string("turnId"), message = root.string("message"),
        payload = root["payload"]?.takeUnless { it is JsonNull }, createdAt = root.long("createdAt") ?: System.currentTimeMillis(),
    )
}

internal fun parseThreads(event: RemoteEvent): List<RemoteThread> {
    val response = event.payload?.jsonObject ?: return emptyList()
    val data = response["result"]?.jsonObject?.get("data") as? JsonArray ?: return emptyList()
    return data.mapNotNull { element ->
        val item = element.jsonObject
        val id = item.string("id") ?: return@mapNotNull null
        RemoteThread(
            id = id,
            title = item.string("name") ?: item.string("preview")?.take(60) ?: "未命名线程",
            preview = item.string("preview").orEmpty(), cwd = item.string("cwd"),
            updatedAt = (item.long("updatedAt") ?: 0L) * 1000L,
            status = item["status"]?.jsonObject?.string("type").orEmpty(),
        )
    }
}

internal fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()
internal fun JsonObject.array(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())
