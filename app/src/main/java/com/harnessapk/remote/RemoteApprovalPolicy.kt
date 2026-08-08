package com.harnessapk.remote

import com.harnessapk.storage.RemoteApprovalEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class RemoteApprovalRisk {
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN,
}

data class RemoteApprovalPolicyDecision(
    val allowFromNotification: Boolean,
    val requiresDetailConfirmation: Boolean,
    val canApproveNow: Boolean,
)

internal fun remoteApprovalPolicy(
    risk: RemoteApprovalRisk,
    deviceLocked: Boolean,
): RemoteApprovalPolicyDecision = RemoteApprovalPolicyDecision(
    allowFromNotification = risk != RemoteApprovalRisk.HIGH,
    requiresDetailConfirmation = risk == RemoteApprovalRisk.HIGH,
    canApproveNow = risk != RemoteApprovalRisk.HIGH || !deviceLocked,
)

internal fun classifyRemoteApprovalRisk(target: String, actionType: String): RemoteApprovalRisk {
    val normalized = "$actionType $target".lowercase()
    return when {
        actionType.equals("PERMISSIONS", ignoreCase = true) ||
            Regex("(^|\\s)(sudo|su)(\\s|$)").containsMatchIn(normalized) ||
            Regex("rm\\s+-[^\\s]*r[^\\s]*f|git\\s+push.*--force|chmod\\s+777").containsMatchIn(normalized) -> RemoteApprovalRisk.HIGH
        actionType.equals("FILE_CHANGE", ignoreCase = true) ||
            Regex("git\\s+push|curl\\s|wget\\s|npm\\s+publish").containsMatchIn(normalized) -> RemoteApprovalRisk.MEDIUM
        target.isBlank() -> RemoteApprovalRisk.UNKNOWN
        else -> RemoteApprovalRisk.LOW
    }
}

internal fun parseRemoteApprovalRisk(value: String): RemoteApprovalRisk =
    RemoteApprovalRisk.entries.firstOrNull { it.name == value.uppercase() } ?: RemoteApprovalRisk.UNKNOWN

internal fun maxRemoteApprovalRisk(first: RemoteApprovalRisk, second: RemoteApprovalRisk): RemoteApprovalRisk =
    if (riskRank(first) >= riskRank(second)) first else second

private fun riskRank(risk: RemoteApprovalRisk): Int = when (risk) {
    RemoteApprovalRisk.UNKNOWN -> 0
    RemoteApprovalRisk.LOW -> 1
    RemoteApprovalRisk.MEDIUM -> 2
    RemoteApprovalRisk.HIGH -> 3
}

private val sensitiveKey = Regex("(?i).*(token|api[_-]?key|secret|password|authorization|credential).*")
private val bearerSecret = Regex("(?i)(authorization\\s*:\\s*bearer\\s+)[^\\s'\"]+")
private val urlSecret = Regex("(?i)([?&](?:access_token|token|api_key|key|secret|password)=)[^&\\s'\"]+")
private val assignmentSecret = Regex("(?i)(\\b[A-Z0-9_]*(?:TOKEN|API_KEY|SECRET|PASSWORD|CREDENTIAL)[A-Z0-9_]*=)[^\\s'\"]+")

internal fun redactRemoteSensitiveText(value: String): String = value
    .replace(bearerSecret) { "${it.groupValues[1]}[REDACTED]" }
    .replace(urlSecret) { "${it.groupValues[1]}[REDACTED]" }
    .replace(assignmentSecret) { "${it.groupValues[1]}[REDACTED]" }

internal fun sanitizeRemoteApprovalJson(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { (key, value) ->
        if (sensitiveKey.matches(key)) JsonPrimitive("[REDACTED]") else sanitizeRemoteApprovalJson(value)
    })
    is JsonArray -> JsonArray(element.map(::sanitizeRemoteApprovalJson))
    is JsonPrimitive -> if (element.isString) JsonPrimitive(redactRemoteSensitiveText(element.content)) else element
    else -> element
}

interface ApprovalResponseWriter {
    suspend fun responseCommandId(approvalId: String): String?
    suspend fun recordResponseCommand(approvalId: String, commandId: String)
}

object NoOpApprovalResponseWriter : ApprovalResponseWriter {
    override suspend fun responseCommandId(approvalId: String): String? = null
    override suspend fun recordResponseCommand(approvalId: String, commandId: String) = Unit
}

class RoomApprovalResponseWriter(
    private val dao: com.harnessapk.storage.RemoteDao,
) : ApprovalResponseWriter {
    override suspend fun responseCommandId(approvalId: String): String? = dao.approval(approvalId)?.responseCommandId

    override suspend fun recordResponseCommand(approvalId: String, commandId: String) {
        val approval = requireNotNull(dao.approval(approvalId)) { "approval not found" }
        require(approval.status == "PENDING") { "approval is no longer pending" }
        require(approval.responseCommandId == null || approval.responseCommandId == commandId) {
            "approval already has another response command"
        }
        dao.upsertApproval(approval.copy(responseCommandId = commandId))
    }
}

class RemoteApprovalCommandCoordinator(
    private val outbox: RemoteCommandOutbox,
    private val approvalWriter: ApprovalResponseWriter,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun enqueue(
        approval: RemoteApprovalEntity,
        decision: ApprovalDecision,
        commandId: String = notificationApprovalCommandId(approval.id, decision),
    ): RebuiltRemoteCommand = mutex.withLock {
        require(approval.status == "PENDING") { "approval is no longer pending" }
        val existingCommandId = approvalWriter.responseCommandId(approval.id)
        require(existingCommandId == null || existingCommandId == commandId) {
            "approval response is already pending"
        }
        val payload = buildJsonObject {
            put("type", "approval.respond")
            put("commandId", commandId)
            put("requestId", commandId)
            put("runId", approval.runId)
            put("approvalId", approval.id)
            put("processEpoch", approval.processEpoch)
            put("serverRequestId", Json.parseToJsonElement(approval.serverRequestIdJson))
            put("decision", approvalDecisionForWire(decision))
        }
        val command = outbox.enqueue(commandId, approval.runId, "approval.respond", payload, now())
        approvalWriter.recordResponseCommand(approval.id, commandId)
        command
    }
}

internal fun notificationApprovalCommandId(approvalId: String, decision: ApprovalDecision): String =
    "approval:$approvalId:${approvalDecisionForWire(decision)}"
