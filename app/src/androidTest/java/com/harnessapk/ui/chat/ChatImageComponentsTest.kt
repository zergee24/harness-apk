package com.harnessapk.ui.chat

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Base64

class ChatImageComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val testUri = Uri.parse("content://com.harnessapk.test/image.jpg")
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val imageDirectory = File(context.filesDir, "chat-images/chat-image-component-tests")

    @After
    fun tearDown() {
        imageDirectory.deleteRecursively()
    }

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

    @Test
    fun readyThumbnailDecodesValidImage() {
        val uri = imageUri(
            name = "valid.png",
            bytes = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9M0V4AAAAASUVORK5CYII=",
            ),
        )

        composeRule.setContent {
            HarnessApkTheme {
                ChatImageThumbnail(
                    image = ChatImageDisplay.Ready(uri, "image/png"),
                    onOpen = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("会话图片缩略图").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun corruptedReadyThumbnailShowsFailureAndRetryAction() {
        val uri = imageUri(name = "corrupted.jpg", bytes = "not-an-image".encodeToByteArray())
        var image by mutableStateOf<ChatImageDisplay>(ChatImageDisplay.Ready(uri, "image/jpeg"))

        composeRule.setContent {
            HarnessApkTheme {
                ChatImageThumbnail(
                    image = image,
                    onOpen = {},
                    onDecodeFailed = { _, message -> image = ChatImageDisplay.Failed(message) },
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("图片加载失败").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("重试").assertHasClickAction()
    }

    private fun imageUri(name: String, bytes: ByteArray): Uri {
        imageDirectory.mkdirs()
        val image = File(imageDirectory, name).apply { writeBytes(bytes) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", image)
    }
}
