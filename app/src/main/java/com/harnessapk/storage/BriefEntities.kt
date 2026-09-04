package com.harnessapk.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 工作简报 P1 本地核心（可回放工作简报设计 §16）。
 * Room v25；`.hbrief schemaVersion` 独立演进，与此处无关。
 * 高频笔迹点不进 Room——追加日志（journal）只在这里登记路径与块元数据。
 */

@Entity(
    tableName = "work_briefs",
    primaryKeys = ["id"],
    indices = [Index("projectId"), Index("status"), Index("updatedAt")],
)
data class WorkBriefEntity(
    val id: String,
    val projectId: String,
    val title: String,
    val status: String,
    val revision: Int,
    val bundleRelativePath: String?,
    val bundleSha256: String?,
    val totalDurationMs: Long,
    val activeDurationMs: Long,
    val audioAvailability: String,
    val sourceBriefId: String?,
    val sourceRevision: Int?,
    val continuationOfBriefId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long,
    val errorMessage: String?,
)

@Entity(
    tableName = "brief_capture_sessions",
    primaryKeys = ["id"],
    indices = [
        // v1 恰好一个场次：数据库层阻止一份 Brief 多场次。
        Index(value = ["briefId"], unique = true),
    ],
    foreignKeys = [
        ForeignKey(
            entity = WorkBriefEntity::class,
            parentColumns = ["id"],
            childColumns = ["briefId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CaptureSessionEntity(
    val id: String,
    val briefId: String,
    val status: String,
    val wallClockStartedAt: Long,
    val durationMs: Long,
    val activeDurationMs: Long,
    @ColumnInfo(defaultValue = "'none'")
    val audioPolicy: String,
    val transcriptionProvider: String?,
    val consentAt: Long?,
    val uploadScope: String?,
    val retentionResult: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val errorMessage: String?,
)

@Entity(
    tableName = "brief_capture_segments",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["sessionId", "segmentIndex"], unique = true),
    ],    foreignKeys = [
        ForeignKey(
            entity = CaptureSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CaptureSegmentEntity(
    val id: String,
    val sessionId: String,
    val segmentIndex: Int,
    val baseOffsetMs: Long,
    val durationMs: Long,
    val wallClockStartedAt: Long,
    val segmentOriginElapsedRealtimeNanos: Long,
    val timebase: String,
    val timingQuality: String,
    val journalPath: String,
    // P1 无录音：以下音频字段全部为 null，P2 落地时填充。
    val audioPath: String?,
    val audioSha256: String?,
    val audioState: String?,
    val sampleRate: Int?,
    val firstFramePosition: Long?,
    val audioTimestampNs: Long?,
    val audioClockDomain: String?,
    val driftEstimateMs: Long?,
    val state: String,
)

@Entity(
    tableName = "brief_canvas_pages",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["sessionId", "pageIndex"], unique = true),
    ],    foreignKeys = [
        ForeignKey(
            entity = CaptureSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CanvasPageEntity(
    val id: String,
    val sessionId: String,
    val pageIndex: Int,
    val logicalWidth: Int,
    val logicalHeight: Int,
    @ColumnInfo(defaultValue = "'blank'")
    val backgroundType: String,
    val backgroundRef: String?,
    val backgroundSha256: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "brief_timeline_events",
    primaryKeys = ["eventId"],
    indices = [
        Index(value = ["sessionId", "sequence"], unique = true),
        Index("sessionId"),
    ],    foreignKeys = [
        ForeignKey(
            entity = CaptureSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TimelineEventEntity(
    val eventId: String,
    val sessionId: String,
    val sequence: Long,
    val type: String,
    val pageId: String?,
    val atOffsetMs: Long,
    val payloadJson: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "brief_user_markers",
    primaryKeys = ["id"],
    indices = [Index("sessionId"), Index("type")],    foreignKeys = [
        ForeignKey(
            entity = CaptureSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class UserMarkerEntity(
    val id: String,
    val sessionId: String,
    val eventId: String?,
    val type: String,
    val pageId: String?,
    val atOffsetMs: Long,
    val note: String,
    val resolvedAt: Long?,
    val createdAt: Long,
)

@Entity(
    tableName = "brief_code_anchors",
    primaryKeys = ["id"],
    indices = [Index("sessionId"), Index("relativePath")],    foreignKeys = [
        ForeignKey(
            entity = CaptureSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CodeAnchorEntity(
    val id: String,
    val sessionId: String,
    val type: String,
    val relativePath: String,
    val startLine: Int?,
    val endLine: Int?,
    val contentHash: String,
    val manualLabel: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "brief_revisions",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["briefId", "revision"], unique = true),
    ],
    foreignKeys = [
        ForeignKey(
            entity = WorkBriefEntity::class,
            parentColumns = ["id"],
            childColumns = ["briefId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BriefRevisionEntity(
    val id: String,
    val briefId: String,
    val revision: Int,
    val createdAt: Long,
    val bundleRelativePath: String,
    val bundleSha256: String,
    val manifestSha256: String?,
)

@Entity(
    tableName = "brief_work_journals",
    primaryKeys = ["id"],
    indices = [Index("sessionId"), Index("state")],
    foreignKeys = [
        ForeignKey(
            entity = CaptureSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class WorkJournalEntity(
    val id: String,
    val sessionId: String,
    val pageId: String,
    val journalPath: String,
    val state: String,
    val lastSequence: Long,
    val byteSize: Long,
    val createdAt: Long,
    val updatedAt: Long,
)
