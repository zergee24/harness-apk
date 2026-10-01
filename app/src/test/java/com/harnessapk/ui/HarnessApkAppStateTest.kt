package com.harnessapk.ui

import com.harnessapk.chat.Conversation
import com.harnessapk.ui.project.ProjectWorkbenchDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HarnessApkAppStateTest {
    @Test
    fun chatTopBarTitleUsesCurrentConversationTitle() {
        val conversations = listOf(
            conversation(id = "c1", title = "圆柱罐体讨论"),
            conversation(id = "c2", title = "清运路线"),
        )

        assertEquals("圆柱罐体讨论", chatTopBarTitle(conversations, "c1"))
    }

    @Test
    fun chatTopBarTitleFallsBackWhenConversationIsMissing() {
        assertEquals("对话", chatTopBarTitle(emptyList(), "missing"))
    }

    @Test
    fun lifeHomeTopBarTitleShowsTheCurrentConversation() {
        // C-2.3：主屏必须让用户知道自己正在哪条会话里，而不是恒定显示"生活"。
        assertEquals(
            "周末买菜要带什么",
            lifeHomeTopBarTitle(overviewTitle = "周末买菜要带什么", conversationTitle = "新会话"),
        )
        // 概览还没算出来时退回 Room 里的标题。
        assertEquals(
            "周末买菜要带什么",
            lifeHomeTopBarTitle(overviewTitle = null, conversationTitle = "周末买菜要带什么"),
        )
    }

    @Test
    fun lifeHomeTopBarTitleFallsBackForFreshConversations() {
        // 全新会话还没有可读标题，不要在主屏顶栏显示"新会话"/"新问题"这种占位符。
        assertEquals("生活", lifeHomeTopBarTitle(overviewTitle = "新会话", conversationTitle = "新会话"))
        assertEquals("生活", lifeHomeTopBarTitle(overviewTitle = "新问题", conversationTitle = "新问题"))
        assertEquals("生活", lifeHomeTopBarTitle(overviewTitle = " ", conversationTitle = null))
        assertEquals("生活", lifeHomeTopBarTitle(overviewTitle = null, conversationTitle = null))
    }

    @Test
    fun workbenchTargetCarriesProjectPathAndRequestKey() {
        val target = projectWorkbenchTarget(
            projectId = "project-1",
            destination = ProjectWorkbenchDestination.FILES,
            selectedPath = "requirements/prd.md",
            requestKey = 7,
        )

        assertEquals("project-1", target.projectId)
        assertEquals(ProjectWorkbenchDestination.FILES, target.destination)
        assertEquals("requirements/prd.md", target.selectedPath)
        assertEquals(7, target.requestKey)
    }

    @Test
    fun homeNavigationUsesBottomBarWithoutPager() {
        val source = File("src/main/java/com/harnessapk/ui/HarnessApkApp.kt").readText().replace("\r\n", "\n")

        assertTrue(source.contains("FloatingNavPill("))
        assertFalse(source.contains("HorizontalPager("))
        assertFalse(source.contains("rememberPagerState("))
        assertFalse(source.contains("WarmSegmentedControl("))
    }

    @Test
    fun homeConversationRequestUsesSuggestedIdentityWithoutProject() {
        assertEquals(
            NewConversationRequest(),
            homeConversationRequest(),
        )
    }

    @Test
    fun simpleHomeAlwaysStartsWithAssistantAndNoProject() {
        assertEquals(
            NewConversationRequest(title = "新问题", identity = com.harnessapk.agent.InitialConversationIdentity.Assistant),
            homeConversationRequest(simpleMode = true),
        )
    }

    @Test
    fun projectConversationRequestUsesProjectTitleAndProjectId() {
        assertEquals(
            NewConversationRequest(title = "移动端 Harness · 项目会话", projectId = "project-1"),
            projectConversationRequest(projectId = "project-1", projectName = "移动端 Harness"),
        )
    }

    private fun conversation(id: String, title: String): Conversation = Conversation(
        id = id,
        title = title,
        updatedAt = 1L,
        promptOriginal = "",
        promptOptimized = "",
        promptFinal = "",
    )
}
