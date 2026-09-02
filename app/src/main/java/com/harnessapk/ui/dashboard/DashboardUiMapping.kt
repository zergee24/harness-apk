package com.harnessapk.ui.dashboard

import com.harnessapk.remote.DashboardThread

// 副屏卡片的纯映射层：状态 → 语义色/文案/排序/汇总，全部无 Android 依赖，可离线单测。

enum class DashboardTone { THINKING, RUNNING, DONE, ERROR, IDLE }

fun dashboardTone(status: String): DashboardTone = when (status) {
    "thinking" -> DashboardTone.THINKING
    "running" -> DashboardTone.RUNNING
    "done" -> DashboardTone.DONE
    "error" -> DashboardTone.ERROR
    else -> DashboardTone.IDLE
}

// 深色副屏底上的固定状态色（ARGB Long），与 Compose 主题解耦。
fun dashboardToneArgb(tone: DashboardTone): Long = when (tone) {
    DashboardTone.THINKING -> 0xFF4C8DFF
    DashboardTone.RUNNING -> 0xFF34C77B
    DashboardTone.DONE -> 0xFF8E8E93
    DashboardTone.ERROR -> 0xFFFF5C5C
    DashboardTone.IDLE -> 0xFF5A5A5E
}

fun dashboardStatusLabel(thread: DashboardThread): String {
    val base = when (dashboardTone(thread.status)) {
        DashboardTone.THINKING -> "思考中"
        DashboardTone.RUNNING -> "运行中"
        DashboardTone.DONE -> "已完成"
        DashboardTone.ERROR -> "已中断"
        DashboardTone.IDLE -> "空闲"
    }
    return if (thread.approx) "$base · ${thread.note ?: "非精确"}" else base
}

// done 且目录时间晚于上次查看时间 → 未读。查看时间戳只存本机。
fun isDashboardUnread(thread: DashboardThread, lastViewedAtMs: Long): Boolean =
    dashboardTone(thread.status) == DashboardTone.DONE && thread.updatedAtMs > lastViewedAtMs

// test 分支未保留 RelativeTimeFormat，这里内置副屏所需的极简相对时间。
fun dashboardRelativeTime(nowMs: Long, timeMs: Long): String {
    if (timeMs <= 0L) return ""
    val diff = nowMs - timeMs
    val minute = 60_000L
    val hour = 3_600_000L
    val day = 86_400_000L
    return when {
        diff < 0L -> ""
        diff < minute -> "刚刚"
        diff < hour -> "${diff / minute} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < 2 * day -> "昨天"
        diff < 7 * day -> "${diff / day} 天前"
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = timeMs }
            "${cal.get(java.util.Calendar.MONTH) + 1}-${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
        }
    }
}

// 一屏控制台：需关注的状态置顶（出错 > 运行 > 思考），随后完成、空闲；
// 同组内按最近活动倒序。
fun dashboardSortPriority(status: String): Int = when (dashboardTone(status)) {
    DashboardTone.ERROR -> 0
    DashboardTone.RUNNING -> 1
    DashboardTone.THINKING -> 2
    DashboardTone.DONE -> 3
    DashboardTone.IDLE -> 4
}

fun sortDashboardThreadsForConsole(threads: List<DashboardThread>): List<DashboardThread> =
    threads.sortedWith(
        compareBy({ dashboardSortPriority(it.status) }, { -it.updatedAtMs }),
    )

// 顶栏一句话汇总：只统计需关注的量，空闲不提。
fun dashboardSummaryLabel(threads: List<DashboardThread>): String {
    var active = 0
    var done = 0
    var error = 0
    for (t in threads) {
        when (dashboardTone(t.status)) {
            DashboardTone.RUNNING, DashboardTone.THINKING -> active++
            DashboardTone.DONE -> done++
            DashboardTone.ERROR -> error++
            DashboardTone.IDLE -> {}
        }
    }
    val parts = buildList {
        if (active > 0) add("运行 $active")
        if (done > 0) add("完成 $done")
        if (error > 0) add("出错 $error")
    }
    return if (parts.isEmpty()) "全部空闲" else parts.joinToString(" · ")
}
