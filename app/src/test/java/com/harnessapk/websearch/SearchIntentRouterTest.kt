package com.harnessapk.websearch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIntentRouterTest {
    @Test
    fun detectsExplicitWebSearchIntent() {
        assertTrue(shouldUseWebSearch("联网查一下 GLM 最新价格"))
        assertTrue(shouldUseWebSearch("搜索最新 Android 版本"))
        assertTrue(shouldUseWebSearch("打开 https://example.com 看看"))
        assertTrue(shouldUseWebSearch("引用来源说明一下"))
    }

    @Test
    fun doesNotSearchForLocalWritingTasks() {
        assertFalse(shouldUseWebSearch("帮我润色这段话"))
        assertFalse(shouldUseWebSearch("总结上面的 Markdown"))
    }
}
