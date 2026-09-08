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
        composeRule.onAllNodesWithText("还没有历史记录").assertCountEquals(0)
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

    @Test
    fun completedOverviewRowUsesInlineTimeWithoutCompletionBadge() {
        composeRule.setContent {
            HarnessApkTheme {
                LazyColumn {
                    lifeOverviewItems(
                        state = LifeConversationOverviewState.Content(
                            items = listOf(
                                archivedItem().copy(
                                    title = "今天的长标题会在需要时换行显示",
                                    summary = "普通完成记录保留标题、摘要和时间。",
                                    updatedAt = System.currentTimeMillis(),
                                    isArchived = false,
                                    canArchive = true,
                                ),
                            ),
                        ),
                        agentsById = emptyMap(),
                        onRetry = {},
                        onOpenChat = {},
                        onEdit = {},
                        onArchive = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("今天的长标题会在需要时换行显示").assertIsDisplayed()
        composeRule.onNodeWithText("今天").assertIsDisplayed()
        composeRule.onAllNodesWithText("已完成").assertCountEquals(0)
    }

    @Test
    fun historySearchMatchesTitleOrSummaryAndShowsDistinctNoMatchState() {
        val titleMatch = archivedItem().copy(title = "周末购物清单")
        val summaryMatch = archivedItem().copy(
            conversationId = "archived-2",
            title = "出门准备",
            summary = "整理购物清单和路线",
        )
        val items = listOf(titleMatch, summaryMatch)

        assertEquals(listOf(titleMatch, summaryMatch), filterLifeOverviewItems(items, "清单"))

        composeRule.setContent {
            HarnessApkTheme {
                LazyColumn {
                    lifeOverviewItems(
                        state = LifeConversationOverviewState.Content(items = items),
                        agentsById = emptyMap(),
                        onRetry = {},
                        onOpenChat = {},
                        onEdit = {},
                        onArchive = {},
                        searchQuery = "不存在",
                    )
                }
            }
        }

        composeRule.onNodeWithText("没有匹配的记录").assertIsDisplayed()
        composeRule.onAllNodesWithText("还没有历史记录").assertCountEquals(0)
    }

    @Test
    fun emptyHistoryKeepsItsOwnEmptyStateWhenSearchQueryIsPresent() {
        composeRule.setContent {
            HarnessApkTheme {
                LazyColumn {
                    lifeOverviewItems(
                        state = LifeConversationOverviewState.Content(items = emptyList()),
                        agentsById = emptyMap(),
                        onRetry = {},
                        onOpenChat = {},
                        onEdit = {},
                        onArchive = {},
                        searchQuery = "不存在",
                    )
                }
            }
        }

        composeRule.onNodeWithText("还没有历史记录").assertIsDisplayed()
        composeRule.onAllNodesWithText("没有匹配的记录").assertCountEquals(0)
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
