package com.harnessapk.ui.chat

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.harnessapk.chat.ChatDocumentPresentation
import com.harnessapk.chat.ExtractedDocument
import com.harnessapk.chat.PendingImageAttachment
import com.harnessapk.ui.theme.HarnessApkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LifeChatComposerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyPayloadKeepsSendDisabledAndPrimaryActionsReachableAt320DpAndLargeText() {
        composeRule.setContent {
            HarnessApkTheme {
                TestChatInputBar()
            }
        }

        composeRule.onNodeWithText("发送").assertIsDisplayed().assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("附件").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("说话").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun typingEnablesSendAndClickSubmitsTheCurrentDraftInShortComposer() {
        val draft = mutableStateOf("")
        var sends = 0

        composeRule.setContent {
            HarnessApkTheme {
                TestChatInputBar(
                    text = draft.value,
                    onTextChange = { draft.value = it },
                    onSend = { sends++ },
                )
            }
        }

        composeRule.onNode(hasSetTextAction()).assertIsDisplayed().performTextInput("周末要带什么")
        composeRule.onNodeWithText("发送").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, sends)
    }

    @Test
    fun simpleModeEnterAddsLineBreakAndCtrlEnterSendsOnce() {
        val draft = mutableStateOf("")
        var sends = 0

        composeRule.setContent {
            HarnessApkTheme {
                TestChatInputBar(
                    text = draft.value,
                    onTextChange = { draft.value = it },
                    onSend = { sends++ },
                )
            }
        }

        val input = composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
        input.performTextInput("需要换行")
        input.performKeyInput { pressKey(Key.Enter) }
        assertEquals(0, sends)
        assertTrue(draft.value.endsWith("\n"))

        input.performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                pressKey(Key.Enter)
            }
        }
        assertEquals(1, sends)
    }

    @Test
    fun textAndDocumentPayloadKeepsAttachmentEntrySendAnd48DpRemovalReachable() {
        val document = testDocument()
        var pickedDocuments = 0
        var removedDocuments = 0
        var sends = 0

        composeRule.setContent {
            HarnessApkTheme {
                TestChatInputBar(
                    text = "整理这份文件",
                    pendingDocuments = listOf(document),
                    onPickDocument = { pickedDocuments++ },
                    onRemoveDocument = { removedDocuments++ },
                    onSend = { sends++ },
                )
            }
        }

        composeRule.onNodeWithText("brief.txt").assertIsDisplayed()
        composeRule.onNodeWithText("发送").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("附件").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule
            .onNodeWithText("选择文件（PDF / Word / Excel / TXT）")
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, pickedDocuments)
        composeRule.onNodeWithText("发送").assertIsEnabled().performClick()
        assertEquals(1, sends)

        val remove = composeRule.onNodeWithContentDescription("移除文件 brief.txt")
        remove.assertIsDisplayed().assertHasClickAction()
        remove.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, removedDocuments)
    }

    @Test
    fun selectedImageKeepsOneAttachmentEntryInTheCompleteComposer() {
        val image = PendingImageAttachment(
            uri = Uri.parse("content://test/photo-1"),
            mimeType = "image/png",
        )

        composeRule.setContent {
            HarnessApkTheme {
                TestChatInputBar(
                    text = "带上这张照片",
                    selectedImages = listOf(image),
                )
            }
        }

        composeRule.onNodeWithContentDescription("移除第 1 张图片").assertIsDisplayed()
        composeRule.onAllNodesWithText("附件", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithText("附件").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("发送").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun documentRowExpandsItsBodyAndKeepsTheTruncationNoticeAccessible() {
        val document = ChatDocumentPresentation(
            id = "document-1",
            uri = "content://test/plan.txt",
            fileName = "plan.txt",
            mimeType = "text/plain",
            sizeBytes = 2_048L,
            sha256 = "hash",
            extractedText = "文件正文仍然可读",
            truncated = true,
        )

        composeRule.setContent {
            HarnessApkTheme {
                ChatDocumentCards(listOf(document))
            }
        }

        composeRule.onNodeWithText("plan.txt").assertIsDisplayed()
        composeRule.onNodeWithText("文件正文仍然可读").assertDoesNotExist()
        composeRule.onNode(hasClickAction()).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("文件正文仍然可读").assertIsDisplayed()
        composeRule.onNodeWithText("文件较长，仅发送已提取的部分").assertIsDisplayed()
    }

    @Test
    fun standaloneComposerKeepsAReachableContextEntryWhenRequested() {
        var opened = 0

        composeRule.setContent {
            HarnessApkTheme {
                TestChatInputBar(
                    showContextBar = true,
                    onOpenContext = { opened++ },
                )
            }
        }

        composeRule.onNodeWithTag("conversation_context_bar").assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun runningStateUsesStopAndEnterCannotSendNextDraft() {
        var sends = 0
        var stops = 0

        composeRule.setContent {
            HarnessApkTheme {
                TestChatInputBar(
                    text = "下一条草稿",
                    isBusy = true,
                    onSend = { sends++ },
                    onStop = { stops++ },
                )
            }
        }

        composeRule.onNodeWithText("停止生成").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, stops)

        composeRule.onNode(hasSetTextAction()).performClick().performKeyInput {
            pressKey(Key.Enter)
        }
        assertEquals(0, sends)
    }

    @Test
    fun queuedStateShowsNonActionableWaitingAndDoesNotInvokeStop() {
        var stops = 0

        composeRule.setContent {
            HarnessApkTheme {
                TestChatInputBar(
                    text = "排队中的下一条",
                    isQueued = true,
                    onStop = { stops++ },
                )
            }
        }

        composeRule.onNodeWithText("等待处理").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("附件").assertIsEnabled()
        composeRule.onNodeWithText("说话").assertIsEnabled()
        assertEquals(0, stops)
    }

    @Test
    fun preparingVoiceAndDocumentExtractionGatePrimarySend() {
        val gate = mutableStateOf(
            ComposerGate(
                text = "准备中的问题",
                isPreparingSend = true,
            ),
        )
        var sends = 0

        composeRule.setContent {
            val currentGate = gate.value
            HarnessApkTheme {
                TestChatInputBar(
                    text = currentGate.text,
                    isVoiceInputActive = currentGate.isVoiceInputActive,
                    isPreparingSend = currentGate.isPreparingSend,
                    documentExtracting = currentGate.documentExtracting,
                    pendingDocuments = currentGate.pendingDocuments,
                    onSend = { sends++ },
                )
            }
        }

        composeRule.onNodeWithText("正在准备").assertIsDisplayed().assertIsNotEnabled()
        assertEquals(0, sends)

        composeRule.runOnIdle {
            sends = 0
            gate.value = ComposerGate(
                text = "录音中的问题",
                isVoiceInputActive = true,
            )
        }
        composeRule.onNodeWithText("发送").assertIsDisplayed().assertIsNotEnabled()
        assertEquals(0, sends)

        composeRule.runOnIdle {
            sends = 0
            gate.value = ComposerGate(
                text = "文件仍在读取",
                pendingDocuments = listOf(testDocument()),
                documentExtracting = true,
            )
        }
        composeRule.onNodeWithText("发送").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("附件").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("说话").assertIsDisplayed().assertIsNotEnabled()
        assertEquals(0, sends)
    }

    @Test
    fun voiceReviewConfirmsExplicitlyAndFinalizingOffersCancelInsteadOfStopRecording() {
        val voicePanel = mutableStateOf(
            VoicePanelFixture(
                label = "确认语音文字",
                transcript = "识别结果",
                reviewing = true,
            ),
        )
        var confirms = 0
        var cancels = 0

        composeRule.setContent {
            val currentPanel = voicePanel.value
            HarnessApkTheme {
                LifeVoicePanel(
                    label = currentPanel.label,
                    transcript = currentPanel.transcript,
                    reviewing = currentPanel.reviewing,
                    listening = currentPanel.listening,
                    onTranscriptChange = {},
                    onConfirm = { confirms++ },
                    onStop = {},
                    onCancel = { cancels++ },
                    onRestart = {},
                )
            }
        }

        composeRule.onNodeWithText("确认使用").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, confirms)

        composeRule.runOnIdle {
            voicePanel.value = VoicePanelFixture(
                label = "正在整理语音",
                transcript = null,
            )
        }

        composeRule.onNodeWithText("取消整理").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("结束录音").assertDoesNotExist()
        assertEquals(1, cancels)
    }
}

