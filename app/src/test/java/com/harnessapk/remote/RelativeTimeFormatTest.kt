package com.harnessapk.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeFormatTest {
    private val now = 1_800_000_000_000L // 固定基准

    @Test
    fun zeroTimeReturnsEmpty() {
        assertEquals("", formatRelativeTime(now, 0L))
    }

    @Test
    fun underOneMinute() {
        assertEquals("刚刚", formatRelativeTime(now, now - 59_000))
    }

    @Test
    fun minutes() {
        assertEquals("5 分钟前", formatRelativeTime(now, now - 5 * 60_000))
    }

    @Test
    fun hours() {
        assertEquals("3 小时前", formatRelativeTime(now, now - 3 * 3_600_000))
    }

    @Test
    fun yesterday() {
        assertEquals("昨天", formatRelativeTime(now, now - 26 * 3_600_000))
    }

    @Test
    fun days() {
        assertEquals("4 天前", formatRelativeTime(now, now - 4 * 86_400_000))
    }

    @Test
    fun futureTimeReturnsEmpty() {
        assertEquals("", formatRelativeTime(now, now + 60_000))
    }
}
