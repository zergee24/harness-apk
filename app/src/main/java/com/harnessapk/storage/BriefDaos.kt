package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkBriefDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBrief(brief: WorkBriefEntity)

    @Update
    suspend fun updateBrief(brief: WorkBriefEntity)

    @Query("SELECT * FROM work_briefs WHERE id = :id")
    suspend fun getById(id: String): WorkBriefEntity?

    @Query("SELECT * FROM work_briefs WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun observeByProject(projectId: String): Flow<List<WorkBriefEntity>>

    @Query("SELECT * FROM work_briefs WHERE status IN ('PREPARING','ACTIVE','PAUSED','STOPPING','PROCESSING','RECOVERABLE')")
    suspend fun listRecoverable(): List<WorkBriefEntity>

    @Query("UPDATE work_briefs SET status = :status, errorMessage = :errorMessage, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, errorMessage: String?, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: CaptureSessionEntity)

    @Update
    suspend fun updateSession(session: CaptureSessionEntity)

    @Query("SELECT * FROM brief_capture_sessions WHERE briefId = :briefId LIMIT 1")
    suspend fun sessionForBrief(briefId: String): CaptureSessionEntity?

    @Query("SELECT * FROM brief_capture_sessions WHERE id = :id")
    suspend fun sessionById(id: String): CaptureSessionEntity?

    @Query("SELECT * FROM brief_capture_sessions")
    suspend fun allSessionsDebug(): List<CaptureSessionEntity>

    @Query("SELECT * FROM work_briefs")
    suspend fun allBriefsDebug(): List<WorkBriefEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(revision: BriefRevisionEntity)

    @Query("SELECT * FROM brief_revisions WHERE briefId = :briefId ORDER BY revision DESC LIMIT 1")
    suspend fun latestRevision(briefId: String): BriefRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJournal(journal: WorkJournalEntity)

    @Update
    suspend fun updateJournal(journal: WorkJournalEntity)

    @Query("SELECT * FROM brief_work_journals WHERE pageId = :pageId AND state = :state")
    suspend fun journalForPage(pageId: String, state: String): WorkJournalEntity?

    @Upsert
    suspend fun upsertJournal(journal: WorkJournalEntity)
}

@Dao
interface BriefCaptureDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegment(segment: CaptureSegmentEntity)

    @Query("SELECT * FROM brief_capture_segments WHERE sessionId = :sessionId ORDER BY segmentIndex")
    suspend fun segmentsBySession(sessionId: String): List<CaptureSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPage(page: CanvasPageEntity)

    @Query("SELECT * FROM brief_canvas_pages WHERE sessionId = :sessionId ORDER BY pageIndex")
    suspend fun pagesBySession(sessionId: String): List<CanvasPageEntity>

    @Query("SELECT COUNT(*) FROM brief_canvas_pages WHERE sessionId = :sessionId")
    suspend fun pageCount(sessionId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTimelineEvent(event: TimelineEventEntity)

    @Query("SELECT * FROM brief_timeline_events WHERE sessionId = :sessionId ORDER BY sequence")
    suspend fun timelineBySession(sessionId: String): List<TimelineEventEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMarker(marker: UserMarkerEntity)

    @Update
    suspend fun updateMarker(marker: UserMarkerEntity)

    @Query("SELECT * FROM brief_user_markers WHERE sessionId = :sessionId ORDER BY atOffsetMs")
    suspend fun markersBySession(sessionId: String): List<UserMarkerEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAnchor(anchor: CodeAnchorEntity)

    @Query("SELECT * FROM brief_code_anchors WHERE sessionId = :sessionId ORDER BY createdAt")
    suspend fun anchorsBySession(sessionId: String): List<CodeAnchorEntity>
}
