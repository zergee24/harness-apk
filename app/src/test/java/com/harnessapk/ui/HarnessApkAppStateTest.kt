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

        assertTrue(source.contains("NavigationBar {"))
        assertTrue(source.contains("NavigationBarItem("))
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
    fun projectConversationRequestUsesProjectTitleAndProjectId() {
        assertEquals(
            NewConversationRequest(title = "移动端 Harness · 项目会话", projectId = "project-1"),
            projectConversationRequest(projectId = "project-1", projectName = "移动端 Harness"),
        )
    }

    @Test
    fun sharedModeSwitcherUsesConsistentThemeShapes() {
        val source = File("src/main/java/com/harnessapk/ui/components/WarmComponents.kt").readText()
        val segmentedSource = source.substringAfter("fun WarmSegmentedControl").substringBefore("@Composable\nfun ActionableEmptyState")

        assertTrue(segmentedSource.contains("shape = MaterialTheme.shapes.large"))
        assertTrue(segmentedSource.contains("shape = MaterialTheme.shapes.medium"))
        assertFalse(segmentedSource.contains("RoundedCornerShape(999.dp)"))
    }

    @Test
    fun homeTopBarKeepsNewConversationActionWithoutModeSwitcherOrSettings() {
        val source = File("src/main/java/com/harnessapk/ui/HarnessApkApp.kt").readText().replace("\r\n", "\n")
        val topBarSource = source.substringAfter("topBar = {").substringBefore("},\n    ) { padding")

        assertTrue(topBarSource.contains("if (isHomeRoute)"))
        assertTrue(topBarSource.contains("HomeTopBar("))
        assertTrue(source.contains("private fun HomeTopBar("))
        assertTrue(source.contains("contentDescription = \"新建对话\""))
        assertFalse(source.contains("ModeSwitcher("))
        assertFalse(source.contains("onOpenSettings"))
        assertFalse(source.contains("WarmSegmentedControl("))
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
