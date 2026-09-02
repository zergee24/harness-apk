package com.harnessapk.ui.dashboard

import com.harnessapk.remote.DashboardThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardUiMappingTest {
    private fun thread(
        status: String,
        approx: Boolean = false,
        note: String? = null,
        updatedAtMs: Long = 1_000L,
    ) = DashboardThread(
        threadId = "t-1", title = "线程", cwd = "/x", gitBranch = null,
        updatedAtMs = updatedAtMs, status = status, approx = approx, note = note,
    )

    @Test
    fun statusMapsToTone() {
        assertEquals(DashboardTone.THINKING, dashboardTone("thinking"))
        assertEquals(DashboardTone.RUNNING, dashboardTone("running"))
        assertEquals(DashboardTone.DONE, dashboardTone("done"))
        assertEquals(DashboardTone.ERROR, dashboardTone("error"))
        assertEquals(DashboardTone.IDLE, dashboardTone(""))
        assertEquals(DashboardTone.IDLE, dashboardTone("unknown_future_status"))
    }

    @Test
    fun tonesHaveDistinctColors() {
        val colors = DashboardTone.entries.map(::dashboardToneArgb).toSet()
        assertEquals(DashboardTone.entries.size, colors.size)
    }

    @Test
    fun labelReflectsApproxState() {
        assertEquals("运行中", dashboardStatusLabel(thread("running")))
        assertEquals(
            "运行中 · 长时间无输出",
            dashboardStatusLabel(thread("running", approx = true, note = "长时间无输出")),
        )
        assertEquals(
            "已完成 · 无结束事件",
            dashboardStatusLabel(thread("done", approx = true, note = "无结束事件")),
        )
        assertEquals("非精确", thread("done", approx = true).let(::dashboardStatusLabel).split(" · ").last())
    }

    @Test
    fun unreadOnlyForDoneAfterLastViewed() {
        assertTrue(isDashboardUnread(thread("done", updatedAtMs = 2_000L), lastViewedAtMs = 1_000L))
        assertTrue(!isDashboardUnread(thread("done", updatedAtMs = 1_000L), lastViewedAtMs = 2_000L))
        assertTrue(!isDashboardUnread(thread("running", updatedAtMs = 2_000L), lastViewedAtMs = 1_000L))
        assertTrue(!isDashboardUnread(thread("error", updatedAtMs = 2_000L), lastViewedAtMs = 1_000L))
    }

    @Test
    fun relativeTimeBuckets() {
        val now = 1_800_000_000_000L
        assertEquals("", dashboardRelativeTime(now, 0L))
        assertEquals("刚刚", dashboardRelativeTime(now, now - 59_000L))
        assertEquals("5 分钟前", dashboardRelativeTime(now, now - 5 * 60_000L))
        assertEquals("3 小时前", dashboardRelativeTime(now, now - 3 * 3_600_000L))
        assertEquals("昨天", dashboardRelativeTime(now, now - 30 * 3_600_000L))
    }
}
