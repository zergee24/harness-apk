package com.harnessapk.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.harnessapk.ui.theme.HarnessApkTheme
import com.harnessapk.ui.wiki.WikiRoutes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HarnessApkAppNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsOpensAgentPackagesAndBackReturnsToSettings() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        openSettings()

        composeRule.onNodeWithText("智能体包").performClick()
        composeRule.onNodeWithText("智能体包").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()

        composeRule.onNodeWithText("设置").assertIsDisplayed()
    }

    @Test
    fun settingsOpensWikiLibraryAndBackReturnsToSettings() {
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
        openSettings()

        composeRule.onNodeWithText("Wiki 知识库").performScrollTo().performClick()
        composeRule.onNodeWithText("Wiki 知识库").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()

        composeRule.onNodeWithText("设置").assertIsDisplayed()
    }

    @Test
    fun externalBundleIntentInputOpensPackagesAndReturnsToSettings() {
        val incomingBundleUri = mutableStateOf<String?>(null)
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp(
                    incomingAgentBundleUri = incomingBundleUri.value?.let(Uri::parse),
                    onIncomingAgentBundleUriConsumed = { incomingBundleUri.value = null },
                )
            }
        }
        openSettings()
        val bundleIntent = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("file:///data/local/tmp/fixture.hbundle"),
            "application/vnd.harness.hbundle",
        )

        composeRule.runOnIdle { incomingBundleUri.value = bundleIntent.dataString }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("智能体包").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
    }

    @Test
    fun externalWikiIntentInputOpensLibraryAndReturnsToSettings() {
        val incomingWikiUri = mutableStateOf<String?>(null)
        composeRule.setContent {
            HarnessApkTheme {
                HarnessApkApp(
                    incomingWikiPackageUri = incomingWikiUri.value?.let(Uri::parse),
                    onIncomingWikiPackageUriConsumed = { incomingWikiUri.value = null },
                )
            }
        }
        openSettings()
        val wikiIntent = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("file:///data/local/tmp/fixture.hwiki"),
            "application/vnd.harness.hwiki+zip",
        )

        composeRule.runOnIdle { incomingWikiUri.value = wikiIntent.dataString }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Wiki 知识库").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
    }

    @Test
    fun wikiCitationRouteKeepsTheExactCitationIdentifier() {
        val citationId = "2f17f6dc-4fe9-4d3a-beb2-9ef46c4379ca"

        assertEquals("wiki-citation/$citationId", WikiRoutes.citation(citationId))
        assertEquals(citationId, WikiRoutes.decodeCitationId(citationId))
    }

    private fun openSettings() {
        composeRule.onNodeWithContentDescription("设置").performClick()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
    }
}
