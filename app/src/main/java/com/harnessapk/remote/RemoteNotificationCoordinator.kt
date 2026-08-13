package com.harnessapk.remote

import com.harnessapk.storage.RemoteApprovalEntity

enum class RemoteNotificationActionKind {
    VIEW,
    ALLOW_ONCE,
    DECLINE,
}

data class RemoteNotificationAction(
    val kind: RemoteNotificationActionKind,
    val label: String,
    val commandId: String?,
)

data class RemoteNotificationPlan(
    val notificationId: Int,
    val runId: String,
    val approvalId: String?,
    val title: String,
    val summary: String,
    val actions: List<RemoteNotificationAction>,
)

class RemoteNotificationCoordinator {
    fun approvalPlan(
        runId: String,
        approvalId: String,
        risk: RemoteApprovalRisk,
    ): RemoteNotificationPlan {
        val actions = buildList {
            add(RemoteNotificationAction(RemoteNotificationActionKind.VIEW, "查看", null))
            if (remoteApprovalPolicy(risk, deviceLocked = false).allowFromNotification) {
                add(
                    RemoteNotificationAction(
                        RemoteNotificationActionKind.ALLOW_ONCE,
                        "允许一次",
                        notificationApprovalCommandId(approvalId, ApprovalDecision.ALLOW_ONCE),
                    ),
                )
            }
            add(
                RemoteNotificationAction(
                    RemoteNotificationActionKind.DECLINE,
                    "拒绝",
                    notificationApprovalCommandId(approvalId, ApprovalDecision.DENY),
                ),
            )
        }
        return RemoteNotificationPlan(
            notificationId = stableNotificationId("approval:$approvalId"),
            runId = runId,
            approvalId = approvalId,
            title = if (risk == RemoteApprovalRisk.HIGH) "高风险操作等待确认" else "Codex 等待审批",
            summary = "打开任务详情查看操作范围",
            actions = actions,
        )
    }

    fun runPlan(runId: String, title: String, summary: String): RemoteNotificationPlan =
        RemoteNotificationPlan(
            notificationId = stableNotificationId("run:$runId"),
            runId = runId,
            approvalId = null,
            title = title,
            summary = redactRemoteSensitiveText(summary),
            actions = listOf(RemoteNotificationAction(RemoteNotificationActionKind.VIEW, "查看", null)),
        )
}

internal fun shouldKeepRemoteConnectionAlive(
    state: RemoteUiState,
    openRunStatuses: List<String>,
): Boolean = state.isWorking || state.activeTurnId != null || openRunStatuses.isNotEmpty()

internal fun pendingApprovalNotificationPlans(
    approvals: List<RemoteApprovalEntity>,
    coordinator: RemoteNotificationCoordinator = RemoteNotificationCoordinator(),
): List<RemoteNotificationPlan> = approvals
    .filter { it.status == "PENDING" && it.responseCommandId == null }
    .map { approval ->
        coordinator.approvalPlan(
            runId = approval.runId,
            approvalId = approval.id,
            risk = parseRemoteApprovalRisk(approval.risk),
        )
    }

internal fun shouldFlushRemoteOutboxOnServiceStart(status: RemoteConnectionStatus): Boolean =
    status == RemoteConnectionStatus.CONNECTED

private fun stableNotificationId(identity: String): Int = identity.hashCode() and Int.MAX_VALUE
