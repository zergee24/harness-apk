package com.harnessapk.ui.agent

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentStatus
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AgentPackagesScreenComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun emptyStateShowsImportActionAndInvokesCallback() {
        var importRequests = 0

        composeRule.setContent {
            HarnessApkTheme {
                AgentPackagesEmptyState(
                    errorText = null,
                    onRequestImport = { importRequests += 1 },
                )
            }
        }

        composeRule.onNodeWithText("还没有智能体包").assertIsDisplayed()
        composeRule.onNodeWithText("导入智能体包").assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.runOnIdle { assertEquals(1, importRequests) }
    }

    @Test
    fun installProgressShowsIndexingFeedback() {
        composeRule.setContent {
            HarnessApkTheme {
                AgentInstallProgress()
            }
        }

        composeRule.onNodeWithText("正在安装智能体并建立资料索引，请保持应用开启。").assertIsDisplayed()
    }

    @Test
    fun successDialogInvokesStartAndDoneWithSourceProject() {
        val agent = agent()
        var startedAgent: Agent? = null
        var startedProjectId: String? = null
        var doneCount = 0

        composeRule.setContent {
            HarnessApkTheme {
                AgentInstallSuccessDialog(
                    agent = agent,
                    sourceProjectId = "project-1",
                    onStartConversation = { selectedAgent, sourceProjectId ->
                        startedAgent = selectedAgent
                        startedProjectId = sourceProjectId
                    },
                    onDone = { doneCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("智能体已安装").assertIsDisplayed()
        composeRule.onNodeWithText("开始对话").assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.onNodeWithText("完成").assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.runOnIdle {
            assertEquals(agent, startedAgent)
            assertEquals("project-1", startedProjectId)
            assertEquals(1, doneCount)
        }
    }

    private fun agent(): Agent = Agent(
        id = "agent-1",
        name = "李德胜",
        summary = "",
        activeVersion = 1,
        publisherFingerprint = "fingerprint",
        status = AgentStatus.READY,
        requiredCorpusCount = 0,
        installedCorpusCount = 0,
    )
}
