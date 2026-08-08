package com.harnessapk.remote

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
        summary: String,
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
            summary = redactRemoteSensitiveText(summary),
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

private fun stableNotificationId(identity: String): Int = identity.hashCode() and Int.MAX_VALUE
