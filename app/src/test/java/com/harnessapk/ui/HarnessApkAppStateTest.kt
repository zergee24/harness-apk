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
    fun homeModeSwitcherUsesSharedSegmentedControlInsteadOfDropdown() {
        val source = File("src/main/java/com/harnessapk/ui/HarnessApkApp.kt").readText()
        val modeSwitcherSource = source.substringAfter("private fun ModeSwitcher").substringBefore("@Composable\nprivate fun HomeTopBarActions")

        assertTrue(modeSwitcherSource.contains("WarmSegmentedControl("))
        assertTrue(modeSwitcherSource.contains("MainMode.entries.map { it.label }"))
        assertFalse(modeSwitcherSource.contains("DropdownMenu"))
        assertFalse(modeSwitcherSource.contains("KeyboardArrowDown"))
        assertFalse(modeSwitcherSource.contains("切换模式"))
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
    fun homeModeSwitcherUsesCompactSingleLineSegments() {
        val appSource = File("src/main/java/com/harnessapk/ui/HarnessApkApp.kt").readText()
        val componentSource = File("src/main/java/com/harnessapk/ui/components/WarmComponents.kt").readText()
        val modeSwitcherSource = appSource.substringAfter("private fun ModeSwitcher").substringBefore("@Composable\nprivate fun HomeTopBarActions")
        val segmentedSource = componentSource.substringAfter("fun WarmSegmentedControl").substringBefore("@Composable\nfun ActionableEmptyState")

        assertTrue(modeSwitcherSource.contains("modifier = modifier"))
        assertTrue(segmentedSource.contains("modifier.height(48.dp)"))
        assertTrue(segmentedSource.contains(".weight(1f)"))
        assertTrue(segmentedSource.contains("style = MaterialTheme.typography.labelMedium"))
        assertTrue(segmentedSource.contains("maxLines = 1"))
        assertTrue(segmentedSource.contains("softWrap = false"))
    }

    @Test
    fun homeModeSwitcherUsesDedicatedTopBarSpace() {
        val source = File("src/main/java/com/harnessapk/ui/HarnessApkApp.kt").readText()
        val topBarSource = source.substringAfter("topBar = {").substringBefore("},\n    ) { padding")

        assertTrue(topBarSource.contains("if (isHomeRoute)"))
        assertTrue(topBarSource.contains("HomeTopBar("))
        assertTrue(source.contains("private fun HomeTopBar("))
        assertTrue(source.contains(".weight(1f, fill = false)"))
        assertTrue(source.contains("widthIn(max = 216.dp)"))
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
