package com.harnessapk.remote

import com.harnessapk.storage.RemoteApprovalEntity
import com.harnessapk.storage.RemoteCommandOutboxEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RemoteNotificationCoordinatorTest {
    @Test
    fun persistedOpenRunKeepsConnectionAliveAfterProcessStateIsLost() {
        val disconnectedState = RemoteUiState()

        assertEquals(
            true,
            shouldKeepRemoteConnectionAlive(
                state = disconnectedState,
                openRunStatuses = listOf(RemoteRunStatus.WAITING_APPROVAL.name),
            ),
        )
        assertEquals(
            false,
            shouldKeepRemoteConnectionAlive(
                state = disconnectedState,
                openRunStatuses = emptyList(),
            ),
        )
    }

    @Test
    fun persistedPendingApprovalIsRebuiltIntoSafeNotification() {
        val plans = pendingApprovalNotificationPlans(listOf(approval(risk = "LOW")))

        assertEquals(1, plans.size)
        assertEquals("approval-1", plans.single().approvalId)
        assertEquals(
            setOf(RemoteNotificationActionKind.VIEW, RemoteNotificationActionKind.DECLINE),
            plans.single().actions.mapTo(mutableSetOf()) { it.kind },
        )
        assertEquals(
            emptyList<RemoteNotificationPlan>(),
            pendingApprovalNotificationPlans(
                listOf(approval(risk = "LOW").copy(responseCommandId = "approval:approval-1:decline")),
            ),
        )
    }

    @Test
    fun onlineServiceStartFlushesOutboxButConnectingServiceWaitsForConnectedCallback() {
        assertEquals(true, shouldFlushRemoteOutboxOnServiceStart(RemoteConnectionStatus.CONNECTED))
        assertEquals(false, shouldFlushRemoteOutboxOnServiceStart(RemoteConnectionStatus.CONNECTING))
        assertEquals(false, shouldFlushRemoteOutboxOnServiceStart(RemoteConnectionStatus.DISCONNECTED))
    }

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
        )

        assertEquals(setOf(RemoteNotificationActionKind.VIEW, RemoteNotificationActionKind.DECLINE), plan.actions.mapTo(mutableSetOf()) { it.kind })
        assertFalse(plan.actions.any { it.kind == RemoteNotificationActionKind.ALLOW_ONCE })
    }

    @Test
    fun lowRiskPlanStillRequiresUnlockedRunDetailForAllow() {
        val plan = RemoteNotificationCoordinator().approvalPlan(
            runId = "run-1",
            approvalId = "approval-1",
            risk = RemoteApprovalRisk.LOW,
        )

        assertEquals(setOf(RemoteNotificationActionKind.VIEW, RemoteNotificationActionKind.DECLINE), plan.actions.mapTo(mutableSetOf()) { it.kind })
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
