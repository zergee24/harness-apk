package com.harnessapk.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.harnessapk.HarnessApkApplication
import com.harnessapk.ui.theme.HarnessApkTheme
import com.harnessapk.ui.theme.ModeTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TabNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPersistedMode() {
        (context as HarnessApkApplication).container.homeModeStore.reset()
    }

    @Test
    fun bottomNavShowsThreeTabsAndSettlesToLife() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithTag("nav-LIFE").assertExists()
        composeRule.onNodeWithTag("nav-WORK").assertExists()
        composeRule.onNodeWithTag("nav-ME").assertExists()
        composeRule.onNodeWithText("还没有会话").assertExists()
    }

    @Test
    fun clickingWorkTabShowsProjectPanel() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithTag("nav-WORK").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("还没有项目").assertExists()
    }

    @Test
    fun clickingMeTabShowsSettingsAggregation() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithTag("nav-ME").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("模型配置").assertExists()
    }

    @Test
    fun lifeModeAppliesWarmLightBackground() {
        var background by mutableStateOf(Color.Unspecified)
        composeRule.setContent {
            ModeTheme(MainMode.LIFE) {
                background = MaterialTheme.colorScheme.background
            }
        }
        assertEquals(Color(0xFFFAF7F6), background)
    }

    @Test
    fun workModeAppliesTechDarkBackground() {
        var background by mutableStateOf(Color.Unspecified)
        composeRule.setContent {
            ModeTheme(MainMode.WORK) {
                background = MaterialTheme.colorScheme.background
            }
        }
        assertEquals(Color(0xFF101417), background)
    }

    @Test
    fun meModeAppliesTechDarkBackground() {
        var background by mutableStateOf(Color.Unspecified)
        composeRule.setContent {
            ModeTheme(MainMode.ME) {
                background = MaterialTheme.colorScheme.background
            }
        }
        assertEquals(Color(0xFF101417), background)
    }
}
