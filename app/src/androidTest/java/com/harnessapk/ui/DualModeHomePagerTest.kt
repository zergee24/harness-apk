package com.harnessapk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.harnessapk.ui.theme.HarnessApkTheme
import com.harnessapk.ui.theme.ModeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DualModeHomePagerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeShowsBothModeTabsAndSettlesToLifePanel() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithText("生活").assertExists()
        composeRule.onNodeWithText("工作").assertExists()
        composeRule.onNodeWithText("还没有会话").assertExists()
    }

    @Test
    fun clickingWorkTabSwitchesToWorkPanel() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithText("工作").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("还没有项目").assertExists()
    }

    @Test
    fun workModeAppliesTechDarkBackground() {
        var background by mutableStateOf(Color.Unspecified)
        composeRule.setContent {
            ModeTheme(MainMode.WORK) {
                background = MaterialTheme.colorScheme.background
            }
        }
        composeRule.onNode(isRoot()).assertExists()
        assertEquals(Color(0xFF101417), background)
    }

    @Test
    fun lifeModeAppliesWarmLightBackground() {
        var background by mutableStateOf(Color.Unspecified)
        composeRule.setContent {
            ModeTheme(MainMode.LIFE) {
                background = MaterialTheme.colorScheme.background
            }
        }
        composeRule.onNode(isRoot()).assertExists()
        assertEquals(Color(0xFFFAF7F6), background)
    }
}
