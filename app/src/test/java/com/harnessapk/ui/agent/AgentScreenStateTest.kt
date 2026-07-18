package com.harnessapk.ui.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentScreenStateTest {
    @Test
    fun emptyStateGuidesUsersToAddAnHBundle() {
        val source = File("src/main/java/com/harnessapk/ui/agent/AgentPackagesScreen.kt").readText()

        assertTrue(source.contains("AgentPackagesEmptyState("))
        assertTrue(source.contains("选择 .hbundle 安装包"))
        assertTrue(source.contains("导入智能体包"))
        assertTrue(source.contains("onRequestImport"))
    }

    @Test
    fun agentPackagesScreenStartsConversationAfterInstallation() {
        val source = File("src/main/java/com/harnessapk/ui/agent/AgentPackagesScreen.kt").readText()

        assertTrue(source.contains("installedAgent"))
        assertTrue(source.contains("开始对话"))
        assertTrue(source.contains("onStartConversation"))
        assertTrue(source.contains("onDone"))
        assertTrue(source.contains("sourceProjectId: String?"))
    }

    @Test
    fun installDialogShowsIndexingProgressWhileInstalling() {
        val source = File("src/main/java/com/harnessapk/ui/agent/AgentPackagesScreen.kt").readText()

        assertTrue(source.contains("LinearProgressIndicator"))
        assertTrue(source.contains("正在安装智能体并建立资料索引"))
    }
}
