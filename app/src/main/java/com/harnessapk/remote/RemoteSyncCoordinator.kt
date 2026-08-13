package com.harnessapk.remote

import androidx.room.withTransaction
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.RemoteApprovalEntity
import com.harnessapk.storage.RemoteSyncCursorEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class RemoteSyncPosition(
    val highestContiguousSequence: Long,
    val gapFromSequence: Long?,
    val reconciliationState: String,
)

data class RemoteRunSnapshot(
    val runId: String,
    val status: String,
    val threadId: String?,
    val turnId: String?,
    val latestLine: String,
    val completionJson: String?,
    val errorMessage: String?,
)

data class RemoteApprovalSnapshot(
    val approvalId: String,
    val runId: String,
    val processEpoch: String,
    val status: String,
    val logicalEventId: String = "snapshot",
    val serverRequestIdJson: String = "null",
    val method: String = "item/commandExecution/requestApproval",
    val itemId: String? = null,
    val actionType: String = "UNKNOWN",
    val target: String = "",
    val commandPreview: String? = null,
    val detailsJson: String = "{}",
    val availableDecisionsJson: String = "[]",
    val risk: String = "UNKNOWN",
    val requestedAt: Long = 0L,
)

data class RemoteRunSnapshotEnvelope(
    val hostId: String,
    val deviceId: String,
    val journalHead: Long,
    val processEpoch: String,
    val runs: List<RemoteRunSnapshot>,
    val approvals: List<RemoteApprovalSnapshot>,
)

interface RemoteSyncSender {
    fun send(command: JsonObject): Boolean
}

interface RemoteSyncState {
    suspend fun position(hostId: String, deviceId: String): RemoteSyncPosition
    suspend fun openRunIds(hostId: String): List<String>
    suspend fun apply(event: RemoteLogicalEvent): ReduceResult
    suspend fun applySnapshot(snapshot: RemoteRunSnapshotEnvelope)
}

class RemoteSyncCoordinator(
    private val state: RemoteSyncState,
    private val sender: RemoteSyncSender,
) {
    suspend fun resume(hostId: String, deviceId: String) {
        val position = state.position(hostId, deviceId)
        sender.send(
            buildJsonObject {
                put("type", "sync.resume")
                put("requestId", "sync.resume:$hostId:$deviceId")
                put("highestContiguousSequence", position.highestContiguousSequence)
                put("openRunIds", buildJsonArray {
                    state.openRunIds(hostId).forEach { add(JsonPrimitive(it)) }
                })
            },
        )
    }

    suspend fun onLogicalEvent(event: RemoteLogicalEvent): ReduceResult {
        val result = state.apply(event)
        when (result) {
            ReduceResult.APPLIED, ReduceResult.DUPLICATE, ReduceResult.IGNORED -> {
                val position = state.position(event.hostId, event.deviceId)
                if (position.gapFromSequence == null) {
                    sender.send(
                        buildJsonObject {
                            put("type", "event.ack")
                            put("requestId", "event.ack:${event.hostId}:${event.deviceId}:${position.highestContiguousSequence}")
                            put("highestContiguousSequence", position.highestContiguousSequence)
                        },
                    )
                }
            }
            ReduceResult.GAP -> requestSnapshot(event.hostId)
        }
        return result
    }

    suspend fun onGap(hostId: String) {
        requestSnapshot(hostId)
    }

    suspend fun onSnapshot(snapshot: RemoteRunSnapshotEnvelope) {
        state.applySnapshot(snapshot)
        sender.send(
            buildJsonObject {
                put("type", "event.ack")
                put("requestId", "event.ack:${snapshot.hostId}:${snapshot.deviceId}:${snapshot.journalHead}")
                put("highestContiguousSequence", snapshot.journalHead)
            },
        )
    }

    private suspend fun requestSnapshot(hostId: String) {
        sender.send(
            buildJsonObject {
                put("type", "run.snapshot")
                put("requestId", "run.snapshot:$hostId")
                put("openRunIds", buildJsonArray {
                    state.openRunIds(hostId).forEach { add(JsonPrimitive(it)) }
                })
            },
        )
    }
}

