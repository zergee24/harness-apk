package com.harnessapk.ui.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.harnessapk.remote.WorkspaceCandidate
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Rule
import org.junit.Test

class ProjectRemoteBindingSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyCandidatesAt320DpAndLargeFontHasNoPathInput() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                HarnessApkTheme {
                    Box(Modifier.width(320.dp)) {
                        ProjectRemoteBindingSheet(
                            projectName = "Harness APK",
                            hostName = "Tony 的 Mac",
                            candidates = emptyList(),
                            candidatesLoaded = true,
                            existingBinding = null,
                            onDismiss = {},
                            onBind = { _, _ -> },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("先在 Mac Codex 中打开一次该项目").assertIsDisplayed()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        composeRule.onNodeWithTag("remote-binding-primary").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun candidateSemanticsNeverExposeRemoteCredentialsOrFullPath() {
        val candidate = WorkspaceCandidate(
            workspaceId = "workspace-1",
            displayName = "harness-apk",
            cwd = "/Users/private-user/Documents/harness-apk",
            repositoryLabel = "https://token-user:secret-token@github.com/acme/harness.git?access_token=leak",
            branch = "test",
            repositoryFingerprint = "fingerprint-1",
            lastUsedAt = 100L,
        )
        composeRule.setContent {
            HarnessApkTheme {
                ProjectRemoteBindingSheet(
                    projectName = "Harness APK",
                    hostName = "Tony 的 Mac",
                    candidates = listOf(candidate),
                    candidatesLoaded = true,
                    existingBinding = null,
                    onDismiss = {},
                    onBind = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("github.com/acme/harness", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Documents/harness-apk").assertIsDisplayed()
        listOf("token-user", "secret-token", "access_token", "leak", candidate.cwd).forEach { secret ->
            composeRule.onAllNodesWithText(secret, substring = true).assertCountEquals(0)
        }
    }
}
