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

class ChatCameraEntryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun imageSourceMenuRoutesEachActionToItsOwnCallback() {
        var cameraActions = 0
        var albumActions = 0

        composeRule.setContent {
            HarnessApkTheme {
                ChatImageSourceEntryMenu(
                    onTakePhoto = { cameraActions++ },
                    onPickFromAlbum = { albumActions++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription("添加图片").performClick()
        composeRule.onNodeWithText("拍照").assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals(1, cameraActions)
        assertEquals(0, albumActions)

        composeRule.onNodeWithContentDescription("添加图片").performClick()
        composeRule.onNodeWithText("从相册选择").assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals(1, cameraActions)
        assertEquals(1, albumActions)
    }
}
