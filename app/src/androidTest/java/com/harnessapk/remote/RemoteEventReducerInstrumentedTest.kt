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
import org.junit.Assert.assertFalse
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

    @Test
    fun approvalSecretsAreRedactedBeforeRoomPersistence() = runBlocking {
        val db = database()
        db.remoteDao().insertRun(run())
        val event = approvalEvent(sequence = 1L).copy(
            payload = buildJsonObject {
                put("approvalId", "approval-1")
                put("serverRequestId", 7)
                put("processEpoch", "epoch-1")
                put("method", "item/commandExecution/requestApproval")
                put("itemId", "item-1")
                put("actionType", "COMMAND_EXECUTION")
                put("target", "curl https://api.example.com?access_token=url-secret")
                put("commandPreview", "Authorization: Bearer header-secret")
                put("details", buildJsonObject { put("apiKey", "json-secret") })
                put("availableDecisions", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("accept"))
                    add(kotlinx.serialization.json.JsonPrimitive("decline"))
                })
                put("risk", "LOW")
            },
        )

        assertEquals(ReduceResult.APPLIED, RemoteEventReducer(db).apply(event))

        val stored = requireNotNull(db.remoteDao().approval("approval-1"))
        val persisted = listOf(stored.target, stored.commandPreview, stored.detailsJson).joinToString()
        listOf("url-secret", "header-secret", "json-secret").forEach { secret ->
            assertFalse(persisted.contains(secret))
        }
        db.close()
    }

    @Test
    fun steerAndInterruptOnlyBecomeTerminalFromLogicalResults() = runBlocking {
        val db = database()
        db.remoteDao().insertRun(run())
        val outbox = RemoteCommandOutbox(RoomRemoteCommandStore(db.remoteDao()))
        outbox.enqueue(
            commandId = "steer-1", runId = "run-1", type = "run.steer",
            payload = RemoteM2Command.Steer("steer-1", "run-1", "turn-1", "补充测试").toJson(),
            now = 10L,
        )
        outbox.enqueue(
            commandId = "interrupt-1", runId = "run-1", type = "run.interrupt",
            payload = RemoteM2Command.Interrupt("interrupt-1", "run-1", "turn-1").toJson(),
            now = 10L,
        )
        val reducer = RemoteEventReducer(db)

        reducer.apply(controlEvent(1L, "run.steered", "steer-1"))
        reducer.apply(controlEvent(2L, "run.interrupt.accepted", "interrupt-1"))

        assertEquals("SUCCEEDED", db.remoteDao().command("steer-1")?.status)
        assertEquals("ACCEPTED", db.remoteDao().command("interrupt-1")?.status)
        assertEquals("RUNNING", db.remoteDao().run("run-1")?.status)

        reducer.apply(controlEvent(3L, "run.cancelled", null))
        assertEquals("SUCCEEDED", db.remoteDao().command("interrupt-1")?.status)
        assertEquals("CANCELLED", db.remoteDao().run("run-1")?.status)
        db.close()
    }

    @Test
    fun timelineSecretsAreRedactedBeforeRoomPersistence() = runBlocking {
        val db = database()
        db.remoteDao().insertRun(run())
        val event = controlEvent(1L, "run.timeline", null).copy(
            payload = buildJsonObject {
                put("presentationKind", "STATUS")
                put("detail", "curl https://example.invalid?token=url-secret")
                put("apiKey", "json-secret")
            },
        )

        RemoteEventReducer(db).apply(event)

        val persisted = db.remoteDao().eventsForRun("run-1").single().payloadJson
        assertFalse(persisted.contains("url-secret"))
        assertFalse(persisted.contains("json-secret"))
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

    private fun controlEvent(sequence: Long, type: String, commandId: String?) = RemoteLogicalEvent(
        schemaVersion = 1,
        eventId = "event-$sequence",
        hostId = "host-1",
        deviceId = "device-1",
        runId = "run-1",
        sequence = sequence,
        type = type,
        payload = buildJsonObject {
            commandId?.let { put("commandId", it) }
            put("latestLine", if (type == "run.cancelled") "任务已停止" else "命令已确认")
        },
        createdAt = 20L + sequence,
    )
}
