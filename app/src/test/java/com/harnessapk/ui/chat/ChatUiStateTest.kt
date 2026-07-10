package com.harnessapk.ui.chat

import com.harnessapk.chat.ChatMessage
import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import com.harnessapk.chat.ReasoningEffort
import com.harnessapk.chat.UiMessagePartDraft
import com.harnessapk.chat.UiMessagePartType
import com.harnessapk.provider.ProviderProfile
import com.harnessapk.session.MarkdownFileChangeItem
import com.harnessapk.session.MarkdownFileChangeStatus
import com.harnessapk.session.MarkdownUpdateOperation
import com.harnessapk.ui.model.resolveModelSelection
import com.harnessapk.ui.model.selectableModelsForProvider
import com.harnessapk.voice.VoiceSettings
import com.harnessapk.websearch.WebSearchSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUiStateTest {
    @Test
    fun assistantMessagesAreUnframedWhileUserMessagesStayWarm() {
        assertEquals(ChatBubblePresentation.UNFRAMED, chatBubblePresentation(MessageRole.ASSISTANT))
        assertEquals(ChatBubblePresentation.WARM_USER, chatBubblePresentation(MessageRole.USER))
        assertEquals(ChatBubblePresentation.NEUTRAL_EVENT, chatBubblePresentation(MessageRole.SYSTEM))
        assertEquals(ChatBubblePresentation.NEUTRAL_EVENT, chatBubblePresentation(MessageRole.ERROR))
    }

    @Test
    fun assistantActivityLabelShowsThinkingForPendingAssistant() {
        val messages = listOf(assistantMessage(status = MessageStatus.PENDING))

        assertEquals("助手正在思考...", assistantActivityLabel(messages))
    }

    @Test
    fun assistantActivityLabelShowsReplyingForStreamingAssistant() {
        val messages = listOf(assistantMessage(status = MessageStatus.STREAMING, content = "你好"))

        assertEquals("助手正在回复...", assistantActivityLabel(messages))
    }

    @Test
    fun assistantActivityLabelIsNullWhenThereIsNoActiveAssistant() {
        val messages = listOf(
            userMessage(),
            assistantMessage(status = MessageStatus.SUCCEEDED, content = "你好"),
        )

        assertNull(assistantActivityLabel(messages))
    }

    @Test
    fun assistantMessageDisplayTextUsesThinkingTextWhenPendingContentIsBlank() {
        val message = assistantMessage(status = MessageStatus.PENDING)

        assertEquals("助手正在思考...", assistantMessageDisplayText(message))
    }

    @Test
    fun assistantMessageDisplayTextUsesPausedTextWhenCancelledContentIsBlank() {
        val message = assistantMessage(status = MessageStatus.CANCELLED)

        assertEquals("已暂停生成", assistantMessageDisplayText(message))
    }

    @Test
    fun selectableModelsForProviderUsesConfiguredModels() {
        val provider = providerProfile(
            defaultModel = "gpt-5.5",
            availableModels = listOf("gpt-5.5", "gpt-5.5-pro"),
        )

        assertEquals(listOf("gpt-5.5", "gpt-5.5-pro"), selectableModelsForProvider(provider))
    }

    @Test
    fun selectableModelsForProviderFallsBackToDefaultModelWhenListIsEmpty() {
        val provider = providerProfile(
            defaultModel = "kimi-k2.7-code",
            availableModels = emptyList(),
        )

        assertEquals(listOf("kimi-k2.7-code"), selectableModelsForProvider(provider))
    }

    @Test
    fun resolveModelSelectionUsesSavedDefaultProviderAndModel() {
        val providers = listOf(
            providerProfile(id = "kimi", name = "Kimi", defaultModel = "kimi-k2.7-code", availableModels = listOf("kimi-k2.7-code")),
            providerProfile(id = "openai", name = "OpenAI", defaultModel = "gpt-5.5", availableModels = listOf("gpt-5.5", "gpt-5.5-mini")),
        )

        val selection = resolveModelSelection(
            providers = providers,
            currentProviderId = null,
            currentModel = "",
            preferredProviderId = "openai",
            preferredModel = "gpt-5.5-mini",
        )

        assertEquals("openai", selection.providerId)
        assertEquals("gpt-5.5-mini", selection.model)
    }

    @Test
    fun resolveModelSelectionFallsBackWhenSavedDefaultIsUnavailable() {
        val providers = listOf(
            providerProfile(id = "kimi", name = "Kimi", defaultModel = "kimi-k2.7-code", availableModels = listOf("kimi-k2.7-code")),
        )

        val selection = resolveModelSelection(
            providers = providers,
            currentProviderId = null,
            currentModel = "",
            preferredProviderId = "openai",
            preferredModel = "gpt-5.5",
        )

        assertEquals("kimi", selection.providerId)
        assertEquals("kimi-k2.7-code", selection.model)
    }

    @Test
    fun resolveModelSelectionKeepsCurrentValidSelection() {
        val providers = listOf(
            providerProfile(id = "kimi", name = "Kimi", defaultModel = "kimi-k2.7-code", availableModels = listOf("kimi-k2.7-code")),
            providerProfile(id = "openai", name = "OpenAI", defaultModel = "gpt-5.5", availableModels = listOf("gpt-5.5", "gpt-5.5-mini")),
        )

        val selection = resolveModelSelection(
            providers = providers,
            currentProviderId = "kimi",
            currentModel = "kimi-k2.7-code",
            preferredProviderId = "openai",
            preferredModel = "gpt-5.5-mini",
        )

        assertEquals("kimi", selection.providerId)
        assertEquals("kimi-k2.7-code", selection.model)
    }

    @Test
    fun resolveModelSelectionCanUseCapabilityProvidedModels() {
        val providers = listOf(
            providerProfile(id = "openai", name = "OpenAI", defaultModel = "gpt-5.5", availableModels = listOf("gpt-5.5")),
        )

        val selection = resolveModelSelection(
            providers = providers,
            currentProviderId = null,
            currentModel = "",
            preferredProviderId = "openai",
            preferredModel = "gpt-5.5-pro",
            selectableModelsForProvider = { listOf("gpt-5.5", "gpt-5.5-pro") },
        )

        assertEquals("openai", selection.providerId)
        assertEquals("gpt-5.5-pro", selection.model)
    }

    @Test
    fun modelPickerButtonTextUsesModelAndReasoningEffortOnly() {
        val providers = listOf(providerProfile(defaultModel = "gpt-5.5", availableModels = listOf("gpt-5.5")))

        assertEquals("gpt-5.5 · 高", modelPickerButtonText(providers, "provider", "gpt-5.5", ReasoningEffort.HIGH))
    }

    @Test
    fun modelPickerButtonTextSupportsExtraHighReasoningEffort() {
        val providers = listOf(providerProfile(defaultModel = "gpt-5.5", availableModels = listOf("gpt-5.5")))

        assertEquals("gpt-5.5 · 超高", modelPickerButtonText(providers, "provider", "gpt-5.5", ReasoningEffort.XHIGH))
    }

    @Test
    fun modelPickerButtonTextPromptsWhenThereIsNoProvider() {
        assertEquals("先配置模型", modelPickerButtonText(emptyList(), null, "", ReasoningEffort.HIGH))
    }

    @Test
    fun modelPickerButtonTextOmitsReasoningForNonOpenAiProvider() {
        val providers = listOf(
            providerProfile(
                name = "Kimi",
                defaultModel = "kimi-k2.7-code",
                availableModels = listOf("kimi-k2.7-code"),
            ),
        )

        assertEquals("kimi-k2.7-code", modelPickerButtonText(providers, "provider", "kimi-k2.7-code", ReasoningEffort.HIGH))
    }

    @Test
    fun errorDisplayTextUsesFirstLineOfDetailedLog() {
        val log = """
            LLM 请求失败：timeout
            --- 诊断日志 ---
            Base URL: https://happycode.vip/v1
        """.trimIndent()

        assertEquals("LLM 请求失败：timeout", errorDisplayText(log))
    }

    @Test
    fun errorCopyTextKeepsFullDetailedLog() {
        val log = """
            LLM 请求失败：timeout
            --- 诊断日志 ---
            Base URL: https://happycode.vip/v1
        """.trimIndent()

        assertEquals(log, errorCopyText(log))
    }

    @Test
    fun messageSelectionCopyTextUsesVisibleContent() {
        val message = assistantMessage(
            status = MessageStatus.SUCCEEDED,
            content = "第一段 **重点**\n第二段",
        )

        assertEquals("第一段 **重点**\n第二段", messageSelectionCopyText(message))
    }

    @Test
    fun messageDisplayPartsUsesPersistedPartsWhenAvailable() {
        val message = assistantMessage(
            status = MessageStatus.SUCCEEDED,
            content = "旧 content 缓存",
        )
        val parts = listOf(
            UiMessagePartDraft(
                index = 0,
                type = UiMessagePartType.TEXT,
                content = "稳定块",
                stable = true,
            ),
            UiMessagePartDraft(
                index = 1,
                type = UiMessagePartType.TEXT,
                content = "尾块",
                stable = false,
            ),
        )

        assertEquals(parts, messageDisplayParts(message, parts))
    }

    @Test
    fun messageDisplayPartsBackfillsLegacyContentWhenPartsAreMissing() {
        val message = assistantMessage(
            status = MessageStatus.SUCCEEDED,
            content = "旧 content 缓存",
        )

        val parts = messageDisplayParts(message, emptyList())

        assertEquals(1, parts.size)
        assertEquals(UiMessagePartType.TEXT, parts.single().type)
        assertEquals("旧 content 缓存", parts.single().content)
        assertTrue(parts.single().stable)
    }

    @Test
    fun messageSelectionCopyTextPrefersPersistedTextParts() {
        val message = assistantMessage(
            status = MessageStatus.SUCCEEDED,
            content = "旧 content 缓存",
        )
        val parts = listOf(
            UiMessagePartDraft(
                index = 0,
                type = UiMessagePartType.REASONING,
                content = "内部推理",
                stable = true,
            ),
            UiMessagePartDraft(
                index = 1,
                type = UiMessagePartType.TEXT,
                content = "可见正文",
                stable = true,
            ),
        )

        assertEquals("可见正文", messageSelectionCopyText(message, parts))
    }

    @Test
    fun messageSelectionCopyTextUsesFullErrorLogWhenPresent() {
        val message = assistantMessage(
            status = MessageStatus.FAILED,
            content = "",
            errorMessage = "LLM 请求失败：timeout\n--- 诊断日志 ---\nBase URL: https://happycode.vip/v1",
        )

        assertEquals(
            "LLM 请求失败：timeout\n--- 诊断日志 ---\nBase URL: https://happycode.vip/v1",
            messageSelectionCopyText(message),
        )
    }

    @Test
    fun handleSendIntentDismissesKeyboardBeforeSendingText() {
        val events = mutableListOf<String>()

        handleSendIntent(
            hasSelectedImage = false,
            dismissKeyboard = { events += "dismiss" },
            sendNow = { events += "send" },
        )

        assertEquals(listOf("dismiss", "send"), events)
    }

    @Test
    fun shouldAutoFocusChatInputOnlyForFreshRequestedEmptyConversation() {
        assertEquals(
            true,
            shouldAutoFocusChatInput(
                autoFocusRequested = true,
                autoFocusAlreadyRequested = false,
                hasMessages = false,
                text = "",
                hasSelectedImage = false,
            ),
        )
        assertEquals(
            false,
            shouldAutoFocusChatInput(
                autoFocusRequested = false,
                autoFocusAlreadyRequested = false,
                hasMessages = false,
                text = "",
                hasSelectedImage = false,
            ),
        )
        assertEquals(
            false,
            shouldAutoFocusChatInput(
                autoFocusRequested = true,
                autoFocusAlreadyRequested = true,
                hasMessages = false,
                text = "",
                hasSelectedImage = false,
            ),
        )
        assertEquals(
            false,
            shouldAutoFocusChatInput(
                autoFocusRequested = true,
                autoFocusAlreadyRequested = false,
                hasMessages = true,
                text = "",
                hasSelectedImage = false,
            ),
        )
        assertEquals(
            false,
            shouldAutoFocusChatInput(
                autoFocusRequested = true,
                autoFocusAlreadyRequested = false,
                hasMessages = false,
                text = "hello",
                hasSelectedImage = false,
            ),
        )
    }

    @Test
    fun handleSendIntentSendsSelectedImageWithoutConfirmation() {
        val events = mutableListOf<String>()

        handleSendIntent(
            hasSelectedImage = true,
            dismissKeyboard = { events += "dismiss" },
            sendNow = { events += "send" },
        )

        assertEquals(listOf("dismiss", "send"), events)
    }

    @Test
    fun sendButtonContentDescriptionUsesPauseWhenBusy() {
        assertEquals("暂停生成", sendButtonContentDescription(isBusy = true))
        assertEquals("发送", sendButtonContentDescription(isBusy = false))
    }

    @Test
    fun chatInputUsesAttachmentAsTrailingActionBeforeComposing() {
        assertTrue(shouldShowCollapsedAttachmentEntry(text = "", hasSelectedImage = false))
        assertTrue(shouldShowCollapsedAttachmentEntry(text = "   ", hasSelectedImage = false))

        assertFalse(shouldShowCollapsedAttachmentEntry(text = "你好", hasSelectedImage = false))
        assertFalse(shouldShowCollapsedAttachmentEntry(text = "", hasSelectedImage = true))

        assertEquals(ChatInputTrailingAction.ATTACHMENT, chatInputTrailingAction(text = "", hasSelectedImage = false, isBusy = false))
        assertEquals(ChatInputTrailingAction.SEND, chatInputTrailingAction(text = "你好", hasSelectedImage = false, isBusy = false))
        assertEquals(ChatInputTrailingAction.SEND, chatInputTrailingAction(text = "", hasSelectedImage = true, isBusy = false))
        assertEquals(ChatInputTrailingAction.SEND, chatInputTrailingAction(text = "", hasSelectedImage = false, isBusy = true))
    }

    @Test
    fun chatContentWidthUsesAvailableWidthOnPhone() {
        assertEquals(390, chatContentMaxWidthDp(availableWidthDp = 390))
        assertEquals(358, messageBubbleMaxWidthDp(contentWidthDp = 390))
    }

    @Test
    fun chatContentWidthIsCappedOnFoldableScreens() {
        assertEquals(760, chatContentMaxWidthDp(availableWidthDp = 1100))
        assertEquals(699, messageBubbleMaxWidthDp(contentWidthDp = 760))
    }

    @Test
    fun messageBubbleSideKeepsAssistantLeftAndUserRight() {
        assertEquals(ChatBubbleSide.START, messageBubbleSide(MessageRole.ASSISTANT))
        assertEquals(ChatBubbleSide.END, messageBubbleSide(MessageRole.USER))
    }

    @Test
    fun handleStopIntentCancelsActiveSend() {
        val events = mutableListOf<String>()

        handleStopIntent(
            cancelActiveSend = { events += "cancel" },
            cancelVisibleAssistant = { events += "mark-cancelled" },
        )

        assertEquals(listOf("cancel", "mark-cancelled"), events)
    }

    @Test
    fun autoScrollModeJumpsToBottomOnInitialConversationLoad() {
        val current = autoScrollKey(
            listOf(
                userMessage(id = "first", content = "第一条"),
                assistantMessage(status = MessageStatus.SUCCEEDED, content = "最后一条"),
            ),
        )

        assertEquals(
            ChatAutoScrollMode.JUMP_TO_BOTTOM,
            chatAutoScrollMode(previous = null, current = current),
        )
    }

    @Test
    fun initialConversationLoadScrollTargetUsesBottomOffset() {
        assertEquals(
            ChatScrollTarget(index = 9, scrollOffset = CHAT_SCROLL_TO_BOTTOM_OFFSET_PX),
            chatScrollTarget(ChatAutoScrollMode.JUMP_TO_BOTTOM, lastMessageIndex = 9),
        )
    }

    @Test
    fun autoScrollModeJumpsToBottomWhenHistoryLoadsAfterEmptyState() {
        val previous = autoScrollKey(emptyList())
        val current = autoScrollKey(
            listOf(
                userMessage(id = "first", content = "第一条"),
                assistantMessage(status = MessageStatus.SUCCEEDED, content = "最后一条"),
            ),
        )

        assertEquals(
            ChatAutoScrollMode.JUMP_TO_BOTTOM,
            chatAutoScrollMode(previous = previous, current = current),
        )
    }

    @Test
    fun autoScrollModeFollowsStreamingContentGrowthWhenUserIsNearBottom() {
        val firstKey = autoScrollKey(
            listOf(assistantMessage(status = MessageStatus.STREAMING, content = "你")),
        )
        val nextKey = autoScrollKey(
            listOf(assistantMessage(status = MessageStatus.STREAMING, content = "你好，继续")),
        )

        assertEquals(
            ChatAutoScrollMode.STREAM_TO_BOTTOM,
            chatAutoScrollMode(previous = firstKey, current = nextKey, canFollowStreaming = true),
        )
    }

    @Test
    fun autoScrollModeFollowsStreamingPartUpdatesWhenLegacyContentLengthIsUnchanged() {
        val firstKey = AutoScrollKey(
            messageCount = 1,
            lastMessageId = "assistant-1",
            lastMessageStatus = MessageStatus.STREAMING,
            lastMessageContentLength = 0,
            lastMessageUpdatedAt = 100L,
        )
        val nextKey = firstKey.copy(lastMessageUpdatedAt = 400L)

        assertEquals(
            ChatAutoScrollMode.STREAM_TO_BOTTOM,
            chatAutoScrollMode(previous = firstKey, current = nextKey, canFollowStreaming = true),
        )
    }

    @Test
    fun autoScrollModeDoesNotFollowStreamingContentGrowthWhenUserScrolledAway() {
        val firstKey = autoScrollKey(
            listOf(assistantMessage(status = MessageStatus.STREAMING, content = "你")),
        )
        val nextKey = autoScrollKey(
            listOf(assistantMessage(status = MessageStatus.STREAMING, content = "你好，继续")),
        )

        assertEquals(
            ChatAutoScrollMode.NONE,
            chatAutoScrollMode(previous = firstKey, current = nextKey, canFollowStreaming = false),
        )
    }

    @Test
    fun autoScrollModeAnimatesOnlyWhenANewMessageAppears() {
        val previous = autoScrollKey(
            listOf(userMessage(id = "first", content = "第一条")),
        )
        val current = autoScrollKey(
            listOf(
                userMessage(id = "first", content = "第一条"),
                assistantMessage(status = MessageStatus.PENDING, content = ""),
            ),
        )

        assertEquals(
            ChatAutoScrollMode.ANIMATE_TO_BOTTOM,
            chatAutoScrollMode(previous = previous, current = current),
        )
    }

    @Test
    fun sessionConfigDoesNotSurfacePromptUsageStatusInMainChat() {
        val source = java.io.File("src/main/java/com/harnessapk/ui/chat/ChatScreen.kt").readText()

        assertFalse(source.contains("已使用优化提示词"))
        assertFalse(source.contains(" · 提示词"))
    }

    @Test
    fun chatInputDoesNotExposeSystemVoiceButton() {
        val source = java.io.File("src/main/java/com/harnessapk/ui/chat/ChatScreen.kt").readText()

        assertFalse(source.contains("onVoiceInput"))
    }

    @Test
    fun chatCapabilityButtonsAreHiddenWhenGlobalCapabilitiesAreDisabled() {
        assertFalse(shouldShowWebSearchButton(WebSearchSettings(enabled = false)))
        assertFalse(shouldShowVoiceInputButton(VoiceSettings(speechInputEnabled = false)))
    }

    @Test
    fun chatCapabilityButtonsAreVisibleWhenGlobalCapabilitiesAreEnabled() {
        assertTrue(shouldShowWebSearchButton(WebSearchSettings(enabled = true)))
        assertTrue(shouldShowVoiceInputButton(VoiceSettings(speechInputEnabled = true)))
    }

    @Test
    fun markdownReviewConfirmTextAllowsWithdrawingAll() {
        assertEquals("撤回全部", markdownReviewConfirmText(emptySet()))
        assertEquals("写入保留项", markdownReviewConfirmText(setOf(0)))
    }

    @Test
    fun markdownWriteBackFailureFeedbackUsesVisibleErrorText() {
        val feedback = markdownWriteBackFailureFeedback(
            IllegalArgumentException("LLM 未返回 Markdown 更新 JSON"),
        )

        assertEquals("LLM 未返回 Markdown 更新 JSON", feedback.errorText)
        assertNull(feedback.statusText)
    }

    @Test
    fun markdownWriteBackAppliedEventListsWrittenFiles() {
        assertEquals(
            "已沉淀到项目：requirements/prd.md、reports/review.md",
            markdownWriteBackAppliedEvent(listOf("requirements/prd.md", "reports/review.md")),
        )
    }

    @Test
    fun fileChangeSuggestionOnlyRespondsToMarkdownWritingIntent() {
        assertTrue(shouldSuggestFileChangeMode("帮我写 PRD，并生成 md"))
        assertTrue(shouldSuggestFileChangeMode("生成md"))
        assertTrue(shouldSuggestFileChangeMode("整理 README"))
        assertTrue(shouldSuggestFileChangeMode("把这段沉淀到项目"))
        assertFalse(shouldSuggestFileChangeMode("这个功能怎么理解？"))
    }

    @Test
    fun fileChangeEntryOnlyShowsForMarkdownWritingIntent() {
        assertTrue(shouldShowFileChangeModeEntry("帮我写 PRD"))
        assertTrue(shouldShowFileChangeModeEntry("生成md"))
        assertFalse(shouldShowFileChangeModeEntry("普通问答"))
        assertFalse(shouldShowFileChangeModeEntry(""))
    }

    @Test
    fun markdownFileChangeConversationContextKeepsRecentUserAndAssistantMessages() {
        val context = markdownFileChangeConversationContext(
            messages = listOf(
                userMessage(content = "讨论移动端项目方向"),
                assistantMessage(status = MessageStatus.SUCCEEDED, content = "建议做 Markdown 优先的项目工作台"),
                ChatMessage(
                    id = "system",
                    conversationId = "conversation",
                    role = MessageRole.SYSTEM,
                    content = "已沉淀到项目：old.md",
                    status = MessageStatus.SUCCEEDED,
                    providerId = null,
                    model = null,
                    errorMessage = null,
                ),
            ),
        )

        assertTrue(context.contains("用户：讨论移动端项目方向"))
        assertTrue(context.contains("助手：建议做 Markdown 优先的项目工作台"))
        assertFalse(context.contains("已沉淀到项目"))
    }

    @Test
    fun fileChangeModeDoesNotSendWithoutProject() {
        assertEquals(
            FileChangeSendDecision.BLOCKED_NEEDS_PROJECT,
            decideFileChangeSend(selectedProjectId = null, text = "生成 PRD", hasSelectedImage = false, isBusy = false),
        )
    }

    @Test
    fun fileChangeModeDoesNotSendImageAttachments() {
        assertEquals(
            FileChangeSendDecision.BLOCKED_UNSUPPORTED_IMAGE,
            decideFileChangeSend(selectedProjectId = "project", text = "生成 PRD", hasSelectedImage = true, isBusy = false),
        )
    }

    @Test
    fun fileChangeCardShowsCompactReadyTitleAndLimitsVisibleItems() {
        val items = (1..8).map { index ->
            MarkdownFileChangeItem(
                draftId = "draft",
                operation = if (index == 1) MarkdownUpdateOperation.CREATE else MarkdownUpdateOperation.UPDATE,
                path = "docs/file-$index.md",
                title = "文件 $index",
                reason = "测试",
                markdown = "# 文件 $index",
                addedLineCount = index,
                removedLineCount = index - 1,
                retained = true,
            )
        }

        assertEquals("已生成 8 个 Markdown 文件变更", markdownFileChangeCardTitle(MarkdownFileChangeStatus.READY, 8))
        assertEquals(6, visibleMarkdownFileChangeItems(items).size)
        assertEquals(2, hiddenMarkdownFileChangeItemCount(items))
        assertEquals("A", markdownFileChangeOperationLabel(items.first()))
        assertEquals("M", markdownFileChangeOperationLabel(items[1]))
    }

    private fun userMessage(
        id: String = "user",
        content: String = "hello",
    ): ChatMessage = ChatMessage(
        id = id,
        conversationId = "conversation",
        role = MessageRole.USER,
        content = content,
        status = MessageStatus.SUCCEEDED,
        providerId = null,
        model = null,
        errorMessage = null,
    )

    private fun assistantMessage(
        status: MessageStatus,
        content: String = "",
        errorMessage: String? = null,
    ): ChatMessage = ChatMessage(
        id = "assistant-$status",
        conversationId = "conversation",
        role = MessageRole.ASSISTANT,
        content = content,
        status = status,
        providerId = "provider",
        model = "model",
        errorMessage = errorMessage,
    )

    private fun providerProfile(
        id: String = "provider",
        name: String = "OpenAI",
        defaultModel: String,
        availableModels: List<String>,
    ): ProviderProfile = ProviderProfile(
        id = id,
        name = name,
        baseUrl = "https://happycode.vip/v1",
        defaultModel = defaultModel,
        defaultVisionModel = null,
        supportsVision = false,
        enabled = true,
        hasApiKey = true,
        availableModels = availableModels,
    )
}
