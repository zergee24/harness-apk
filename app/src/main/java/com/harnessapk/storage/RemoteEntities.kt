package com.harnessapk.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "project_remote_bindings",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["projectId", "backendId"], unique = true),
        Index("hostId"),
        Index("workspaceId"),
    ],
)
data class ProjectRemoteBindingEntity(
    val id: String,
    val projectId: String,
    val backendId: String,
    val hostId: String,
    val workspaceId: String,
    val cwd: String,
    val displayName: String,
    val repositoryFingerprint: String,
    val repositoryLabel: String?,
    val state: String,
    val verifiedAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "remote_runs",
    primaryKeys = ["id"],
    indices = [Index("projectId"), Index("bindingId"), Index("hostId"), Index("status")],
)
data class RemoteRunEntity(
    val id: String,
    val projectId: String,
    val projectNameSnapshot: String,
    val bindingId: String,
    val bindingSnapshotJson: String,
    val hostId: String,
    val backendId: String,
    val threadId: String?,
    val turnId: String?,
    val objective: String,
    val status: String,
    val latestLine: String,
    val lastLogicalSequence: Long,
    val startedAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val completionJson: String?,
    val errorMessage: String?,
)

@Entity(
    tableName = "remote_run_events",
    primaryKeys = ["logicalEventId"],
    foreignKeys = [
        ForeignKey(
            entity = RemoteRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("runId"),
        Index("itemId"),
        Index(value = ["hostId", "deviceId", "sequence"], unique = true),
    ],
)
data class RemoteRunEventEntity(
    val logicalEventId: String,
    val runId: String,
    val hostId: String,
    val deviceId: String,
    val sequence: Long,
    val type: String,
    val itemId: String?,
    val presentationKind: String,
    val payloadJson: String,
    val createdAt: Long,
)

@Entity(
    tableName = "remote_approvals",
    primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(
            entity = RemoteRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("runId"),
        Index(value = ["logicalEventId"], unique = true),
        Index("status"),
    ],
)
data class RemoteApprovalEntity(
    val id: String,
    val runId: String,
    val logicalEventId: String,
    val serverRequestIdJson: String,
    val processEpoch: String,
    val method: String,
    val itemId: String?,
    val actionType: String,
    val target: String,
    val commandPreview: String?,
    val detailsJson: String,
    val availableDecisionsJson: String,
    val risk: String,
    val status: String,
    val responseCommandId: String?,
    val requestedAt: Long,
    val resolvedAt: Long?,
)

@Entity(
    tableName = "remote_command_outbox",
    primaryKeys = ["commandId"],
    indices = [Index("runId"), Index("status"), Index("nextAttemptAt")],
)
data class RemoteCommandOutboxEntity(
    val commandId: String,
    val runId: String?,
    val type: String,
    val payloadJson: String,
    val payloadSha256: String,
    val status: String,
    val attemptCount: Int,
    val nextAttemptAt: Long,
    val lastAttemptAt: Long?,
    val acknowledgedAt: Long?,
    val completedAt: Long?,
    val resultJson: String?,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "remote_sync_cursors",
    primaryKeys = ["hostId", "deviceId"],
)
data class RemoteSyncCursorEntity(
    val hostId: String,
    val deviceId: String,
    val lastContiguousSequence: Long,
    val gapFromSequence: Long?,
    val reconciliationState: String,
    val lastSyncedAt: Long,
)
