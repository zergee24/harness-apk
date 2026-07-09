package com.harnessapk.ui.chat

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.harnessapk.chat.ChatMessage
import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import com.harnessapk.chat.PendingImageAttachment
import com.harnessapk.chat.ReasoningEffort
import com.harnessapk.chat.UiMessagePartDraft
import com.harnessapk.chat.UiMessagePartType
import com.harnessapk.chat.defaultReasoningEffort
import com.harnessapk.chat.supportsReasoningEffort
import com.harnessapk.common.AppContainer
import com.harnessapk.common.toUserMessage
import com.harnessapk.provider.ProviderProfile
import com.harnessapk.provider.ModelCapabilityResolver
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.provider.modelConfigForProvider
import com.harnessapk.provider.parseProviderCapabilityCatalogJson
import com.harnessapk.session.MarkdownFileChangeController
import com.harnessapk.session.MarkdownFileChangeItem
import com.harnessapk.session.MarkdownFileChangeState
import com.harnessapk.session.MarkdownFileChangeStatus
import com.harnessapk.session.MarkdownDeliverable
import com.harnessapk.session.MarkdownDiffLine
import com.harnessapk.session.MarkdownDiffLineType
import com.harnessapk.session.MarkdownSnapshot
import com.harnessapk.session.MarkdownUpdatePlannerUseCase
import com.harnessapk.session.MarkdownUpdateOperation
import com.harnessapk.session.MarkdownUpdateProposal
import com.harnessapk.session.SessionRequestContext
import com.harnessapk.session.SessionSummary
import com.harnessapk.session.WorkspaceProject
import com.harnessapk.session.buildMarkdownDiff
import com.harnessapk.session.markdownReviewSummary
import com.harnessapk.session.canWriteBackMarkdown
import com.harnessapk.storage.DefaultModelPreference
import com.harnessapk.storage.ProviderCapabilityCatalogSnapshot
import com.harnessapk.ui.markdown.MarkdownMessage
import com.harnessapk.ui.model.resolveModelSelection
import com.harnessapk.websearch.WebSearchContext
import com.harnessapk.websearch.WebSearchRequest
import com.harnessapk.websearch.WebSearchSettings
import com.harnessapk.websearch.nativeWebSearchModeForRequest
import com.harnessapk.websearch.shouldUseExternalWebSearch
import com.harnessapk.voice.VoiceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun ChatScreen(
    container: AppContainer,
    conversationId: String,
    initialProjectId: String? = null,
    autoFocusInput: Boolean = false,
    sessionConfigRequestKey: Int = 0,
    onSessionConfigRequestConsumed: () -> Unit = {},
    contentPadding: PaddingValues,
) {
    val messages by container.chatRepository.observeMessages(conversationId).collectAsState(initial = emptyList())
    val memory by container.chatRepository.observeMemory(conversationId).collectAsState(initial = null)
    val providers by container.providerRepository.observeEnabled().collectAsState(initial = emptyList())
    val defaultModelPreference by container.settingsStore.defaultModelPreference.collectAsState(
        initial = DefaultModelPreference(),
    )
    val providerCatalogSnapshot by container.settingsStore.providerCapabilityCatalogSnapshot.collectAsState(
        initial = ProviderCapabilityCatalogSnapshot(),
    )
    val webSearchSettings by container.settingsStore.webSearchSettings.collectAsState(
        initial = WebSearchSettings(),
    )
    val voiceSettings by container.settingsStore.voiceSettings.collectAsState(
        initial = VoiceSettings(),
    )
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val inputFocusRequester = remember { FocusRequester() }
    var text by remember { mutableStateOf("") }
    var selectedImage by remember { mutableStateOf<Uri?>(null) }
    var selectedMimeType by remember { mutableStateOf("image/png") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var selectedProviderId by remember { mutableStateOf<String?>(null) }
    var selectedModel by remember { mutableStateOf("") }
    var selectedReasoningEffort by remember { mutableStateOf(defaultReasoningEffort()) }
    var isSending by remember { mutableStateOf(false) }
    var activeSendJob by remember { mutableStateOf<Job?>(null) }
    var webSearchEnabled by remember { mutableStateOf(false) }
    var showSessionConfig by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf<List<WorkspaceProject>>(emptyList()) }
    var deliverables by remember { mutableStateOf<List<MarkdownDeliverable>>(emptyList()) }
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var projectContext by remember { mutableStateOf("") }
    var rawSessionPrompt by remember { mutableStateOf("") }
    var optimizedSessionPrompt by remember { mutableStateOf("") }
    var finalSessionPrompt by remember { mutableStateOf("") }
    var sessionStatus by remember { mutableStateOf<String?>(null) }
    var sessionConfigStatus by remember { mutableStateOf<String?>(null) }
    var isOptimizingPrompt by remember { mutableStateOf(false) }
    var pendingWriteBack by remember { mutableStateOf<ChatMessage?>(null) }
    var pendingSelectionCopy by remember { mutableStateOf<ChatMessage?>(null) }
    var pendingMarkdownReview by remember { mutableStateOf<MarkdownUpdateReviewState?>(null) }
    var pendingMarkdownReviewDraftId by remember { mutableStateOf<String?>(null) }
    var markdownFileChangeStates by remember(conversationId) { mutableStateOf<List<MarkdownFileChangeState>>(emptyList()) }
    var retainedReviewIndexes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var isPlanningMarkdownUpdates by remember { mutableStateOf(false) }
    var isCompressingContext by remember { mutableStateOf(false) }
    var speakingMessageId by remember { mutableStateOf<String?>(null) }
    var textToSpeechReady by remember { mutableStateOf(false) }
    var textToSpeech by remember { mutableStateOf<TextToSpeech?>(null) }
    var initialProjectApplied by remember { mutableStateOf(false) }
    var autoFocusInputRequested by remember(conversationId) { mutableStateOf(false) }
    var streamingAutoScrollEnabled by remember(conversationId) { mutableStateOf(true) }
    val remoteProviderCatalog = remember(providerCatalogSnapshot.rawJson) {
        providerCatalogSnapshot.rawJson?.let { rawJson ->
            runCatching { parseProviderCapabilityCatalogJson(rawJson, container.json) }.getOrNull()
        }
    }
    val capabilityResolver = remember(remoteProviderCatalog) {
        ModelCapabilityResolver(remoteCatalog = remoteProviderCatalog)
    }
    val selectableModelsByProviderId = remember(providers, capabilityResolver) {
        providers.associate { provider ->
            provider.id to capabilityResolver.selectableModels(provider).map { it.modelId }
        }
    }
    val markdownUpdatePlanner = remember(container) {
        MarkdownUpdatePlannerUseCase(
            providerRepository = container.providerRepository,
            client = container.openAiClient,
            dispatchers = container.dispatchers,
        )
    }
    val markdownFileChangeController = remember {
        MarkdownFileChangeController(timeProvider = { System.currentTimeMillis() })
    }

    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
    val selectedModelConfig = modelConfigForProvider(selectedProvider, selectedModel)
    val contextStatus = contextWindowStatus(
        messages = messages,
        memory = memory,
        modelConfig = selectedModelConfig,
    )
    val assistantActivityText = assistantActivityLabel(messages)
    val busyText = assistantActivityText ?: if (isSending) "正在发送..." else null
    val isAssistantBusy = busyText != null
    val dismissKeyboard = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        Unit
    }

    DisposableEffect(context) {
        val mainHandler = Handler(Looper.getMainLooper())
        val engine = TextToSpeech(context.applicationContext) { status ->
            textToSpeechReady = status == TextToSpeech.SUCCESS
        }
        engine.language = Locale.SIMPLIFIED_CHINESE
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    if (speakingMessageId == utteranceId) speakingMessageId = null
                }
            }

            @Deprecated("Deprecated in Android SDK")
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    speakingMessageId = null
                    errorText = "朗读失败，请检查系统 TTS 设置"
                }
            }
        })
        textToSpeech = engine
        onDispose {
            engine.stop()
            engine.shutdown()
            textToSpeech = null
            textToSpeechReady = false
            speakingMessageId = null
        }
    }

    fun saveSessionPrompt(
        original: String = rawSessionPrompt,
        optimized: String = optimizedSessionPrompt,
        final: String = finalSessionPrompt,
    ) {
        scope.launch {
            container.chatRepository.updateConversationPrompt(
                id = conversationId,
                original = original,
                optimized = optimized,
                final = final,
            )
        }
    }

    LaunchedEffect(conversationId) {
        runCatching { container.chatRepository.conversation(conversationId) }
            .onSuccess { conversation ->
                rawSessionPrompt = conversation?.promptOriginal.orEmpty()
                optimizedSessionPrompt = conversation?.promptOptimized.orEmpty()
                finalSessionPrompt = conversation?.promptFinal.orEmpty()
                val projectId = conversation?.projectId
                if (!projectId.isNullOrBlank()) {
                    selectedProjectId = projectId
                }
            }
            .onFailure { sessionStatus = it.toUserMessage() }
    }

    LaunchedEffect(conversationId, initialProjectId) {
        if (!initialProjectId.isNullOrBlank()) {
            container.chatRepository.updateConversationProject(conversationId, initialProjectId)
        }
    }

    LaunchedEffect(providers, defaultModelPreference, selectableModelsByProviderId) {
        val selection = resolveModelSelection(
            providers = providers,
            currentProviderId = selectedProviderId,
            currentModel = selectedModel,
            preferredProviderId = defaultModelPreference.providerId,
            preferredModel = defaultModelPreference.model,
            selectableModelsForProvider = { provider ->
                selectableModelsByProviderId[provider.id].orEmpty()
            },
        )
        selectedProviderId = selection.providerId
        selectedModel = selection.model
    }

    var previousAutoScrollKey by remember(conversationId) { mutableStateOf<AutoScrollKey?>(null) }
    LaunchedEffect(conversationId) {
        snapshotFlow { listState.isScrollInProgress to listState.canFollowStreaming(messages.lastIndex) }
            .collect { (isScrollInProgress, isNearBottom) ->
                when {
                    isNearBottom -> streamingAutoScrollEnabled = true
                    isScrollInProgress -> streamingAutoScrollEnabled = false
                }
            }
    }
    LaunchedEffect(autoScrollKey(messages)) {
        val currentKey = autoScrollKey(messages)
        val scrollMode = chatAutoScrollMode(
            previous = previousAutoScrollKey,
            current = currentKey,
            canFollowStreaming = streamingAutoScrollEnabled || listState.canFollowStreaming(messages.lastIndex),
        )
        previousAutoScrollKey = currentKey
        when (scrollMode) {
            ChatAutoScrollMode.JUMP_TO_BOTTOM -> {
                chatScrollTarget(scrollMode, messages.lastIndex)?.let {
                    listState.scrollToItem(it.index, it.scrollOffset)
                }
            }
            ChatAutoScrollMode.ANIMATE_TO_BOTTOM -> {
                streamingAutoScrollEnabled = true
                chatScrollTarget(scrollMode, messages.lastIndex)?.let {
                    listState.animateScrollToItem(it.index, it.scrollOffset)
                }
            }
            ChatAutoScrollMode.STREAM_TO_BOTTOM -> {
                streamingAutoScrollEnabled = true
                chatScrollTarget(scrollMode, messages.lastIndex)?.let {
                    listState.scrollToItem(it.index, it.scrollOffset)
                }
            }
            ChatAutoScrollMode.NONE -> Unit
        }
    }

    LaunchedEffect(conversationId, autoFocusInput, messages.size, text, selectedImage) {
        if (
            shouldAutoFocusChatInput(
                autoFocusRequested = autoFocusInput,
                autoFocusAlreadyRequested = autoFocusInputRequested,
                hasMessages = messages.isNotEmpty(),
                text = text,
                hasSelectedImage = selectedImage != null,
            )
        ) {
            autoFocusInputRequested = true
            delay(250)
            inputFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    LaunchedEffect(webSearchSettings.enabled) {
        if (!shouldShowWebSearchButton(webSearchSettings)) {
            webSearchEnabled = false
        }
    }

    LaunchedEffect(sessionConfigRequestKey) {
        if (sessionConfigRequestKey > 0) {
            showSessionConfig = true
            onSessionConfigRequestConsumed()
        }
    }

    LaunchedEffect(Unit) {
        runCatching { container.projectWorkspaceGateway.listProjects() }
            .onSuccess {
                projects = it
                if (!initialProjectApplied && initialProjectId != null && it.any { project -> project.id == initialProjectId }) {
                    selectedProjectId = initialProjectId
                    initialProjectApplied = true
                }
            }
            .onFailure { sessionStatus = it.toUserMessage() }
    }

    LaunchedEffect(selectedProjectId) {
        val projectId = selectedProjectId
        deliverables = emptyList()
        projectContext = ""
        if (projectId != null) {
            runCatching {
                val nextDeliverables = container.projectWorkspaceGateway.listDeliverables(projectId)
                val nextContext = container.projectWorkspaceGateway.readProjectContext(projectId)
                nextDeliverables to nextContext
            }.onSuccess { (nextDeliverables, nextContext) ->
                deliverables = nextDeliverables
                projectContext = nextContext
            }.onFailure {
                sessionStatus = it.toUserMessage()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            selectedImage = uri
            selectedMimeType = context.contentResolver.getType(uri) ?: "image/png"
        }
    }

    fun sendNow() {
        val body = text.trim()
        if (isAssistantBusy || body.isEmpty() && selectedImage == null) return
        val nativeWebSearchMode = nativeWebSearchModeForRequest(
            query = body,
            enabledForSession = webSearchEnabled,
            settings = webSearchSettings,
            provider = selectedProvider,
        )
        val sessionRequestContext = sessionRequestContext(
            finalPrompt = finalSessionPrompt.ifBlank { optimizedSessionPrompt.ifBlank { rawSessionPrompt } },
            project = projects.firstOrNull { it.id == selectedProjectId },
            projectContext = projectContext,
            markdowns = deliverables,
        )
        val job = scope.launch {
            errorText = null
            isSending = true
            try {
                val webSearchContext = buildWebSearchContext(
                    query = body,
                    enabledForSession = webSearchEnabled,
                    settings = webSearchSettings,
                    nativeWebSearchMode = nativeWebSearchMode,
                    search = {
                        container.webSearchClient.searchKeywords(
                            WebSearchRequest(query = body, maxResults = webSearchSettings.maxResults),
                        )
                    },
                    onFailure = {
                        errorText = "联网搜索失败：${it.toUserMessage()}，已继续发送给模型"
                    },
                )
                container.sendMessageUseCase.send(
                    conversationId = conversationId,
                    text = body.ifEmpty { "请看这张截图" },
                    attachments = selectedImage?.let {
                        listOf(PendingImageAttachment(it, selectedMimeType))
                    } ?: emptyList(),
                    providerId = selectedProviderId,
                    modelOverride = selectedModel,
                    reasoningEffort = selectedReasoningEffort,
                    sessionContext = sessionRequestContext,
                    webSearchContext = webSearchContext,
                    nativeWebSearchMode = nativeWebSearchMode,
                )
                text = ""
                selectedImage = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                errorText = error.toUserMessage()
            } finally {
                isSending = false
                activeSendJob = null
            }
        }
        activeSendJob = job
    }

    fun stopNow() {
        handleStopIntent(
            cancelActiveSend = {
                activeSendJob?.cancel()
                activeSendJob = null
                isSending = false
            },
            cancelVisibleAssistant = {
                scope.launch {
                    container.chatRepository.cancelActiveAssistantMessages(conversationId)
                }
            },
        )
    }

    fun speakAssistantMessage(message: ChatMessage) {
        if (!voiceSettings.ttsEnabled) {
            errorText = "请先在设置 -> 语音能力启用回复朗读"
            return
        }
        val engine = textToSpeech
        if (!textToSpeechReady || engine == null) {
            errorText = "系统 TTS 还未准备好，请稍后再试"
            return
        }
        val content = assistantMessageDisplayText(message).trim()
        if (content.isBlank()) return
        if (speakingMessageId == message.id) {
            engine.stop()
            speakingMessageId = null
            return
        }
        engine.stop()
        engine.language = Locale.SIMPLIFIED_CHINESE
        engine.setSpeechRate(voiceSettings.ttsSpeechRate)
        speakingMessageId = message.id
        engine.speak(content.take(MAX_TTS_TEXT_LENGTH), TextToSpeech.QUEUE_FLUSH, null, message.id)
    }

    fun compressContextNow() {
        if (isCompressingContext || !contextWindowCanManualCompress(contextStatus)) return
        scope.launch {
            isCompressingContext = true
            errorText = null
            runCatching {
                container.manualContextCompressionUseCase.compress(
                    conversationId = conversationId,
                    modelConfig = selectedModelConfig,
                )
            }.onSuccess {
                if (!it.compressed) errorText = it.message
            }.onFailure {
                errorText = it.toUserMessage()
            }
            isCompressingContext = false
        }
    }

    fun optimizeSessionPrompt() {
        val rawPrompt = rawSessionPrompt.trim()
        if (rawPrompt.isBlank()) {
            sessionConfigStatus = "请先输入会话提示词"
            return
        }
        scope.launch {
            isOptimizingPrompt = true
            sessionConfigStatus = null
            runCatching {
                container.promptOptimizerUseCase.optimize(
                    rawPrompt = rawPrompt,
                    projectContext = projectContext,
                    deliverableMarkdown = markdownIndexForPrompt(deliverables),
                    providerId = selectedProviderId,
                    modelOverride = selectedModel,
                )
            }.onSuccess {
                optimizedSessionPrompt = it
                saveSessionPrompt(original = rawSessionPrompt, optimized = it, final = finalSessionPrompt)
                sessionConfigStatus = "已生成优化结果"
            }.onFailure {
                sessionConfigStatus = it.toUserMessage()
            }
            isOptimizingPrompt = false
        }
    }

    suspend fun markdownSnapshots(projectId: String): List<MarkdownSnapshot> =
        container.projectWorkspaceGateway.listDeliverables(projectId).map { deliverable ->
            MarkdownSnapshot(
                id = deliverable.id,
                title = deliverable.title,
                path = deliverable.path,
                markdown = container.projectWorkspaceGateway.readDeliverable(projectId, deliverable.id),
            )
        }

    fun buildReviewState(
        proposals: List<MarkdownUpdateProposal>,
        snapshots: List<MarkdownSnapshot>,
    ): MarkdownUpdateReviewState {
        val byPath = snapshots.associateBy { it.path }
        return MarkdownUpdateReviewState(
            proposals = proposals,
            diffs = proposals.map { proposal ->
                buildMarkdownDiff(
                    oldMarkdown = byPath[proposal.path]?.markdown.orEmpty(),
                    newMarkdown = proposal.markdown,
                )
            },
        )
    }

    fun upsertMarkdownFileChangeState(state: MarkdownFileChangeState) {
        markdownFileChangeStates = markdownFileChangeStates
            .filterNot { it.draft.id == state.draft.id } + state
    }

    fun reviewStateForFileChange(state: MarkdownFileChangeState): MarkdownUpdateReviewState =
        MarkdownUpdateReviewState(
            proposals = state.items.map {
                MarkdownUpdateProposal(
                    operation = it.operation,
                    path = it.path,
                    title = it.title,
                    reason = it.reason,
                    markdown = it.markdown,
                )
            },
            diffs = state.diffs,
        )

    fun generateMarkdownFileChange(state: MarkdownFileChangeState, userRequest: String) {
        val planning = markdownFileChangeController.markPlanning(state)
        upsertMarkdownFileChangeState(planning)
        scope.launch {
            runCatching {
                val project = projects.firstOrNull { it.id == planning.draft.projectId }
                val snapshots = markdownSnapshots(planning.draft.projectId)
                val plan = markdownUpdatePlanner.planFromUserRequest(
                    projectName = project?.name.orEmpty(),
                    projectContext = projectContext,
                    markdowns = snapshots,
                    userRequest = userRequest,
                    conversationContext = markdownFileChangeConversationContext(messages),
                    providerId = selectedProviderId,
                    modelOverride = selectedModel,
                )
                markdownFileChangeController.markReady(planning, plan, snapshots)
            }.onSuccess { ready ->
                upsertMarkdownFileChangeState(ready)
            }.onFailure { error ->
                upsertMarkdownFileChangeState(
                    markdownFileChangeController.markFailed(planning, error.toUserMessage()),
                )
            }
        }
    }

    fun sendFileChangeNow() {
        val body = text.trim()
        when (decideFileChangeSend(selectedProjectId, body, selectedImage != null, isAssistantBusy)) {
            FileChangeSendDecision.BLOCKED_NEEDS_PROJECT -> {
                errorText = "请先选择项目"
                return
            }
            FileChangeSendDecision.BLOCKED_EMPTY_INPUT -> return
            FileChangeSendDecision.BLOCKED_UNSUPPORTED_IMAGE -> {
                errorText = "文件变更模式暂不支持图片输入"
                return
            }
            FileChangeSendDecision.BLOCKED_BUSY -> return
            FileChangeSendDecision.SEND -> Unit
        }

        val projectId = selectedProjectId ?: return
        scope.launch {
            errorText = null
            val userMessageId = container.chatRepository.insertUserMessage(
                conversationId = conversationId,
                content = body,
                attachments = emptyList(),
            )
            text = ""
            val draftState = markdownFileChangeController.createPlanningDraft(
                conversationId = conversationId,
                projectId = projectId,
                sourceUserMessageId = userMessageId,
            )
            upsertMarkdownFileChangeState(draftState)
            generateMarkdownFileChange(draftState, body)
        }
    }

    fun retryMarkdownFileChange(state: MarkdownFileChangeState) {
        val sourceText = messages.firstOrNull { it.id == state.draft.sourceUserMessageId }?.content.orEmpty()
        if (sourceText.isBlank()) {
            upsertMarkdownFileChangeState(
                markdownFileChangeController.markFailed(state, "找不到原始文件变更请求"),
            )
            return
        }
        generateMarkdownFileChange(state, sourceText)
    }

    fun showMarkdownFileChangeDiff(state: MarkdownFileChangeState) {
        pendingMarkdownReviewDraftId = state.draft.id
        pendingMarkdownReview = reviewStateForFileChange(state)
        retainedReviewIndexes = state.items.mapIndexedNotNull { index, item ->
            index.takeIf { item.retained }
        }.toSet()
    }

    fun applyMarkdownFileChangeState(state: MarkdownFileChangeState, retainedIndexes: Set<Int>) {
        val retained = state.items
            .filterIndexed { index, _ -> index in retainedIndexes }
            .map {
                MarkdownUpdateProposal(
                    operation = it.operation,
                    path = it.path,
                    title = it.title,
                    reason = it.reason,
                    markdown = it.markdown,
                )
            }
        if (retained.isEmpty()) {
            val dismissed = markdownFileChangeController.dismiss(state)
            upsertMarkdownFileChangeState(dismissed)
            pendingMarkdownReview = null
            pendingMarkdownReviewDraftId = null
            retainedReviewIndexes = emptySet()
            return
        }

        scope.launch {
            runCatching {
                val written = container.projectWorkspaceGateway.applyMarkdownUpdates(state.draft.projectId, retained)
                container.chatRepository.insertSystemEvent(
                    conversationId = conversationId,
                    content = markdownWriteBackAppliedEvent(written.map { it.path }),
                )
                written
            }.onSuccess { written ->
                upsertMarkdownFileChangeState(
                    markdownFileChangeController.markApplied(state, written.map { it.path }),
                )
                if (selectedProjectId == state.draft.projectId) {
                    deliverables = container.projectWorkspaceGateway.listDeliverables(state.draft.projectId)
                }
                pendingMarkdownReview = null
                pendingMarkdownReviewDraftId = null
                retainedReviewIndexes = emptySet()
                sessionStatus = "已写入 ${written.size} 项 Markdown 更新"
                errorText = null
            }.onFailure {
                val feedback = markdownWriteBackFailureFeedback(it)
                errorText = feedback.errorText
                sessionStatus = feedback.statusText
            }
        }
    }

    fun requestMarkdownReview(message: ChatMessage) {
        val projectId = selectedProjectId
        if (!canWriteBackMarkdown(projectId, null, message.content)) {
            errorText = "请先选择项目"
            return
        }
        scope.launch {
            isPlanningMarkdownUpdates = true
            sessionStatus = null
            errorText = null
            runCatching {
                val resolvedProjectId = projectId ?: error("请先选择项目")
                val project = projects.firstOrNull { it.id == resolvedProjectId }
                val snapshots = markdownSnapshots(resolvedProjectId)
                val plan = markdownUpdatePlanner.plan(
                    projectName = project?.name.orEmpty(),
                    projectContext = projectContext,
                    markdowns = snapshots,
                    assistantMarkdown = message.content,
                    providerId = selectedProviderId,
                    modelOverride = selectedModel,
                )
                val review = buildReviewState(plan.proposals, snapshots)
                require(review.proposals.isNotEmpty()) { "LLM 没有生成可审核的 Markdown 更新" }
                review
            }.onSuccess { review ->
                pendingMarkdownReviewDraftId = null
                pendingMarkdownReview = review
                retainedReviewIndexes = review.proposals.indices.toSet()
            }.onFailure {
                val feedback = markdownWriteBackFailureFeedback(it)
                errorText = feedback.errorText
                sessionStatus = feedback.statusText
            }
            isPlanningMarkdownUpdates = false
            pendingWriteBack = null
        }
    }

    fun applyRetainedMarkdownUpdates(
        review: MarkdownUpdateReviewState,
        retainedIndexes: Set<Int>,
        draftId: String?,
    ) {
        if (draftId != null) {
            markdownFileChangeStates.firstOrNull { it.draft.id == draftId }?.let { state ->
                applyMarkdownFileChangeState(state, retainedIndexes)
            }
            return
        }
        val projectId = selectedProjectId
        if (projectId.isNullOrBlank()) {
            errorText = "请先选择项目"
            return
        }
        val retained = review.proposals.filterIndexed { index, _ -> index in retainedIndexes }
        if (retained.isEmpty()) {
            pendingMarkdownReview = null
            pendingMarkdownReviewDraftId = null
            retainedReviewIndexes = emptySet()
            sessionStatus = "已撤回全部 Markdown 更新"
            errorText = null
            return
        }
        scope.launch {
            runCatching {
                val written = container.projectWorkspaceGateway.applyMarkdownUpdates(projectId, retained)
                container.chatRepository.insertSystemEvent(
                    conversationId = conversationId,
                    content = markdownWriteBackAppliedEvent(written.map { it.path }),
                )
                written
            }.onSuccess {
                deliverables = container.projectWorkspaceGateway.listDeliverables(projectId)
                pendingMarkdownReview = null
                pendingMarkdownReviewDraftId = null
                retainedReviewIndexes = emptySet()
                sessionStatus = "已写入 ${it.size} 项 Markdown 更新"
                errorText = null
            }.onFailure {
                val feedback = markdownWriteBackFailureFeedback(it)
                errorText = feedback.errorText
                sessionStatus = feedback.statusText
            }
        }
    }

    pendingMarkdownReview?.let { review ->
        MarkdownUpdateReviewDialog(
            review = review,
            retainedIndexes = retainedReviewIndexes,
            onToggleRetained = { index ->
                retainedReviewIndexes = if (index in retainedReviewIndexes) {
                    retainedReviewIndexes - index
                } else {
                    retainedReviewIndexes + index
                }
                pendingMarkdownReviewDraftId?.let { draftId ->
                    markdownFileChangeStates.firstOrNull { it.draft.id == draftId }?.let { state ->
                        upsertMarkdownFileChangeState(
                            markdownFileChangeController.toggleRetained(state, index),
                        )
                    }
                }
            },
            onConfirm = { applyRetainedMarkdownUpdates(review, retainedReviewIndexes, pendingMarkdownReviewDraftId) },
            onDismiss = {
                pendingMarkdownReview = null
                pendingMarkdownReviewDraftId = null
                retainedReviewIndexes = emptySet()
            },
        )
    }

    if (showModelPicker) {
        ModelPickerDialog(
            providers = providers,
            selectedProviderId = selectedProviderId,
            selectedModel = selectedModel,
            selectedReasoningEffort = selectedReasoningEffort,
            selectableModelsByProviderId = selectableModelsByProviderId,
            onSelectProvider = { provider ->
                selectedProviderId = provider.id
                selectedModel = selectableModelsByProviderId[provider.id].orEmpty().firstOrNull().orEmpty()
                selectedReasoningEffort = defaultReasoningEffort()
            },
            onModelChange = { selectedModel = it },
            onReasoningEffortChange = { selectedReasoningEffort = it },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showSessionConfig) {
        SessionConfigDialog(
            projects = projects,
            selectedProjectId = selectedProjectId,
            promptText = finalSessionPrompt.ifBlank { rawSessionPrompt },
            optimizedPrompt = optimizedSessionPrompt,
            status = sessionConfigStatus,
            isOptimizing = isOptimizingPrompt,
            onSelectProject = {
                selectedProjectId = it
                sessionConfigStatus = null
            },
            onPromptChange = {
                rawSessionPrompt = it
                finalSessionPrompt = it
                sessionConfigStatus = null
            },
            onOptimizePrompt = ::optimizeSessionPrompt,
            onUseOptimizedPrompt = {
                finalSessionPrompt = optimizedSessionPrompt
                rawSessionPrompt = optimizedSessionPrompt
                saveSessionPrompt(final = optimizedSessionPrompt)
                optimizedSessionPrompt = ""
                sessionConfigStatus = null
            },
            onDismiss = {
                saveSessionPrompt()
                showSessionConfig = false
            },
        )
    }

    pendingWriteBack?.let { message ->
        AlertDialog(
            onDismissRequest = { pendingWriteBack = null },
            title = { Text("生成文件变更") },
            text = {
                Text(
                    "会先生成 Markdown 更新计划，你可以审核每个文件的 diff，并逐项保留或撤回。",
                )
            },
            confirmButton = {
                TextButton(enabled = !isPlanningMarkdownUpdates, onClick = { requestMarkdownReview(message) }) {
                    Text(if (isPlanningMarkdownUpdates) "生成中..." else "生成文件变更")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingWriteBack = null }) { Text("取消") }
            },
        )
    }

    pendingSelectionCopy?.let { message ->
        MessageSelectionCopyDialog(
            text = messageSelectionCopyText(message),
            onCopyAll = {
                clipboard.setText(AnnotatedString(messageSelectionCopyText(message)))
            },
            onDismiss = { pendingSelectionCopy = null },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding),
    ) {
        errorText?.let { ResponsiveChatContentRail { InlineError(it) } }
        sessionStatus?.let { ResponsiveChatContentRail { InlineStatus(it) } }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val contentMaxWidth = chatContentMaxWidthDp(maxWidth.value.toInt()).dp
            val bubbleMaxWidth = messageBubbleMaxWidthDp(contentMaxWidth.value.toInt()).dp
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        ChatContentRail(contentMaxWidth = contentMaxWidth) {
                            EmptyChatState()
                        }
                    }
                }
                items(messages, key = { it.id }) { message ->
                    val persistedParts by container.chatRepository
                        .observeMessageParts(message.id)
                        .collectAsState(initial = emptyList())
                    ChatContentRail(contentMaxWidth = contentMaxWidth) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (message.role == MessageRole.SYSTEM) {
                                ContextEventLine(message.content)
                            } else {
                                val displayParts = messageDisplayParts(message, persistedParts)
                                MessageBubble(
                                    message = message,
                                    parts = displayParts,
                                    maxBubbleWidth = bubbleMaxWidth,
                                    canWriteBack = message.role == MessageRole.ASSISTANT &&
                                        message.status == MessageStatus.SUCCEEDED &&
                                        canWriteBackMarkdown(selectedProjectId, null, message.content),
                                    onWriteBack = { pendingWriteBack = message },
                                    onCopy = {
                                        clipboard.setText(
                                            AnnotatedString(
                                                messageSelectionCopyText(message, displayParts),
                                            ),
                                        )
                                    },
                                    onSelectCopy = { pendingSelectionCopy = message },
                                    isSpeaking = speakingMessageId == message.id,
                                    onSpeak = { speakAssistantMessage(message) },
                                )
                            }
                            markdownFileChangeStates
                                .filter { it.draft.sourceUserMessageId == message.id }
                                .forEach { state ->
                                    MarkdownFileChangeCard(
                                        state = state,
                                        onShowDiff = { showMarkdownFileChangeDiff(state) },
                                        onApply = {
                                            applyMarkdownFileChangeState(
                                                state = state,
                                                retainedIndexes = state.items.mapIndexedNotNull { index, item ->
                                                    index.takeIf { item.retained }
                                                }.toSet(),
                                            )
                                        },
                                        onRetry = { retryMarkdownFileChange(state) },
                                        onDismiss = {
                                            upsertMarkdownFileChangeState(markdownFileChangeController.dismiss(state))
                                        },
                                    )
                                }
                        }
                    }
                }
            }
        }

        busyText?.let { ResponsiveChatContentRail { AssistantActivityIndicator(it) } }

        ResponsiveChatContentRail {
            ChatInputBar(
                text = text,
                onTextChange = { text = it },
                selectedImage = selectedImage,
                onPickImage = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onRemoveImage = { selectedImage = null },
                showWebSearch = shouldShowWebSearchButton(webSearchSettings),
                webSearchEnabled = webSearchEnabled,
                onToggleWebSearch = { enabled ->
                    if (enabled && !webSearchSettings.enabled) {
                        errorText = "请先在设置 -> 搜索能力启用联网搜索"
                    } else {
                        errorText = null
                        webSearchEnabled = enabled
                    }
                },
                showVoiceInput = shouldShowVoiceInputButton(voiceSettings),
                onStartVoiceTranscription = {
                    errorText = "语音能力已开启，但当前版本暂未接入可用的语音输入方案"
                },
                providers = providers,
                selectedProviderId = selectedProviderId,
                selectedModel = selectedModel,
                selectedReasoningEffort = selectedReasoningEffort,
                onOpenModelPicker = { showModelPicker = true },
                contextStatus = contextStatus,
                isCompressingContext = isCompressingContext,
                onCompressContext = ::compressContextNow,
                inputFocusRequester = inputFocusRequester,
                canSend = !isAssistantBusy &&
                    selectedProvider != null &&
                    selectedModel.isNotBlank() &&
                    (text.isNotBlank() || selectedImage != null),
                isBusy = isAssistantBusy,
                onSend = {
                    if (isAssistantBusy) {
                        stopNow()
                    } else {
                        handleSendIntent(
                            hasSelectedImage = selectedImage != null,
                            dismissKeyboard = dismissKeyboard,
                            sendNow = ::sendNow,
                        )
                    }
                },
                showFileChangeSuggestion = shouldShowFileChangeModeEntry(text),
                canSendFileChange = shouldShowFileChangeModeEntry(text) && !isAssistantBusy,
                onSendFileChange = {
                    dismissKeyboard()
                    sendFileChangeNow()
                },
            )
        }
    }
}

