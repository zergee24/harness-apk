package com.harnessapk.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebShellDownloadTest {
    @Test
    fun `filename from content disposition wins`() {
        assertEquals(
            "artifact.zip",
            webShellDownloadFileName(
                "https://chatgpt.com/files/abc",
                "attachment; filename=\"artifact.zip\"",
                "application/zip",
            ),
        )
    }

    @Test
    fun `rfc5987 encoded filename is decoded`() {
        assertEquals(
            "原型说明.md",
            webShellDownloadFileName(
                "https://chatgpt.com/files/abc",
                "attachment; filename*=UTF-8''%E5%8E%9F%E5%9E%8B%E8%AF%B4%E6%98%8E.md",
                null,
            ),
        )
    }

    @Test
    fun `url last segment is the fallback`() {
        assertEquals(
            "app-debug.apk",
            webShellDownloadFileName("https://example.com/downloads/app-debug.apk?ts=1", null, null),
        )
    }

    @Test
    fun `timestamp fallback when url has no segment`() {
        val name = webShellDownloadFileName("https://chatgpt.com/", null, null)
        assertTrue("应为时间戳兜底名: $name", name.startsWith("download_"))
    }
}
