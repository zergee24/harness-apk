package com.harnessapk.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.harnessapk.storage.RemoteApprovalEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "AppBadge"
private const val BADGE_CHANNEL_ID = "app_badge"
private const val BADGE_NOTIFICATION_ID = 1026

/**
 * 通知体系最轻的一层：桌面图标角标。
 * 语义 = 等待人工处理的远程审批数（与高优提醒通知同源，DB 为准，离线也可更新）。
 *
 * API 26+ 没有直接写角标的系统 API：Samsung/Honor 等启动器从活跃通知派生角标，
 * 载体就是一条 IMPORTANCE_MIN 的静默常驻通知（number 字段承载计数）；
 * Pixel 类启动器只亮点；通知权限未授予或启动器不支持时静默降级。
 */
fun observeAppBadgeCount(pendingApprovals: Flow<List<RemoteApprovalEntity>>): Flow<Int> =
    pendingApprovals.map { approvals -> pendingApprovalNotificationPlans(approvals).size }

fun updateAppBadge(context: Context, count: Int) {
    val manager = NotificationManagerCompat.from(context)
    runCatching {
        if (count <= 0) {
            manager.cancel(BADGE_NOTIFICATION_ID)
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(BADGE_CHANNEL_ID, "桌面角标", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(true)
            },
        )
        manager.notify(
            BADGE_NOTIFICATION_ID,
            NotificationCompat.Builder(context, BADGE_CHANNEL_ID)
                .setNumber(count)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Codex 等待审批 × $count")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build(),
        )
    }.onFailure { Log.w(TAG, "update badge failed", it) }
}
