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
        val payload = event.payload as? JsonObject ?: buildJsonObject {}
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

    private fun statusFor(
        type: String,
        payload: JsonObject,
        current: RemoteRunStatus,
    ): RemoteRunStatus = when (type) {
        "run.starting" -> RemoteRunStatus.STARTING
        "run.started", "run.running", "run.approval.resolved" -> RemoteRunStatus.RUNNING
        "run.approval.requested" -> RemoteRunStatus.WAITING_APPROVAL
        "run.user_input.requested" -> RemoteRunStatus.WAITING_USER
        "run.reconciling" -> RemoteRunStatus.RECONCILING
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
    payloadJson = payload?.let(::canonicalJson) ?: "null",
    createdAt = createdAt,
)

private fun RemoteLogicalEvent.toApprovalEntity(payload: JsonObject): RemoteApprovalEntity {
    val method = requireNotNull(payload.string("method")) { "approval method is required" }
    require(isRemoteApprovalMethod(method)) { "unsupported approval method $method" }
    val logicalDecisions = payload["availableDecisions"] as? JsonArray ?: JsonArray(emptyList())
    return RemoteApprovalEntity(
        id = requireNotNull(payload.string("approvalId")) { "approvalId is required" },
        runId = runId,
        logicalEventId = eventId,
        serverRequestIdJson = requireNotNull(payload["serverRequestId"]) {
            "serverRequestId is required"
        }.toString(),
        processEpoch = requireNotNull(payload.string("processEpoch")) { "processEpoch is required" },
        method = method,
        itemId = payload.string("itemId"),
        actionType = requireNotNull(payload.string("actionType")) { "actionType is required" },
        target = requireNotNull(payload.string("target")) { "target is required" },
        commandPreview = payload.string("commandPreview"),
        detailsJson = payload["details"]?.let(::canonicalJson) ?: "{}",
        availableDecisionsJson = canonicalJson(logicalDecisions),
        risk = payload.string("risk") ?: "UNKNOWN",
        status = "PENDING",
        responseCommandId = null,
        requestedAt = createdAt,
        resolvedAt = null,
    )
}