private fun sessionRequestContext(
    finalPrompt: String,
    project: WorkspaceProject?,
    projectContext: String,
    markdowns: List<MarkdownDeliverable>,
): SessionRequestContext? {
    val markdownIndex = markdownIndexForPrompt(markdowns)
    if (
        finalPrompt.isBlank() &&
        project == null &&
        projectContext.isBlank() &&
        markdownIndex.isBlank()
    ) {
        return null
    }
    return SessionRequestContext(
        finalPrompt = finalPrompt,
        projectName = project?.name,
        deliverableTitle = null,
        projectContext = projectContext,
        deliverableMarkdown = markdownIndex,
    )
}

private fun markdownIndexForPrompt(markdowns: List<MarkdownDeliverable>): String =
    markdowns.takeIf { it.isNotEmpty() }?.joinToString(separator = "\n") {
        "- ${it.path}｜${it.title}"
    }.orEmpty()

private suspend fun buildWebSearchContext(
    query: String,
    enabledForSession: Boolean,
    settings: WebSearchSettings,
    nativeWebSearchMode: NativeWebSearchMode?,
    search: suspend () -> com.harnessapk.websearch.WebSearchResult,
    onFailure: (Throwable) -> Unit,
): WebSearchContext? {
    if (!shouldUseExternalWebSearch(query, enabledForSession, settings, nativeWebSearchMode)) return null
    return runCatching { WebSearchContext(search()) }
        .onFailure(onFailure)
        .getOrNull()
        ?.takeIf { it.results.results.isNotEmpty() }
}

