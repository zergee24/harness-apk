package com.harnessapk.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.harnessapk.chat.UiMessagePartDraft
import com.harnessapk.chat.UiMessagePartType
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Rule
import org.junit.Test

class ReasoningPartTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeReasoningStartsExpandedAndCanBeCollapsed() {
        composeRule.setContent {
            HarnessApkTheme {
                ReasoningPart(
                    part = UiMessagePartDraft(
                        index = 4,
                        type = UiMessagePartType.REASONING,
                        content = "第一步\n第二步",
                        stable = false,
                    ),
                    autoExpand = true,
                )
            }
        }

        composeRule.onNodeWithText("第一步\n第二步").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("收起思考过程").performClick()
        composeRule.onNodeWithText("第一步\n第二步").assertDoesNotExist()
        composeRule.onNodeWithText("第二步").assertIsDisplayed()
    }
}
