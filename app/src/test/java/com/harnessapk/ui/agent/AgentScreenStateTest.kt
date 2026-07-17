package com.harnessapk.ui.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentScreenStateTest {
    @Test
    fun emptyStateGuidesUsersToAddAnHBundle() {
        val source = File("src/main/java/com/harnessapk/ui/agent/AgentScreen.kt").readText()

        assertTrue(source.contains("AgentEmptyState("))
        assertTrue(source.contains("选择 .hbundle 安装包"))
        assertTrue(source.contains("添加智能体"))
        assertTrue(source.contains("onRequestImport"))
    }
}