internal data class AutoScrollKey(
    val messageCount: Int,
    val lastMessageId: String?,
    val lastMessageStatus: MessageStatus?,
    val lastMessageContentLength: Int,
    val lastMessageUpdatedAt: Long,
)

internal fun autoScrollKey(messages: List<ChatMessage>): AutoScrollKey {
    val lastMessage = messages.lastOrNull()
    return AutoScrollKey(
        messageCount = messages.size,
        lastMessageId = lastMessage?.id,
        lastMessageStatus = lastMessage?.status,
        lastMessageContentLength = lastMessage?.content?.length ?: 0,
        lastMessageUpdatedAt = lastMessage?.updatedAt ?: 0L,
    )
}

internal enum class ChatAutoScrollMode {
    NONE,
    JUMP_TO_BOTTOM,
    ANIMATE_TO_BOTTOM,
    STREAM_TO_BOTTOM,
}

internal data class ChatScrollTarget(
    val index: Int,
    val scrollOffset: Int,
)

internal fun chatScrollTarget(mode: ChatAutoScrollMode, lastMessageIndex: Int): ChatScrollTarget? {
    if (lastMessageIndex < 0) return null
    return when (mode) {
        ChatAutoScrollMode.JUMP_TO_BOTTOM,
        ChatAutoScrollMode.STREAM_TO_BOTTOM -> ChatScrollTarget(
            index = lastMessageIndex,
            scrollOffset = CHAT_SCROLL_TO_BOTTOM_OFFSET_PX,
        )
        ChatAutoScrollMode.ANIMATE_TO_BOTTOM -> ChatScrollTarget(index = lastMessageIndex, scrollOffset = 0)
        ChatAutoScrollMode.NONE -> null
    }
}

