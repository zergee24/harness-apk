package com.harnessapk.remote

import com.harnessapk.storage.RemoteApprovalEntity
import com.harnessapk.storage.RemoteCommandOutboxEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RemoteNotificationCoordinatorTest {
    @Test
    fun duplicateNotificationDeclineCreatesOneOutboxCommand() = runBlocking {
        val store = RecordingCommandStore()
        val coordinator = RemoteApprovalCommandCoordinator(
            outbox = RemoteCommandOutbox(store),
            approvalWriter = NoOpApprovalResponseWriter,
            now = { 100L },
        )
        val approval = approval(risk = "LOW")
        val commandId = notificationApprovalCommandId(approval.id, ApprovalDecision.DENY)

        coordinator.enqueue(approval, ApprovalDecision.DENY, commandId)
        coordinator.enqueue(approval, ApprovalDecision.DENY, commandId)

        assertEquals(1, store.insertCount)
        assertEquals("decline", store.rows.single().payloadJson.substringAfter("\"decision\":\"").substringBefore('"'))
    }

    @Test
    fun highRiskPlanContainsViewAndDeclineButNoAllow() {
        val plan = RemoteNotificationCoordinator().approvalPlan(
            runId = "run-1",
            approvalId = "approval-1",
            risk = RemoteApprovalRisk.HIGH,
            summary = "sudo rm -rf build",
        )

        assertEquals(setOf(RemoteNotificationActionKind.VIEW, RemoteNotificationActionKind.DECLINE), plan.actions.mapTo(mutableSetOf()) { it.kind })
        assertFalse(plan.actions.any { it.kind == RemoteNotificationActionKind.ALLOW_ONCE })
    }

    private fun approval(risk: String) = RemoteApprovalEntity(
        id = "approval-1", runId = "run-1", logicalEventId = "event-1",
        serverRequestIdJson = "7", processEpoch = "epoch-1",
        method = "item/commandExecution/requestApproval", itemId = "item-1",
        actionType = "COMMAND_EXECUTION", target = "./gradlew test", commandPreview = "./gradlew test",
        detailsJson = "{}", availableDecisionsJson = "[\"accept\",\"decline\"]", risk = risk,
        status = "PENDING", responseCommandId = null, requestedAt = 1L, resolvedAt = null,
    )
}

private class RecordingCommandStore : RemoteCommandStore {
    val rows = mutableListOf<RemoteCommandOutboxEntity>()
    var insertCount = 0
    override suspend fun insert(command: RemoteCommandOutboxEntity) { rows += command; insertCount++ }
    override suspend fun find(commandId: String): RemoteCommandOutboxEntity? = rows.firstOrNull { it.commandId == commandId }
    override suspend fun upsert(command: RemoteCommandOutboxEntity) {
        rows.removeAll { it.commandId == command.commandId }
        rows += command
    }
    override suspend fun retryable(now: Long): List<RemoteCommandOutboxEntity> = rows
}
