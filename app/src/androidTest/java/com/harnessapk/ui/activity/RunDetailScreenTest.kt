package com.harnessapk.ui.activity

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.remote.RemoteCompletionEvidence
import com.harnessapk.remote.RemoteCompletionVerification
import com.harnessapk.remote.RemoteTimelinePresentation
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.RemoteRunEntity
import com.harnessapk.storage.RemoteRunEventEntity
import com.harnessapk.storage.MarkdownChangeDraftItemEntity
import com.harnessapk.ui.theme.HarnessApkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun completionAndUnknownTimelineRemainReadableAt320DpAndFont13() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                HarnessApkTheme {
                    Box(Modifier.width(320.dp)) {
                        androidx.compose.foundation.layout.Column {
                            RemoteTimelineSection(
                                listOf(
                                    RemoteTimelinePresentation(
                                        id = "event-1", itemId = "item-1", title = "正在处理",
                                        detail = "", diagnosticPayload = "很长的未知命令 --future-option diagnostic-value",
                                        createdAt = 1L, compressible = true, kind = "FUTURE_TOOL",
                                    ),
                                ),
                            )
                            RemoteCompletionCard(
                                RemoteCompletionEvidence(
                                    summary = "完成修复", changedFiles = emptyList(), tests = emptyList(),
                                    gitState = null, unresolved = emptyList(), completedAt = 1L,
                                ),
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("正在处理").assertIsDisplayed()
        composeRule.onNodeWithText("文件未验证").assertIsDisplayed()
        composeRule.onNodeWithText("测试未验证").assertIsDisplayed()
        composeRule.onNodeWithText("Git 未验证").assertIsDisplayed()
        composeRule.onNodeWithText("旧版结果未验证").assertIsDisplayed()
        composeRule.onNodeWithText("此结果可查看，但不能作为 M3 项目沉淀证据。请升级 Mac Bridge 后运行新任务。")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("任务完成证据")
            .assertContentDescriptionEquals("任务完成证据")
        composeRule.onNodeWithText("查看诊断信息").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("很长的未知命令 --future-option diagnostic-value").assertIsDisplayed()
        composeRule.onNodeWithText("查看结果").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun tenThousandEventsQueryOnlyLoadsLatestHundredUnder200MsP95() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.remoteDao().insertRun(run())
            db.remoteDao().insertEvents((1L..10_000L).map(::event))
            val samples = mutableListOf<Long>()
            repeat(10) {
                val started = SystemClock.elapsedRealtimeNanos()
                val rows = db.remoteDao().observeRecentEvents("run-1", 100).first()
                samples += (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
                assertEquals(100, rows.size)
                assertEquals(10_000L, rows.first().sequence)
            }
            val older = db.remoteDao().eventsBefore("run-1", 9_901L, 100)
            assertEquals(100, older.size)
            assertEquals(9_900L, older.first().sequence)
            val sorted = samples.sorted()
            val p95 = sorted[((sorted.size * 95 + 99) / 100 - 1).coerceAtLeast(0)]
            Log.i("HarnessM2Perf", "timeline_query_10k_p95_ms=$p95 samples=$samples")
            assertTrue("p95=${p95}ms samples=$samples", p95 < 200L)
        } finally {
            db.close()
        }
    }

    @Test
    fun remoteCompletionUsesDepositThenOneDiffApplyReviewAt320Dp() {
        composeRule.setContent {
            HarnessApkTheme {
                Box(Modifier.width(320.dp)) {
                    androidx.compose.foundation.layout.Column {
                        RemoteCompletionCard(
                            evidence = RemoteCompletionEvidence(
                                summary = "完成 M3",
                                changedFiles = listOf("docs/result.md"),
                                tests = emptyList(),
                                gitState = "UNCOMMITTED",
                                unresolved = emptyList(),
                                completedAt = 1L,
                                schemaVersion = 2,
                                completionId = "completion-1",
                                verification = RemoteCompletionVerification.VERIFIED_V2,
                            ),
                            onDepositToProject = {},
                        )
                        RemoteMarkdownDraftReviewCard(
                            items = listOf(
                                MarkdownChangeDraftItemEntity(
                                    id = "item-1", draftId = "draft-1", itemIndex = 0,
                                    operation = "CREATE", relativePath = "reports/remote-run.md",
                                    title = "Remote Run", reason = "沉淀", proposedMarkdown = "# 完成 M3",
                                    retained = true, baselineSha256 = null, expectedAbsent = true,
                                    applyStatus = null, applyErrorMessage = null,
                                ),
                            ),
                            status = "READY",
                            onApply = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("沉淀到项目").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        composeRule.onNodeWithText("审核 Diff").assertIsDisplayed()
        composeRule.onNodeWithText("应用所选").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
    }

    private fun run() = RemoteRunEntity(
        id = "run-1", projectId = "project-1", projectNameSnapshot = "Harness APK",
        bindingId = "binding-1", bindingSnapshotJson = "{}", hostId = "host-1",
        threadId = "thread-1", turnId = "turn-1", objective = "实现 M2", status = "RUNNING",
        latestLine = "正在运行", lastLogicalSequence = 0L, startedAt = 1L, updatedAt = 1L,
        completedAt = null, completionJson = null, errorMessage = null,
    )

    private fun event(sequence: Long) = RemoteRunEventEntity(
        logicalEventId = "event-$sequence", runId = "run-1", hostId = "host-1", deviceId = "device-1",
        sequence = sequence, type = "run.timeline", itemId = "item-$sequence",
        presentationKind = "STATUS", payloadJson = "{}", createdAt = sequence,
    )
}
