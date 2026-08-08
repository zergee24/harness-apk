package com.harnessapk.ui.chat

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatVoiceInputTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun voiceAndStopActionsUseTheSmallInputAction() {
        var starts = 0
        var stops = 0
        val voiceActive = mutableStateOf(false)

        composeRule.setContent {
            HarnessApkTheme {
                ChatInputVoiceAction(
                    voiceActive = voiceActive.value,
                    enabled = true,
                    onStart = { starts++ },
                    onStop = { stops++ },
                )
            }
        }
        composeRule.onNodeWithContentDescription("开始语音输入").performClick()
        assertEquals(1, starts)

        composeRule.runOnIdle {
            voiceActive.value = true
        }
        composeRule.onNodeWithContentDescription("停止语音输入").performClick()
        assertEquals(1, stops)
    }
}
