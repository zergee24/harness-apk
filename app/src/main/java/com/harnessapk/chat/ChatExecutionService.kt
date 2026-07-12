package com.harnessapk.chat

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

class ChatExecutionService : Service() {
    private val container by lazy { (application as HarnessApkApplication).container }
    private val scope by lazy { CoroutineScope(SupervisorJob() + container.dispatchers.io) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification(activeCount = 0))
        scope.launch {
            container.chatExecutionCoordinator.activeExecutionCount.collectLatest { activeCount ->
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification(activeCount))
                if (activeCount == 0) stopWhenIdle()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        container.chatExecutionCoordinator.resumePending()
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun stopWhenIdle() {
        delay(IDLE_STOP_DELAY_MILLIS)
        if (
            shouldStopForegroundService(
                activeCount = container.chatExecutionCoordinator.activeExecutionCount.value,
                hasOpenWork = container.chatExecutionRepository.hasOpenWork(),
            )
        ) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "会话生成",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notification(activeCount: Int) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("会话正在生成")
        .setContentText(foregroundNotificationText(activeCount))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "chat_execution"
        private const val NOTIFICATION_ID = 1024
        private const val IDLE_STOP_DELAY_MILLIS = 750L

        fun start(context: Context) {
            val intent = Intent(context, ChatExecutionService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}

internal fun foregroundNotificationText(activeCount: Int): String =
    "正在生成 ${activeCount.coerceAtLeast(1)} 个回复"

internal fun shouldStopForegroundService(activeCount: Int, hasOpenWork: Boolean): Boolean =
    activeCount == 0 && !hasOpenWork
