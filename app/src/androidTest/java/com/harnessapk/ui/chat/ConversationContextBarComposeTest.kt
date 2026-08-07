package com.harnessapk.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConversationContextBarComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun narrowLargeFontBarOpensScrollableContextSheetWithoutHorizontalControls() {
        val showSheet = mutableStateOf(false)
        val selectedProjects = mutableListOf<String?>()
        val summary = ConversationContextSummary(
            projectName = "健康生活长期管理项目",
            identityName = "家庭健康顾问",
            enabledWikiCount = 2,
            model = "gpt-5.6-terra",
            webSearchEnabled = true,
            contextPercent = 18,
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                HarnessApkTheme {
                    Box(modifier = Modifier.width(320.dp)) {
                        ConversationContextBar(summary = summary, onClick = { showSheet.value = true })
                        if (showSheet.value) {
                            ConversationContextSheet(
                                summary = summary,
                                projects = listOf(
                                    ContextProjectOption("health", "健康生活长期管理项目"),
                                    ContextProjectOption("family", "家庭事务"),
                                ),
                                selectedProjectId = "health",
                                projectLocked = true,
                                identityState = ConversationIdentityUiState(
                                    selectedAgentId = null,
                                    selectedName = "家庭健康顾问",
                                    mutable = false,
                                    options = listOf(ConversationIdentityOption(null, "家庭健康顾问", null)),
                                ),
                                wikiLabel = "自动 · 2",
                                showWebSearch = true,
                                webSearchEnabled = true,
                                canCompressContext = false,
                                isCompressingContext = false,
                                onSelectProject = selectedProjects::add,
                                onSelectIdentity = {},
                                onOpenWiki = {},
                                onOpenModel = {},
                                onToggleWebSearch = {},
                                onCompressContext = {},
                                onDismiss = { showSheet.value = false },
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("调整会话上下文").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("会话上下文").assertIsDisplayed()
        composeRule.onAllNodesWithText("在此项目继续")[0].assertIsDisplayed()
        composeRule.onNodeWithText("家庭事务").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(listOf("family"), selectedProjects) }
    }
}
