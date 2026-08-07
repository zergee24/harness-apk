package com.harnessapk.ui.capture

import com.harnessapk.capture.CaptureDraft
import com.harnessapk.capture.CaptureItem
import com.harnessapk.capture.CaptureItemKind
import com.harnessapk.capture.CaptureSource
import com.harnessapk.capture.CaptureStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureDestinationSheetTest {
    @Test
    fun summaryCombinesTextImagesAndFiles() {
        val items = listOf(
            CaptureItem("1", CaptureItemKind.IMAGE, "a.jpg", "image/jpeg", "file:///a", 1, "a"),
            CaptureItem("2", CaptureItemKind.IMAGE, "b.jpg", "image/jpeg", "file:///b", 1, "b"),
            CaptureItem("3", CaptureItemKind.FILE, "c.pdf", "application/pdf", "file:///c", 1, "c"),
        )
        val draft = CaptureDraft("d", CaptureSource.ANDROID_SHARE, "补充说明", items, CaptureStatus.READY, 1, 2)

        assertEquals("补充说明 · 图片 2 张，文件 1 个", captureDraftSummary(draft))
    }
}
