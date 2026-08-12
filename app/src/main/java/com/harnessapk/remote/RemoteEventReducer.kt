package com.harnessapk.remote

import androidx.room.withTransaction
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.RemoteApprovalEntity
import com.harnessapk.storage.RemoteRunEntity
import com.harnessapk.storage.RemoteRunEventEntity
import com.harnessapk.storage.RemoteSyncCursorEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

enum class ReduceResult {
    APPLIED,
    DUPLICATE,
    GAP,
}

class RemoteEventReducer(
    private val database: AppDatabase,
) {
    private val dao = database.remoteDao()

    suspend fun apply(event: RemoteLogicalEvent): ReduceResult = database.withTransaction {
        if (dao.eventExists(event.eventId)) return@withTransaction ReduceResult.DUPLICATE
        val cursor = dao.cursor(event.hostId, event.deviceId) ?: RemoteSyncCursorEntity(
            hostId = event.hostId,
            deviceId = event.deviceId,
            lastContiguousSequence = 0L,
            gapFromSequence = null,
            reconciliationState = "IN_SYNC",
            lastSyncedAt = 0L,
        )
        val expectedSequence = cursor.lastContiguousSequence + 1L
        if (event.sequence != expectedSequence) {
            dao.upsertCursor(
                cursor.copy(
                    gapFromSequence = cursor.gapFromSequence ?: expectedSequence,
                    reconciliationState = "GAP",
                    lastSyncedAt = event.createdAt,
                ),
            )
            dao.markOpenRunsReconciling(event.hostId, event.createdAt)
            return@withTransaction ReduceResult.GAP
        }

        val run = requireNotNull(dao.run(event.runId)) { "unknown remote run ${event.runId}" }
        dao.insertEvent(event.toEntity())
        reduceDomainState(run, event)
        dao.upsertCursor(
            cursor.copy(
                lastContiguousSequence = event.sequence,
                gapFromSequence = null,
                reconciliationState = "IN_SYNC",
                lastSyncedAt = event.createdAt,
            ),
        )
        ReduceResult.APPLIED
    }

    private suspend fun reduceDomainState(run: RemoteRunEntity, event: RemoteLogicalEvent) {
        val payload = sanitizeRemoteApprovalJson(event.payload as? JsonObject ?: buildJsonObject {}) as JsonObject
        val currentStatus = remoteRunStatus(run.status)
        val incomingStatus = statusFor(event.type, payload, currentStatus)
        val reducedStatus = RemoteRunRepository.reduceStatus(currentStatus, incomingStatus)
        val isTerminal = reducedStatus in setOf(
            RemoteRunStatus.COMPLETED,
            RemoteRunStatus.FAILED,
            RemoteRunStatus.CANCELLED,
        )
        if (event.type == "run.approval.requested" && !isTerminal) {
            dao.insertApproval(event.toApprovalEntity(payload))
        }
        if (event.type == "run.approval.resolved") {
            payload.string("approvalId")?.let { approvalId ->
                dao.approval(approvalId)?.let { approval ->
                    dao.upsertApproval(
                        approval.copy(
                            status = payload.string("status") ?: "RESOLVED",
                            resolvedAt = event.createdAt,
                        ),
                    )
                }
            }
            payload.string("commandId")?.let { commandId ->
                dao.command(commandId)?.let { command ->
                    dao.upsertCommand(
                        command.copy(
                            status = RemoteCommandStatus.SUCCEEDED.name,
                            acknowledgedAt = command.acknowledgedAt ?: event.createdAt,
                            completedAt = event.createdAt,
                            resultJson = canonicalJson(payload),
                            nextAttemptAt = Long.MAX_VALUE,
                            updatedAt = event.createdAt,
                        ),
                    )
                }
            }
        }
        when (event.type) {
            "run.steered" -> payload.string("commandId")?.let { commandId ->
                completeCommand(commandId, event.createdAt, payload, RemoteCommandStatus.SUCCEEDED)
            }
            "run.interrupt.accepted" -> payload.string("commandId")?.let { commandId ->
                completeCommand(commandId, event.createdAt, payload, RemoteCommandStatus.ACCEPTED)
            }
            "run.control.failed" -> payload.string("commandId")?.let { commandId ->
                completeCommand(commandId, event.createdAt, payload, RemoteCommandStatus.FAILED)
            }
            "run.control.unknown" -> payload.string("commandId")?.let { commandId ->
                completeCommand(commandId, event.createdAt, payload, RemoteCommandStatus.UNKNOWN)
            }
        }
        if (event.type in setOf("run.completed", "run.failed", "run.cancelled")) {
            dao.openCommandsForRun(run.id, "run.interrupt").forEach { command ->
                dao.upsertCommand(
                    command.copy(
                        status = RemoteCommandStatus.SUCCEEDED.name,
                        acknowledgedAt = command.acknowledgedAt ?: event.createdAt,
                        completedAt = event.createdAt,
                        resultJson = canonicalJson(payload),
                        nextAttemptAt = Long.MAX_VALUE,
                        updatedAt = event.createdAt,
                    ),
                )
            }
        }
        val completedAt = if (isTerminal) run.completedAt ?: event.createdAt else run.completedAt
        dao.upsertRun(
            run.copy(
                threadId = payload.string("threadId") ?: run.threadId,
                turnId = payload.string("turnId") ?: run.turnId,
                status = reducedStatus.name,
                latestLine = payload.string("latestLine") ?: latestLineFor(event.type, run.latestLine),
                lastLogicalSequence = maxOf(run.lastLogicalSequence, event.sequence),
                updatedAt = maxOf(run.updatedAt, event.createdAt),
                completedAt = completedAt,
                completionJson = payload["completion"]?.takeUnless { it is JsonNull }?.toString()
                    ?: run.completionJson,
                errorMessage = payload.string("errorMessage") ?: run.errorMessage,
            ),
        )
    }

    private suspend fun completeCommand(
        commandId: String,
        now: Long,
        payload: JsonObject,
        status: RemoteCommandStatus,
    ) {
        dao.command(commandId)?.let { command ->
            dao.upsertCommand(
                command.copy(
                    status = status.name,
                    acknowledgedAt = command.acknowledgedAt ?: now,
                    completedAt = if (status in setOf(RemoteCommandStatus.SUCCEEDED, RemoteCommandStatus.FAILED)) now else null,
                    resultJson = canonicalJson(payload),
                    nextAttemptAt = Long.MAX_VALUE,
                    lastError = if (status == RemoteCommandStatus.FAILED) payload.string("errorMessage") else null,
                    updatedAt = now,
                ),
            )
        }
    }

    private fun statusFor(
        type: String,
        payload: JsonObject,
        current: RemoteRunStatus,
    ): RemoteRunStatus = when (type) {
        "run.starting" -> RemoteRunStatus.STARTING
        "run.started", "run.running", "run.approval.resolved", "run.steered",
        "run.interrupt.accepted", "run.timeline", "run.agent.delta" -> current.takeIf {
            it in setOf(RemoteRunStatus.WAITING_APPROVAL, RemoteRunStatus.WAITING_USER, RemoteRunStatus.RECONCILING)
        } ?: RemoteRunStatus.RUNNING
        "run.approval.requested" -> RemoteRunStatus.WAITING_APPROVAL
        "run.user_input.requested" -> RemoteRunStatus.WAITING_USER
        "run.reconciling" -> RemoteRunStatus.RECONCILING
        "run.control.unknown" -> RemoteRunStatus.RECONCILING
        "run.completed" -> RemoteRunStatus.COMPLETED
        "run.failed" -> RemoteRunStatus.FAILED
        "run.cancelled" -> RemoteRunStatus.CANCELLED
        "run.snapshot" -> payload.string("status")?.let(::remoteRunStatus) ?: current
        else -> current
    }

    private fun latestLineFor(type: String, fallback: String): String = when (type) {
        "run.approval.requested" -> "等待手机审批"
        "run.user_input.requested" -> "等待用户输入；请在 Mac UI 重新发起"
        "run.reconciling" -> "正在与 Mac 对账"
        "run.completed" -> "任务已完成"
        "run.failed" -> "任务失败"
        "run.cancelled" -> "任务已停止"
        else -> fallback
    }
}

