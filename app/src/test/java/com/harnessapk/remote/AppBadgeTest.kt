package com.harnessapk.remote

import com.harnessapk.remote.dashboardUnreadCount
import org.junit.Assert.assertEquals
import org.junit.Test

class AppBadgeTest {
    private fun thread(
        threadId: String = "t-1",
        status: String,
        updatedAtMs: Long = 2_000L,
    ) = DashboardThread(
        threadId = threadId, title = "线程", cwd = "/x", gitBranch = null,
        updatedAtMs = updatedAtMs, status = status,
    )

    private fun count(threads: List<DashboardThread>, viewedAtMs: Long = 1_000L): Int =
        dashboardUnreadCount(threads) { viewedAtMs }

    @Test
    fun `only terminal statuses newer than anchor count as unread`() {
        // 对齐 ZCode 终态通知：completed（含中断）与 error 才提示。
        assertEquals(1, count(listOf(thread(status = "done"))))
        assertEquals(1, count(listOf(thread(status = "error"))))
        assertEquals(0, count(listOf(thread(status = "running"))))
        assertEquals(0, count(listOf(thread(status = "thinking"))))
        assertEquals(0, count(listOf(thread(status = "idle"))))
        assertEquals(0, count(listOf(thread(status = "unknown_future_status"))))
    }

    @Test
    fun `terminal at or before anchor is not unread`() {
        assertEquals(0, count(listOf(thread(status = "done", updatedAtMs = 1_000L))))
        assertEquals(0, count(listOf(thread(status = "error", updatedAtMs = 500L))))
    }

    @Test
    fun `covers codex and zcode sources alike`() {
        val zcode = thread(threadId = "z-1", status = "done").copy(source = "zcode")
        val codex = thread(threadId = "c-1", status = "done")
        assertEquals(2, count(listOf(zcode, codex)))
    }
}
