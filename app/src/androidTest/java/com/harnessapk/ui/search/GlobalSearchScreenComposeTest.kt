package com.harnessapk.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.harnessapk.search.LocalSearchDocumentType
import com.harnessapk.search.LocalSearchResult
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GlobalSearchScreenComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun narrowLargeFontUsesOneResultListAndKeepsExactTargetClickable() {
        val opened = mutableListOf<String>()
        val result = LocalSearchResult(
            id = "message:1",
            type = LocalSearchDocumentType.MESSAGE,
            title = "家庭健康长期管理计划",
            snippet = "讨论老人用药提醒和每周复诊安排",
            conversationId = "conversation-1",
            messageId = "message-1",
            projectId = "project-1",
            updatedAt = 1L,
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                HarnessApkTheme {
                    Box(modifier = Modifier.width(320.dp)) {
                        GlobalSearchContent(
                            query = "老人用药",
                            onQueryChange = {},
                            results = listOf(result),
                            loading = false,
                            onResult = { opened += it.id },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("消息").assertIsDisplayed()
        composeRule.onNodeWithText("家庭健康长期管理计划")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(listOf("message:1"), opened) }
    }
}
