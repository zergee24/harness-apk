package com.harnessapk.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.harnessapk.HarnessApkApplication
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class LifePanelQuickEntryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPersistedMode() {
        (context as HarnessApkApplication).container.homeModeStore.reset()
    }

    @Test
    fun lifePanelOffersAgentAndWikiQuickEntries() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithText("智能体").assertIsDisplayed()
        composeRule.onNodeWithText("知识库").assertIsDisplayed()
    }

    @Test
    fun agentQuickEntryOpensAgentPackages() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithText("智能体").performClick()
        composeRule.onNodeWithText("智能体包").assertIsDisplayed()
    }

    @Test
    fun wikiQuickEntryOpensWikiLibrary() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        composeRule.onNodeWithText("知识库").performClick()
        composeRule.onNodeWithText("Wiki 知识库").assertIsDisplayed()
    }

    @Test
    fun newConversationActionSharesTheQuickEntryRow() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }

        val agentBounds = composeRule
            .onNodeWithText("智能体")
            .fetchSemanticsNode()
            .boundsInRoot
        val wikiBounds = composeRule
            .onNodeWithText("知识库")
            .fetchSemanticsNode()
            .boundsInRoot
        val actionBounds = composeRule
            .onNodeWithContentDescription("新建对话")
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(abs(agentBounds.center.y - actionBounds.center.y) <= 1f)
        assertTrue(abs(wikiBounds.center.y - actionBounds.center.y) <= 1f)
        assertTrue(actionBounds.left > wikiBounds.right)
    }
}
