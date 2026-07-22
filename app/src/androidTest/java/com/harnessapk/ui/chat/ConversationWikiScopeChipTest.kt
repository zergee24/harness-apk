package com.harnessapk.ui.chat

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConversationWikiScopeChipTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun scopeEntryIsVisibleAndOpensThePicker() {
        var opened = 0

        composeRule.setContent {
            HarnessApkTheme {
                ConversationWikiScopeChip(
                    state = ConversationWikiUiState(
                        toolbarLabel = "知识库",
                        options = emptyList(),
                    ),
                    onClick = { opened += 1 },
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("调整本会话可用知识库")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithText("知识库").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, opened) }
    }
}
