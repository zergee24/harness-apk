package com.harnessapk.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureModelsTest {
    @Test
    fun captureTextMergesWithoutDestroyingExistingDraft() {
        assertEquals("已有草稿\n分享内容", mergeCaptureText("已有草稿", "分享内容"))
        assertEquals("已有草稿", mergeCaptureText("已有草稿", ""))
        assertEquals("分享内容", mergeCaptureText("", "分享内容"))
    }
}
