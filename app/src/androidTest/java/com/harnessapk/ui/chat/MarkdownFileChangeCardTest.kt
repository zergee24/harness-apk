package com.harnessapk.ui.chat

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.harnessapk.session.MarkdownFileChangeDraft
import com.harnessapk.session.MarkdownFileChangeFailure
import com.harnessapk.session.MarkdownFileChangeItem
import com.harnessapk.session.MarkdownFileChangeState
import com.harnessapk.session.MarkdownFileChangeStatus
import com.harnessapk.session.MarkdownUpdateOperation
import com.harnessapk.session.MarkdownUpdateProposal
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class MarkdownFileChangeCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appliedCardExposesFilesAndGitActions() {
        var openedFilesCount = 0
        var openedGitCount = 0

        composeRule.setContent {
            HarnessApkTheme {
                MarkdownFileChangeCard(
                    state = state(
                        status = MarkdownFileChangeStatus.APPLIED,
                        appliedPaths = listOf("requirements/prd.md"),
                    ),
                    onShowDiff = {},
                    onApply = {},
                    onRetry = {},
                    onRetryFailed = {},
                    onDismiss = {},
                    onOpenFiles = { openedFilesCount++ },
                    onOpenGit = { openedGitCount++ },
                )
            }
        }

        composeRule.onNodeWithText("requirements/prd.md").assertIsDisplayed()
        composeRule.onNodeWithText("查看文件").assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.onNodeWithText("查看 Git 变更").assertIsDisplayed().assertHasClickAction().performClick()

        assertEquals(1, openedFilesCount)
        assertEquals(1, openedGitCount)
    }

    @Test
    fun partialCardShowsFailureAndRetriesOnlyFailedItems() {
        var openedFilesCount = 0
        var openedGitCount = 0
        var retriedFailedCount = 0

        composeRule.setContent {
            HarnessApkTheme {
                MarkdownFileChangeCard(
                    state = state(
                        status = MarkdownFileChangeStatus.PARTIALLY_APPLIED,
                        appliedPaths = listOf("requirements/prd.md"),
                        failures = listOf(
                            MarkdownFileChangeFailure(
                                proposal = proposal("reports/review.md"),
                                errorMessage = "没有写入权限",
                            ),
                        ),
                    ),
                    onShowDiff = {},
                    onApply = {},
                    onRetry = {},
                    onRetryFailed = { retriedFailedCount++ },
                    onDismiss = {},
                    onOpenFiles = { openedFilesCount++ },
                    onOpenGit = { openedGitCount++ },
                )
            }
        }

        composeRule.onNodeWithText("部分文件已写入").assertIsDisplayed()
        composeRule.onNodeWithText("reports/review.md").assertIsDisplayed()
        composeRule.onNodeWithText("没有写入权限").assertIsDisplayed()
        composeRule.onNodeWithText("查看文件").assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.onNodeWithText("查看 Git 变更").assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.onNodeWithText("仅重试失败项").assertIsDisplayed().assertHasClickAction()
            .performClick()

        assertEquals(1, openedFilesCount)
        assertEquals(1, openedGitCount)
        assertEquals(1, retriedFailedCount)
    }
}

private fun proposal(path: String) = MarkdownUpdateProposal(
    operation = MarkdownUpdateOperation.CREATE,
    path = path,
    title = path.substringAfterLast('/').substringBeforeLast('.'),
    reason = "测试",
    markdown = "# Test",
)

private fun state(
    status: MarkdownFileChangeStatus,
    appliedPaths: List<String>,
    failures: List<MarkdownFileChangeFailure> = emptyList(),
): MarkdownFileChangeState {
    val proposals = (appliedPaths.map(::proposal) + failures.map { it.proposal })
        .distinctBy { it.path }
    return MarkdownFileChangeState(
        draft = MarkdownFileChangeDraft(
            id = "draft",
            conversationId = "conversation",
            projectId = "project",
            sourceUserMessageId = "user",
            status = status,
            summary = markdownFileChangeCardTitle(status, proposals.size),
            createdAt = 1L,
            updatedAt = 1L,
        ),
        items = proposals.map { proposal ->
            MarkdownFileChangeItem(
                draftId = "draft",
                operation = proposal.operation,
                path = proposal.path,
                title = proposal.title,
                reason = proposal.reason,
                markdown = proposal.markdown,
                addedLineCount = 1,
                removedLineCount = 0,
                retained = true,
            )
        },
        appliedPaths = appliedPaths,
        applyFailures = failures,
    )
}
