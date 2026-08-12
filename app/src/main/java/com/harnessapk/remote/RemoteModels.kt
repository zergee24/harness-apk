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

enum class ApprovalDecision {
    ALLOW_ONCE,
    DENY,
}

internal fun approvalDecisionForWire(decision: ApprovalDecision): String = when (decision) {
    ApprovalDecision.ALLOW_ONCE -> "accept"
    ApprovalDecision.DENY -> "decline"
}

sealed interface RemoteM2Command {
    val commandId: String
    val runId: String

    data class Start(
        override val commandId: String,
        override val runId: String,
        val bindingId: String,
        val workspaceId: String,
        val repositoryFingerprint: String,
        val objective: String,
        val contextSnapshot: JsonObject,
    ) : RemoteM2Command {
        init {
            require(commandId.isNotBlank()) { "commandId is required" }
            require(runId.isNotBlank()) { "runId is required" }
            require(bindingId.isNotBlank()) { "bindingId is required" }
            require(workspaceId.isNotBlank()) { "workspaceId is required" }
            require(repositoryFingerprint.isNotBlank()) { "repositoryFingerprint is required" }
            require(objective.isNotBlank()) { "objective is required" }
        }

        fun toJson(): JsonObject = buildJsonObject {
            put("type", JsonPrimitive("run.start"))
            put("commandId", JsonPrimitive(commandId))
            put("requestId", JsonPrimitive(commandId))
            put("runId", JsonPrimitive(runId))
            put("bindingId", JsonPrimitive(bindingId))
            put("workspaceId", JsonPrimitive(workspaceId))
            put("repositoryFingerprint", JsonPrimitive(repositoryFingerprint))
            put("objective", JsonPrimitive(objective))
            put("contextSnapshot", contextSnapshot)
        }
    }

    data class Steer(
        override val commandId: String,
        override val runId: String,
        val expectedTurnId: String,
        val text: String,
    ) : RemoteM2Command {
        init {
            require(commandId.isNotBlank()) { "commandId is required" }
            require(runId.isNotBlank()) { "runId is required" }
            require(expectedTurnId.isNotBlank()) { "expectedTurnId is required" }
            require(text.isNotBlank()) { "steer text is required" }
        }

        fun toJson(): JsonObject = buildJsonObject {
            put("type", JsonPrimitive("run.steer"))
            put("commandId", JsonPrimitive(commandId))
            put("requestId", JsonPrimitive(commandId))
            put("runId", JsonPrimitive(runId))
            put("expectedTurnId", JsonPrimitive(expectedTurnId))
            put("text", JsonPrimitive(text))
        }
    }

    data class Interrupt(
        override val commandId: String,
        override val runId: String,
        val expectedTurnId: String,
    ) : RemoteM2Command {
        init {
            require(commandId.isNotBlank()) { "commandId is required" }
            require(runId.isNotBlank()) { "runId is required" }
            require(expectedTurnId.isNotBlank()) { "expectedTurnId is required" }
        }

        fun toJson(): JsonObject = buildJsonObject {
            put("type", JsonPrimitive("run.interrupt"))
            put("commandId", JsonPrimitive(commandId))
            put("requestId", JsonPrimitive(commandId))
            put("runId", JsonPrimitive(runId))
            put("expectedTurnId", JsonPrimitive(expectedTurnId))
        }
    }
}

enum class RemoteServerInteractionKind {
    APPROVAL,
    USER_INPUT,
    OTHER,
}

private val remoteApprovalMethods = setOf(
    "item/commandExecution/requestApproval",
    "item/fileChange/requestApproval",
    "item/permissions/requestApproval",
)

internal fun isRemoteApprovalMethod(method: String): Boolean = method in remoteApprovalMethods

internal fun remoteServerInteractionKind(method: String): RemoteServerInteractionKind = when {
    isRemoteApprovalMethod(method) -> RemoteServerInteractionKind.APPROVAL
    method == "item/tool/requestUserInput" -> RemoteServerInteractionKind.USER_INPUT
    else -> RemoteServerInteractionKind.OTHER
}

data class RemoteFeatureAvailability(
    val canStartM2Run: Boolean,
    val canOpenLegacyHistory: Boolean,
)

private val requiredM2RunCapabilities = setOf(
    "workspace.candidates.v1",
    "run.lifecycle.v1",
    "logical-replay.v1",
)

internal fun remoteFeatureAvailability(capabilities: Set<String>): RemoteFeatureAvailability =
    RemoteFeatureAvailability(
        canStartM2Run = capabilities.containsAll(requiredM2RunCapabilities),
        canOpenLegacyHistory = true,
    )

data class RemoteLogicalEvent(
    val schemaVersion: Int,
    val eventId: String,
    val hostId: String,
    val deviceId: String,
    val runId: String,
    val sequence: Long,
    val type: String,
    val payload: JsonElement?,
    val createdAt: Long,
)

internal fun parseRemoteLogicalEvent(raw: String): RemoteLogicalEvent {
    val root = Json.parseToJsonElement(raw).jsonObject
    val schemaVersion = root.long("schemaVersion")?.toInt()
        ?: throw IllegalArgumentException("schemaVersion is required")
    require(schemaVersion == 1) { "unsupported logical event schema $schemaVersion" }
    val sequence = root.long("sequence") ?: throw IllegalArgumentException("sequence is required")
    require(sequence > 0) { "sequence must be positive" }
    return RemoteLogicalEvent(
        schemaVersion = schemaVersion,
        eventId = root.requiredString("eventId"),
        hostId = root.requiredString("hostId"),
        deviceId = root.requiredString("deviceId"),
        runId = root.requiredString("runId"),
        sequence = sequence,
        type = root.requiredString("type"),
        payload = root["payload"]?.takeUnless { it is JsonNull },
        createdAt = root.long("createdAt") ?: throw IllegalArgumentException("createdAt is required"),
    )
}

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

data class RemoteNotification(
    val title: String,
    val message: String,
    val runId: String? = null,
    val approvalId: String? = null,
    val risk: RemoteApprovalRisk = RemoteApprovalRisk.UNKNOWN,
)

data class RemoteUiState(
    val connectionStatus: RemoteConnectionStatus = RemoteConnectionStatus.DISCONNECTED,
    val errorMessage: String? = null,
    val threads: List<RemoteThread> = emptyList(),
    val selectedThreadId: String? = null,
    val activeTurnId: String? = null,
    val timeline: List<RemoteTimelineItem> = emptyList(),
    val approvals: List<RemoteApproval> = emptyList(),
    val isWorking: Boolean = false,
    val workspaceCandidates: List<WorkspaceCandidate> = emptyList(),
    val workspaceCandidatesLoaded: Boolean = false,
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

internal fun parseWorkspaceCandidates(event: RemoteEvent): List<WorkspaceCandidate> {
    val data = event.payload as? JsonArray ?: return emptyList()
    return data.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val workspaceId = item.string("workspaceId")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val cwd = item.string("cwd")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val fingerprint = item.string("repositoryFingerprint")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        WorkspaceCandidate(
            workspaceId = workspaceId,
            displayName = item.string("displayName").orEmpty().ifBlank { cwd.substringAfterLast('/') },
            cwd = cwd,
            repositoryLabel = item.string("repositoryLabel"),
            branch = item.string("branch"),
            repositoryFingerprint = fingerprint,
            lastUsedAt = item.long("lastUsedAt") ?: 0L,
        )
    }
}

internal fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()
internal fun JsonObject.array(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())

private fun JsonObject.requiredString(key: String): String =
    requireNotNull(string(key)?.takeIf { it.isNotBlank() }) { "$key is required" }
