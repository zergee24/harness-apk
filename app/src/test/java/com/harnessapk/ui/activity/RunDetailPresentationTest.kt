package com.harnessapk.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class RunDetailPresentationTest {
    @Test
    fun duplicateLatestLineIsHiddenButDistinctProgressRemainsVisible() {
        assertEquals(null, remoteRunSecondaryLine("QUEUED", "等待 Mac 接收"))
        assertEquals("正在读取工作区", remoteRunSecondaryLine("RUNNING", "正在读取工作区"))
        assertEquals(null, remoteRunSecondaryLine("RUNNING", ""))
    }
}
