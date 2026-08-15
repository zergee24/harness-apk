package com.harnessapk.ui.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.harnessapk.remote.RemoteTimelineItem
import com.harnessapk.remote.RemoteConnectionStatus
import com.harnessapk.remote.RemoteThread
import com.harnessapk.remote.RemoteThreadExecution
import com.harnessapk.remote.RemoteThreadExecutionState
import com.harnessapk.remote.WorkspaceCandidate
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Rule
import org.junit.Test

class RemoteScreenComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun threadListHeaderMakesHostStatusAndPrimaryActionObvious() {
        var createRequests = 0
        composeRule.setContent {
            HarnessApkTheme {
                Box(Modifier.width(320.dp)) {
                    RemoteThreadListHeader(
                        hostName = "Tony Mac mini",
                        connectionStatus = RemoteConnectionStatus.CONNECTED,
                        creating = false,
                        onRefresh = {},
                        onCreate = { createRequests++ },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Tony Mac mini").assertIsDisplayed()
        composeRule.onNodeWithText("在线").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("刷新远程会话").assertIsDisplayed()
        composeRule.onNodeWithText("新建会话").performClick()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(1, createRequests) }
    }

    @Test
    fun threadCardPrioritizesReadableSummaryOverRawPayloadAndPath() {
        val updatedAt = 1_000_000L
        composeRule.setContent {
            HarnessApkTheme {
                RemoteThreadCard(
                    thread = RemoteThread(
                        id = "thread-1",
                        title = "M3 独立实施与验收",
                        preview = """
                            <codex_delegation>
                              <source_thread_id>019fe1cf</source_thread_id>
                              <input>任务：完整执行 M3，并验证所有自动化 Gate。</input>
                            </codex_delegation>
                        """.trimIndent(),
                        cwd = "/Users/tony/.codex/worktrees/0e43/harness-apk",
                        updatedAt = updatedAt,
                        status = "idle",
                        latestUserMessage = "这是最近一句用户输入",
                    ),
                    nowMillis = updatedAt + 30_000L,
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("打开远程会话：M3 独立实施与验收").assertIsDisplayed()
        composeRule.onNodeWithText("这是最近一句用户输入").assertIsDisplayed()
        composeRule.onAllNodesWithText("任务：完整执行 M3，并验证所有自动化 Gate。").assertCountEquals(0)
        composeRule.onNodeWithText("0e43 / harness-apk").assertIsDisplayed()
        composeRule.onNodeWithText("刚刚").assertIsDisplayed()
        composeRule.onAllNodesWithText("<codex_delegation>", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("/Users/tony/.codex/worktrees", substring = true).assertCountEquals(0)
    }

    @Test
    fun threadCardMakesRunningStateVisibleWithoutOpeningTheConversation() {
        composeRule.setContent {
            HarnessApkTheme {
                RemoteThreadCard(
                    thread = RemoteThread(
                        id = "thread-running",
                        title = "正在执行的任务",
                        preview = "检查完整自动化",
                        cwd = "/workspace",
                        updatedAt = 1_000L,
                        status = "active",
                        execution = RemoteThreadExecution(RemoteThreadExecutionState.RUNNING),
                    ),
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithText("执行中").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("会话状态：执行中").assertIsDisplayed()
    }

    @Test
    fun truncatedDelegationPreviewStillStartsAtReadableInput() {
        val truncated = """
            <codex_delegation>
              <source_thread_id>019fe1cf</source_thread_id>
              <input>任务：完整执行 M3，并验证所有自动化 Gate。

            项目：/Users/tony/Documents/harness-apk
        """.trimIndent()

        org.junit.Assert.assertEquals(
            "任务：完整执行 M3，并验证所有自动化 Gate。",
            remoteThreadPreviewText(truncated),
        )
    }

    @Test
    fun visibleThreadCardRequestsLatestUserMessageOnlyWhileMissing() {
        val requests = mutableListOf<String>()
        val thread = mutableStateOf(
            RemoteThread(
                id = "thread-lazy",
                title = "懒加载会话",
                preview = "最早一句",
                cwd = "/workspace",
                updatedAt = 1_000L,
                status = "idle",
            ),
        )
        composeRule.setContent {
            HarnessApkTheme {
                RemoteThreadCard(
                    thread = thread.value,
                    onLoadLatestUserMessage = { requests += it },
                    onClick = {},
                )
            }
        }

        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(listOf("thread-lazy"), requests)
            thread.value = thread.value.copy(latestUserMessage = "最新一句")
        }
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(listOf("thread-lazy"), requests)
        }
    }

    @Test
    fun threadCardStartsLazySummaryAfterBridgeCapabilityArrives() {
        val requests = mutableListOf<String>()
        val enabled = mutableStateOf(false)
        val thread = RemoteThread(
            id = "thread-capability",
            title = "能力晚到的会话",
            preview = "最早一句",
            cwd = "/workspace",
            updatedAt = 1_000L,
            status = "idle",
        )
        composeRule.setContent {
            HarnessApkTheme {
                RemoteThreadCard(
                    thread = thread,
                    latestUserMessageLoadingEnabled = enabled.value,
                    onLoadLatestUserMessage = { requests += it },
                    onClick = {},
                )
            }
        }

        composeRule.runOnIdle {
            org.junit.Assert.assertTrue(requests.isEmpty())
            enabled.value = true
        }
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(listOf("thread-capability"), requests)
        }
    }

    @Test
    fun activeThreadCardPollsLazilyAndStopsAfterTerminalState() {
        composeRule.mainClock.autoAdvance = false
        val requests = mutableListOf<String>()
        val thread = mutableStateOf(
            RemoteThread(
                id = "thread-polling",
                title = "执行状态轮询",
                preview = "检查状态",
                cwd = "/workspace",
                updatedAt = 1_000L,
                status = "active",
                latestUserMessage = "检查状态",
                execution = RemoteThreadExecution(RemoteThreadExecutionState.RUNNING),
            ),
        )
        composeRule.setContent {
            HarnessApkTheme {
                RemoteThreadCard(
                    thread = thread.value,
                    executionStatusLoadingEnabled = true,
                    onLoadLatestUserMessage = { requests += it },
                    onClick = {},
                )
            }
        }

        composeRule.runOnIdle { org.junit.Assert.assertEquals(1, requests.size) }
        composeRule.mainClock.advanceTimeBy(3_000L)
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(2, requests.size)
            thread.value = thread.value.copy(
                status = "idle",
                execution = RemoteThreadExecution(RemoteThreadExecutionState.COMPLETED),
            )
        }
        composeRule.mainClock.advanceTimeBy(6_000L)
        composeRule.runOnIdle { org.junit.Assert.assertEquals(2, requests.size) }
    }

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
    fun growingStreamingReplyKeepsItsBottomAboveTheComposer() {
        val initial = (1..20).map { index ->
            RemoteTimelineItem("old-$index", "userMessage", "历史消息 $index", "completed")
        } + RemoteTimelineItem("agent-live", "agentMessage", "开始回答", "streaming")
        val items = mutableStateOf(initial)
        lateinit var timelineState: LazyListState
        composeRule.setContent {
            HarnessApkTheme {
                Box(Modifier.height(420.dp)) {
                    timelineState = rememberLazyListState()
                    RemoteTimelineList(
                        threadId = "thread-live",
                        items = items.value,
                        loading = false,
                        listState = timelineState,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            items.value = initial.dropLast(1) + RemoteTimelineItem(
                "agent-live",
                "agentMessage",
                "# READY\n\n- 第一项说明\n- 第二项说明\n- 第三项说明\n- 第四项说明",
                "completed",
            )
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val layout = timelineState.layoutInfo
            val latest = layout.visibleItemsInfo.single { it.key == "agent-live" }
            org.junit.Assert.assertTrue(
                "latest bottom=${latest.offset + latest.size}, viewport=${layout.viewportEndOffset}",
                latest.offset + latest.size <= layout.viewportEndOffset,
            )
        }
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
    fun longCommandIsCompactByDefaultAndCanBeExpandedOnDemand() {
        composeRule.setContent {
            HarnessApkTheme {
                TimelineCard(
                    RemoteTimelineItem(
                        id = "command-long",
                        kind = "commandExecution",
                        text = "git status && ".repeat(30),
                        status = "failed",
                    ),
                )
            }
        }

        composeRule.onNodeWithText("查看完整命令").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("收起命令").assertIsDisplayed()
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
    fun workingBannerExplainsLongWaitInsteadOfLeavingOnlyAStopIcon() {
        composeRule.setContent {
            HarnessApkTheme {
                RemoteWorkingBanner(startedAtMillis = 1_000L, nowMillis = 48_000L)
            }
        }

        composeRule.onNodeWithText("Codex 正在处理 · 已等待 47 秒").assertIsDisplayed()
        composeRule.onNodeWithText("收到新内容后会继续实时显示").assertIsDisplayed()
    }

    @Test
    fun terminalStatusBannerKeepsCompletionVisibleInsideConversation() {
        composeRule.setContent {
            HarnessApkTheme {
                RemoteExecutionStatusBanner(
                    execution = RemoteThreadExecution(
                        state = RemoteThreadExecutionState.COMPLETED,
                        completedAtMillis = 48_000L,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("任务已完成").assertIsDisplayed()
        composeRule.onNodeWithText("可以继续发送消息开始下一轮").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("当前会话状态：已完成").assertIsDisplayed()
    }

    @Test
    fun lazyContinuationNoticeExplainsThatTheSourceThreadWasPreserved() {
        composeRule.setContent {
            HarnessApkTheme {
                TimelineCard(
                    RemoteTimelineItem(
                        id = "continuation:thread-new",
                        kind = "continuation",
                        text = "历史较长，已懒加载最近上下文并在同一工作目录创建续聊会话。原会话仍保留，可随时返回查看。",
                    ),
                )
            }
        }

        composeRule.onNodeWithText("大会话续聊").assertIsDisplayed()
        composeRule.onNodeWithText("历史较长，已懒加载最近上下文并在同一工作目录创建续聊会话。原会话仍保留，可随时返回查看。").assertIsDisplayed()
    }

    @Test
    fun activeDetailPollsUntilBridgeReportsTerminalState() {
        composeRule.mainClock.autoAdvance = false
        val requests = mutableListOf<String>()
        val thread = mutableStateOf(
            RemoteThread(
                id = "thread-detail-polling",
                title = "详情状态恢复",
                preview = "执行测试",
                cwd = "/workspace",
                updatedAt = 1_000L,
                status = "active",
                execution = RemoteThreadExecution(RemoteThreadExecutionState.RUNNING),
            ),
        )
        composeRule.setContent {
            HarnessApkTheme {
                RemoteThreadDetailStatus(
                    thread = thread.value,
                    execution = thread.value.execution,
                    executionStatusLoadingEnabled = true,
                    onLoadThreadSummary = { requests += it },
                )
            }
        }

        composeRule.runOnIdle { org.junit.Assert.assertEquals(1, requests.size) }
        composeRule.mainClock.advanceTimeBy(3_000L)
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(2, requests.size)
            thread.value = thread.value.copy(
                status = "idle",
                execution = RemoteThreadExecution(RemoteThreadExecutionState.COMPLETED),
            )
        }
        composeRule.mainClock.advanceTimeBy(6_000L)
        composeRule.runOnIdle { org.junit.Assert.assertEquals(2, requests.size) }
        composeRule.onNodeWithText("任务已完成").assertIsDisplayed()
    }

    @Test
    fun unknownDetailRetriesRecoveryInsteadOfStayingUnsyncedForever() {
        composeRule.mainClock.autoAdvance = false
        val requests = mutableListOf<String>()
        val thread = mutableStateOf(
            RemoteThread(
                id = "thread-detail-recovery",
                title = "终态恢复",
                preview = "等待同步",
                cwd = "/workspace",
                updatedAt = 2_000L,
                status = "notLoaded",
            ),
        )
        composeRule.setContent {
            HarnessApkTheme {
                RemoteThreadDetailStatus(
                    thread = thread.value,
                    execution = thread.value.execution,
                    executionStatusLoadingEnabled = true,
                    onLoadThreadSummary = { requests += it },
                )
            }
        }

        composeRule.runOnIdle { org.junit.Assert.assertEquals(1, requests.size) }
        composeRule.mainClock.advanceTimeBy(10_000L)
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(2, requests.size)
            thread.value = thread.value.copy(
                status = "idle",
                execution = RemoteThreadExecution(RemoteThreadExecutionState.COMPLETED),
            )
        }
        composeRule.mainClock.advanceTimeBy(20_000L)
        composeRule.runOnIdle { org.junit.Assert.assertEquals(2, requests.size) }
        composeRule.onNodeWithContentDescription("当前会话状态：已完成").assertIsDisplayed()
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
    fun timelineOffersExplicitOlderHistoryPageWithoutReplacingConversation() {
        var requests = 0
        composeRule.setContent {
            HarnessApkTheme {
                Box(Modifier.height(600.dp)) {
                    RemoteTimelineList(
                        threadId = "thread-1",
                        items = listOf(
                            RemoteTimelineItem("user-1", "userMessage", "最近问题"),
                            RemoteTimelineItem("agent-1", "agentMessage", "最近回答"),
                        ),
                        loading = false,
                        canLoadOlder = true,
                        loadingOlder = false,
                        onLoadOlder = { requests++ },
                    )
                }
            }
        }

        composeRule.onNodeWithText("加载更早内容").assertIsDisplayed().performClick()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(1, requests) }
        composeRule.onNodeWithText("最近回答").assertIsDisplayed()
    }

    @Test
    fun timelineCanContinuePastAnEmptyRenderableSummaryPage() {
        composeRule.setContent {
            HarnessApkTheme {
                Box(Modifier.height(600.dp)) {
                    RemoteTimelineList(
                        threadId = "thread-1",
                        items = emptyList(),
                        loading = false,
                        canLoadOlder = true,
                    )
                }
            }
        }

        composeRule.onNodeWithText("加载更早内容").assertIsDisplayed()
    }

    @Test
    fun prependingOlderPageKeepsThePreviousFirstMessageVisible() {
        val recent = (1..12).map { index ->
            RemoteTimelineItem("recent-$index", "agentMessage", "原有消息 $index\n第二行内容", "completed")
        }
        val items = mutableStateOf(recent)
        val loadingOlder = mutableStateOf(false)
        lateinit var timelineState: LazyListState
        var anchorOffsetBefore = 0
        composeRule.setContent {
            HarnessApkTheme {
                Box(Modifier.height(400.dp)) {
                    timelineState = rememberLazyListState()
                    RemoteTimelineList(
                        threadId = "thread-1",
                        items = items.value,
                        loading = false,
                        canLoadOlder = true,
                        loadingOlder = loadingOlder.value,
                        onLoadOlder = { loadingOlder.value = true },
                        listState = timelineState,
                    )
                }
            }
        }

        composeRule.runOnIdle { timelineState.requestScrollToItem(0) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            anchorOffsetBefore = timelineState.layoutInfo.visibleItemsInfo.single { it.key == "recent-1" }.offset
        }
        composeRule.onNodeWithText("加载更早内容").performClick()
        composeRule.onNodeWithText("正在加载更早内容…").assertIsDisplayed()
        composeRule.runOnIdle {
            items.value = (1..12).map { index ->
                RemoteTimelineItem("older-$index", "userMessage", "更早消息 $index\n第二行内容", "completed")
            } + recent
            loadingOlder.value = false
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val anchorAfter = timelineState.layoutInfo.visibleItemsInfo.single { it.key == "recent-1" }
            org.junit.Assert.assertEquals(anchorOffsetBefore, anchorAfter.offset)
        }
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

        composeRule.onNodeWithText("新建远程会话").assertIsDisplayed()
        composeRule.onNodeWithText("Harness APK").performClick()
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(listOf("/Users/tony/Documents/harness-apk"), selected)
        }
    }
}
