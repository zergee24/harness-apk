package com.harnessapk.ui

import com.harnessapk.chat.Conversation
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
    fun homeModeSwitcherUsesSegmentedPillInsteadOfDropdown() {
        val source = File("src/main/java/com/harnessapk/ui/HarnessApkApp.kt").readText()
        val modeSwitcherSource = source.substringAfter("private fun ModeSwitcher").substringBefore("@Composable\nprivate fun HomeTopBarActions")

        assertTrue(modeSwitcherSource.contains("MainMode.entries.forEach"))
        assertTrue(modeSwitcherSource.contains("Surface("))
        assertFalse(modeSwitcherSource.contains("DropdownMenu"))
        assertFalse(modeSwitcherSource.contains("KeyboardArrowDown"))
        assertFalse(modeSwitcherSource.contains("切换模式"))
    }

    @Test
    fun homeModeSwitcherUsesFixedMatchingCornerRadius() {
        val source = File("src/main/java/com/harnessapk/ui/HarnessApkApp.kt").readText()
        val modeSwitcherSource = source.substringAfter("private fun ModeSwitcher").substringBefore("@Composable\nprivate fun HomeTopBarActions")

        assertTrue(source.contains("private val HomeModeSwitcherShape = RoundedCornerShape(16.dp)"))
        assertFalse(modeSwitcherSource.contains("RoundedCornerShape(999.dp)"))
        assertEquals(2, Regex("shape = HomeModeSwitcherShape").findAll(modeSwitcherSource).count())
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