internal fun chatAutoScrollMode(
    previous: AutoScrollKey?,
    current: AutoScrollKey,
    canFollowStreaming: Boolean = false,
): ChatAutoScrollMode = when {
    current.messageCount == 0 -> ChatAutoScrollMode.NONE
    previous == null -> ChatAutoScrollMode.JUMP_TO_BOTTOM
    previous.messageCount == 0 -> ChatAutoScrollMode.JUMP_TO_BOTTOM
    current.messageCount > previous.messageCount -> ChatAutoScrollMode.ANIMATE_TO_BOTTOM
    current.lastMessageId != previous.lastMessageId -> ChatAutoScrollMode.ANIMATE_TO_BOTTOM
    canFollowStreaming &&
        current.lastMessageStatus == MessageStatus.STREAMING &&
        current.lastMessageContentLength > previous.lastMessageContentLength -> ChatAutoScrollMode.STREAM_TO_BOTTOM
    else -> ChatAutoScrollMode.NONE
}

private fun LazyListState.canFollowStreaming(lastMessageIndex: Int): Boolean {
    if (lastMessageIndex < 0) return false
    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
    if (lastVisibleItem.index < lastMessageIndex) return false
    val distanceToBottom = (lastVisibleItem.offset + lastVisibleItem.size) - layoutInfo.viewportEndOffset
    return distanceToBottom <= STREAMING_AUTO_SCROLL_BOTTOM_THRESHOLD_PX
}

