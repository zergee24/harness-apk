package com.harnessapk.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.RemoteApprovalEntity
import com.harnessapk.storage.RemoteRunEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteApprovalActionInstrumentedTest {
    @Test
    fun duplicateDeclineKeepsApprovalPendingAndCreatesOneStableOutboxCommand() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dao = database.remoteDao()
        dao.insertRun(run())
        dao.insertApproval(approval())
        val outbox = RemoteCommandOutbox(RoomRemoteCommandStore(dao))
        val coordinator = RemoteApprovalCommandCoordinator(
            outbox,
            RoomApprovalResponseWriter(dao),
            now = { 100L },
        )
        val commandId = notificationApprovalCommandId("approval-1", ApprovalDecision.DENY)

        coordinator.enqueue(requireNotNull(dao.approval("approval-1")), ApprovalDecision.DENY, commandId)
        coordinator.enqueue(requireNotNull(dao.approval("approval-1")), ApprovalDecision.DENY, commandId)

        assertEquals("PENDING", dao.approval("approval-1")?.status)
        assertEquals(commandId, dao.approval("approval-1")?.responseCommandId)
        assertEquals("approval.respond", dao.command(commandId)?.type)
        assertEquals("decline", JsonTestValue.string(dao.command(commandId)?.payloadJson, "decision"))

        assertEquals(
            ReduceResult.APPLIED,
            RemoteEventReducer(database).apply(
                RemoteLogicalEvent(
                    schemaVersion = 1, eventId = "event-resolved", hostId = "host-1", deviceId = "device-1",
                    runId = "run-1", sequence = 1L, type = "run.approval.resolved",
                    payload = kotlinx.serialization.json.buildJsonObject {
                        put("approvalId", "approval-1")
                        put("commandId", commandId)
                        put("status", "RESOLVED")
                    },
                    createdAt = 200L,
                ),
            ),
        )
        assertEquals("RESOLVED", dao.approval("approval-1")?.status)
        assertEquals("SUCCEEDED", dao.command(commandId)?.status)
        database.close()
    }

    private fun run() = RemoteRunEntity(
        id = "run-1", projectId = "project-1", projectNameSnapshot = "Harness APK",
        bindingId = "binding-1", bindingSnapshotJson = "{}", hostId = "host-1", backendId = "codex",
        threadId = "thread-1", turnId = "turn-1", objective = "实现 M2", status = "WAITING_APPROVAL",
        latestLine = "等待审批", lastLogicalSequence = 1L, startedAt = 1L, updatedAt = 1L,
        completedAt = null, completionJson = null, errorMessage = null,
    )

    private fun approval() = RemoteApprovalEntity(
        id = "approval-1", runId = "run-1", logicalEventId = "event-1", serverRequestIdJson = "7",
        processEpoch = "epoch-1", method = "item/commandExecution/requestApproval", itemId = "item-1",
        actionType = "COMMAND_EXECUTION", target = "./gradlew test", commandPreview = "./gradlew test",
        detailsJson = "{}", availableDecisionsJson = "[\"accept\",\"decline\"]", risk = "LOW",
        status = "PENDING", responseCommandId = null, requestedAt = 1L, resolvedAt = null,
    )
}

private object JsonTestValue {
    fun string(raw: String?, key: String): String? = raw?.let {
        kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject[key]?.jsonPrimitive?.content
    }
}
