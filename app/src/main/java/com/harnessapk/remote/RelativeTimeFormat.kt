package com.harnessapk.remote

import java.util.Calendar
import java.util.concurrent.TimeUnit

fun formatRelativeTime(nowMs: Long, timeMs: Long): String {
    if (timeMs <= 0L) return ""
    val diff = nowMs - timeMs
    return when {
        diff < 0L -> ""
        diff < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
        diff < TimeUnit.HOURS.toMillis(1) -> "${diff / TimeUnit.MINUTES.toMillis(1)} 分钟前"
        diff < TimeUnit.DAYS.toMillis(1) -> "${diff / TimeUnit.HOURS.toMillis(1)} 小时前"
        diff < TimeUnit.DAYS.toMillis(2) -> "昨天"
        diff < TimeUnit.DAYS.toMillis(7) -> "${diff / TimeUnit.DAYS.toMillis(1)} 天前"
        else -> {
            val cal = Calendar.getInstance().apply { timeInMillis = timeMs }
            "${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}"
        }
    }
}
