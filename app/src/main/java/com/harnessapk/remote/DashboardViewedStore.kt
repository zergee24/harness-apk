package com.harnessapk.remote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// 「done + 未读」的已查看时间戳，仅存本机 SharedPreferences。
class DashboardViewedStore(context: Context) {
    private val prefs = context.getSharedPreferences("dashboard_viewed", Context.MODE_PRIVATE)

    // 已读锚点的版本号：副屏打点后，角标等订阅方立即重算未读，不等下一次快照。
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun lastViewedAt(threadId: String): Long = prefs.getLong(threadId, 0L)

    fun markViewed(threadId: String, atMs: Long) {
        prefs.edit().putLong(threadId, atMs).apply()
        _revision.value += 1
    }

    fun markAllViewed(threadIds: Collection<String>, atMs: Long) {
        val editor = prefs.edit()
        threadIds.forEach { editor.putLong(it, atMs) }
        editor.apply()
        _revision.value += 1
    }
}
