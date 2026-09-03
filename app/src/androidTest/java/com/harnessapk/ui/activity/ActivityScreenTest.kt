package com.harnessapk.ui.activity

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.harnessapk.activity.ActivityItem
import com.harnessapk.activity.ActivityState
import com.harnessapk.activity.ActivityTarget
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ActivityScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun groupedActivityAt320DpOpensExactRunAndExposesPendingSemantics() {
        var openedRun: String? = null
        composeRule.setContent {
            HarnessApkTheme {
                Box(Modifier.width(320.dp)) {
                    ActivityScreenContent(
                        state = ActivityState(
                            needsAction = listOf(item("approval:1", "run-1", "需要审批")),
                            inProgress = listOf(item("run:2", "run-2", "正在运行")),
                            recentCompleted = listOf(item("run:3", "run-3", "已完成")),
                        ),
                        onOpenChat = {},
                        onOpenRun = { openedRun = it },
                    )
                }
            }
        }

        composeRule.onNodeWithText("需要处理").assertIsDisplayed()
        composeRule.onNodeWithText("进行中").assertIsDisplayed()
        composeRule.onNodeWithText("最近完成").assertIsDisplayed()
        composeRule.onNodeWithText("需要审批").assertContentDescriptionEquals("需要审批，远程任务，需要处理")
        composeRule.onNodeWithText("需要审批").performClick()
        assertEquals("run-1", openedRun)
    }

    private fun item(id: String, targetId: String, title: String) = ActivityItem(
        id = id,
        target = ActivityTarget.REMOTE_RUN,
        targetId = targetId,
        title = title,
        summary = "Harness APK",
        statusLabel = title,
        risk = null,
        updatedAt = 1L,
    )
}
