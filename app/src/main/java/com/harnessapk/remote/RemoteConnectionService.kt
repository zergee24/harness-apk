package com.harnessapk.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.harnessapk.HarnessApkApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RemoteConnectionService : Service() {
    private val container by lazy { (application as HarnessApkApplication).container }
    private val scope by lazy { CoroutineScope(SupervisorJob() + container.dispatchers.io) }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Codex 远程任务", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Codex 远程提醒", NotificationManager.IMPORTANCE_HIGH))
        startForeground(ID, notification("正在连接 Mac"))
        container.remoteRepository.connect()
        scope.launch {
            container.remoteRepository.state.collectLatest { state ->
                manager.notify(ID, notification(if (state.isWorking) "Codex 正在工作" else "正在同步远程状态"))
                if (!state.isWorking && state.activeTurnId == null) {
                    delay(5_000)
                    if (!container.remoteRepository.state.value.isWorking) stopSelf()
                }
            }
        }
        scope.launch {
            container.remoteRepository.notifications.collect { alert ->
                manager.notify(ALERT_ID, alertNotification(alert))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Harness Codex Remote")
        .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).build()

    private fun alertNotification(alert: RemoteNotification) = NotificationCompat.Builder(this, ALERT_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_more)
        .setContentTitle(alert.title)
        .setContentText(alert.message)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    companion object {
        private const val CHANNEL = "codex_remote"
        private const val ALERT_CHANNEL = "codex_remote_alerts"
        private const val ID = 1025
        private const val ALERT_ID = 1026
        fun start(context: Context) = androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, RemoteConnectionService::class.java))
    }
}
