package com.harnessapk.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
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
            reasoningEffortLabel = "超高",
            webSearchEnabled = true,
            contextPercent = 18,
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                HarnessApkTheme {
                    Box(modifier = Modifier.width(320.dp).height(720.dp)) {
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
                                    selectedName = "普通助手",
                                    mutable = true,
                                    options = listOf(
                                        ConversationIdentityOption(null, "普通助手", null),
                                        ConversationIdentityOption("nutrition", "营养规划师", 2),
                                    ),
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
        composeRule.onNodeWithTag("conversation_context_sheet_content").assertIsDisplayed()
        composeRule.onNodeWithText("身份").assertDoesNotExist()
        composeRule.onNodeWithText("家庭事务").assertDoesNotExist()
        composeRule.onNodeWithText("营养规划师 · v2").assertDoesNotExist()

        composeRule.onNodeWithTag("context_project_selector").performClick()
        composeRule.onNodeWithText("家庭事务").performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(listOf("family"), selectedProjects) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("conversation_context_sheet_content").performTouchInput {
            val lower = center.copy(y = center.y + 120f)
            val upper = center.copy(y = center.y - 120f)
            down(lower)
            moveTo(upper, delayMillis = 200)
            up()
            down(upper)
            moveTo(lower, delayMillis = 200)
            up()
        }
        composeRule.onNodeWithTag("conversation_context_sheet_content").assertIsDisplayed()
    }

    @Test
    fun agentSelectorUsesSmartAgentLabelAndExpandsOnDemand() {
        val showSheet = mutableStateOf(false)
        val selectedAgents = mutableListOf<String?>()
        val summary = ConversationContextSummary(
            projectName = null,
            identityName = "普通助手",
            enabledWikiCount = 0,
            model = "gpt-5.6-terra",
            reasoningEffortLabel = "超高",
            webSearchEnabled = false,
            contextPercent = 0,
        )
        composeRule.setContent {
            HarnessApkTheme {
                Box(modifier = Modifier.width(320.dp).height(720.dp)) {
                    ConversationContextBar(summary = summary, onClick = { showSheet.value = true })
                    if (showSheet.value) {
                        ConversationContextSheet(
                            summary = summary,
                            projects = emptyList(),
                            selectedProjectId = null,
                            projectLocked = false,
                            identityState = ConversationIdentityUiState(
                                selectedAgentId = null,
                                selectedName = "普通助手",
                                mutable = true,
                                options = listOf(
                                    ConversationIdentityOption(null, "普通助手", null),
                                    ConversationIdentityOption("nutrition", "营养规划师", 2),
                                ),
                            ),
                            wikiLabel = "未选择",
                            showWebSearch = false,
                            webSearchEnabled = false,
                            canCompressContext = false,
                            isCompressingContext = false,
                            onSelectProject = {},
                            onSelectIdentity = selectedAgents::add,
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

        composeRule.onNodeWithContentDescription("调整会话上下文").performClick()
        composeRule.onNodeWithText("智能体").assertIsDisplayed()
        composeRule.onNodeWithText("身份").assertDoesNotExist()
        composeRule.onNodeWithText("营养规划师 · v2").assertDoesNotExist()

        composeRule.onNodeWithTag("context_agent_selector")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("context_agent_selector").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("收起智能体").assertIsDisplayed()
        composeRule.onNodeWithText("营养规划师 · v2").performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(listOf("nutrition"), selectedAgents) }
    }
}
