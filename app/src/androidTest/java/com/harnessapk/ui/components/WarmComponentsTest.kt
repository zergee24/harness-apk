package com.harnessapk.ui.components

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Rule
import org.junit.Test

class WarmComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun segmentedControlExposesBothLargeTextChoices() {
        composeRule.setContent {
            HarnessApkTheme {
                WarmSegmentedControl(
                    options = listOf("会话", "项目"),
                    selectedIndex = 0,
                    onSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("会话").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText("项目").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun emptyStateProvidesVisiblePrimaryAction() {
        composeRule.setContent {
            HarnessApkTheme {
                ActionableEmptyState(
                    title = "还没有会话",
                    message = "新建会话后，内容会保存在本机。",
                    actionLabel = "新建会话",
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("还没有会话").assertIsDisplayed()
        composeRule.onNodeWithText("新建会话").assertIsDisplayed().assertHasClickAction()
    }
}
