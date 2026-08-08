package com.harnessapk.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.RemoteRunEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteEventReducerInstrumentedTest {
    @Test
    fun sameLogicalEventDoesNotDuplicateTimelineOrApproval() = runBlocking {
        val db = database()
        db.remoteDao().insertRun(run())
        val reducer = RemoteEventReducer(db)
        val event = approvalEvent(sequence = 1L)

        assertEquals(ReduceResult.APPLIED, reducer.apply(event))
        assertEquals(ReduceResult.DUPLICATE, reducer.apply(event))

        assertEquals(1, db.remoteDao().eventsForRun("run-1").size)
        assertEquals(1, db.remoteDao().approvalsForRun("run-1").size)
        assertEquals("WAITING_APPROVAL", db.remoteDao().run("run-1")?.status)
        assertEquals(1L, db.remoteDao().cursor("host-1", "device-1")?.lastContiguousSequence)
        db.close()
    }

    @Test
    fun gapMarksOpenRunsReconcilingWithoutAdvancingCursor() = runBlocking {
        val db = database()
        db.remoteDao().insertRun(run())
        val reducer = RemoteEventReducer(db)

        assertEquals(ReduceResult.GAP, reducer.apply(approvalEvent(sequence = 2L)))

        assertEquals("RECONCILING", db.remoteDao().run("run-1")?.status)
        assertEquals(0, db.remoteDao().eventsForRun("run-1").size)
        val cursor = db.remoteDao().cursor("host-1", "device-1")
        assertEquals(0L, cursor?.lastContiguousSequence)
        assertEquals(1L, cursor?.gapFromSequence)
        assertEquals("GAP", cursor?.reconciliationState)
        assertNull(db.remoteDao().approval("approval-1"))
        db.close()
    }

    private fun database(): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    private fun run() = RemoteRunEntity(
        id = "run-1",
        projectId = "project-1",
        projectNameSnapshot = "Project One",
        bindingId = "binding-1",
        bindingSnapshotJson = "{}",
        hostId = "host-1",
        threadId = "thread-1",
        turnId = "turn-1",
        objective = "Implement M2",
        status = "RUNNING",
        latestLine = "Started",
        lastLogicalSequence = 0L,
        startedAt = 10L,
        updatedAt = 10L,
        completedAt = null,
        completionJson = null,
        errorMessage = null,
    )

    private fun approvalEvent(sequence: Long) = RemoteLogicalEvent(
        schemaVersion = 1,
        eventId = "event-approval-1",
        hostId = "host-1",
        deviceId = "device-1",
        runId = "run-1",
        sequence = sequence,
        type = "run.approval.requested",
        payload = buildJsonObject {
            put("approvalId", "approval-1")
            put("serverRequestId", 7)
            put("processEpoch", "epoch-1")
            put("method", "item/commandExecution/requestApproval")
            put("itemId", "item-1")
            put("actionType", "COMMAND_EXECUTION")
            put("target", "./gradlew test")
            put("commandPreview", "./gradlew test")
            put("details", buildJsonObject { put("cwd", "/workspace") })
            put("availableDecisions", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("accept"))
                add(kotlinx.serialization.json.JsonPrimitive("decline"))
            })
            put("risk", "LOW")
        },
        createdAt = 20L,
    )
}
