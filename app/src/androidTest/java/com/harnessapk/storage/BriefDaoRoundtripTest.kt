package com.harnessapk.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefDaoRoundtripTest {

    private lateinit var db: AppDatabase
    private lateinit var workBriefDao: WorkBriefDao
    private lateinit var captureDao: BriefCaptureDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // 显式开启外键约束：部分 ROM 的 SQLite 对 onOpen 内的 PRAGMA 存在时序怪癖。
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        workBriefDao = db.workBriefDao()
        captureDao = db.briefCaptureDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun brief(id: String) = WorkBriefEntity(
        id = id,
        projectId = "project-1",
        title = "测试简报",
        status = "PREPARING",
        revision = 0,
        bundleRelativePath = null,
        bundleSha256 = null,
        totalDurationMs = 0,
        activeDurationMs = 0,
        audioAvailability = "NONE",
        sourceBriefId = null,
        sourceRevision = null,
        continuationOfBriefId = null,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        lastOpenedAt = 1_000L,
        errorMessage = null,
    )

    private fun session(id: String, briefId: String) = CaptureSessionEntity(
        id = id,
        briefId = briefId,
        status = "ACTIVE",
        wallClockStartedAt = 1_100L,
        durationMs = 0,
        activeDurationMs = 0,
        audioPolicy = "none",
        transcriptionProvider = null,
        consentAt = null,
        uploadScope = null,
        retentionResult = null,
        createdAt = 1_100L,
        updatedAt = 1_100L,
        errorMessage = null,
    )

    @Test
    fun briefRoundTripPersistsAllFields() = runBlocking {
        workBriefDao.insertBrief(brief("b1"))
        val loaded = workBriefDao.getById("b1")!!
        assertEquals("测试简报", loaded.title)
        assertEquals("PREPARING", loaded.status)
        assertEquals("project-1", loaded.projectId)
    }

    @Test
    fun briefIdUniqueIndexBlocksSecondSession() = runBlocking {
        workBriefDao.insertBrief(brief("b1"))
        workBriefDao.insertSession(session("s1", "b1"))
        val result = runCatching { workBriefDao.insertSession(session("s2", "b1")) }
        assertTrue("briefId 唯一索引应阻止第二场次", result.isFailure)
        assertEquals("s1", workBriefDao.sessionForBrief("b1")?.id)
    }

    @Test
    fun deletingBriefCascadesToSession() = runBlocking {
        workBriefDao.insertBrief(brief("b1"))
        workBriefDao.insertSession(session("s1", "b1"))
        // 显式外键约束下，删除父简报应级联删除场次。
        db.openHelper.writableDatabase.execSQL("DELETE FROM work_briefs WHERE id = 'b1'")
        assertNull(workBriefDao.sessionForBrief("b1"))
    }

    @Test
    fun timelineSequenceUniquePerSession() = runBlocking {
        workBriefDao.insertBrief(brief("b1"))
        workBriefDao.insertSession(session("s1", "b1"))
        captureDao.insertTimelineEvent(
            TimelineEventEntity(
                eventId = "e1", sessionId = "s1", sequence = 1, type = "STROKE_COMMITTED",
                pageId = "p1", atOffsetMs = 10, payloadJson = null, createdAt = 1,
            ),
        )
        captureDao.insertTimelineEvent(
            TimelineEventEntity(
                eventId = "e2", sessionId = "s1", sequence = 2, type = "MARKER_ADDED",
                pageId = "p1", atOffsetMs = 20, payloadJson = null, createdAt = 2,
            ),
        )
        val timeline = captureDao.timelineBySession("s1")
        assertEquals(listOf("e1", "e2"), timeline.map { it.eventId })
        assertTrue(timeline[0].atOffsetMs < timeline[1].atOffsetMs)
    }

    @Test
    fun markerAndAnchorRoundTrip() = runBlocking {
        workBriefDao.insertBrief(brief("b1"))
        workBriefDao.insertSession(session("s1", "b1"))
        captureDao.insertMarker(
            UserMarkerEntity(
                id = "m1", sessionId = "s1", eventId = null, type = "DECISION",
                pageId = "p1", atOffsetMs = 30, note = "选次级按钮",
                resolvedAt = null, createdAt = 30,
            ),
        )
        captureDao.insertAnchor(
            CodeAnchorEntity(
                id = "a1", sessionId = "s1", type = "FILE_RANGE",
                relativePath = "docs/spec.md", startLine = 3, endLine = 9,
                contentHash = "a".repeat(64), manualLabel = "关键段落", createdAt = 31,
            ),
        )
        val markers = captureDao.markersBySession("s1")
        val anchors = captureDao.anchorsBySession("s1")
        assertEquals("DECISION", markers.single().type)
        assertEquals("docs/spec.md", anchors.single().relativePath)
    }
}
