package com.harnessapk.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.harnessapk.HarnessApkApplication
import kotlinx.coroutines.launch

class RemoteNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_APPROVAL) return
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID)?.takeIf(String::isNotBlank) ?: return
        val commandId = intent.getStringExtra(EXTRA_COMMAND_ID)?.takeIf(String::isNotBlank) ?: return
        val decision = intent.getStringExtra(EXTRA_DECISION)?.let { value ->
            ApprovalDecision.entries.firstOrNull { it.name == value }
        } ?: return
        val pendingResult = goAsync()
        val app = context.applicationContext as HarnessApkApplication
        app.container.applicationScope.launch {
            try {
                val approval = app.container.database.remoteDao().approval(approvalId) ?: return@launch
                val risk = parseRemoteApprovalRisk(approval.risk)
                if (decision == ApprovalDecision.ALLOW_ONCE && !remoteApprovalPolicy(risk, deviceLocked = false).allowFromNotification) {
                    return@launch
                }
                app.container.remoteApprovalCommandCoordinator.enqueue(approval, decision, commandId)
                RemoteConnectionService.start(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_APPROVAL = "com.harnessapk.remote.APPROVAL_ACTION"
        const val EXTRA_RUN_ID = "remote_run_id"
        const val EXTRA_APPROVAL_ID = "remote_approval_id"
        const val EXTRA_COMMAND_ID = "remote_command_id"
        const val EXTRA_DECISION = "remote_decision"
    }
}
