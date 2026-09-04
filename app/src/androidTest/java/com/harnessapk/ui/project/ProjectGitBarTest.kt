package com.harnessapk.ui.project

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.harnessapk.git.GitChangeType
import com.harnessapk.git.GitFileChange
import com.harnessapk.git.GitStatusSummary
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Rule
import org.junit.Test

class ProjectGitBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun dirtyStatus(changeCount: Int) = GitStatusSummary(
        currentBranch = "test",
        isClean = false,
        stagedCount = 0,
        unstagedCount = changeCount,
        untrackedCount = 0,
        aheadCount = 0,
        behindCount = 0,
        files = List(changeCount) { index ->
            GitFileChange(path = "change-$index", type = GitChangeType.MODIFIED)
        },
    )

    @Test
    fun notAvailableShowsInitAndCloneActions() {
        composeRule.setContent {
            HarnessApkTheme {
                ProjectGitBar(
                    status = null,
                    expanded = false,
                    onToggleExpanded = {},
                    onInitRepository = {},
                    onCloneRepository = {},
                )
            }
        }

        composeRule.onNodeWithText("当前项目还不是 Git 仓库").assertIsDisplayed()
        composeRule.onNodeWithText("初始化 Git").assertIsDisplayed()
        composeRule.onNodeWithText("克隆仓库").assertIsDisplayed()
    }

    @Test
    fun cleanStateShowsBranchWithoutChangeCount() {
        composeRule.setContent {
            HarnessApkTheme {
                ProjectGitBar(
                    status = GitStatusSummary(
                        currentBranch = "main",
                        isClean = true,
                        stagedCount = 0,
                        unstagedCount = 0,
                        untrackedCount = 0,
                        aheadCount = 0,
                        behindCount = 0,
                        files = emptyList(),
                    ),
                    expanded = false,
                    onToggleExpanded = {},
                    onInitRepository = {},
                    onCloneRepository = {},
                )
            }
        }

        composeRule.onNodeWithText("main · 工作区干净").assertIsDisplayed()
    }

    @Test
    fun changedStateShowsChangeCountAndToggleExpands() {
        var expanded by mutableStateOf(false)
        composeRule.setContent {
            HarnessApkTheme {
                ProjectGitBar(
                    status = dirtyStatus(changeCount = 2),
                    expanded = expanded,
                    onToggleExpanded = { expanded = !expanded },
                    onInitRepository = {},
                    onCloneRepository = {},
                )
            }
        }

        composeRule.onNodeWithText("test · 2 项变更").assertIsDisplayed()
        composeRule.onNodeWithText("test · 2 项变更").performClick()
        composeRule.runOnIdle { check(expanded) }
    }
}
