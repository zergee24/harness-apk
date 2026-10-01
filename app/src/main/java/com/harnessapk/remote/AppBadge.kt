package com.harnessapk.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.harnessapk.storage.RemoteApprovalEntity
import com.harnessapk.ui.dashboard.DashboardTone
import com.harnessapk.ui.dashboard.dashboardTone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private const val TAG = "AppBadge"
private const val BADGE_CHANNEL_ID = "app_badge"
private const val BADGE_NOTIFICATION_ID = 1026

/**
 * 通知体系最轻的一层：桌面图标角标。
 * 计数 = 等待人工处理的远程审批 + Mac 代理线程的终态未读（done/error 且新于已读锚点，
 * 覆盖 bridge 快照里的 codex/zcode 会话；终态语义对齐 ZCode 的 completedSuccess/
 * completedInterrupted/error，运行与思考态不打扰）。DB 与快照为准，离线也可更新。
 *
 * API 26+ 没有直接写角标的系统 API：Samsung/Honor 等启动器从活跃通知派生角标，
 * 载体就是一条 IMPORTANCE_MIN 的静默常驻通知（number 字段承载计数）；
 * Pixel 类启动器只亮点；通知权限未授予或启动器不支持时静默降级。
 */
fun observeAppBadgeCount(
    pendingApprovals: Flow<List<RemoteApprovalEntity>>,
    dashboard: Flow<DashboardState>,
    viewedStore: DashboardViewedStore,
): Flow<Int> = combine(
    pendingApprovals.map { approvals -> pendingApprovalNotificationPlans(approvals).size },
    dashboard,
    viewedStore.revision,
) { approvalCount, state, _ ->
    approvalCount + dashboardUnreadCount(state.threads) { viewedStore.lastViewedAt(it) }
}

/** 终态未读计数：状态为终态且目录时间晚于该线程的已读锚点。 */
internal fun dashboardUnreadCount(
    threads: List<DashboardThread>,
    lastViewedAt: (String) -> Long,
): Int = threads.count { thread ->
    val tone = dashboardTone(thread.status)
    (tone == DashboardTone.DONE || tone == DashboardTone.ERROR) &&
        thread.updatedAtMs > lastViewedAt(thread.threadId)
}

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
                .setContentTitle("待处理事项 × $count")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build(),
        )
    }.onFailure { Log.w(TAG, "update badge failed", it) }
}
