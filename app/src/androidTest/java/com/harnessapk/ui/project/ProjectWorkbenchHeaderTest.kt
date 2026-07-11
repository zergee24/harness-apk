package com.harnessapk.ui.project

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Rule
import org.junit.Test

class ProjectWorkbenchHeaderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectedProjectShowsSinglePrimarySessionActionAndReadOnlyOverview() {
        composeRule.setContent {
            HarnessApkTheme {
                ProjectWorkbenchHeader(
                    projectName = "家庭健康记录",
                    overview = ProjectWorkbenchOverview("2 个会话", "3 个文件", "Git 状态未读取"),
                    onSelectProject = {},
                    onCreateSession = {},
                    overflowContent = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("切换项目")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithText("新建项目会话")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithText("2 个会话").assertIsDisplayed().assertHasNoClickAction()
        composeRule.onNodeWithText("3 个文件").assertIsDisplayed().assertHasNoClickAction()
        composeRule.onNodeWithText("Git 状态未读取").assertIsDisplayed().assertHasNoClickAction()
    }
}