internal enum class ChatBubbleSide {
    START,
    END,
}

internal fun messageBubbleSide(role: MessageRole): ChatBubbleSide =
    if (role == MessageRole.USER) ChatBubbleSide.END else ChatBubbleSide.START

internal fun chatContentMaxWidthDp(availableWidthDp: Int): Int =
    availableWidthDp.coerceAtMost(MAX_CHAT_CONTENT_WIDTH_DP).coerceAtLeast(0)

internal fun messageBubbleMaxWidthDp(contentWidthDp: Int): Int =
    ((contentWidthDp * 92) / 100)
        .coerceAtMost(MAX_MESSAGE_BUBBLE_WIDTH_DP)
        .coerceAtLeast(0)

internal fun assistantActivityLabel(messages: List<ChatMessage>): String? {
    val activeAssistant = messages.lastOrNull {
        it.role == MessageRole.ASSISTANT &&
            (it.status == MessageStatus.PENDING || it.status == MessageStatus.STREAMING)
    } ?: return null

    return when (activeAssistant.status) {
        MessageStatus.PENDING -> "助手正在思考..."
        MessageStatus.STREAMING -> "助手正在回复..."
        else -> null
    }
}

internal fun assistantMessageDisplayText(message: ChatMessage): String = when {
    message.content.isNotBlank() -> message.content
    message.role == MessageRole.ASSISTANT && message.status == MessageStatus.PENDING -> "助手正在思考..."
    message.role == MessageRole.ASSISTANT && message.status == MessageStatus.STREAMING -> "助手正在回复..."
    message.role == MessageRole.ASSISTANT && message.status == MessageStatus.CANCELLED -> "已暂停生成"
    else -> ""
}

internal fun messageDisplayParts(
    message: ChatMessage,
    persistedParts: List<UiMessagePartDraft>,
): List<UiMessagePartDraft> {
    if (persistedParts.isNotEmpty()) return persistedParts
    val fallbackText = assistantMessageDisplayText(message).takeIf { it.isNotBlank() } ?: return emptyList()
    return listOf(
        UiMessagePartDraft(
            index = 0,
            type = UiMessagePartType.TEXT,
            content = fallbackText,
            metadata = emptyMap(),
            stable = message.status != MessageStatus.PENDING && message.status != MessageStatus.STREAMING,
        ),
    )
}

