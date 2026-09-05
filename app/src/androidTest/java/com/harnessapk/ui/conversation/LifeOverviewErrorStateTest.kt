package com.harnessapk.ui.conversation

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.harnessapk.chat.LifeConversationDisplayStatus
import com.harnessapk.chat.LifeConversationOverviewItem
import com.harnessapk.chat.LifeConversationOverviewState
import com.harnessapk.storage.LifeConversationOrigin
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LifeOverviewErrorStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun overviewErrorWithoutCacheDoesNotRenderEmptyState() {
        composeRule.setContent {
            HarnessApkTheme {
                LazyColumn {
                    lifeOverviewItems(
                        state = LifeConversationOverviewState.Error("读取失败"),
                        agentsById = emptyMap(),
                        onRetry = {},
                        onOpenChat = {},
                        onEdit = {},
                        onArchive = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("记录读取失败").assertIsDisplayed()
        composeRule.onAllNodesWithText("还没有聊过").assertCountEquals(0)
    }

    @Test
    fun archiveErrorWithoutCacheDoesNotRenderEmptyState() {
        composeRule.setContent {
            HarnessApkTheme {
                LazyColumn {
                    archivedConversationItems(
                        state = LifeConversationOverviewState.Error("归档读取失败"),
                        onRetry = {},
                        onOpenChat = {},
                        onRestore = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("记录读取失败").assertIsDisplayed()
        composeRule.onAllNodesWithText("这里还没有归档记录。").assertCountEquals(0)
    }

    @Test
    fun cachedArchiveRowsRemainInteractiveAndFailedRestoreHasRetryFeedback() {
        var restoreCalls = 0
        composeRule.setContent {
            HarnessApkTheme {
                LazyColumn {
                    archivedConversationItems(
                        state = LifeConversationOverviewState.Error(
                            message = "暂时无法读取最新归档",
                            cachedItems = listOf(archivedItem()),
                        ),
                        onRetry = {},
                        onOpenChat = {},
                        onRestore = { restoreCalls++ },
                    )
                }
            }
        }

        composeRule.onNodeWithText("周末出行归档").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("更多").performClick()
        composeRule.onNodeWithText("恢复到最近聊过").performClick()
        composeRule.runOnIdle { assertEquals(1, restoreCalls) }
        assertEquals("恢复失败，请重试", archiveRestoreFeedbackMessage(restored = false))
    }

    private fun archivedItem() = LifeConversationOverviewItem(
        conversationId = "archived-1",
        title = "周末出行归档",
        summary = "归档的出行问题",
        updatedAt = 1_000L,
        status = LifeConversationDisplayStatus.COMPLETED,
        hasDraft = false,
        isArchived = true,
        origin = LifeConversationOrigin.LIFE_TEXT,
        agentId = null,
        agentVersion = null,
        canArchive = false,
    )
}
