package com.harnessapk.remote

import android.content.Context

// 「done + 未读」的已查看时间戳，仅存本机 SharedPreferences。
class DashboardViewedStore(context: Context) {
    private val prefs = context.getSharedPreferences("dashboard_viewed", Context.MODE_PRIVATE)

    fun lastViewedAt(threadId: String): Long = prefs.getLong(threadId, 0L)

    fun markViewed(threadId: String, atMs: Long) {
        prefs.edit().putLong(threadId, atMs).apply()
    }

    fun markAllViewed(threadIds: Collection<String>, atMs: Long) {
        val editor = prefs.edit()
        threadIds.forEach { editor.putLong(it, atMs) }
        editor.apply()
    }
}