private fun RemoteLogicalEvent.toEntity() = RemoteRunEventEntity(
    logicalEventId = eventId,
    runId = runId,
    hostId = hostId,
    deviceId = deviceId,
    sequence = sequence,
    type = type,
    itemId = (payload as? JsonObject)?.string("itemId"),
    presentationKind = (payload as? JsonObject)?.string("presentationKind") ?: "STATUS",
    payloadJson = payload?.let(::sanitizeRemoteApprovalJson)?.let(::canonicalJson) ?: "null",
    createdAt = createdAt,
)

private fun RemoteLogicalEvent.toApprovalEntity(payload: JsonObject): RemoteApprovalEntity {
    val sanitized = sanitizeRemoteApprovalJson(payload) as JsonObject
    val method = requireNotNull(sanitized.string("method")) { "approval method is required" }
    require(isRemoteApprovalMethod(method)) { "unsupported approval method $method" }
    val logicalDecisions = sanitized["availableDecisions"] as? JsonArray ?: JsonArray(emptyList())
    val actionType = requireNotNull(sanitized.string("actionType")) { "actionType is required" }
    val target = requireNotNull(sanitized.string("target")) { "target is required" }
    val risk = maxRemoteApprovalRisk(
        parseRemoteApprovalRisk(sanitized.string("risk").orEmpty()),
        classifyRemoteApprovalRisk(target, actionType),
    )
    return RemoteApprovalEntity(
        id = requireNotNull(sanitized.string("approvalId")) { "approvalId is required" },
        runId = runId,
        logicalEventId = eventId,
        serverRequestIdJson = requireNotNull(sanitized["serverRequestId"]) {
            "serverRequestId is required"
        }.toString(),
        processEpoch = requireNotNull(sanitized.string("processEpoch")) { "processEpoch is required" },
        method = method,
        itemId = sanitized.string("itemId"),
        actionType = actionType,
        target = target,
        commandPreview = sanitized.string("commandPreview"),
        detailsJson = sanitized["details"]?.let(::canonicalJson) ?: "{}",
        availableDecisionsJson = canonicalJson(logicalDecisions),
        risk = risk.name,
        status = "PENDING",
        responseCommandId = null,
        requestedAt = createdAt,
        resolvedAt = null,
    )
}
