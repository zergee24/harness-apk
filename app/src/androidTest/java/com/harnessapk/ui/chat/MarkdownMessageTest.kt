package com.harnessapk.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.harnessapk.ui.markdown.MarkdownMessage
import org.junit.Rule
import org.junit.Test

class MarkdownMessageTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersModelMarkdownWithoutRawHeadingOrTableMarkers() {
        composeRule.setContent {
            MaterialTheme {
                MarkdownMessage(
                    markdown = """
                    前通用环境为准。
                    ---
                    ##一、主流天赋加点###1. Q技能（共生体/帽子） 主输出流
                    |等级|天赋|说明|
                    |---|---|---|
                    |1|**Pressurized Glands（压力腺体）**|Q任务：用尖刺击中英雄叠层|
                    """.trimIndent(),
                )
            }
        }

        composeRule.onNodeWithText("一、主流天赋加点").assertIsDisplayed()
        composeRule.onNodeWithText("1. Q技能（共生体/帽子） 主输出流").assertIsDisplayed()
        composeRule.onNodeWithText("等级").assertIsDisplayed()
        composeRule.onNodeWithText("Pressurized Glands（压力腺体）").assertIsDisplayed()
        composeRule.onNodeWithText("##一、主流天赋加点###1. Q技能（共生体/帽子） 主输出流").assertDoesNotExist()
        composeRule.onNodeWithText("|---|---|---|").assertDoesNotExist()
    }

    @Test
    fun rendersCodeBlockLanguageAndCopyAction() {
        composeRule.setContent {
            MaterialTheme {
                MarkdownMessage(
                    markdown = """
                    ```kotlin
                    val x = 1
                    ```
                    """.trimIndent(),
                )
            }
        }

        composeRule.onNodeWithText("kotlin").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("复制代码").assertIsDisplayed()
    }
}
