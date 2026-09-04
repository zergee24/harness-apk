package com.harnessapk.workbrief

import androidx.room.withTransaction
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.BriefRevisionEntity
import com.harnessapk.storage.CanvasPageEntity
import com.harnessapk.storage.CaptureSessionEntity
import com.harnessapk.storage.CodeAnchorEntity
import com.harnessapk.storage.TimelineEventEntity
import com.harnessapk.storage.UserMarkerEntity
import com.harnessapk.storage.WorkBriefEntity
import java.util.UUID

/**
 * 工作简报 P1 仓库：创建/状态推进/标记/锚点/时间轴的读写封装。
 * 状态合法性由 [BriefStateMachine] 把关；事务在 [AppDatabase.withTransaction] 内完成。
 */
class WorkBriefRepository(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    private val workBriefDao get() = db.workBriefDao()
    private val captureDao get() = db.briefCaptureDao()

    /** 新建简报：Brief(DRAFT) + 场次(PREPARING) + 第 1 页（空白底图）同事务创建。 */
    suspend fun createBrief(projectId: String, title: String, logicalWidth: Int, logicalHeight: Int): String {
        val now = clock()
        val briefId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()
        db.withTransaction {
            workBriefDao.insertBrief(
                WorkBriefEntity(
                    id = briefId,
                    projectId = projectId,
                    title = title.trim().ifBlank { "未命名简报" },
                    status = WorkBriefStatus.DRAFT.name,
                    revision = 0,
                    bundleRelativePath = null,
                    bundleSha256 = null,
                    totalDurationMs = 0,
                    activeDurationMs = 0,
                    audioAvailability = "DISABLED",
                    sourceBriefId = null,
                    sourceRevision = null,
                    continuationOfBriefId = null,
                    createdAt = now,
                    updatedAt = now,
                    lastOpenedAt = now,
                    errorMessage = null,
                ),
            )
            workBriefDao.insertSession(
                CaptureSessionEntity(
                    id = sessionId,
                    briefId = briefId,
                    status = CaptureSessionStatus.PREPARING.name,
                    wallClockStartedAt = now,
                    durationMs = 0,
                    activeDurationMs = 0,
                    audioPolicy = "none",
                    transcriptionProvider = null,
                    consentAt = null,
                    uploadScope = null,
                    retentionResult = null,
                    createdAt = now,
                    updatedAt = now,
                    errorMessage = null,
                ),
            )
            captureDao.insertPage(
                CanvasPageEntity(
                    id = pageId,
                    sessionId = sessionId,
                    pageIndex = 0,
                    logicalWidth = logicalWidth,
                    logicalHeight = logicalHeight,
                    backgroundType = "blank",
                    backgroundRef = null,
                    backgroundSha256 = null,
                    createdAt = now,
                ),
            )
        }
        return briefId
    }

    suspend fun startCapture(briefId: String) {
        val now = clock()
        val brief = requireBrief(briefId)
        val session = requireSession(briefId)
        BriefStateMachine.requireBriefTransition(
            WorkBriefStatus.valueOf(brief.status),
            WorkBriefStatus.CAPTURING,
        )
        BriefStateMachine.requireSessionTransition(
            CaptureSessionStatus.valueOf(session.status),
            CaptureSessionStatus.ACTIVE,
        )
        db.withTransaction {
            workBriefDao.updateBrief(brief.copy(status = WorkBriefStatus.CAPTURING.name, updatedAt = now, lastOpenedAt = now))
            workBriefDao.updateSession(session.copy(status = CaptureSessionStatus.ACTIVE.name, updatedAt = now))
        }
        appendTimeline(session.id, "CAPTURE_STARTED", pageId = null, atOffsetMs = 0, now = now)
    }

    suspend fun pauseCapture(briefId: String, nowMs: Long = clock()) {
        // §8.1/8.2：PAUSED 是场次状态，简报保持 CAPTURING 不变。
        val now = clock()
        val brief = requireBrief(briefId)
        val session = requireSession(briefId)
        BriefStateMachine.requireSessionTransition(
            CaptureSessionStatus.valueOf(session.status),
            CaptureSessionStatus.PAUSED,
        )
        db.withTransaction {
            workBriefDao.updateBrief(brief.copy(updatedAt = now))
            workBriefDao.updateSession(
                session.copy(
                    status = CaptureSessionStatus.PAUSED.name,
                    activeDurationMs = session.activeDurationMs,
                    updatedAt = now,
                ),
            )
        }
        appendTimeline(session.id, "CAPTURE_PAUSED", pageId = null, atOffsetMs = nowMs - session.wallClockStartedAt, now = now)
    }

    suspend fun resumeCapture(briefId: String) {
        val now = clock()
        val brief = requireBrief(briefId)
        val session = requireSession(briefId)
        BriefStateMachine.requireSessionTransition(
            CaptureSessionStatus.valueOf(session.status),
            CaptureSessionStatus.ACTIVE,
        )
        db.withTransaction {
            workBriefDao.updateBrief(brief.copy(status = WorkBriefStatus.CAPTURING.name, updatedAt = now))
            workBriefDao.updateSession(session.copy(status = CaptureSessionStatus.ACTIVE.name, updatedAt = now))
        }
        appendTimeline(session.id, "CAPTURE_RESUMED", pageId = null, atOffsetMs = 0, now = now)
    }

    suspend fun stopCapture(briefId: String, nowMs: Long = clock()) {
        val now = clock()
        val brief = requireBrief(briefId)
        val session = requireSession(briefId)
        BriefStateMachine.requireBriefTransition(WorkBriefStatus.valueOf(brief.status), WorkBriefStatus.PROCESSING)
        BriefStateMachine.requireSessionTransition(
            CaptureSessionStatus.valueOf(session.status),
            CaptureSessionStatus.STOPPING,
        )
        db.withTransaction {
            workBriefDao.updateBrief(brief.copy(status = WorkBriefStatus.PROCESSING.name, updatedAt = now))
            workBriefDao.updateSession(session.copy(status = CaptureSessionStatus.STOPPING.name, updatedAt = now))
        }
        appendTimeline(session.id, "CAPTURE_STOPPED", pageId = null, atOffsetMs = nowMs - session.wallClockStartedAt, now = now)
    }

    /** PROCESSING -> READY：bundle（Task 8）落盘并校验后调用。 */
    suspend fun markReady(briefId: String, bundleRelativePath: String, bundleSha256: String) {
        val now = clock()
        val brief = requireBrief(briefId)
        val session = requireSession(briefId)
        BriefStateMachine.requireBriefTransition(WorkBriefStatus.valueOf(brief.status), WorkBriefStatus.READY)
        BriefStateMachine.requireSessionTransition(
            CaptureSessionStatus.valueOf(session.status),
            CaptureSessionStatus.SEALED,
        )
        db.withTransaction {
            val newRevision = brief.revision + 1
            workBriefDao.updateBrief(
                brief.copy(
                    status = WorkBriefStatus.READY.name,
                    revision = newRevision,
                    bundleRelativePath = bundleRelativePath,
                    bundleSha256 = bundleSha256,
                    updatedAt = now,
                    lastOpenedAt = now,
                ),
            )
            workBriefDao.insertRevision(
                BriefRevisionEntity(
                    id = UUID.randomUUID().toString(),
                    briefId = briefId,
                    revision = newRevision,
                    createdAt = now,
                    bundleRelativePath = bundleRelativePath,
                    bundleSha256 = bundleSha256,
                    manifestSha256 = null,
                ),
            )
            workBriefDao.updateSession(session.copy(status = CaptureSessionStatus.SEALED.name, updatedAt = now))
        }
    }

    suspend fun addMarker(briefId: String, type: UserMarkerType, pageId: String, atOffsetMs: Long, note: String): String {
        android.util.Log.w("BriefRepoEntry", "addMarker entry briefId=$briefId pageId=$pageId")
        val now = clock()
        val session = requireSession(briefId)
        val id = UUID.randomUUID().toString()
        captureDao.insertMarker(
            UserMarkerEntity(
                id = id,
                sessionId = session.id,
                eventId = null,
                type = type.name,
                pageId = pageId,
                atOffsetMs = atOffsetMs,
                note = note,
                resolvedAt = null,
                createdAt = now,
            ),
        )
        appendTimeline(session.id, "MARKER_ADDED", pageId = pageId, atOffsetMs = atOffsetMs, now = now)
        return id
    }

    suspend fun addFileAnchor(
        briefId: String,
        type: String,
        relativePath: String,
        startLine: Int?,
        endLine: Int?,
        contentHash: String,
        manualLabel: String?,
    ): String {
        val now = clock()
        val session = requireSession(briefId)
        val id = UUID.randomUUID().toString()
        captureDao.insertAnchor(
            CodeAnchorEntity(
                id = id,
                sessionId = session.id,
                type = type,
                relativePath = relativePath,
                startLine = startLine,
                endLine = endLine,
                contentHash = contentHash,
                manualLabel = manualLabel,
                createdAt = now,
            ),
        )
        appendTimeline(session.id, "ANCHOR_ADDED", pageId = null, atOffsetMs = 0, now = now)
        return id
    }

    suspend fun appendTimeline(sessionId: String, type: String, pageId: String?, atOffsetMs: Long, now: Long = clock()): Long {
        val session = requireSession(sessionId)
        val sequence = captureDao.timelineBySession(sessionId).maxOfOrNull { it.sequence }?.plus(1) ?: 1
        captureDao.insertTimelineEvent(
            TimelineEventEntity(
                eventId = UUID.randomUUID().toString(),
                sessionId = sessionId,
                sequence = sequence,
                type = type,
                pageId = pageId,
                atOffsetMs = atOffsetMs,
                payloadJson = null,
                createdAt = now,
            ),
        )
        return sequence
    }

    private suspend fun requireBrief(briefId: String): WorkBriefEntity =
        workBriefDao.getById(briefId) ?: error("简报不存在：$briefId")

    private suspend fun requireSession(briefId: String): CaptureSessionEntity =
        workBriefDao.sessionForBrief(briefId) ?: run {
            val all = workBriefDao.allSessionsDebug()
            val briefs = workBriefDao.allBriefsDebug()
            android.util.Log.e(
                "WorkBriefRepo",
                "session missing for brief=$briefId dbPath=${db.openHelper.writableDatabase.path}; sessions=" +
                    all.joinToString { "${it.id}(brief=${it.briefId})" } +
                    "; briefs=" + briefs.joinToString { "${it.id}(${it.status})" },
            )
            error("简报 $briefId 没有记录场次")
        }
}
