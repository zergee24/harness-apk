package com.harnessapk.ui.chat

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConversationIdentityPickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun draftPickerSelectsReadyPersonAndLockedPickerOnlyShowsDisclosure() {
        var selectedAgentId: String? = null
        var detailsShown = 0
        val draft = ConversationIdentityUiState(
            selectedAgentId = null,
            selectedName = "普通助手",
            mutable = true,
            options = listOf(
                ConversationIdentityOption(null, "普通助手", null),
                ConversationIdentityOption("a1", "李德胜", 3),
            ),
        )

        var state by mutableStateOf(draft)
        composeRule.setContent {
            HarnessApkTheme {
                ConversationIdentityPicker(
                    state = state,
                    onSelectAgentId = { selectedAgentId = it },
                    onShowDetails = { detailsShown += 1 },
                )
            }
        }

        composeRule.onNodeWithText("普通助手").performClick()
        composeRule.onNodeWithText("李德胜").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals("a1", selectedAgentId) }

        composeRule.runOnIdle {
            state = draft.copy(selectedAgentId = "a1", selectedName = "李德胜", mutable = false)
        }

        composeRule.onNodeWithText("李德胜 · 基于资料模拟").assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.runOnIdle { assertEquals(1, detailsShown) }
    }
}
