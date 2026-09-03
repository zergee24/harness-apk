package com.harnessapk.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.harnessapk.HarnessApkApplication
import com.harnessapk.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RemoteConnectionService : Service() {
    private val container by lazy { (application as HarnessApkApplication).container }
    private val scope by lazy { CoroutineScope(SupervisorJob() + container.dispatchers.io) }
    private val notificationCoordinator = RemoteNotificationCoordinator()
    private var visibleApprovalNotificationIds = emptySet<Int>()

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Codex 远程任务", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Codex 远程提醒", NotificationManager.IMPORTANCE_HIGH))
        startForeground(ID, notification("正在连接 Mac"))
        container.remoteRepository.connect()
        scope.launch {
            combine(
                container.remoteRepository.state,
                container.database.remoteDao().observeOpenRuns(),
            ) { state, openRuns -> state to openRuns.map { it.status } }
                .collectLatest { (state, openRunStatuses) ->
                manager.notify(ID, notification(if (state.isWorking) "Codex 正在工作" else "正在同步远程状态"))
                if (!shouldKeepRemoteConnectionAlive(state, openRunStatuses)) {
                    delay(5_000)
                    stopSelf()
                }
            }
        }
        scope.launch {
            container.database.remoteDao().observePendingApprovals().collect { approvals ->
                val plans = pendingApprovalNotificationPlans(approvals, notificationCoordinator)
                val nextIds = plans.mapTo(mutableSetOf()) { it.notificationId }
                (visibleApprovalNotificationIds - nextIds).forEach(manager::cancel)
                plans.forEach { plan ->
                    manager.notify(plan.notificationId, alertNotification(plan))
                }
                visibleApprovalNotificationIds = nextIds
            }
        }
        scope.launch {
            container.remoteRepository.notifications.collect { alert ->
                val plan = alert.toPlan()
                manager.notify(plan.notificationId, alertNotification(plan))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        container.remoteRepository.connect()
        if (shouldFlushRemoteOutboxOnServiceStart(container.remoteRepository.state.value.connectionStatus)) {
            scope.launch { container.remoteTransport.flush() }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Harness Codex Remote")
        .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).build()

    private fun RemoteNotification.toPlan(): RemoteNotificationPlan =
        if (runId != null && approvalId != null) {
            notificationCoordinator.approvalPlan(runId, approvalId, risk)
        } else if (runId != null) {
            notificationCoordinator.runPlan(runId, title, message)
        } else {
            notificationCoordinator.runPlan("legacy", title, message)
        }

    private fun alertNotification(plan: RemoteNotificationPlan): android.app.Notification {
        val builder = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(plan.title)
            .setContentText(plan.summary)
            .setAutoCancel(true)
            .setContentIntent(runPendingIntent(plan.runId, plan.notificationId))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        plan.actions.forEachIndexed { index, action ->
            val pendingIntent = when (action.kind) {
                RemoteNotificationActionKind.VIEW -> runPendingIntent(plan.runId, plan.notificationId + index + 1)
                RemoteNotificationActionKind.ALLOW_ONCE,
                RemoteNotificationActionKind.DECLINE,
                -> approvalPendingIntent(plan, action, plan.notificationId + index + 1)
            }
            builder.addAction(0, action.label, pendingIntent)
        }
        return builder.build()
    }

    private fun runPendingIntent(runId: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        this,
        requestCode,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_REMOTE_RUN_ID, runId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun approvalPendingIntent(
        plan: RemoteNotificationPlan,
        action: RemoteNotificationAction,
        requestCode: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        this,
        requestCode,
        Intent(this, RemoteNotificationActionReceiver::class.java)
            .setAction(RemoteNotificationActionReceiver.ACTION_APPROVAL)
            .putExtra(RemoteNotificationActionReceiver.EXTRA_RUN_ID, plan.runId)
            .putExtra(RemoteNotificationActionReceiver.EXTRA_APPROVAL_ID, plan.approvalId)
            .putExtra(RemoteNotificationActionReceiver.EXTRA_COMMAND_ID, action.commandId)
            .putExtra(
                RemoteNotificationActionReceiver.EXTRA_DECISION,
                if (action.kind == RemoteNotificationActionKind.ALLOW_ONCE) ApprovalDecision.ALLOW_ONCE.name else ApprovalDecision.DENY.name,
            ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val CHANNEL = "codex_remote"
        private const val ALERT_CHANNEL = "codex_remote_alerts"
        private const val ID = 1025
        fun start(context: Context) = androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, RemoteConnectionService::class.java))
    }
}
