package com.harnessapk.ui.chat

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatImageComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val testUri = Uri.parse("content://com.harnessapk.test/image.jpg")

    @Test
    fun thumbnailOpensImagePreview() {
        var opened = false

        composeRule.setContent {
            HarnessApkTheme {
                ChatImageThumbnail(
                    image = ChatImageDisplay.Ready(testUri, "image/jpeg"),
                    onOpen = { opened = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("打开图片预览").performClick()

        assertTrue(opened)
    }

    @Test
    fun previewClosesAndSavesOnlyAfterSaveClick() {
        var previewOpen by mutableStateOf(false)
        var saveCalls = 0

        composeRule.setContent {
            HarnessApkTheme {
                ChatImageThumbnail(
                    image = ChatImageDisplay.Ready(testUri, "image/jpeg"),
                    onOpen = { previewOpen = true },
                )
                if (previewOpen) {
                    ChatImagePreviewDialog(
                        image = ChatImageDisplay.Ready(testUri, "image/jpeg"),
                        onDismiss = { previewOpen = false },
                        onSave = { saveCalls++ },
                        saveStatus = null,
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("打开图片预览").performClick()
        composeRule.onNodeWithContentDescription("关闭图片预览").assertIsDisplayed()
        assertEquals(0, saveCalls)
        composeRule.onNodeWithText("保存图片").performClick()
        assertEquals(1, saveCalls)
        composeRule.onNodeWithContentDescription("关闭图片预览").performClick()
        composeRule.onNodeWithContentDescription("关闭图片预览").assertDoesNotExist()
    }

    @Test
    fun failedThumbnailShowsRetryAction() {
        composeRule.setContent {
            HarnessApkTheme {
                ChatImageThumbnail(
                    image = ChatImageDisplay.Failed("图片地址无效"),
                    onOpen = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("图片加载失败").assertIsDisplayed()
        composeRule.onNodeWithText("重试").assertHasClickAction()
    }
}
