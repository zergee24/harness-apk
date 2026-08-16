package com.harnessapk.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.RemoteRunEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteRunRecoveryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun processDeathDuplicateGapAndSnapshotPreserveDurableRunState() = runBlocking {
        val databaseName = "remote-recovery-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        try {
            var database = open(databaseName)
            val dao = database.remoteDao()
            dao.insertRun(run("run-approval"))
            dao.insertRun(run("run-running"))
            dao.insertRun(run("run-complete"))
            val approvalEvent = approvalEvent(sequence = 1L)
            assertEquals(ReduceResult.APPLIED, RemoteEventReducer(database).apply(approvalEvent))
            database.close()

            database = open(databaseName)
            val recoveredDao = database.remoteDao()
            assertNotNull(recoveredDao.run("run-approval"))
            assertEquals("PENDING", recoveredDao.approval("approval-old")?.status)
            assertEquals(ReduceResult.DUPLICATE, RemoteEventReducer(database).apply(approvalEvent))
            assertEquals(1, recoveredDao.eventsForRun("run-approval").size)
            assertEquals(1, recoveredDao.approvalsForRun("run-approval").size)

            assertEquals(
                ReduceResult.GAP,
                RemoteEventReducer(database).apply(
                    RemoteLogicalEvent(
                        schemaVersion = 1,
                        eventId = "event-out-of-order",
                        hostId = "host-1",
                        deviceId = "device-1",
                        runId = "run-running",
                        sequence = 3L,
                        type = "run.running",
                        payload = null,
                        createdAt = 30L,
                    ),
                ),
            )
            val gapPosition = RoomRemoteSyncState(database, RemoteEventReducer(database))
                .position("host-1", "device-1")
            assertEquals("RECONCILING", recoveredDao.run("run-running")?.status)
            assertEquals("PENDING", recoveredDao.approval("approval-old")?.status)
            assertFalse(isRemoteApprovalActionEnabled("PENDING", gapPosition))

            val snapshot = RemoteRunSnapshotEnvelope(
                hostId = "host-1",
                deviceId = "device-1",
                journalHead = 5L,
                processEpoch = "epoch-2",
                runs = listOf(
                    RemoteRunSnapshot("run-running", "RUNNING", "thread-running", "turn-running", "正在修改", null, null),
                    RemoteRunSnapshot("run-approval", "WAITING_APPROVAL", "thread-approval", "turn-approval", "等待审批", null, null),
                    RemoteRunSnapshot("run-complete", "COMPLETED", "thread-complete", "turn-complete", "已完成", "{}", null),
                ),
                approvals = listOf(
                    RemoteApprovalSnapshot("approval-new", "run-approval", "epoch-2", "PENDING"),
                    RemoteApprovalSnapshot("approval-old", "run-approval", "epoch-1", "PENDING"),
                ),
            )
            RoomRemoteSyncState(database, RemoteEventReducer(database)).applySnapshot(snapshot)

            assertEquals("RUNNING", recoveredDao.run("run-running")?.status)
            assertEquals("WAITING_APPROVAL", recoveredDao.run("run-approval")?.status)
            assertEquals("COMPLETED", recoveredDao.run("run-complete")?.status)
            assertEquals(0, recoveredDao.eventsForRun("run-complete").size)
            assertEquals("PENDING", recoveredDao.approval("approval-new")?.status)
            assertEquals("STALE", recoveredDao.approval("approval-old")?.status)
            val cursor = recoveredDao.cursor("host-1", "device-1")
            assertEquals(5L, cursor?.lastContiguousSequence)
            assertEquals(null, cursor?.gapFromSequence)
            assertEquals("IN_SYNC", cursor?.reconciliationState)
            database.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun open(name: String): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        name,
    ).build()

    private fun run(id: String) = RemoteRunEntity(
        id = id,
        projectId = "project-1",
        projectNameSnapshot = "Harness APK",
        bindingId = "binding-1",
        bindingSnapshotJson = "{}",
        hostId = "host-1", backendId = "codex",
        threadId = null,
        turnId = null,
        objective = "实现 M2",
        status = "RUNNING",
        latestLine = "已开始",
        lastLogicalSequence = 0L,
        startedAt = 10L,
        updatedAt = 10L,
        completedAt = null,
        completionJson = null,
        errorMessage = null,
    )

    private fun approvalEvent(sequence: Long) = RemoteLogicalEvent(
        schemaVersion = 1,
        eventId = "event-approval-old",
        hostId = "host-1",
        deviceId = "device-1",
        runId = "run-approval",
        sequence = sequence,
        type = "run.approval.requested",
        payload = buildJsonObject {
            put("approvalId", "approval-old")
            put("serverRequestId", 7)
            put("processEpoch", "epoch-1")
            put("method", "item/commandExecution/requestApproval")
            put("itemId", "item-1")
            put("actionType", "COMMAND_EXECUTION")
            put("target", "./gradlew test")
            put("commandPreview", "./gradlew test")
            put("details", buildJsonObject { put("cwd", "/workspace") })
            put("availableDecisions", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("accept"))
                add(kotlinx.serialization.json.JsonPrimitive("decline"))
            })
            put("risk", "LOW")
        },
        createdAt = 20L,
    )
}