private val TEST_CONTEXT_SUMMARY = ConversationContextSummary(
    projectName = null,
    identityName = "普通助手",
    enabledWikiCount = 0,
    model = "测试模型",
    reasoningEffortLabel = "标准",
    webSearchEnabled = false,
    contextPercent = 0,
)

private data class ComposerGate(
    val text: String,
    val isVoiceInputActive: Boolean = false,
    val isPreparingSend: Boolean = false,
    val documentExtracting: Boolean = false,
    val pendingDocuments: List<ExtractedDocument> = emptyList(),
)

private data class VoicePanelFixture(
    val label: String,
    val transcript: String?,
    val reviewing: Boolean = false,
    val listening: Boolean = false,
)

private fun testDocument(
    name: String = "brief.txt",
): ExtractedDocument = ExtractedDocument(
    uri = "content://test/$name",
    fileName = name,
    mimeType = "text/plain",
    text = "测试文档正文",
    truncated = false,
    originalCharCount = 7,
)

@Composable
private fun TestChatInputBar(
    text: String = "",
    onTextChange: (String) -> Unit = {},
    selectedImages: List<PendingImageAttachment> = emptyList(),
    pendingDocuments: List<ExtractedDocument> = emptyList(),
    documentExtracting: Boolean = false,
    onPickDocument: () -> Unit = {},
    onRemoveDocument: (ExtractedDocument) -> Unit = {},
    isVoiceInputActive: Boolean = false,
    isBusy: Boolean = false,
    isPreparingSend: Boolean = false,
    isQueued: Boolean = false,
    onSend: () -> Unit = {},
    onStop: () -> Unit = {},
    showContextBar: Boolean = false,
    onOpenContext: () -> Unit = {},
) {
    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
        Box(Modifier.width(320.dp).height(280.dp)) {
            ChatInputBar(
                text = text,
                onTextChange = onTextChange,
                selectedImages = selectedImages,
                onTakePhoto = {},
                onPickFromAlbum = {},
                onRemoveImage = {},
                pendingDocuments = pendingDocuments,
                documentExtracting = documentExtracting,
                onPickDocument = onPickDocument,
                onRemoveDocument = onRemoveDocument,
                onStartVoiceTranscription = {},
                isVoiceInputActive = isVoiceInputActive,
                onStopVoiceTranscription = {},
                contextSummary = TEST_CONTEXT_SUMMARY,
                onOpenContext = onOpenContext,
                inputFocusRequester = remember { FocusRequester() },
                canSend = text.isNotBlank() || selectedImages.isNotEmpty() || pendingDocuments.isNotEmpty(),
                isBusy = isBusy,
                isPreparingSend = isPreparingSend,
                onSend = onSend,
                onStop = onStop,
                showFileChangeSuggestion = false,
                canSendFileChange = false,
                onSendFileChange = {},
                showContextBar = showContextBar,
                simpleMode = true,
                hasHistory = true,
                isQueued = isQueued,
            )
        }
    }
}