internal fun modelPickerButtonText(
    providers: List<ProviderProfile>,
    selectedProviderId: String?,
    selectedModel: String,
    selectedReasoningEffort: ReasoningEffort,
): String {
    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId } ?: return "先配置模型"
    val reasoningText = if (supportsReasoningEffort(selectedProvider, selectedModel)) {
        " · ${selectedReasoningEffort.label}"
    } else {
        ""
    }
    return "$selectedModel$reasoningText"
}

internal fun errorDisplayText(errorText: String): String = errorText
    .lineSequence()
    .firstOrNull()
    ?.takeIf { it.isNotBlank() }
    ?: errorText

internal fun errorCopyText(errorText: String): String = errorText

internal fun messageSelectionCopyText(
    message: ChatMessage,
    parts: List<UiMessagePartDraft> = emptyList(),
): String =
    message.errorMessage?.let(::errorCopyText)
        ?: parts.visibleText().takeIf { it.isNotBlank() }
        ?: assistantMessageDisplayText(message)

private fun List<UiMessagePartDraft>.visibleText(): String =
    filter { it.type == UiMessagePartType.TEXT }
        .joinToString(separator = "") { it.content }

internal fun handleSendIntent(
    hasSelectedImage: Boolean,
    dismissKeyboard: () -> Unit,
    sendNow: () -> Unit,
) {
    dismissKeyboard()
    sendNow()
}

internal fun shouldAutoFocusChatInput(
    autoFocusRequested: Boolean,
    autoFocusAlreadyRequested: Boolean,
    hasMessages: Boolean,
    text: String,
    hasSelectedImage: Boolean,
): Boolean =
    autoFocusRequested &&
        !autoFocusAlreadyRequested &&
        !hasMessages &&
        text.isBlank() &&
        !hasSelectedImage

internal fun shouldShowWebSearchButton(settings: WebSearchSettings): Boolean = settings.enabled

internal fun shouldShowVoiceInputButton(settings: VoiceSettings): Boolean = settings.speechInputEnabled

internal fun handleStopIntent(
    cancelActiveSend: () -> Unit,
    cancelVisibleAssistant: () -> Unit,
) {
    cancelActiveSend()
    cancelVisibleAssistant()
}

internal fun sendButtonContentDescription(isBusy: Boolean): String =
    if (isBusy) "暂停生成" else "发送"

internal enum class ChatInputTrailingAction {
    ATTACHMENT,
    SEND,
}

internal fun shouldShowCollapsedAttachmentEntry(
    text: String,
    hasSelectedImage: Boolean,
): Boolean = text.isBlank() && !hasSelectedImage

internal fun chatInputTrailingAction(
    text: String,
    hasSelectedImage: Boolean,
    isBusy: Boolean,
): ChatInputTrailingAction =
    if (shouldShowCollapsedAttachmentEntry(text, hasSelectedImage) && !isBusy) {
        ChatInputTrailingAction.ATTACHMENT
    } else {
        ChatInputTrailingAction.SEND
    }

internal enum class FileChangeSendDecision {
    SEND,
    BLOCKED_NEEDS_PROJECT,
    BLOCKED_EMPTY_INPUT,
    BLOCKED_UNSUPPORTED_IMAGE,
    BLOCKED_BUSY,
}

internal fun decideFileChangeSend(
    selectedProjectId: String?,
    text: String,
    hasSelectedImage: Boolean,
    isBusy: Boolean,
): FileChangeSendDecision = when {
    selectedProjectId.isNullOrBlank() -> FileChangeSendDecision.BLOCKED_NEEDS_PROJECT
    text.isBlank() -> FileChangeSendDecision.BLOCKED_EMPTY_INPUT
    hasSelectedImage -> FileChangeSendDecision.BLOCKED_UNSUPPORTED_IMAGE
    isBusy -> FileChangeSendDecision.BLOCKED_BUSY
    else -> FileChangeSendDecision.SEND
}

internal fun shouldSuggestFileChangeMode(text: String): Boolean {
    val normalized = text.trim().lowercase(Locale.ROOT)
    if (normalized.isBlank()) return false
    return fileChangeSuggestionKeywords.any { normalized.contains(it) }
}

internal fun shouldShowFileChangeModeEntry(text: String): Boolean =
    shouldSuggestFileChangeMode(text)

internal fun markdownFileChangeConversationContext(messages: List<ChatMessage>): String =
    messages
        .filter {
            it.content.isNotBlank() &&
                it.status == MessageStatus.SUCCEEDED &&
                (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT)
        }
        .takeLast(MAX_FILE_CHANGE_CONTEXT_MESSAGES)
        .joinToString(separator = "\n\n") { message ->
            val role = if (message.role == MessageRole.USER) "用户" else "助手"
            "$role：${message.content.trim().take(MAX_FILE_CHANGE_CONTEXT_MESSAGE_CHARS)}"
        }

internal fun markdownFileChangeCardTitle(
    status: MarkdownFileChangeStatus,
    itemCount: Int,
): String = when (status) {
    MarkdownFileChangeStatus.PLANNING -> "正在生成 Markdown 文件变更..."
    MarkdownFileChangeStatus.READY -> "已生成 $itemCount 个 Markdown 文件变更"
    MarkdownFileChangeStatus.APPLIED -> "已应用 $itemCount 个 Markdown 文件变更"
    MarkdownFileChangeStatus.DISMISSED -> "已撤回 Markdown 文件变更"
    MarkdownFileChangeStatus.FAILED -> "Markdown 文件变更生成失败"
}

internal fun visibleMarkdownFileChangeItems(items: List<MarkdownFileChangeItem>): List<MarkdownFileChangeItem> =
    items.take(MAX_FILE_CHANGE_CARD_ITEMS)

internal fun hiddenMarkdownFileChangeItemCount(items: List<MarkdownFileChangeItem>): Int =
    (items.size - MAX_FILE_CHANGE_CARD_ITEMS).coerceAtLeast(0)

internal fun markdownFileChangeOperationLabel(item: MarkdownFileChangeItem): String =
    when (item.operation) {
        MarkdownUpdateOperation.CREATE -> "A"
        MarkdownUpdateOperation.UPDATE -> "M"
    }

private fun assistantMessageStatusText(message: ChatMessage): String? = when {
    message.role != MessageRole.ASSISTANT -> null
    message.status == MessageStatus.PENDING -> "正在连接模型..."
    message.status == MessageStatus.STREAMING -> "正在接收回复..."
    message.status == MessageStatus.CANCELLED -> "已暂停"
    else -> null
}

