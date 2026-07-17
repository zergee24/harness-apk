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

    @Test
    fun agentScreenShowsTopicsAndCanOpenOrCreateOne() {
        val source = File("src/main/java/com/harnessapk/ui/agent/AgentScreen.kt").readText()

        assertTrue(source.contains("新话题"))
        assertTrue(source.contains("个话题"))
        assertTrue(source.contains("onOpenConversation"))
    }

    @Test
    fun installDialogShowsIndexingProgressWhileInstalling() {
        val source = File("src/main/java/com/harnessapk/ui/agent/AgentScreen.kt").readText()

        assertTrue(source.contains("LinearProgressIndicator"))
        assertTrue(source.contains("正在安装智能体并建立资料索引"))
    }
}