class RoomRemoteSyncState(
    private val database: AppDatabase,
    private val reducer: RemoteEventReducer,
) : RemoteSyncState {
    private val dao = database.remoteDao()

    override suspend fun position(hostId: String, deviceId: String): RemoteSyncPosition {
        val cursor = dao.cursor(hostId, deviceId)
        return RemoteSyncPosition(
            highestContiguousSequence = cursor?.lastContiguousSequence ?: 0L,
            gapFromSequence = cursor?.gapFromSequence,
            reconciliationState = cursor?.reconciliationState ?: "IN_SYNC",
        )
    }

    override suspend fun openRunIds(hostId: String): List<String> =
        dao.openRunsForHost(hostId).map { it.id }

    override suspend fun apply(event: RemoteLogicalEvent): ReduceResult = reducer.apply(event)

    override suspend fun applySnapshot(snapshot: RemoteRunSnapshotEnvelope) {
        database.withTransaction {
            snapshot.runs.forEach { remote ->
                val local = dao.run(remote.runId) ?: return@forEach
                val localStatus = remoteRunStatus(local.status)
                val snapshotStatus = remoteRunStatus(remote.status)
                val reconciledStatus = if (localStatus in terminalRunStatuses) localStatus else snapshotStatus
                val snapshotIsTerminal = snapshotStatus in terminalRunStatuses
                val frozenCompletion = remote.completionJson?.takeIf { snapshotIsTerminal }?.let { raw ->
                    runCatching {
                        freezeRemoteCompletion(database, remote.runId, raw, System.currentTimeMillis())
                    }.getOrNull()
                }
                dao.upsertRun(
                    local.copy(
                        threadId = remote.threadId ?: local.threadId,
                        turnId = remote.turnId ?: local.turnId,
                        status = reconciledStatus.name,
                        latestLine = remote.latestLine.ifBlank { local.latestLine },
                        updatedAt = maxOf(local.updatedAt, System.currentTimeMillis()),
                        completedAt = if (reconciledStatus in terminalRunStatuses) {
                            local.completedAt ?: System.currentTimeMillis()
                        } else {
                            local.completedAt
                        },
                        completionJson = frozenCompletion ?: local.completionJson,
                        errorMessage = remote.errorMessage ?: local.errorMessage,
                    ),
                )
                if (reconciledStatus in terminalRunStatuses) {
                    dao.openCommandsForRun(remote.runId, "run.interrupt").forEach { command ->
                        dao.upsertCommand(
                            command.copy(
                                status = RemoteCommandStatus.SUCCEEDED.name,
                                acknowledgedAt = command.acknowledgedAt ?: System.currentTimeMillis(),
                                completedAt = command.completedAt ?: System.currentTimeMillis(),
                                nextAttemptAt = Long.MAX_VALUE,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }
            val approvalsByRun = snapshot.approvals.groupBy { it.runId }
            snapshot.runs.forEach { run ->
                val authoritative = approvalsByRun[run.runId].orEmpty().associateBy { it.approvalId }
                dao.approvalsForRun(run.runId).forEach { local ->
                    val remote = authoritative[local.id]
                    val nextStatus = when {
                        remote == null -> "STALE"
                        remote.processEpoch != snapshot.processEpoch -> "STALE"
                        else -> remote.status
                    }
                    dao.upsertApproval(local.copy(status = nextStatus))
                    if (nextStatus != "PENDING") {
                        local.responseCommandId?.let { commandId ->
                            dao.command(commandId)?.let { command ->
                                dao.upsertCommand(
                                    command.copy(
                                        status = RemoteCommandStatus.SUCCEEDED.name,
                                        acknowledgedAt = command.acknowledgedAt ?: System.currentTimeMillis(),
                                        completedAt = System.currentTimeMillis(),
                                        nextAttemptAt = Long.MAX_VALUE,
                                        updatedAt = System.currentTimeMillis(),
                                    ),
                                )
                            }
                        }
                    }
                }
                authoritative.values.forEach { remote ->
                    val existing = dao.approval(remote.approvalId)
                    val status = if (remote.processEpoch == snapshot.processEpoch) remote.status else "STALE"
                    dao.upsertApproval(
                        existing?.copy(status = status, processEpoch = remote.processEpoch)
                            ?: remote.toEntity(status),
                    )
                }
            }
            dao.upsertCursor(
                RemoteSyncCursorEntity(
                    hostId = snapshot.hostId,
                    deviceId = snapshot.deviceId,
                    lastContiguousSequence = snapshot.journalHead,
                    gapFromSequence = null,
                    reconciliationState = "IN_SYNC",
                    lastSyncedAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}

private val terminalRunStatuses = setOf(
    RemoteRunStatus.COMPLETED,
    RemoteRunStatus.FAILED,
    RemoteRunStatus.CANCELLED,
)

internal fun isRemoteApprovalActionEnabled(
    approvalStatus: String,
    position: RemoteSyncPosition,
): Boolean = approvalStatus == "PENDING" &&
    position.gapFromSequence == null &&
    position.reconciliationState == "IN_SYNC"

private fun RemoteApprovalSnapshot.toEntity(resolvedStatus: String) = RemoteApprovalEntity(
    id = approvalId,
    runId = runId,
    logicalEventId = logicalEventId,
    serverRequestIdJson = serverRequestIdJson,
    processEpoch = processEpoch,
    method = method,
    itemId = itemId,
    actionType = actionType,
    target = target,
    commandPreview = commandPreview,
    detailsJson = detailsJson,
    availableDecisionsJson = availableDecisionsJson,
    risk = risk,
    status = resolvedStatus,
    responseCommandId = null,
    requestedAt = requestedAt,
    resolvedAt = null,
)

internal fun parseRemoteRunSnapshot(payload: JsonObject): RemoteRunSnapshotEnvelope {
    fun JsonObject.required(key: String): String =
        requireNotNull(this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)) { "$key is required" }
    val runs = (payload["runs"] as? JsonArray).orEmpty().map { element ->
        val run = element.jsonObject
        RemoteRunSnapshot(
            runId = run.required("runId"),
            status = run.required("status"),
            threadId = run.string("threadId"),
            turnId = run.string("turnId"),
            latestLine = redactRemoteSensitiveText(run.string("latestLine").orEmpty()),
            completionJson = run["completion"]?.takeUnless { it is JsonNull }
                ?.let(::sanitizeRemoteApprovalJson)?.toString(),
            errorMessage = run.string("errorMessage")?.let(::redactRemoteSensitiveText),
        )
    }
    val approvals = (payload["approvals"] as? JsonArray).orEmpty().map { element ->
        val approval = sanitizeRemoteApprovalJson(element.jsonObject) as JsonObject
        val actionType = approval.string("actionType") ?: "UNKNOWN"
        val target = approval.string("target").orEmpty()
        val risk = maxRemoteApprovalRisk(
            parseRemoteApprovalRisk(approval.string("risk").orEmpty()),
            classifyRemoteApprovalRisk(target, actionType),
        )
        RemoteApprovalSnapshot(
            approvalId = approval.required("approvalId"),
            runId = approval.required("runId"),
            processEpoch = approval.required("processEpoch"),
            status = approval.required("status"),
            logicalEventId = approval.string("logicalEventId") ?: "snapshot:${approval.required("approvalId")}",
            serverRequestIdJson = approval["serverRequestId"]?.toString() ?: "null",
            method = approval.string("method") ?: "item/commandExecution/requestApproval",
            itemId = approval.string("itemId"),
            actionType = actionType,
            target = target,
            commandPreview = approval.string("commandPreview"),
            detailsJson = approval["details"]?.toString() ?: "{}",
            availableDecisionsJson = approval["availableDecisions"]?.toString() ?: "[]",
            risk = risk.name,
            requestedAt = approval.long("requestedAt") ?: 0L,
        )
    }
    return RemoteRunSnapshotEnvelope(
        hostId = payload.required("hostId"),
        deviceId = payload.required("deviceId"),
        journalHead = payload.long("journalHead") ?: 0L,
        processEpoch = payload.required("processEpoch"),
        runs = runs,
        approvals = approvals,
    )
}