@Composable
private fun ContextEventLine(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                text = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

private data class MarkdownUpdateReviewState(
    val proposals: List<MarkdownUpdateProposal>,
    val diffs: List<List<MarkdownDiffLine>>,
)

@Composable
private fun MarkdownUpdateReviewDialog(
    review: MarkdownUpdateReviewState,
    retainedIndexes: Set<Int>,
    onToggleRetained: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Markdown 更新审核") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = markdownReviewSummary(review.proposals, retainedIndexes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                review.proposals.forEachIndexed { index, proposal ->
                    MarkdownUpdateReviewItem(
                        index = index,
                        proposal = proposal,
                        diff = review.diffs.getOrElse(index) { emptyList() },
                        retained = index in retainedIndexes,
                        onToggleRetained = onToggleRetained,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
            ) { Text(markdownReviewConfirmText(retainedIndexes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun MarkdownUpdateReviewItem(
    index: Int,
    proposal: MarkdownUpdateProposal,
    diff: List<MarkdownDiffLine>,
    retained: Boolean,
    onToggleRetained: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(proposal.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = proposal.path,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                TextButton(onClick = { onToggleRetained(index) }) {
                    Text(if (retained) "撤回" else "保留")
                }
            }
            if (proposal.reason.isNotBlank()) {
                Text(
                    text = proposal.reason,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                diff.take(MAX_REVIEW_DIFF_LINES).forEach { line ->
                    MarkdownDiffLineView(line)
                }
                if (diff.size > MAX_REVIEW_DIFF_LINES) {
                    Text(
                        text = "... 已截断 ${diff.size - MAX_REVIEW_DIFF_LINES} 行",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownDiffLineView(line: MarkdownDiffLine) {
    val prefix = when (line.type) {
        MarkdownDiffLineType.ADDED -> "+"
        MarkdownDiffLineType.REMOVED -> "-"
        MarkdownDiffLineType.CONTEXT -> " "
    }
    val color = when (line.type) {
        MarkdownDiffLineType.ADDED -> MaterialTheme.colorScheme.primary
        MarkdownDiffLineType.REMOVED -> MaterialTheme.colorScheme.error
        MarkdownDiffLineType.CONTEXT -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = "$prefix ${line.text}",
        color = color,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
private fun SessionConfigDialog(
    projects: List<WorkspaceProject>,
    selectedProjectId: String?,
    promptText: String,
    optimizedPrompt: String,
    status: String?,
    isOptimizing: Boolean,
    onSelectProject: (String?) -> Unit,
    onPromptChange: (String) -> Unit,
    onOptimizePrompt: () -> Unit,
    onUseOptimizedPrompt: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("会话配置") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (projects.isEmpty()) {
                    Text(
                        text = "项目模块还未接入，可先作为临时会话使用。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = selectedProjectId == null,
                            onClick = { onSelectProject(null) },
                            label = { Text("临时") },
                        )
                        projects.forEach { project ->
                            FilterChip(
                                selected = project.id == selectedProjectId,
                                onClick = { onSelectProject(project.id) },
                                label = { Text(project.name) },
                            )
                        }
                    }
                }
                HorizontalDivider()
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = promptText,
                    onValueChange = onPromptChange,
                    label = { Text("会话提示词") },
                    minLines = 3,
                    maxLines = 5,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !isOptimizing,
                        onClick = onOptimizePrompt,
                    ) {
                        Text(if (isOptimizing) "优化中..." else "优化")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = optimizedPrompt.isNotBlank(),
                        onClick = onUseOptimizedPrompt,
                    ) { Text("使用结果") }
                }
                if (optimizedPrompt.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 1.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "优化结果",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp)
                                    .verticalScroll(rememberScrollState()),
                                text = optimizedPrompt,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                status?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

private const val MAX_REVIEW_DIFF_LINES = 120
private const val MAX_WRITE_BACK_EVENT_PATHS = 3
private const val MAX_TTS_TEXT_LENGTH = 4_000
private const val MAX_CHAT_CONTENT_WIDTH_DP = 760
private const val MAX_MESSAGE_BUBBLE_WIDTH_DP = 700
internal const val CHAT_SCROLL_TO_BOTTOM_OFFSET_PX = 1_000_000
private const val STREAMING_AUTO_SCROLL_BOTTOM_THRESHOLD_PX = 640
private const val MAX_FILE_CHANGE_CARD_ITEMS = 6
private const val MAX_FILE_CHANGE_CONTEXT_MESSAGES = 10
private const val MAX_FILE_CHANGE_CONTEXT_MESSAGE_CHARS = 2_000
private val fileChangeSuggestionKeywords = listOf(
    "生成 md",
    "生成md",
    "生成markdown",
    "生成 markdown",
    "写 prd",
    "写prd",
    "更新文档",
    "沉淀到项目",
    "生成方案",
    "整理 readme",
    "整理readme",
)

internal fun markdownReviewConfirmText(retainedIndexes: Set<Int>): String =
    if (retainedIndexes.isEmpty()) "撤回全部" else "写入保留项"

internal data class MarkdownWriteBackFeedback(
    val errorText: String?,
    val statusText: String?,
)

internal fun markdownWriteBackFailureFeedback(error: Throwable): MarkdownWriteBackFeedback =
    MarkdownWriteBackFeedback(errorText = error.toUserMessage(), statusText = null)

internal fun markdownWriteBackAppliedEvent(paths: List<String>): String {
    val visiblePaths = paths
        .filter { it.isNotBlank() }
        .take(MAX_WRITE_BACK_EVENT_PATHS)
    return if (visiblePaths.isEmpty()) {
        "已沉淀到项目"
    } else {
        "已沉淀到项目：${visiblePaths.joinToString("、")}"
    }
}

@Composable
private fun ModelPickerDialog(
    providers: List<ProviderProfile>,
    selectedProviderId: String?,
    selectedModel: String,
    selectedReasoningEffort: ReasoningEffort,
    selectableModelsByProviderId: Map<String, List<String>>,
    onSelectProvider: (ProviderProfile) -> Unit,
    onModelChange: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId } ?: providers.firstOrNull()
    val selectableModels = selectedProvider?.let { selectableModelsByProviderId[it.id] }.orEmpty()
    val showReasoningEffort = selectedProvider?.let { supportsReasoningEffort(it, selectedModel) } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "供应商",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    providers.forEach { provider ->
                        FilterChip(
                            selected = provider.id == selectedProviderId,
                            onClick = { onSelectProvider(provider) },
                            label = { Text(provider.name) },
                        )
                    }
                }
                HorizontalDivider()
                Text(
                    text = "模型",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (selectableModels.isEmpty()) {
                    Text(
                        text = "请先在模型配置里为该供应商维护可选模型。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        selectableModels.forEach { model ->
                            FilterChip(
                                modifier = Modifier.fillMaxWidth(),
                                selected = model == selectedModel,
                                onClick = { onModelChange(model) },
                                label = {
                                    Text(
                                        text = model,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
                if (showReasoningEffort) {
                    HorizontalDivider()
                    Text(
                        text = "推理强度",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ReasoningEffort.entries.forEach { effort ->
                            FilterChip(
                                selected = effort == selectedReasoningEffort,
                                onClick = { onReasoningEffortChange(effort) },
                                label = { Text(effort.label) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

@Composable
private fun MarkdownFileChangeCard(
    state: MarkdownFileChangeState,
    onShowDiff: () -> Unit,
    onApply: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 380.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = state.draft.summary.ifBlank {
                        markdownFileChangeCardTitle(state.draft.status, state.items.size)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                when (state.draft.status) {
                    MarkdownFileChangeStatus.PLANNING -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    MarkdownFileChangeStatus.READY -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            visibleMarkdownFileChangeItems(state.items).forEach { item ->
                                MarkdownFileChangeItemRow(item)
                            }
                            val hiddenCount = hiddenMarkdownFileChangeItemCount(state.items)
                            if (hiddenCount > 0) {
                                Text(
                                    text = "还有 $hiddenCount 个文件",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onShowDiff) { Text("查看 diff") }
                            TextButton(onClick = onApply) {
                                Text(if (state.items.all { it.retained }) "应用全部" else "应用保留项")
                            }
                            TextButton(onClick = onDismiss) { Text("撤回") }
                        }
                    }
                    MarkdownFileChangeStatus.FAILED -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onRetry) { Text("重试") }
                            TextButton(onClick = onDismiss) { Text("撤回") }
                        }
                    }
                    MarkdownFileChangeStatus.APPLIED,
                    MarkdownFileChangeStatus.DISMISSED,
                    -> Unit
                }
            }
        }
    }
}

@Composable
private fun MarkdownFileChangeItemRow(item: MarkdownFileChangeItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = markdownFileChangeOperationLabel(item),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.path,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            if (item.reason.isNotBlank()) {
                Text(
                    text = item.reason,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "+${item.addedLineCount} -${item.removedLineCount}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ResponsiveChatContentRail(content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        ChatContentRail(
            contentMaxWidth = chatContentMaxWidthDp(maxWidth.value.toInt()).dp,
            content = content,
        )
    }
}

@Composable
private fun ChatContentRail(
    contentMaxWidth: Dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = contentMaxWidth)
                .fillMaxWidth(),
        ) {
            content()
        }
    }
}

@Composable
private fun MessagePartsColumn(
    parts: List<UiMessagePartDraft>,
    textColor: androidx.compose.ui.graphics.Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            key(part.index, part.type) {
                MessagePartView(part = part, textColor = textColor)
            }
        }
    }
}

@Composable
private fun MessagePartView(
    part: UiMessagePartDraft,
    textColor: androidx.compose.ui.graphics.Color,
) {
    when (part.type) {
        UiMessagePartType.TEXT -> MarkdownMessage(markdown = part.content, textColor = textColor)
        UiMessagePartType.REASONING -> ReasoningPart(part)
        UiMessagePartType.SEARCH_RESULT -> SearchResultPart(part)
        UiMessagePartType.TOOL_CALL -> MetadataPart(label = "工具调用", content = part.content)
        UiMessagePartType.TOOL_RESULT -> MetadataPart(label = "工具结果", content = part.content)
        UiMessagePartType.ERROR_DETAIL -> MetadataPart(label = "错误详情", content = part.content)
        UiMessagePartType.FILE_CHANGE -> MetadataPart(label = "文件变更", content = part.content)
        UiMessagePartType.IMAGE -> MetadataPart(label = "图片", content = part.content.ifBlank { "图片附件" })
        UiMessagePartType.DOCUMENT -> MetadataPart(label = "文档", content = part.content.ifBlank { "文档附件" })
        UiMessagePartType.SYSTEM_EVENT -> MetadataPart(label = "系统事件", content = part.content)
    }
}

@Composable
private fun ReasoningPart(part: UiMessagePartDraft) {
    var expanded by remember(part.index) { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (expanded) "收起思考过程" else "思考过程",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (expanded) {
                Text(
                    text = part.content,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SearchResultPart(part: UiMessagePartDraft) {
    val title = part.metadata["title"].orEmpty().ifBlank { "搜索结果" }
    val url = part.metadata["url"].orEmpty()
    MetadataPart(
        label = title,
        content = listOf(url, part.content)
            .filter { it.isNotBlank() }
            .joinToString("\n"),
    )
}

@Composable
private fun MetadataPart(
    label: String,
    content: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (content.isNotBlank()) {
                Text(
                    text = content,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    parts: List<UiMessagePartDraft>,
    maxBubbleWidth: Dp,
    canWriteBack: Boolean,
    onWriteBack: () -> Unit,
    onCopy: () -> Unit,
    onSelectCopy: () -> Unit,
    isSpeaking: Boolean,
    onSpeak: () -> Unit,
) {
    val isUser = message.role == MessageRole.USER
    val selectionCopyText = messageSelectionCopyText(message, parts)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = when (messageBubbleSide(message.role)) {
            ChatBubbleSide.START -> Arrangement.Start
            ChatBubbleSide.END -> Arrangement.End
        },
    ) {
        Surface(
            modifier = Modifier.widthIn(max = maxBubbleWidth),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 18.dp,
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            tonalElevation = if (isUser) 0.dp else 1.dp,
            shadowElevation = if (isUser) 0.dp else 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isUser) "你" else message.model ?: "助手",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (selectionCopyText.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (message.role == MessageRole.ASSISTANT && selectionCopyText.isNotBlank()) {
                                IconButton(
                                    modifier = Modifier.size(32.dp),
                                    onClick = onSpeak,
                                ) {
                                    Icon(
                                        imageVector = if (isSpeaking) Icons.Filled.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
                                        contentDescription = if (isSpeaking) "停止朗读" else "朗读回复",
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                            }
                            IconButton(
                                modifier = Modifier.size(32.dp),
                                onClick = onSelectCopy,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.TextFields,
                                    contentDescription = "选择复制",
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                            if (message.role == MessageRole.ASSISTANT && (selectionCopyText.isNotBlank() || message.errorMessage != null)) {
                                IconButton(
                                    modifier = Modifier.size(32.dp),
                                    onClick = onCopy,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentCopy,
                                        contentDescription = "复制",
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (parts.isNotEmpty()) {
                    SelectionContainer {
                        MessagePartsColumn(
                            parts = parts,
                            textColor = if (isUser) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                assistantMessageStatusText(message)?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                message.errorMessage?.let {
                    Text(
                        text = errorDisplayText(it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "可复制详细日志",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (canWriteBack) {
                    TextButton(onClick = onWriteBack) {
                        Text("生成文件变更")
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageSelectionCopyDialog(
    text: String,
    onCopyAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择复制") },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                onValueChange = {},
                readOnly = true,
                minLines = 4,
                maxLines = 12,
            )
        },
        confirmButton = {
            TextButton(onClick = onCopyAll) {
                Text("整段复制")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    selectedImage: Uri?,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
    showWebSearch: Boolean,
    webSearchEnabled: Boolean,
    onToggleWebSearch: (Boolean) -> Unit,
    showVoiceInput: Boolean,
    onStartVoiceTranscription: () -> Unit,
    providers: List<ProviderProfile>,
    selectedProviderId: String?,
    selectedModel: String,
    selectedReasoningEffort: ReasoningEffort,
    onOpenModelPicker: () -> Unit,
    contextStatus: ContextWindowStatus,
    isCompressingContext: Boolean,
    onCompressContext: () -> Unit,
    inputFocusRequester: FocusRequester,
    canSend: Boolean,
    isBusy: Boolean,
    onSend: () -> Unit,
    showFileChangeSuggestion: Boolean,
    canSendFileChange: Boolean,
    onSendFileChange: () -> Unit,
) {
    var showContextDetails by remember { mutableStateOf(false) }
    val trailingAction = chatInputTrailingAction(
        text = text,
        hasSelectedImage = selectedImage != null,
        isBusy = isBusy,
    )

    Surface(
        modifier = Modifier.imePadding(),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            selectedImage?.let {
                SelectedImagePreview(uri = it, onRemove = onRemoveImage)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showWebSearch) {
                    FilterChip(
                        selected = webSearchEnabled,
                        onClick = { onToggleWebSearch(!webSearchEnabled) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        label = { Text("联网") },
                    )
                }
                if (showVoiceInput) {
                    FilterChip(
                        selected = false,
                        onClick = onStartVoiceTranscription,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        label = { Text("语音") },
                    )
                }
                ModelStatusChip(
                    providers = providers,
                    selectedProviderId = selectedProviderId,
                    selectedModel = selectedModel,
                    selectedReasoningEffort = selectedReasoningEffort,
                    onOpenModelPicker = onOpenModelPicker,
                )
                ContextStatusChip(
                    contextStatus = contextStatus,
                    expanded = showContextDetails,
                    isCompressingContext = isCompressingContext,
                    onExpandedChange = { showContextDetails = it },
                    onCompressContext = onCompressContext,
                )
                if (showFileChangeSuggestion) {
                    FilterChip(
                        selected = false,
                        enabled = canSendFileChange,
                        onClick = onSendFileChange,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Assignment,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        label = { Text("文件变更") },
                    )
                }
            }
            if (showFileChangeSuggestion) {
                Text(
                    text = "建议使用文件变更模式",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(inputFocusRequester),
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = { Text("发消息") },
                    minLines = 1,
                    maxLines = 5,
                )
                when (trailingAction) {
                    ChatInputTrailingAction.ATTACHMENT -> IconButton(onClick = onPickImage) {
                        Icon(Icons.Outlined.Add, contentDescription = "选择图片")
                    }
                    ChatInputTrailingAction.SEND -> FilledIconButton(
                        enabled = isBusy || canSend,
                        onClick = onSend,
                    ) {
                        Icon(
                            imageVector = if (isBusy) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                            contentDescription = sendButtonContentDescription(isBusy),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelStatusChip(
    providers: List<ProviderProfile>,
    selectedProviderId: String?,
    selectedModel: String,
    selectedReasoningEffort: ReasoningEffort,
    onOpenModelPicker: () -> Unit,
) {
    FilterChip(
        selected = false,
        enabled = providers.isNotEmpty(),
        onClick = onOpenModelPicker,
        label = {
            Text(
                text = modelPickerButtonText(providers, selectedProviderId, selectedModel, selectedReasoningEffort),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun ContextStatusChip(
    contextStatus: ContextWindowStatus,
    expanded: Boolean,
    isCompressingContext: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCompressContext: () -> Unit,
) {
    val canManualCompress = contextWindowCanManualCompress(contextStatus)
    Box {
        FilterChip(
            selected = false,
            onClick = { onExpandedChange(true) },
            leadingIcon = {
                ContextUsageRing(
                    progress = contextWindowUsageProgress(contextStatus),
                    modifier = Modifier.size(18.dp),
                )
            },
            label = {
                Text(
                    text = contextWindowStatusCompactText(contextStatus),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 260.dp, max = 320.dp)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "上下文使用",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = contextWindowStatusText(contextStatus),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { contextWindowUsageProgress(contextStatus) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "自动压缩阈值：${contextStatus.compressionThresholdPercent}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    enabled = canManualCompress && !isCompressingContext,
                    onClick = {
                        onExpandedChange(false)
                        onCompressContext()
                    },
                ) {
                    Text(
                        when {
                            isCompressingContext -> "压缩中..."
                            canManualCompress -> "手动压缩"
                            else -> "暂不需要压缩"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextUsageRing(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    val progressColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val strokeWidth = 2.5.dp.toPx()
        val inset = strokeWidth / 2f
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        drawArc(
            color = progressColor,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun SelectedImagePreview(uri: Uri, onRemove: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(uri) {
        runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "已选择图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Image, contentDescription = null)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("图片已选择", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, contentDescription = "移除图片")
            }
        }
    }
}

@Composable
private fun AssistantActivityIndicator(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun InlineError(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            modifier = Modifier.padding(12.dp),
            text = text,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun InlineStatus(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            modifier = Modifier.padding(12.dp),
            text = text,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun EmptyChatState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "开始一段对话",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "支持多供应商和截图输入。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.heightIn(min = 8.dp))
        HorizontalDivider(
            modifier = Modifier.widthIn(max = 160.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
