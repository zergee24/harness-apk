package com.harnessapk.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSyncCoordinatorTest {
    @Test
    fun reconnectAfterTenMinutesResumesFromPersistedCursorWithoutClearingRoom() = runBlocking {
        val state = FakeRemoteSyncState(
            cursor = RemoteSyncPosition(41L, null, "IN_SYNC"),
            openRunIds = listOf("run-running", "run-approval"),
        )
        val sender = RecordingSyncSender()
        val coordinator = RemoteSyncCoordinator(state, sender)

        coordinator.resume("host-1", "device-1")

        val command = sender.commands.single()
        assertEquals("sync.resume", command.string("type"))
        assertEquals(41L, command.long("highestContiguousSequence"))
        assertEquals(listOf("run-running", "run-approval"), command.array("openRunIds").map { it.toString().trim('"') })
        assertFalse(state.wasCleared)
    }

    @Test
    fun contiguousOrDuplicateEventAcksOnlyAfterDurableApply() = runBlocking {
        val state = FakeRemoteSyncState(
            applyResults = ArrayDeque(listOf(ReduceResult.APPLIED, ReduceResult.DUPLICATE)),
            cursor = RemoteSyncPosition(5L, null, "IN_SYNC"),
        )
        val sender = RecordingSyncSender()
        val coordinator = RemoteSyncCoordinator(state, sender)
        val event = logicalEvent(sequence = 5L)

        coordinator.onLogicalEvent(event)
        coordinator.onLogicalEvent(event)

        assertEquals(listOf("event.ack", "event.ack"), sender.commands.mapNotNull { it.string("type") })
        assertTrue(state.applyCompletedBeforeSend)
    }

    @Test
    fun outOfOrderEventRequestsSnapshotAndDoesNotAckAcrossGap() = runBlocking {
        val state = FakeRemoteSyncState(
            applyResults = ArrayDeque(listOf(ReduceResult.GAP)),
            cursor = RemoteSyncPosition(4L, 5L, "GAP"),
            openRunIds = listOf("run-1"),
        )
        val sender = RecordingSyncSender()
        val coordinator = RemoteSyncCoordinator(state, sender)

        coordinator.onLogicalEvent(logicalEvent(sequence = 8L))

        assertEquals(listOf("run.snapshot"), sender.commands.mapNotNull { it.string("type") })
        assertFalse(sender.commands.any { it.string("type") == "event.ack" })
    }

    @Test
    fun snapshotReconcilesRunningApprovalCompletedAndOldEpochApproval() = runBlocking {
        val state = FakeRemoteSyncState()
        val coordinator = RemoteSyncCoordinator(state, RecordingSyncSender())
        val snapshot = RemoteRunSnapshotEnvelope(
            hostId = "host-1",
            deviceId = "device-1",
            journalHead = 50L,
            processEpoch = "epoch-2",
            runs = listOf(
                RemoteRunSnapshot("run-running", "RUNNING", "thread-1", "turn-1", "正在修改", null, null),
                RemoteRunSnapshot("run-approval", "WAITING_APPROVAL", "thread-2", "turn-2", "等待审批", null, null),
                RemoteRunSnapshot("run-complete", "COMPLETED", "thread-3", "turn-3", "已完成", "{}", null),
            ),
            approvals = listOf(
                RemoteApprovalSnapshot("approval-new", "run-approval", "epoch-2", "PENDING"),
                RemoteApprovalSnapshot("approval-old", "run-approval", "epoch-1", "STALE"),
            ),
        )

        coordinator.onSnapshot(snapshot)

        assertEquals(snapshot, state.appliedSnapshot)
    }

    @Test
    fun gapDisablesPendingApprovalUntilSnapshotReconciliationCompletes() {
        assertFalse(
            isRemoteApprovalActionEnabled(
                approvalStatus = "PENDING",
                position = RemoteSyncPosition(7L, 8L, "GAP"),
            ),
        )
        assertTrue(
            isRemoteApprovalActionEnabled(
                approvalStatus = "PENDING",
                position = RemoteSyncPosition(9L, null, "IN_SYNC"),
            ),
        )
    }

    @Test
    fun snapshotCompletionIsRedactedBeforeRoomCanPersistIt() {
        val snapshot = parseRemoteRunSnapshot(
            Json.parseToJsonElement(
                """
                {
                  "hostId":"host-1","deviceId":"device-1","journalHead":1,"processEpoch":"epoch-1",
                  "runs":[{
                    "runId":"run-1","status":"COMPLETED","latestLine":"token=url-secret",
                    "completion":{"summary":"done","apiKey":"json-secret","changedFiles":[],"tests":[],"unresolved":[],"completedAt":1}
                  }],
                  "approvals":[]
                }
                """.trimIndent(),
            ).jsonObject,
        )

        val run = snapshot.runs.single()
        assertFalse(run.latestLine.contains("url-secret"))
        assertFalse(requireNotNull(run.completionJson).contains("json-secret"))
    }

    private fun logicalEvent(sequence: Long) = RemoteLogicalEvent(
        schemaVersion = 1,
        eventId = "event-$sequence",
        hostId = "host-1",
        deviceId = "device-1",
        runId = "run-1",
        sequence = sequence,
        type = "run.running",
        payload = null,
        createdAt = 1L,
    )
}

private class RecordingSyncSender : RemoteSyncSender {
    val commands = mutableListOf<JsonObject>()
    override fun send(command: JsonObject): Boolean {
        commands += command
        return true
    }
}

private class FakeRemoteSyncState(
    private val applyResults: ArrayDeque<ReduceResult> = ArrayDeque(),
    private val cursor: RemoteSyncPosition = RemoteSyncPosition(0L, null, "IN_SYNC"),
    private val openRunIds: List<String> = emptyList(),
) : RemoteSyncState {
    var wasCleared = false
    var applyCompletedBeforeSend = false
    var appliedSnapshot: RemoteRunSnapshotEnvelope? = null

    override suspend fun position(hostId: String, deviceId: String): RemoteSyncPosition = cursor
    override suspend fun openRunIds(hostId: String): List<String> = openRunIds
    override suspend fun apply(event: RemoteLogicalEvent): ReduceResult {
        applyCompletedBeforeSend = true
        return applyResults.removeFirstOrNull() ?: ReduceResult.APPLIED
    }
    override suspend fun applySnapshot(snapshot: RemoteRunSnapshotEnvelope) {
        appliedSnapshot = snapshot
    }
}
