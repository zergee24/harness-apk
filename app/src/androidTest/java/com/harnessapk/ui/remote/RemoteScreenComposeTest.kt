package com.harnessapk.ui.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.harnessapk.remote.RemoteTimelineItem
import com.harnessapk.remote.WorkspaceCandidate
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Rule
import org.junit.Test

class RemoteScreenComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun openingThreadHistoryPositionsLatestMessageOnScreen() {
        val history = (1..40).map { index ->
            RemoteTimelineItem(
                id = "message-$index",
                kind = if (index % 2 == 0) "agentMessage" else "userMessage",
                text = "远程消息 $index",
                status = "completed",
            )
        }

        composeRule.setContent {
            HarnessApkTheme {
                Box(Modifier.height(600.dp)) {
                    RemoteTimelineList(
                        threadId = "thread-1",
                        items = history,
                        loading = false,
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("远程消息 40").assertIsDisplayed()
    }

    @Test
    fun timelineCardRendersConversationWithLocalizedLabelsAndMarkdown() {
        composeRule.setContent {
            HarnessApkTheme {
                TimelineCard(
                    RemoteTimelineItem(
                        id = "agent-1",
                        kind = "agentMessage",
                        text = "# 检查结果\n\n**渲染正常**",
                        status = "completed",
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Codex").assertIsDisplayed()
        composeRule.onNodeWithText("检查结果").assertIsDisplayed()
        composeRule.onNodeWithText("渲染正常").assertIsDisplayed()
        composeRule.onNodeWithText("已完成").assertIsDisplayed()
        composeRule.onAllNodesWithText("agentMessage").assertCountEquals(0)
        composeRule.onAllNodesWithText("completed").assertCountEquals(0)
    }

    @Test
    fun composerImeActionSendsMessageAndClearsInput() {
        val sent = mutableListOf<String>()
        composeRule.setContent {
            HarnessApkTheme {
                RemoteComposer(isWorking = false, onSubmit = { sent += it })
            }
        }

        val input = composeRule.onNodeWithTag("remote-composer-input")
        input.performTextInput("Reply READY")
        input.performImeAction()

        composeRule.runOnIdle { org.junit.Assert.assertEquals(listOf("Reply READY"), sent) }
        composeRule.onNodeWithTag("remote-composer-input")
            .assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test
    fun timelineExplainsLoadingAndEmptyStatesInsteadOfShowingBlankPage() {
        val loading = mutableStateOf(true)
        composeRule.setContent {
            HarnessApkTheme {
                RemoteTimelineList(
                    threadId = "thread-1",
                    items = emptyList(),
                    loading = loading.value,
                )
            }
        }
        composeRule.onNodeWithText("正在读取 Mac 会话…").assertIsDisplayed()

        composeRule.runOnIdle { loading.value = false }
        composeRule.onNodeWithText("这个会话还没有消息").assertIsDisplayed()
    }

    @Test
    fun createThreadOffersRecentMacWorkspacesWithoutTypingAbsolutePath() {
        val selected = mutableListOf<String>()
        composeRule.setContent {
            HarnessApkTheme {
                CreateThreadDialog(
                    onDismiss = {},
                    onCreate = { selected += it },
                    candidates = listOf(
                        WorkspaceCandidate(
                            workspaceId = "workspace-1",
                            displayName = "Harness APK",
                            cwd = "/Users/tony/Documents/harness-apk",
                            repositoryLabel = "harness-apk",
                            branch = "test",
                            repositoryFingerprint = "fingerprint",
                            lastUsedAt = 1L,
                        ),
                    ),
                    candidatesLoaded = true,
                    creating = false,
                )
            }
        }

        composeRule.onNodeWithText("Harness APK").performClick()
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(listOf("/Users/tony/Documents/harness-apk"), selected)
        }
    }
}
