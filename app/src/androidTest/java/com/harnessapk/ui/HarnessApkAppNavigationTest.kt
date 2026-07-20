package com.harnessapk.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.harnessapk.ui.theme.HarnessApkTheme
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

    private fun openSettings() {
        composeRule.onNodeWithContentDescription("设置").performClick()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
    }
}
