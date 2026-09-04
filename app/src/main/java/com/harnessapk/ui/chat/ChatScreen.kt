package com.harnessapk.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.provider.Settings
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.harnessapk.chat.ChatMessage
import com.harnessapk.chat.Conversation
import com.harnessapk.chat.ConversationDraft
import com.harnessapk.chat.DocumentTextExtractor
import com.harnessapk.chat.ExtractedDocument
import com.harnessapk.chat.ChatExecutionEntry
import com.harnessapk.chat.ChatExecutionPhase
import com.harnessapk.chat.ChatExecutionRequestContext
import com.harnessapk.chat.ChatExecutionStatus
import com.harnessapk.chat.ContextSnapshotDraftV2
import com.harnessapk.chat.ContextSnapshotV2
import com.harnessapk.chat.ChatSendRequestPhase
import com.harnessapk.chat.ChatSendRequestState
import com.harnessapk.chat.EnqueueChatRequest
import com.harnessapk.chat.ChatAttachment
import com.harnessapk.chat.ChatImageSource
import com.harnessapk.chat.ChatImageStore
import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import com.harnessapk.chat.PendingImageAttachment
import com.harnessapk.chat.ReasoningEffort
import com.harnessapk.chat.StreamingMessageSnapshot
import com.harnessapk.chat.UiMessagePartDraft
import com.harnessapk.chat.UiMessagePartType
import com.harnessapk.chat.defaultReasoningEffort
import com.harnessapk.chat.hideWikiCitationTokensForDisplay
import com.harnessapk.chat.identityLockedForPendingSend
import com.harnessapk.chat.projectContextSha256
import com.harnessapk.chat.supportsReasoningEffort
import com.harnessapk.agent.AgentVersionCoverage
import com.harnessapk.agent.InitialConversationIdentity
import com.harnessapk.agentmemory.AgentMemory
import com.harnessapk.common.AppContainer
import com.harnessapk.common.toUserMessage
import com.harnessapk.project.ProjectFileRevisionState
import com.harnessapk.projectsearch.ProjectSourceAuthority
import com.harnessapk.provider.ProviderProfile
import com.harnessapk.provider.ModelCapabilityResolver
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.provider.modelConfigForProvider
import com.harnessapk.provider.parseProviderCapabilityCatalogJson
import com.harnessapk.session.MarkdownBatchApplyResult
import com.harnessapk.session.ContextFactEvidence
import com.harnessapk.session.MarkdownFileApplyStatus
import com.harnessapk.session.MarkdownFileChangeController
import com.harnessapk.session.MarkdownFileChangeConversationContext
import com.harnessapk.session.MarkdownFileChangeFailure
import com.harnessapk.session.MarkdownFileChangeItem
import com.harnessapk.session.MarkdownFileChangePlanningException
import com.harnessapk.session.MarkdownFileChangeState
import com.harnessapk.session.MarkdownFileChangeStatus
import com.harnessapk.session.MarkdownDraftOrigin
import com.harnessapk.session.MarkdownDraftOriginType
import com.harnessapk.session.stableMarkdownDraftId
import com.harnessapk.session.MarkdownDraftOwner
import com.harnessapk.session.MarkdownDeliverable
import com.harnessapk.session.MarkdownDiffLine
import com.harnessapk.session.MarkdownSnapshot
import com.harnessapk.session.MarkdownUpdatePlannerUseCase
import com.harnessapk.session.MarkdownUpdateOperation
import com.harnessapk.session.MarkdownUpdateProposal
import com.harnessapk.session.SessionRequestContext
import com.harnessapk.session.SessionSummary
import com.harnessapk.session.WorkspaceProject
import com.harnessapk.session.WikiMarkdownSourceContext
import com.harnessapk.session.buildMarkdownDiff
import com.harnessapk.session.markdownReviewSummary
import com.harnessapk.session.canWriteBackMarkdown
import com.harnessapk.storage.DefaultModelPreference
import com.harnessapk.storage.ProjectEvidenceSnapshotEntity
import com.harnessapk.storage.MarkdownChangeDraftRecord
import com.harnessapk.storage.ProviderCapabilityCatalogSnapshot
import com.harnessapk.ui.components.InlineStatusMessage
import com.harnessapk.ui.components.MarkdownDraftDiff
import com.harnessapk.ui.components.StatusTone
import com.harnessapk.ui.agent.AgentMemorySheet
import com.harnessapk.ui.agent.canOperateAgentMemory
import com.harnessapk.ui.agent.resolveAgentMemorySourceTarget
import com.harnessapk.ui.agent.AgentMemorySourceTarget
import com.harnessapk.ui.markdown.MarkdownMessage
import com.harnessapk.ui.markdown.MarkdownLinkTarget
import com.harnessapk.ui.markdown.markdownTextForCopy
import com.harnessapk.ui.markdown.markdownLinkTarget
import com.harnessapk.ui.model.resolveModelSelection
import com.harnessapk.ui.theme.HarnessSpacing
import com.harnessapk.websearch.WebSearchContext
import com.harnessapk.websearch.WebSearchRequest
import com.harnessapk.websearch.WebSearchSettings
import com.harnessapk.websearch.nativeWebSearchModeForRequest
import com.harnessapk.websearch.shouldUseExternalWebSearch
import com.harnessapk.voice.VoiceSettings
import com.harnessapk.voice.VoiceInputPhase
import com.harnessapk.voice.VoiceInputState
import com.harnessapk.wiki.WikiRef
import com.harnessapk.wiki.WikiVersionState
import com.harnessapk.wiki.MessageWikiCitation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.UUID
import java.security.MessageDigest

internal enum class ChatImageSourceAction {
    REQUEST_CAMERA_PERMISSION,
    LAUNCH_CAMERA,
}

internal fun cameraAction(permissionGranted: Boolean): ChatImageSourceAction =
    if (permissionGranted) ChatImageSourceAction.LAUNCH_CAMERA
    else ChatImageSourceAction.REQUEST_CAMERA_PERMISSION

internal data class CameraCancelledFeedback(
    val text: String,
    val errorText: String?,
)

internal fun cameraCancelledFeedback(
    currentText: String,
    @Suppress("UNUSED_PARAMETER") currentErrorText: String?,
): CameraCancelledFeedback = CameraCancelledFeedback(
    text = currentText,
    errorText = null,
)

internal data class PendingCameraUriState(
    val savedUri: String? = null,
) {
    fun start(uri: String): PendingCameraUriState = copy(savedUri = uri)

    fun clear(): PendingCameraUriState = copy(savedUri = null)
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun ChatScreen(
    container: AppContainer,
    conversationId: String,
    initialProjectId: String? = null,
    autoFocusInput: Boolean = false,
    sessionConfigRequestKey: Int = 0,
    onSessionConfigRequestConsumed: () -> Unit = {},
    wikiScopeRequestKey: Int = 0,
    onWikiScopeRequestConsumed: () -> Unit = {},
    searchRequestKey: Int = 0,
    onSearchRequestConsumed: () -> Unit = {},
    onOpenProjectFiles: (projectId: String, selectedPath: String?) -> Unit = { _, _ -> },
    onOpenProjectGit: (projectId: String) -> Unit = {},
    onContinueInProject: (conversationId: String, projectId: String?) -> Unit = { _, _ -> },
    initialSourceMessageId: String? = null,
    onOpenConversationMessage: (conversationId: String, messageId: String) -> Unit = { _, _ -> },
    onOpenWikiCitation: (String) -> Unit = {},
    voiceInputState: VoiceInputState = VoiceInputState(),
    onStartVoiceInput: (currentDraft: String, language: String) -> Unit = { _, _ -> },
    onStopVoiceInput: () -> Unit = {},
    onVoiceInputConsumed: () -> Unit = {},
    startWithCamera: Boolean = false,
    startWithVoice: Boolean = false,
    contentPadding: PaddingValues,
) {
    val persistedMessages by remember(conversationId) {
        container.chatRepository.observeMessages(conversationId)
            .map { messages -> PersistedMessagesState.Loaded(messages) as PersistedMessagesState }
    }.collectAsState(initial = PersistedMessagesState.Loading)
    val messageState = persistedMessages
    val messages = messageState.messagesOrEmpty()
    val persistedMarkdownDraftRecords by remember(conversationId) {
        container.database.markdownChangeDraftDao().observeRecordsForConversation(conversationId)
    }.collectAsState(initial = emptyList())
    val messagePartsById by remember(conversationId) {
        container.chatRepository.observeMessagePartsForConversation(conversationId)
    }.collectAsState(initial = emptyMap())
    val attachmentsByMessageId by remember(conversationId) {
        container.chatRepository.observeAttachmentsForConversation(conversationId)
    }.collectAsState(initial = emptyMap())
    val wikiCitationsByMessageId by remember(conversationId) {
        container.conversationWikiRepository.observeCitationsForConversation(conversationId)
    }.collectAsState(initial = emptyMap())
    val agents by container.agentRepository.observeAgents().collectAsState(initial = emptyList())
    val installedWikis by container.wikiRepository.observeWikis().collectAsState(initial = emptyList())
    val executionEntries by container.chatExecutionRepository
        .observeForConversation(conversationId)
        .collectAsState(initial = emptyList())
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
    var text by rememberSaveable(conversationId) { mutableStateOf("") }
    var selectedImages by remember { mutableStateOf<List<PendingImageAttachment>>(emptyList()) }
    var persistentDraftLoaded by remember(conversationId) { mutableStateOf(false) }
    var pendingCameraUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    // 文档附件（pdf/xlsx/docx/csv/txt）：文本抽取后并入发送文本；仅在当前会话内存中保留
    var pendingDocuments by remember { mutableStateOf<List<ExtractedDocument>>(emptyList()) }
    var documentExtracting by remember { mutableStateOf(false) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (pendingDocuments.size >= DocumentTextExtractor.MAX_DOCUMENTS_PER_MESSAGE) {
            errorText = "每条消息最多添加 ${DocumentTextExtractor.MAX_DOCUMENTS_PER_MESSAGE} 个文件"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            documentExtracting = true
            errorText = null
            val result = runCatching {
                withContext(container.dispatchers.io) { DocumentTextExtractor.extract(context, uri) }
            }
            documentExtracting = false
            result.fold(
                onSuccess = { document ->
                    if (pendingDocuments.any { it.fileName == document.fileName }) {
                        errorText = "已添加同名文件：${document.fileName}"
                    } else {
                        pendingDocuments = pendingDocuments + document
                    }
                },
                onFailure = { error -> errorText = error.message ?: "文件读取失败" },
            )
        }
    }
    var selectedProjectEvidence by remember(conversationId) {
        mutableStateOf<ProjectEvidenceSnapshotEntity?>(null)
    }
    var selectedProjectEvidenceRevision by remember(conversationId) {
        mutableStateOf(ProjectFileRevisionState.UNAVAILABLE)
    }
    var selectedProjectEvidenceConversationId by remember(conversationId) {
        mutableStateOf<String?>(null)
    }
    var showModelPicker by remember { mutableStateOf(false) }
    var selectedProviderId by remember { mutableStateOf<String?>(null) }
    var selectedModel by remember { mutableStateOf("") }
    var selectedReasoningEffort by remember { mutableStateOf(defaultReasoningEffort()) }
    var webSearchEnabled by remember { mutableStateOf(false) }
    var conversation by remember(conversationId) { mutableStateOf<Conversation?>(null) }
    var isAgentConversation by remember(conversationId) { mutableStateOf(false) }
    var firstMessagePending by remember(conversationId) { mutableStateOf(false) }
    var sendSnapshotInFlight by remember(conversationId) { mutableStateOf(false) }
    var identityMessageStateKnown by remember(conversationId) { mutableStateOf(false) }
    var persistedUserMessage by remember(conversationId) { mutableStateOf(false) }
    var showIdentityDetails by remember { mutableStateOf(false) }
    var showWikiScopePicker by remember(conversationId) { mutableStateOf(false) }
    var showMessageSearch by remember(conversationId) { mutableStateOf(false) }
    var messageSearchQuery by remember(conversationId) { mutableStateOf("") }
    var debouncedMessageSearchQuery by remember(conversationId) { mutableStateOf("") }
    var messageSearchFilter by remember(conversationId) { mutableStateOf(ConversationSearchFilter.ALL) }
    var messageSearchCursor by remember(conversationId) { mutableStateOf(0) }
    var highlightedMessageId by remember(conversationId) { mutableStateOf<String?>(null) }
    var messageSearchOriginIndex by remember(conversationId) { mutableStateOf(0) }
    var messageSearchOriginOffset by remember(conversationId) { mutableStateOf(0) }
    var conversationWikiMounts by remember(conversationId) { mutableStateOf(emptyList<com.harnessapk.wiki.ConversationWikiMount>()) }
    var conversationWikiCatalog by remember(conversationId) { mutableStateOf(emptyList<ConversationWikiCatalogEntry>()) }
    var fixedVersionCoverage by remember(conversationId) { mutableStateOf<AgentVersionCoverage?>(null) }
    var agentOpening by remember(conversationId) { mutableStateOf<String?>(null) }
    var showSessionConfig by remember { mutableStateOf(false) }
    var showConversationContext by remember { mutableStateOf(false) }
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
    var pendingDepositProjectSelection by remember { mutableStateOf<ChatMessage?>(null) }
    var pendingSelectionCopy by remember { mutableStateOf<ChatMessage?>(null) }
    var pendingMarkdownReview by remember { mutableStateOf<MarkdownUpdateReviewState?>(null) }
    var pendingMarkdownReviewDraftId by remember { mutableStateOf<String?>(null) }
    var pendingLegacyMarkdownReviewToken by remember { mutableStateOf<LegacyMarkdownReviewToken?>(null) }
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
    var pendingSourceMessageId by remember(conversationId, initialSourceMessageId) {
        mutableStateOf(initialSourceMessageId)
    }

    LaunchedEffect(conversationId) {
        val records = container.database.markdownChangeDraftDao().listRecordsForConversation(conversationId)
        records.filter {
            it.draft.status == MarkdownFileChangeStatus.PLANNING.name &&
                !container.isMarkdownDraftPlanning(it.draft.id)
        }.forEach { record ->
            container.database.markdownChangeDraftDao().updateDraft(
                record.draft.copy(
                    status = MarkdownFileChangeStatus.FAILED.name,
                    summary = "上次规划被进程中断，可重试",
                    errorMessage = "规划被进程中断",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        records
            .filter { it.draft.status == MarkdownFileChangeStatus.APPLYING.name }
            .forEach { record ->
                val succeeded = record.items.count { it.applyStatus == MarkdownFileApplyStatus.SUCCEEDED.name }
                record.items.filter { it.retained && it.applyStatus == null }.forEach { item ->
                    container.database.markdownChangeDraftDao().updateItemApplyResult(
                        item.id,
                        MarkdownFileApplyStatus.FAILED.name,
                        "进程中断，结果未确认，可安全重试",
                    )
                }
                container.database.markdownChangeDraftDao().updateDraft(
                    record.draft.copy(
                        status = if (succeeded > 0) {
                            MarkdownFileChangeStatus.PARTIALLY_APPLIED.name
                        } else {
                            MarkdownFileChangeStatus.FAILED.name
                        },
                        summary = "上次应用被中断；已保存 $succeeded 个文件结果，可安全重试其余项",
                        errorMessage = "应用被中断",
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
    }
    LaunchedEffect(persistedMarkdownDraftRecords) {
        markdownFileChangeStates = persistedMarkdownDraftRecords.mapNotNull { record ->
            val projectId = record.draft.projectId
            val snapshots = runCatching {
                container.projectWorkspaceGateway.listDeliverables(projectId).map { deliverable ->
                    MarkdownSnapshot(
                        id = deliverable.id,
                        title = deliverable.title,
                        path = deliverable.path,
                        markdown = container.projectWorkspaceGateway.readDeliverable(projectId, deliverable.id),
                    )
                }
            }.getOrDefault(emptyList())
            persistedMarkdownFileChangeState(record, snapshots)
        }
    }
    var sourceLocationConsumed by remember(conversationId, initialSourceMessageId) {
        mutableStateOf(false)
    }
    var sourceLocationStatus by remember(conversationId, initialSourceMessageId) {
        mutableStateOf<String?>(null)
    }
    val currentAgentId = conversation?.agentId
    val relationshipMemories by remember(currentAgentId) {
        currentAgentId?.let(container.agentMemoryRepository::observe)
            ?: flowOf(emptyList<AgentMemory>())
    }.collectAsState(initial = emptyList())
    val identityController = remember(conversationId) {
        ConversationIdentityController(
            scope = scope,
            selectDraft = { agentId ->
                container.conversationIdentityRepository.selectDraft(conversationId, agentId)
            },
            reloadConversation = { container.chatRepository.conversation(conversationId) },
        )
    }
    val identityControllerState by identityController.state.collectAsState()
    val wikiScopeController = remember(conversationId) {
        ConversationWikiController(
            scope = scope,
            applyScope = { selections ->
                container.conversationWikiRepository.replaceMountScope(conversationId, selections)
            },
            restoreDefaultsAction = {
                container.conversationWikiRepository.restoreDefaults(conversationId)
            },
            reloadMounts = {
                container.conversationWikiRepository.mounts(conversationId)
            },
        )
    }
    val wikiScopeControllerState by wikiScopeController.state.collectAsState()
    val sendRequestState by container.chatSendRecoveryStore
        .observe(conversationId)
        .collectAsState(initial = container.chatSendRecoveryStore.current(conversationId))
    LaunchedEffect(conversationId) {
        val draft = withContext(container.dispatchers.io) {
            container.conversationDraftStore.load(conversationId)
        }
        if (text.isEmpty() && selectedImages.isEmpty()) {
            text = draft.text
            selectedImages = draft.attachments
        }
        persistentDraftLoaded = true
    }
    LaunchedEffect(conversationId, persistentDraftLoaded, text, selectedImages) {
        if (!persistentDraftLoaded) return@LaunchedEffect
        withContext(container.dispatchers.io) {
            container.conversationDraftStore.save(
                conversationId,
                ConversationDraft(text = text, attachments = selectedImages),
            )
        }
    }
    AgentMemoryConversationLeaveEffect(
        conversationId = conversationId,
        onConversationLeft = container.agentMemoryCoordinator::onConversationLeft,
    )
    LaunchedEffect(
        sendRequestState?.requestId,
        sendRequestState?.phase,
        sendRequestState?.currentDraftText,
        sendRequestState?.currentDraftAttachments,
    ) {
        val request = sendRequestState ?: return@LaunchedEffect
        if (request.phase == ChatSendRequestPhase.IN_FLIGHT || request.phase == ChatSendRequestPhase.UNKNOWN) {
            text = request.currentDraftText
            selectedImages = request.currentDraftAttachments
        }
    }
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
    val markdownDraftApplyController = remember(conversationId) { MarkdownDraftApplyController() }
    var applyingMarkdownDraftIds by remember(conversationId) { mutableStateOf<Set<String>>(emptySet()) }
    val legacyMarkdownReviewApplyController = remember(conversationId) { LegacyMarkdownReviewApplyController() }
    var applyingLegacyMarkdownReviewToken by remember(conversationId) {
        mutableStateOf<LegacyMarkdownReviewToken?>(null)
    }

    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
    val selectedModelConfig = modelConfigForProvider(selectedProvider, selectedModel)
    val contextStatus = contextWindowStatus(
        messages = messages,
        memory = memory,
        modelConfig = selectedModelConfig,
    )
    val isAssistantBusy = hasRunningChatExecution(executionEntries)
    val identityState = remember(
        conversation,
        messages,
        agents,
        firstMessagePending,
        identityControllerState.selectionPending,
        identityMessageStateKnown,
        persistedUserMessage,
        sendRequestState,
    ) {
        conversationIdentityUiState(
            conversation = conversation,
            messages = messages,
            agents = agents,
            firstMessagePending = firstMessagePending ||
                identityControllerState.selectionPending ||
                identityLockedForPendingSend(sendRequestState),
            messageStateKnown = identityMessageStateKnown,
            persistedUserMessage = persistedUserMessage,
        )
    }
    val wikiScopeState = remember(conversationWikiMounts, conversationWikiCatalog) {
        conversationWikiUiState(conversationWikiMounts, conversationWikiCatalog)
    }
    val executionByUserMessageId = remember(executionEntries) {
        executionEntries.associateBy(ChatExecutionEntry::userMessageId)
    }
    val executionByAssistantMessageId = remember(executionEntries) {
        executionEntries.mapNotNull { entry ->
            entry.assistantMessageId?.let { assistantMessageId -> assistantMessageId to entry }
        }.toMap()
    }
    val latestExecutionId = executionEntries.maxByOrNull(ChatExecutionEntry::sequence)?.id
    val searchDocuments = remember(messages, messagePartsById, wikiCitationsByMessageId) {
        buildConversationSearchDocuments(messages, messagePartsById, wikiCitationsByMessageId)
    }
    val dismissKeyboard = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        Unit
    }

    LaunchedEffect(voiceInputState) {
        val hasVoiceUpdate = voiceInputState.phase != VoiceInputPhase.IDLE ||
            voiceInputState.committedText != null
        if (hasVoiceUpdate && voiceInputState.displayText != text) {
            text = voiceInputState.displayText
        }
        voiceInputState.errorMessage?.let { message ->
            errorText = if (voiceInputState.incomplete) "$message，已保留当前文字" else message
        }
        if (
            voiceInputState.committedText != null ||
            voiceInputState.phase == VoiceInputPhase.CANCELLED ||
            voiceInputState.phase == VoiceInputPhase.ERROR
        ) {
            onVoiceInputConsumed()
        }
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
        runCatching {
            container.chatRepository.conversation(conversationId) to
                container.chatRepository.hasUserMessage(conversationId)
        }
            .onSuccess { (loadedConversation, hasPersistedUserMessage) ->
                conversation = loadedConversation
                identityMessageStateKnown = true
                persistedUserMessage = hasPersistedUserMessage
                rawSessionPrompt = loadedConversation?.promptOriginal.orEmpty()
                optimizedSessionPrompt = loadedConversation?.promptOptimized.orEmpty()
                finalSessionPrompt = loadedConversation?.promptFinal.orEmpty()
                val projectId = loadedConversation?.projectId
                if (!projectId.isNullOrBlank()) {
                    selectedProjectId = projectId
                }
                val agentId = loadedConversation?.agentId
                isAgentConversation = !agentId.isNullOrBlank()
                if (isAgentConversation) webSearchEnabled = false
            }
            .onFailure { sessionStatus = it.toUserMessage() }
    }

    LaunchedEffect(conversationId, installedWikis) {
        runCatching {
            val catalog = installedWikis.map { wiki ->
                ConversationWikiCatalogEntry(
                    wikiId = wiki.id,
                    title = wiki.title,
                    versions = container.wikiRepository.listVersions(wiki.id).map { version ->
                        ConversationWikiCatalogVersion(
                            ref = WikiRef(version.wikiId, version.version),
                            ready = version.state == WikiVersionState.READY.name,
                            active = wiki.activeVersion == version.version,
                        )
                    },
                )
            }
            container.conversationWikiRepository.mounts(conversationId) to catalog
        }.onSuccess { (mounts, catalog) ->
            conversationWikiMounts = mounts
            conversationWikiCatalog = catalog
        }.onFailure { failure ->
            errorText = failure.toUserMessage()
        }
    }

    LaunchedEffect(identityControllerState.settledGeneration) {
        identityControllerState.refreshedConversation?.let { refreshedConversation ->
            conversation = refreshedConversation
            isAgentConversation = refreshedConversation.agentId != null
            if (isAgentConversation) webSearchEnabled = false
        }
        identityControllerState.failure?.let { errorText = it.toUserMessage() }
    }

    LaunchedEffect(wikiScopeControllerState.settledGeneration) {
        wikiScopeControllerState.refreshedMounts?.let { mounts ->
            conversationWikiMounts = mounts
            showWikiScopePicker = false
        }
    }

    LaunchedEffect(
        conversationId,
        conversation?.agentId,
        conversation?.agentVersion,
        identityMessageStateKnown,
        messageState,
    ) {
        val agentId = conversation?.agentId
        val version = conversation?.agentVersion
        agentOpening = if (
            identityMessageStateKnown && messageState.isLoadedEmpty() && agentId != null && version != null
        ) {
            container.agentRepository.opening(agentId, version)
        } else {
            null
        }
    }

    LaunchedEffect(conversationId, initialProjectId) {
        if (!initialProjectId.isNullOrBlank()) {
            val loadedConversation = container.chatRepository.conversation(conversationId)
            val hasUserMessage = container.chatRepository.hasUserMessage(conversationId)
            if (loadedConversation?.projectId == null && !hasUserMessage) {
                container.chatRepository.updateConversationProject(conversationId, initialProjectId)
                conversation = container.chatRepository.conversation(conversationId)
                selectedProjectId = initialProjectId
            } else {
                selectedProjectId = loadedConversation?.projectId
            }
        }
    }

    LaunchedEffect(messages) {
        if (messages.any { it.role == MessageRole.USER }) {
            persistedUserMessage = true
            firstMessagePending = reduceFirstMessagePending(
                pending = firstMessagePending,
                isFirstUserMessage = true,
                event = FirstMessagePendingEvent.USER_OBSERVED,
            )
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
        if (!pendingSourceMessageId.isNullOrBlank() && !sourceLocationConsumed) {
            previousAutoScrollKey = currentKey
            return@LaunchedEffect
        }
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

    LaunchedEffect(
        conversationId,
        pendingSourceMessageId,
        messageState,
        sourceLocationConsumed,
    ) {
        val sourceMessageId = pendingSourceMessageId
        if (
            !shouldConsumeSourceMessageLocation(
                sourceMessageId = sourceMessageId,
                messagesLoaded = messageState is PersistedMessagesState.Loaded,
                consumed = sourceLocationConsumed,
            )
        ) {
            return@LaunchedEffect
        }
        sourceLocationConsumed = true
        val index = sourceMessageIndex(messages, sourceMessageId.orEmpty())
        if (index == null) {
            sourceLocationStatus = "来源消息不可用"
        } else {
            sourceLocationStatus = null
            streamingAutoScrollEnabled = false
            listState.scrollToItem(index)
            highlightedMessageId = sourceMessageId
        }
    }

    LaunchedEffect(conversationId, autoFocusInput, messages.size, text, selectedImages) {
        if (
            shouldAutoFocusChatInput(
                autoFocusRequested = autoFocusInput,
                autoFocusAlreadyRequested = autoFocusInputRequested,
                hasMessages = messages.isNotEmpty(),
                text = text,
                hasSelectedImage = selectedImages.isNotEmpty(),
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

    // 全局「搜索能力」开启时，新会话默认联网（人物会话仍强制关闭）
    LaunchedEffect(webSearchSettings.enabled, conversation?.id, isAgentConversation) {
        if (webSearchSettings.enabled && !isAgentConversation) {
            webSearchEnabled = true
        }
    }

    LaunchedEffect(sessionConfigRequestKey) {
        if (sessionConfigRequestKey > 0) {
            showSessionConfig = true
            onSessionConfigRequestConsumed()
        }
    }

    LaunchedEffect(wikiScopeRequestKey) {
        if (wikiScopeRequestKey > 0) {
            showWikiScopePicker = true
            onWikiScopeRequestConsumed()
        }
    }

    LaunchedEffect(searchRequestKey) {
        if (searchRequestKey > 0) {
            messageSearchOriginIndex = listState.firstVisibleItemIndex
            messageSearchOriginOffset = listState.firstVisibleItemScrollOffset
            messageSearchQuery = ""
            messageSearchCursor = 0
            showMessageSearch = true
            onSearchRequestConsumed()
        }
    }

    LaunchedEffect(highlightedMessageId) {
        if (highlightedMessageId != null) {
            delay(1_500)
            highlightedMessageId = null
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

    fun discardPendingCameraImage() {
        val pendingState = PendingCameraUriState(pendingCameraUriString)
        val uri = pendingState.savedUri?.let(Uri::parse) ?: return
        pendingCameraUriString = pendingState.clear().savedUri
        container.applicationScope.launch {
            container.chatImageStore.deleteIfManaged(uri)
        }
    }

    fun updateActiveDraftSnapshot(nextText: String, nextAttachments: List<PendingImageAttachment>) {
        val request = container.chatSendRecoveryStore.current(conversationId) ?: return
        container.chatSendRecoveryStore.updateCurrentDraft(
            conversationId = conversationId,
            expectedRequestId = request.requestId,
            text = nextText,
            attachments = nextAttachments,
        )
    }

    fun syncActiveDraftSnapshot() = updateActiveDraftSnapshot(text, selectedImages)

    fun deleteReplacedImageIfNoLongerSubmitted(uri: Uri) {
        if (container.chatSendRecoveryStore.current(conversationId)?.submittedAttachments?.any { it.uri == uri } == true) return
        scope.launch {
            container.chatImageStore.deleteIfManaged(uri)
        }
    }

    fun appendSelectedImages(images: List<PendingImageAttachment>) {
        val withinLimit = images.filter { image ->
            val size = chatImageSizeBytes(context, image.uri)
            size == null || size <= MAX_CHAT_IMAGE_BYTES
        }
        if (withinLimit.size < images.size) errorText = "图片超过 8 MB，请选择更小的截图或图片"
        val distinct = withinLimit.filterNot { candidate -> selectedImages.any { it.uri == candidate.uri } }
        val available = (MAX_CHAT_IMAGE_ATTACHMENTS - selectedImages.size).coerceAtLeast(0)
        val accepted = distinct.take(available)
        if (accepted.size < distinct.size) errorText = "每条消息最多添加 $MAX_CHAT_IMAGE_ATTACHMENTS 张图片"
        if (accepted.isEmpty()) return
        val next = selectedImages + accepted
        updateActiveDraftSnapshot(text, next)
        selectedImages = next
    }

    fun removeSelectedImage(uri: Uri) {
        if (selectedImages.none { it.uri == uri }) return
        val next = selectedImages.filterNot { it.uri == uri }
        updateActiveDraftSnapshot(text, next)
        selectedImages = next
        deleteReplacedImageIfNoLongerSubmitted(uri)
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val pendingState = PendingCameraUriState(pendingCameraUriString)
        val uri = pendingState.savedUri?.let(Uri::parse)
        pendingCameraUriString = pendingState.clear().savedUri
        if (success && uri != null) {
            appendSelectedImages(listOf(PendingImageAttachment(uri, "image/jpeg")))
        } else {
            uri?.let { cancelledUri ->
                scope.launch {
                    container.chatImageStore.deleteIfManaged(cancelledUri)
                }
            }
            val feedback = cameraCancelledFeedback(text, errorText)
            updateActiveDraftSnapshot(feedback.text, selectedImages)
            text = feedback.text
            errorText = feedback.errorText
        }
    }

    fun launchCamera() {
        discardPendingCameraImage()
        val uri = container.chatImageStore.createCameraUri()
        pendingCameraUriString = PendingCameraUriState().start(uri.toString()).savedUri
        cameraLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCamera()
        } else {
            errorText = "未获得相机权限，可从相册选择图片"
        }
    }

    // 简洁模式「拍照提问」：进入会话即拉起相机（权限走标准请求流程）
    LaunchedEffect(startWithCamera, conversationId) {
        if (startWithCamera) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    // 简洁模式「语音提问」：进入会话即开始语音输入
    LaunchedEffect(startWithVoice, conversationId) {
        if (startWithVoice) {
            errorText = null
            onStartVoiceInput(text, voiceSettings.defaultTranscriptionLanguage)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_CHAT_IMAGE_ATTACHMENTS),
    ) { uris ->
        appendSelectedImages(uris.map { uri ->
            PendingImageAttachment(uri, context.contentResolver.getType(uri) ?: "image/png")
        })
    }

    suspend fun settlePersistedSend(submittedAttachments: List<PendingImageAttachment>): List<String> {
        val problems = mutableListOf<String>()
        try {
            conversation = container.chatRepository.conversation(conversationId)
            isAgentConversation = conversation?.agentId != null
            if (isAgentConversation) webSearchEnabled = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            problems += "会话状态刷新失败：${error.toUserMessage()}"
        }
        submittedAttachments.forEach { attachment ->
            try {
                container.chatImageStore.deleteIfManaged(attachment.uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                problems += "图片临时文件清理失败：${error.toUserMessage()}"
            }
        }
        return problems
    }

    fun reportPostPersistProblems(
        prefix: String,
        problems: List<String>,
        alwaysReport: Boolean = false,
    ) {
        if (problems.isNotEmpty() || alwaysReport) {
            firstMessagePending = reduceFirstMessagePending(
                pending = firstMessagePending,
                isFirstUserMessage = true,
                event = FirstMessagePendingEvent.POST_SUCCESS_FAILED,
            )
            if (problems.isEmpty()) {
                sessionStatus = prefix
            } else {
                sessionStatus = prefix + "：" + problems.joinToString("；")
            }
        }
    }

    fun sendNow() {
        // 文档附件文本并入消息正文（随消息持久化，经上下文压缩后仍在）
        val submittedText = DocumentTextExtractor.withDocumentBlocks(text, pendingDocuments)
        val body = submittedText
        if (body.isEmpty() && selectedImages.isEmpty()) return
        if (
            firstMessagePending ||
            sendSnapshotInFlight ||
            !identityController.canSend() ||
            !canAcceptChatSend(identityMessageStateKnown, container.chatSendRecoveryStore.current(conversationId))
        ) return
        val isFirstUserMessage = identityMessageStateKnown &&
            !persistedUserMessage &&
            messages.none { it.role == MessageRole.USER }
        val projectIdSnapshot = selectedProjectId
        val selectedProjectSnapshot = projects.firstOrNull { it.id == projectIdSnapshot }
        val projectContextSnapshot = projectContext
        val providerIdSnapshot = selectedProviderId ?: return
        val modelSnapshot = selectedModel.takeIf(String::isNotBlank) ?: return
        val reasoningEffortSnapshot = selectedReasoningEffort
        val webSearchEnabledSnapshot = webSearchEnabled
        val webSearchSettingsSnapshot = webSearchSettings
        val agentIdSnapshot = conversation?.agentId
        val agentVersionSnapshot = conversation?.agentVersion
        val capturedAt = System.currentTimeMillis()
        val sessionRequestContext = sessionRequestContext(
            finalPrompt = finalSessionPrompt.ifBlank { optimizedSessionPrompt.ifBlank { rawSessionPrompt } },
            project = selectedProjectSnapshot,
            projectContext = projectContextSnapshot,
            markdowns = deliverables,
        )
        val draftAttachments = selectedImages
        pendingDocuments = emptyList()
        val requestId = UUID.randomUUID().toString()
        val requestState = ChatSendRequestState(
            requestId = requestId,
            submittedText = submittedText,
            submittedAttachments = draftAttachments,
            isFirstUserMessage = isFirstUserMessage,
        )
        sendSnapshotInFlight = true
        scope.launch {
            val wikiScope = try {
                container.conversationWikiRepository.snapshotEnabled(conversationId)
            } catch (cancelled: CancellationException) {
                sendSnapshotInFlight = false
                throw cancelled
            } catch (error: Throwable) {
                sendSnapshotInFlight = false
                errorText = "读取本会话知识库失败：${error.toUserMessage()}"
                return@launch
            }
            val enqueueRequest = EnqueueChatRequest(
                requestId = requestId,
                conversationId = conversationId,
                content = body.ifEmpty { "请看这张截图" },
                attachments = draftAttachments,
                providerId = providerIdSnapshot,
                model = modelSnapshot,
                reasoningEffort = reasoningEffortSnapshot,
                requestContext = ChatExecutionRequestContext(
                    sessionContext = sessionRequestContext,
                    webSearchEnabled = webSearchEnabledSnapshot,
                    webSearchSettings = webSearchSettingsSnapshot,
                    wikiScopeSnapshot = wikiScope,
                ),
                contextSnapshotDraft = ContextSnapshotDraftV2(
                    projectId = projectIdSnapshot,
                    projectName = selectedProjectSnapshot?.name,
                    projectContextSha256 = projectContextSha256(projectIdSnapshot, projectContextSnapshot),
                    agentId = agentIdSnapshot,
                    agentVersion = agentVersionSnapshot,
                    wikiScope = wikiScope,
                    providerId = providerIdSnapshot,
                    model = modelSnapshot,
                    reasoningEffort = reasoningEffortSnapshot,
                    webSearchEnabled = webSearchEnabledSnapshot,
                    capturedAt = capturedAt,
                ),
            )
            if (container.chatSendRecoveryManager.start(conversationId, requestState, enqueueRequest) == null) {
                sendSnapshotInFlight = false
                return@launch
            }
            firstMessagePending = reduceFirstMessagePending(
                pending = firstMessagePending,
                isFirstUserMessage = isFirstUserMessage,
                event = FirstMessagePendingEvent.SEND_ACCEPTED,
            )
            errorText = null
            sendSnapshotInFlight = false
        }
    }

    LaunchedEffect(sendRequestState?.requestId, sendRequestState?.phase) {
        val request = sendRequestState ?: return@LaunchedEffect
        when (request.phase) {
            ChatSendRequestPhase.IN_FLIGHT -> Unit
            ChatSendRequestPhase.UNKNOWN -> sessionStatus = "消息状态待确认，请勿重复发送"
            ChatSendRequestPhase.LANDED -> {
                val consumed = container.chatSendRecoveryStore.consumeTerminal(conversationId, request.requestId)
                    ?: return@LaunchedEffect
                val terminalDraft = reduceTerminalDraft(
                    phase = consumed.phase,
                    submittedText = consumed.submittedText,
                    submittedAttachments = consumed.submittedAttachments,
                    currentText = consumed.currentDraftText,
                    currentAttachments = consumed.currentDraftAttachments,
                )
                text = terminalDraft.text
                selectedImages = terminalDraft.attachments
                persistedUserMessage = true
                firstMessagePending = reduceFirstMessagePending(
                    pending = firstMessagePending,
                    isFirstUserMessage = consumed.isFirstUserMessage,
                    event = FirstMessagePendingEvent.USER_OBSERVED,
                )
                sessionStatus = null
                val problems = settlePersistedSend(consumed.submittedAttachments)
                reportPostPersistProblems(
                    prefix = if (consumed.originalFailure == null) "消息已发送"
                    else "消息已入队，后台调度或执行启动失败，将由恢复机制继续处理",
                    problems = problems,
                    alwaysReport = consumed.originalFailure != null,
                )
            }
            ChatSendRequestPhase.NOT_LANDED -> {
                val consumed = container.chatSendRecoveryStore.consumeTerminal(conversationId, request.requestId)
                    ?: return@LaunchedEffect
                val terminalDraft = reduceTerminalDraft(
                    phase = consumed.phase,
                    submittedText = consumed.submittedText,
                    submittedAttachments = consumed.submittedAttachments,
                    currentText = consumed.currentDraftText,
                    currentAttachments = consumed.currentDraftAttachments,
                )
                text = terminalDraft.text
                selectedImages = terminalDraft.attachments
                val retainedUris = consumed.currentDraftAttachments.mapTo(hashSetOf()) { it.uri }
                consumed.submittedAttachments.filterNot { it.uri in retainedUris }.forEach { attachment ->
                    scope.launch { container.chatImageStore.deleteIfManaged(attachment.uri) }
                }
                firstMessagePending = reduceFirstMessagePending(
                    pending = firstMessagePending,
                    isFirstUserMessage = consumed.isFirstUserMessage,
                    event = FirstMessagePendingEvent.ENQUEUE_FAILED,
                )
                errorText = consumed.cancellation?.let { "消息未发送，已取消" }
                    ?: consumed.originalFailure?.toUserMessage()
                    ?: "消息发送失败"
                sessionStatus = null
            }
        }
    }

    fun stopNow() {
        container.chatExecutionCoordinator.cancelActive(conversationId)
    }

    fun speakAssistantMessageNow(message: ChatMessage) {
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

    fun speakAssistantMessage(message: ChatMessage) {
        if (!voiceSettings.ttsEnabled) {
            errorText = "请先在设置 -> 语音能力启用回复朗读"
            return
        }
        speakAssistantMessageNow(message)
    }

    // 自动朗读回复：默认关闭（voiceSettings.ttsAutoRead），开启时助手回复生成完成即朗读
    var lastAutoReadMessageId by rememberSaveable(conversationId) { mutableStateOf<String?>(null) }
    LaunchedEffect(messages, voiceSettings.ttsAutoRead) {
        if (!voiceSettings.ttsAutoRead) return@LaunchedEffect
        val lastAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return@LaunchedEffect
        if (
            lastAssistant.status == MessageStatus.SUCCEEDED &&
            lastAssistant.id != lastAutoReadMessageId &&
            lastAssistant.content.isNotBlank()
        ) {
            lastAutoReadMessageId = lastAssistant.id
            speakAssistantMessageNow(lastAssistant)
        }
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

    fun selectContextProject(targetProjectId: String?) {
        val hasUserMessage = persistedUserMessage ||
            firstMessagePending ||
            messages.any { it.role == MessageRole.USER }
        when (conversationProjectChange(selectedProjectId, targetProjectId, hasUserMessage)) {
            ConversationProjectChange.KEEP_CURRENT -> Unit
            ConversationProjectChange.UPDATE_CURRENT -> {
                selectedProjectId = targetProjectId
                sessionConfigStatus = null
                scope.launch {
                    runCatching {
                        container.chatRepository.updateConversationProject(conversationId, targetProjectId)
                        container.chatRepository.conversation(conversationId)
                    }.onSuccess { refreshed ->
                        conversation = refreshed
                    }.onFailure { error ->
                        sessionConfigStatus = error.toUserMessage()
                    }
                }
            }
            ConversationProjectChange.CONTINUE_IN_NEW -> {
                val targetProject = projects.firstOrNull { it.id == targetProjectId }
                scope.launch {
                    runCatching {
                        val identity = identityState.selectedAgentId?.let(InitialConversationIdentity::Agent)
                            ?: InitialConversationIdentity.Assistant
                        val newConversationId = container.newConversationUseCase.create(
                            title = targetProject?.name?.let { "$it 会话" } ?: "新会话",
                            projectId = targetProjectId,
                            identity = identity,
                            wikiScope = conversationWikiMounts.filter { it.enabled }.map { it.ref },
                        )
                        container.conversationDraftStore.save(
                            newConversationId,
                            ConversationDraft(text = text, attachments = selectedImages),
                        )
                        container.conversationDraftStore.clear(conversationId)
                        newConversationId
                    }.onSuccess { newConversationId ->
                        showConversationContext = false
                        onContinueInProject(newConversationId, targetProjectId)
                    }.onFailure { error ->
                        sessionConfigStatus = error.toUserMessage()
                    }
                }
            }
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

    suspend fun frozenContextFactEvidence(projectId: String, messageId: String): List<ContextFactEvidence> =
        container.database.projectSearchDao().evidenceForMessage(messageId)
            .filter { it.projectId == projectId }
            .mapNotNull { evidence ->
                val authority = runCatching { ProjectSourceAuthority.valueOf(evidence.authority) }.getOrNull()
                    ?: return@mapNotNull null
                ContextFactEvidence(
                    id = evidence.id,
                    authority = authority,
                    sourceSha256 = evidence.sourceSha256,
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

    suspend fun persistMarkdownFileChangeState(state: MarkdownFileChangeState) {
        val dao = container.database.markdownChangeDraftDao()
        val persistedDraft = dao.findDraft(state.draft.id) ?: return
        dao.updateDraft(
            persistedDraft.copy(
                status = state.draft.status.name,
                summary = state.draft.summary,
                updatedAt = state.draft.updatedAt,
            ),
        )
        val failures = state.applyFailures.associateBy { it.proposal.path }
        val items = dao.listItems(state.draft.id).map { item ->
            val uiItem = state.items.firstOrNull { it.path == item.relativePath }
            item.copy(
                retained = uiItem?.retained ?: item.retained,
                applyStatus = when {
                    item.relativePath in state.appliedPaths -> MarkdownFileApplyStatus.SUCCEEDED.name
                    item.relativePath in failures -> MarkdownFileApplyStatus.FAILED.name
                    else -> item.applyStatus
                },
                applyErrorMessage = failures[item.relativePath]?.errorMessage ?: item.applyErrorMessage,
            )
        }
        dao.replaceItems(state.draft.id, items)
        when {
            state.draft.status == MarkdownFileChangeStatus.DISMISSED ->
                container.markContextFacts(state.draft.id, "DISMISSED")
            state.appliedPaths.any(::isRootContextMarkdownPath) ->
                container.markContextFacts(state.draft.id, "APPLIED")
        }
    }

    fun upsertAndPersistMarkdownFileChangeState(state: MarkdownFileChangeState) {
        upsertMarkdownFileChangeState(state)
        scope.launch { persistMarkdownFileChangeState(state) }
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
                    baselineSha256 = it.baselineSha256,
                    expectedAbsent = it.expectedAbsent,
                )
            },
            diffs = state.diffs,
        )

    fun generateMarkdownFileChange(state: MarkdownFileChangeState, userRequest: String) {
        val planning = markdownFileChangeController.markPlanning(state)
        if (!container.beginMarkdownDraftPlanning(planning.draft.id)) return
        container.applicationScope.launch {
            try {
            if (scope.isActive) withContext(container.dispatchers.main) {
                upsertMarkdownFileChangeState(planning)
            }
            val sourceId = planning.draft.sourceUserMessageId ?: planning.draft.id
            val owner = MarkdownDraftOwner(
                projectId = planning.draft.projectId,
                conversationId = planning.draft.conversationId,
                sourceUserMessageId = planning.draft.sourceUserMessageId,
            )
            val origin = MarkdownDraftOrigin(
                type = MarkdownDraftOriginType.EXPLICIT_CHANGE,
                sourceId = sourceId,
                sourceSha256 = markdownOriginSha256(userRequest),
                sourceProjectId = planning.draft.projectId,
            )
            runCatching {
                container.markdownDraftCoordinator.persistPlanning(
                    owner = owner,
                    origin = origin,
                    preferredDraftId = planning.draft.id,
                )
                val project = projects.firstOrNull { it.id == planning.draft.projectId }
                val snapshots = markdownSnapshots(planning.draft.projectId)
                val conversationContext = markdownFileChangeConversationContext(messages)
                val wikiContext = loadProjectMarkdownWikiContext(conversationContext.messageIds) { messageIds ->
                    container.wikiMarkdownContextRepository.forMessageIds(messageIds)
                }
                val explicitEvidence = planning.draft.sourceUserMessageId?.let { messageId ->
                    listOf(
                        ContextFactEvidence(
                            id = "message:$messageId",
                            authority = ProjectSourceAuthority.USER_STATED,
                            sourceSha256 = markdownOriginSha256(userRequest),
                        ),
                    )
                }.orEmpty()
                val plan = markdownUpdatePlanner.planFromUserRequest(
                    projectName = project?.name.orEmpty(),
                    projectContext = projectContext,
                    markdowns = snapshots,
                    userRequest = userRequest,
                    conversationContext = conversationContext.text,
                    providerId = selectedProviderId,
                    modelOverride = selectedModel,
                    wikiCitations = wikiContext.citations,
                    wikiCoverage = wikiContext.coverage,
                    allowedEvidence = explicitEvidence,
                    suppressedContextFactKeys = container.database.projectSearchDao()
                        .suppressedContextFactKeys(planning.draft.projectId, sourceId)
                        .toSet(),
                )
                val ready = markdownFileChangeController.markReady(planning, plan, snapshots)
                container.markdownDraftCoordinator.persistPlan(
                    owner = owner,
                    origin = origin,
                    plan = plan,
                    snapshots = snapshots,
                    preferredDraftId = planning.draft.id,
                )
                ready
            }.onSuccess { ready ->
                if (scope.isActive) withContext(container.dispatchers.main) {
                    upsertMarkdownFileChangeState(ready)
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    withContext(NonCancellable) {
                        container.markdownDraftCoordinator.persistFailure(
                            owner = owner,
                            origin = origin,
                            errorMessage = "规划已中断，可重试",
                            preferredDraftId = planning.draft.id,
                        )
                    }
                    throw error
                }
                val failed = markdownFileChangeController.markFailed(planning, error.toUserMessage())
                container.markdownDraftCoordinator.persistFailure(
                    owner = owner,
                    origin = origin,
                    errorMessage = error.toUserMessage(),
                    preferredDraftId = planning.draft.id,
                )
                if (scope.isActive) withContext(container.dispatchers.main) {
                    upsertMarkdownFileChangeState(failed)
                }
            }
            } finally {
                container.finishMarkdownDraftPlanning(planning.draft.id)
            }
        }
    }

    fun sendFileChangeNow() {
        val body = text.trim()
        when (decideFileChangeSend(selectedProjectId, body, selectedImages.isNotEmpty(), isAssistantBusy)) {
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
        val explicitMessageId = java.util.UUID.randomUUID().toString()
        errorText = null
        text = ""
        container.applicationScope.launch {
            runCatching {
                container.chatRepository.insertUserMessage(
                    conversationId = conversationId,
                    content = body,
                    attachments = emptyList(),
                    messageId = explicitMessageId,
                )
                val draftState = markdownFileChangeController.createPlanningDraft(
                    conversationId = conversationId,
                    projectId = projectId,
                    sourceUserMessageId = explicitMessageId,
                    draftId = stableMarkdownDraftId(
                        MarkdownDraftOriginType.EXPLICIT_CHANGE,
                        projectId,
                        explicitMessageId,
                    ),
                )
                generateMarkdownFileChange(draftState, body)
            }.onFailure { error ->
                if (scope.isActive) withContext(container.dispatchers.main) {
                    errorText = error.toUserMessage()
                }
            }
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
        pendingLegacyMarkdownReviewToken = null
        pendingMarkdownReview = reviewStateForFileChange(state)
        retainedReviewIndexes = state.items.mapIndexedNotNull { index, item ->
            index.takeIf { item.retained }
        }.toSet()
    }

    fun applyMarkdownFileChangeProposals(
        state: MarkdownFileChangeState,
        proposals: List<MarkdownUpdateProposal>,
    ) {
        if (proposals.isEmpty()) return
        val currentState = markdownFileChangeStates.firstOrNull { it.draft.id == state.draft.id }
        if (currentState != state) return
        val attempt = markdownDraftApplyController.begin(state.draft.id) ?: return
        applyingMarkdownDraftIds = applyingMarkdownDraftIds + state.draft.id
        scope.launch {
            try {
                val selectedPaths = proposals.map { it.path }.toSet()
                val selectedItemIds = container.database.markdownChangeDraftDao()
                    .listItems(state.draft.id)
                    .filter { it.relativePath in selectedPaths }
                    .map { it.id }
                    .toSet()
                val result = container.markdownDraftApplyCoordinator.apply(
                    draftId = state.draft.id,
                    projectId = state.draft.projectId,
                    selectedItemIds = selectedItemIds,
                )
                val appliedState = markdownFileChangeController.markApplyResult(state, result)
                markdownDraftApplyController.complete(attempt)

                finalizeMarkdownWriteBackBeforeRefresh(
                    finalize = {
                        applyingMarkdownDraftIds = applyingMarkdownDraftIds - state.draft.id
                        upsertMarkdownFileChangeState(appliedState)
                        if (pendingMarkdownReviewDraftId == state.draft.id) {
                            pendingMarkdownReview = null
                            pendingMarkdownReviewDraftId = null
                            retainedReviewIndexes = emptySet()
                        }
                        sessionStatus = markdownWriteBackResultStatus(result)
                        errorText = markdownWriteBackResultError(result)
                    },
                    afterFinalize = {
                        if (conversation?.projectId == state.draft.projectId) {
                            persistMarkdownWriteBackLinks(result) { relativePath ->
                                container.markdownNotebookRepository.linkMarkdown(
                                    conversationId = conversationId,
                                    projectId = state.draft.projectId,
                                    relativePath = relativePath,
                                )
                            }
                        }
                        persistMarkdownWriteBackResultEvent(result) { event ->
                            container.chatRepository.insertSystemEvent(conversationId, event)
                        }
                    },
                    refreshDeliverables = if (
                        result.succeeded.isNotEmpty() && selectedProjectId == state.draft.projectId
                    ) {
                        {
                            val refreshed = container.projectWorkspaceGateway.listDeliverables(state.draft.projectId)
                            if (selectedProjectId == state.draft.projectId) deliverables = refreshed
                        }
                    } else {
                        null
                    },
                )
                if (result.succeeded.isNotEmpty()) {
                    val writtenPaths = result.succeeded.mapNotNull { it.writtenDeliverable?.path ?: it.proposal.path }
                    container.projectAppliedPaths.update { current ->
                        val existing = current[state.draft.projectId].orEmpty()
                        current + (state.draft.projectId to (existing + writtenPaths).distinct())
                    }
                    container.projectContentInvalidation.tryEmit(state.draft.projectId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val feedback = markdownWriteBackFailureFeedback(error)
                errorText = feedback.errorText
                sessionStatus = feedback.statusText
            } finally {
                markdownDraftApplyController.complete(attempt)
                if (!markdownDraftApplyController.isApplying(state.draft.id)) {
                    applyingMarkdownDraftIds = applyingMarkdownDraftIds - state.draft.id
                }
            }
        }
    }

    fun retryFailedMarkdownFileChanges(state: MarkdownFileChangeState) {
        val currentState = markdownFileChangeStates.firstOrNull { it.draft.id == state.draft.id } ?: return
        applyMarkdownFileChangeProposals(
            state = currentState,
            proposals = markdownFileChangeController.retryableProposals(currentState),
        )
    }

    fun applyMarkdownFileChangeState(state: MarkdownFileChangeState, retainedIndexes: Set<Int>) {
        val retained = state.items
            .filterIndexed { index, _ -> index in retainedIndexes }
            .map { item ->
                MarkdownUpdateProposal(
                    operation = item.operation,
                    path = item.path,
                    title = item.title,
                    reason = item.reason,
                    markdown = item.markdown,
                    baselineSha256 = item.baselineSha256,
                    expectedAbsent = item.expectedAbsent,
                )
            }
        if (retained.isEmpty()) {
            markdownDraftApplyController.invalidate(state.draft.id)
            applyingMarkdownDraftIds = applyingMarkdownDraftIds - state.draft.id
            upsertAndPersistMarkdownFileChangeState(markdownFileChangeController.dismiss(state))
            pendingMarkdownReview = null
            pendingMarkdownReviewDraftId = null
            retainedReviewIndexes = emptySet()
            return
        }
        applyMarkdownFileChangeProposals(state, retained)
    }

    fun requestMarkdownReview(message: ChatMessage, targetProjectId: String? = selectedProjectId) {
        val projectId = targetProjectId
        if (!canWriteBackMarkdown(projectId, null, message.content)) {
            pendingWriteBack = null
            pendingDepositProjectSelection = message
            return
        }
        val resolvedProjectId = projectId ?: return
        markdownFileChangeStates.firstOrNull {
            it.draft.assistantMessageId == message.id && it.draft.projectId == resolvedProjectId
        }?.takeIf { it.draft.status != MarkdownFileChangeStatus.FAILED }?.let { existing ->
            pendingWriteBack = null
            when (existing.draft.status) {
                MarkdownFileChangeStatus.READY,
                MarkdownFileChangeStatus.PARTIALLY_APPLIED,
                -> showMarkdownFileChangeDiff(existing)
                else -> sessionStatus = existing.draft.summary
            }
            return
        }
        val planningId = stableMarkdownDraftId(
            MarkdownDraftOriginType.ASSISTANT_MESSAGE,
            resolvedProjectId,
            message.id,
        )
        if (!container.beginMarkdownDraftPlanning(planningId)) return
        isPlanningMarkdownUpdates = true
        sessionStatus = null
        errorText = null
        container.applicationScope.launch {
            var persistedDraftId = planningId
            try {
            val sourceUserMessageId = sourceUserMessageIdForAssistant(messages, message.id)
            var planning = markdownFileChangeController.createPlanningDraft(
                conversationId = conversationId,
                projectId = resolvedProjectId,
                sourceUserMessageId = sourceUserMessageId,
                assistantMessageId = message.id,
                draftId = planningId,
            )
            val owner = MarkdownDraftOwner(
                projectId = resolvedProjectId,
                conversationId = conversationId,
                sourceUserMessageId = sourceUserMessageId,
                assistantMessageId = message.id,
            )
            val origin = MarkdownDraftOrigin(
                type = MarkdownDraftOriginType.ASSISTANT_MESSAGE,
                sourceId = message.id,
                sourceSha256 = markdownOriginSha256(message.content),
                sourceProjectId = resolvedProjectId,
            )
            runCatching {
                val persistedPlanning = container.markdownDraftCoordinator.persistPlanning(
                    owner = owner,
                    origin = origin,
                    preferredDraftId = planning.draft.id,
                )
                if (persistedPlanning.draft.id != planning.draft.id) {
                    persistedDraftId = persistedPlanning.draft.id
                    container.beginMarkdownDraftPlanning(persistedDraftId)
                    planning = markdownFileChangeController.createPlanningDraft(
                        conversationId = conversationId,
                        projectId = resolvedProjectId,
                        sourceUserMessageId = sourceUserMessageId,
                        assistantMessageId = message.id,
                        draftId = persistedPlanning.draft.id,
                    )
                }
                val snapshots = markdownSnapshots(resolvedProjectId)
                if (persistedPlanning.draft.status != MarkdownFileChangeStatus.PLANNING.name) {
                    return@runCatching requireNotNull(
                        persistedMarkdownFileChangeState(
                            MarkdownChangeDraftRecord(persistedPlanning.draft, persistedPlanning.items),
                            snapshots,
                        ),
                    )
                }
                if (scope.isActive) withContext(container.dispatchers.main) {
                    upsertMarkdownFileChangeState(planning)
                }
                val project = projects.firstOrNull { it.id == resolvedProjectId }
                val wikiContext = loadProjectMarkdownWikiContext(listOf(message.id)) { messageIds ->
                    container.wikiMarkdownContextRepository.forMessageIds(messageIds)
                }
                val allowedEvidence = frozenContextFactEvidence(resolvedProjectId, message.id)
                val plan = markdownUpdatePlanner.plan(
                    projectName = project?.name.orEmpty(),
                    projectContext = projectContext,
                    markdowns = snapshots,
                    assistantMarkdown = message.content,
                    providerId = selectedProviderId,
                    modelOverride = selectedModel,
                    wikiCitations = wikiContext.citations,
                    wikiCoverage = wikiContext.coverage,
                    allowedEvidence = allowedEvidence,
                    suppressedContextFactKeys = container.database.projectSearchDao()
                        .suppressedContextFactKeys(resolvedProjectId, message.id)
                        .toSet(),
                )
                val ready = markdownFileChangeController.markReady(planning, plan, snapshots)
                container.markdownDraftCoordinator.persistPlan(
                    owner = owner,
                    origin = origin,
                    plan = plan,
                    snapshots = snapshots,
                    preferredDraftId = planning.draft.id,
                )
                ready
            }.onSuccess { ready ->
                if (scope.isActive) withContext(container.dispatchers.main) {
                    upsertMarkdownFileChangeState(ready)
                    pendingLegacyMarkdownReviewToken = null
                    if (ready.draft.status == MarkdownFileChangeStatus.NO_CHANGES) {
                        pendingMarkdownReview = null
                        pendingMarkdownReviewDraftId = null
                        retainedReviewIndexes = emptySet()
                        sessionStatus = ready.draft.summary
                    } else if (ready.draft.status in setOf(
                            MarkdownFileChangeStatus.READY,
                            MarkdownFileChangeStatus.PARTIALLY_APPLIED,
                        )
                    ) {
                        pendingMarkdownReviewDraftId = ready.draft.id
                        pendingMarkdownReview = reviewStateForFileChange(ready)
                        retainedReviewIndexes = ready.items.indices.toSet()
                    } else {
                        pendingMarkdownReview = null
                        pendingMarkdownReviewDraftId = null
                        retainedReviewIndexes = emptySet()
                        sessionStatus = ready.draft.summary
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    withContext(NonCancellable) {
                        container.markdownDraftCoordinator.persistFailure(
                            owner = owner,
                            origin = origin,
                            errorMessage = "规划已中断，可重试",
                            preferredDraftId = planning.draft.id,
                        )
                    }
                    throw error
                }
                val failed = markdownFileChangeController.markFailed(planning, error.toUserMessage())
                container.markdownDraftCoordinator.persistFailure(
                    owner = owner,
                    origin = origin,
                    errorMessage = error.toUserMessage(),
                    preferredDraftId = planning.draft.id,
                )
                if (scope.isActive) withContext(container.dispatchers.main) {
                    upsertMarkdownFileChangeState(failed)
                    val feedback = markdownWriteBackFailureFeedback(error)
                    errorText = feedback.errorText
                    sessionStatus = feedback.statusText
                }
            }
            if (scope.isActive) withContext(container.dispatchers.main) {
                isPlanningMarkdownUpdates = false
                pendingWriteBack = null
            }
            } finally {
                container.finishMarkdownDraftPlanning(planningId)
                if (persistedDraftId != planningId) container.finishMarkdownDraftPlanning(persistedDraftId)
            }
        }
    }

    fun applyRetainedMarkdownUpdates(
        review: MarkdownUpdateReviewState,
        retainedIndexes: Set<Int>,
        draftId: String?,
        legacyReviewToken: LegacyMarkdownReviewToken?,
    ) {
        if (draftId != null) {
            markdownFileChangeStates.firstOrNull { it.draft.id == draftId }?.let { state ->
                applyMarkdownFileChangeState(state, retainedIndexes)
            }
            return
        }
        val reviewToken = legacyReviewToken ?: return
        val attempt = legacyMarkdownReviewApplyController.begin(reviewToken) ?: return
        applyingLegacyMarkdownReviewToken = reviewToken
        val projectId = selectedProjectId
        if (projectId.isNullOrBlank()) {
            if (
                pendingLegacyMarkdownReviewToken == reviewToken &&
                legacyMarkdownReviewApplyController.complete(attempt)
            ) {
                applyingLegacyMarkdownReviewToken = null
                errorText = "请先选择项目"
            }
            return
        }
        val retained = review.proposals.filterIndexed { index, _ -> index in retainedIndexes }
        if (retained.isEmpty()) {
            if (
                pendingLegacyMarkdownReviewToken == reviewToken &&
                legacyMarkdownReviewApplyController.complete(attempt)
            ) {
                applyingLegacyMarkdownReviewToken = null
                pendingMarkdownReview = null
                pendingMarkdownReviewDraftId = null
                pendingLegacyMarkdownReviewToken = null
                retainedReviewIndexes = emptySet()
                sessionStatus = "已撤回全部 Markdown 更新"
                errorText = null
            }
            return
        }
        scope.launch {
            try {
                val result = try {
                    container.projectWorkspaceGateway.applyMarkdownUpdates(projectId, retained)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (
                        pendingLegacyMarkdownReviewToken == reviewToken &&
                        legacyMarkdownReviewApplyController.complete(attempt)
                    ) {
                        applyingLegacyMarkdownReviewToken = null
                        val feedback = markdownWriteBackFailureFeedback(error)
                        errorText = feedback.errorText
                        sessionStatus = feedback.statusText
                    }
                    return@launch
                }
                if (
                    pendingLegacyMarkdownReviewToken != reviewToken ||
                    !legacyMarkdownReviewApplyController.complete(attempt)
                ) {
                    return@launch
                }
                finalizeMarkdownWriteBackBeforeRefresh(
                    finalize = {
                        applyingLegacyMarkdownReviewToken = null
                        val retryReview = legacyPartialRetryReviewState(
                            review = review,
                            retainedIndexes = retainedIndexes,
                            result = result,
                        )
                        if (retryReview.proposals.isEmpty()) {
                            pendingMarkdownReview = null
                            pendingMarkdownReviewDraftId = null
                            pendingLegacyMarkdownReviewToken = null
                            retainedReviewIndexes = emptySet()
                        } else {
                            pendingMarkdownReview = retryReview
                            retainedReviewIndexes = retryReview.proposals.indices.toSet()
                        }
                        sessionStatus = markdownWriteBackResultStatus(result)
                        errorText = markdownWriteBackResultError(result)
                    },
                    afterFinalize = {
                        persistMarkdownWriteBackLinks(result) { relativePath ->
                            container.markdownNotebookRepository.linkMarkdown(
                                conversationId = conversationId,
                                projectId = projectId,
                                relativePath = relativePath,
                            )
                        }
                        persistMarkdownWriteBackResultEvent(result) { event ->
                            container.chatRepository.insertSystemEvent(conversationId, event)
                        }
                    },
                    refreshDeliverables = if (result.succeeded.isNotEmpty()) {
                        {
                            val refreshed = container.projectWorkspaceGateway.listDeliverables(projectId)
                            if (selectedProjectId == projectId) deliverables = refreshed
                        }
                    } else {
                        null
                    },
                )
                if (result.succeeded.isNotEmpty()) {
                    val writtenPaths = result.succeeded.mapNotNull { it.writtenDeliverable?.path ?: it.proposal.path }
                    container.projectAppliedPaths.update { current ->
                        val existing = current[projectId].orEmpty()
                        current + (projectId to (existing + writtenPaths).distinct())
                    }
                    container.projectContentInvalidation.tryEmit(projectId)
                }
            } finally {
                legacyMarkdownReviewApplyController.complete(attempt)
                if (
                    !legacyMarkdownReviewApplyController.isApplying(reviewToken) &&
                    applyingLegacyMarkdownReviewToken == reviewToken
                ) {
                    applyingLegacyMarkdownReviewToken = null
                }
            }
        }
    }

    pendingMarkdownReview?.let { review ->
        val isApplyingReview = when (val draftId = pendingMarkdownReviewDraftId) {
            null -> isLegacyMarkdownReviewApplying(
                pendingToken = pendingLegacyMarkdownReviewToken,
                activeToken = applyingLegacyMarkdownReviewToken,
            )
            else -> draftId in applyingMarkdownDraftIds
        }
        MarkdownUpdateReviewDialog(
            review = review,
            retainedIndexes = retainedReviewIndexes,
            isApplying = isApplyingReview,
            onToggleRetained = { index ->
                if (isApplyingReview) return@MarkdownUpdateReviewDialog
                retainedReviewIndexes = if (index in retainedReviewIndexes) {
                    retainedReviewIndexes - index
                } else {
                    retainedReviewIndexes + index
                }
                pendingMarkdownReviewDraftId?.let { draftId ->
                    markdownFileChangeStates.firstOrNull { it.draft.id == draftId }?.let { state ->
                        upsertAndPersistMarkdownFileChangeState(
                            markdownFileChangeController.toggleRetained(state, index),
                        )
                    }
                }
            },
            onConfirm = {
                applyRetainedMarkdownUpdates(
                    review = review,
                    retainedIndexes = retainedReviewIndexes,
                    draftId = pendingMarkdownReviewDraftId,
                    legacyReviewToken = pendingLegacyMarkdownReviewToken,
                )
            },
            onDismiss = {
                if (isApplyingReview) return@MarkdownUpdateReviewDialog
                pendingLegacyMarkdownReviewToken?.let(legacyMarkdownReviewApplyController::invalidate)
                pendingMarkdownReview = null
                pendingMarkdownReviewDraftId = null
                pendingLegacyMarkdownReviewToken = null
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

    if (showIdentityDetails) {
        currentAgentId?.let { agentId ->
            AgentMemorySheet(
                agentId = agentId,
                version = conversation?.agentVersion,
                installedCorpusCount = fixedVersionCoverage?.installedRequiredCorpusCount,
                requiredCorpusCount = fixedVersionCoverage?.requiredCorpusCount,
                publisherFingerprint = agents.firstOrNull { it.id == agentId }?.publisherFingerprint,
                memories = relationshipMemories,
                sourceAvailable = { memory ->
                    if (!canOperateAgentMemory(agentId, memory)) {
                        false
                    } else {
                        val sourceConversation =
                            container.chatRepository.conversation(memory.sourceConversationId)
                        val sourceMessage = container.chatRepository.message(memory.sourceMessageId)
                        sourceConversation?.agentId == agentId &&
                            sourceMessage?.conversationId == memory.sourceConversationId
                    }
                },
                onOpenSource = { memory ->
                    when (
                        resolveAgentMemorySourceTarget(
                            currentConversationId = conversationId,
                            memory = memory,
                            sourceAvailable = canOperateAgentMemory(agentId, memory),
                        )
                    ) {
                        is AgentMemorySourceTarget.CurrentConversation -> {
                            pendingSourceMessageId = memory.sourceMessageId
                            sourceLocationConsumed = false
                            sourceLocationStatus = null
                        }
                        is AgentMemorySourceTarget.OtherConversation -> {
                            onOpenConversationMessage(
                                memory.sourceConversationId,
                                memory.sourceMessageId,
                            )
                        }
                        AgentMemorySourceTarget.Unavailable -> {
                            sourceLocationStatus = "来源消息不可用"
                        }
                    }
                },
                onEdit = { memory, content ->
                    conversation?.agentId == agentId &&
                        canOperateAgentMemory(agentId, memory) &&
                        container.agentMemoryRepository.edit(memory.id, content)
                },
                onDelete = { memory ->
                    conversation?.agentId == agentId &&
                        canOperateAgentMemory(agentId, memory) &&
                        container.agentMemoryRepository.delete(memory.id)
                },
                onClear = {
                    if (conversation?.agentId != agentId) {
                        false
                    } else {
                        val clearedCount = container.agentMemoryRepository.clear(agentId)
                        clearedCount > 0 || container.agentMemoryRepository.list(agentId).isEmpty()
                    }
                },
                onDismiss = { showIdentityDetails = false },
            )
        }
    }

    if (showMessageSearch) {
        LaunchedEffect(messageSearchQuery) {
            delay(150)
            debouncedMessageSearchQuery = messageSearchQuery
        }
        val matches = remember(searchDocuments, debouncedMessageSearchQuery, messageSearchFilter) {
            searchConversationDocuments(searchDocuments, debouncedMessageSearchQuery, messageSearchFilter)
        }
        fun jumpToSearchResult(cursor: Int) {
            if (matches.isEmpty()) return
            messageSearchCursor = cursor.mod(matches.size)
            val match = matches[messageSearchCursor]
            highlightedMessageId = match.messageId
            scope.launch { listState.animateScrollToItem(match.messageIndex) }
        }
        AlertDialog(
            onDismissRequest = {
                showMessageSearch = false
                scope.launch { listState.scrollToItem(messageSearchOriginIndex, messageSearchOriginOffset) }
            },
            title = { Text("查找消息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = messageSearchQuery,
                        onValueChange = {
                            messageSearchQuery = it
                            messageSearchCursor = 0
                        },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        label = { Text("关键词") },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ConversationSearchFilter.entries.forEach { filter ->
                            FilterChip(
                                modifier = Modifier.heightIn(min = 48.dp),
                                selected = messageSearchFilter == filter,
                                onClick = {
                                    messageSearchFilter = filter
                                    messageSearchCursor = 0
                                },
                                label = { Text(filter.label) },
                            )
                        }
                    }
                    Text(
                        text = if (matches.isEmpty()) "没有匹配消息" else "${messageSearchCursor + 1} / ${matches.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = matches.isNotEmpty(), onClick = { jumpToSearchResult(messageSearchCursor + 1) }) {
                    Text("下一条")
                }
            },
            dismissButton = {
                Row {
                    TextButton(enabled = matches.isNotEmpty(), onClick = { jumpToSearchResult(messageSearchCursor - 1) }) {
                        Text("上一条")
                    }
                    TextButton(onClick = {
                        showMessageSearch = false
                        scope.launch { listState.scrollToItem(messageSearchOriginIndex, messageSearchOriginOffset) }
                    }) {
                        Text("关闭")
                    }
                }
            },
        )
    }

    if (showWikiScopePicker) {
        ConversationWikiPicker(
            state = wikiScopeState,
            controllerState = wikiScopeControllerState,
            onApply = wikiScopeController::apply,
            onRestoreDefaults = wikiScopeController::restoreDefaults,
            onDismiss = { showWikiScopePicker = false },
        )
    }

    if (showConversationContext) {
        val contextSummary = ConversationContextSummary(
            projectName = projects.firstOrNull { it.id == selectedProjectId }?.name,
            identityName = identityState.selectedName,
            enabledWikiCount = wikiScopeState.options.count { it.enabled && !it.unavailable },
            model = selectedModel,
            reasoningEffortLabel = selectedReasoningEffort.label,
            webSearchEnabled = webSearchEnabled,
            contextPercent = contextWindowUsagePercent(contextStatus),
        )
        ConversationContextSheet(
            summary = contextSummary,
            projects = projects.map { ContextProjectOption(it.id, it.name) },
            selectedProjectId = selectedProjectId,
            projectLocked = persistedUserMessage || firstMessagePending || messages.any { it.role == MessageRole.USER },
            identityState = identityState,
            wikiLabel = wikiScopeState.toolbarLabel,
            showWebSearch = shouldShowWebSearchButton(webSearchSettings) && !isAgentConversation,
            webSearchEnabled = webSearchEnabled,
            canCompressContext = contextWindowCanManualCompress(contextStatus),
            isCompressingContext = isCompressingContext,
            onSelectProject = ::selectContextProject,
            onSelectIdentity = identityController::selectIdentity,
            onOpenWiki = {
                showConversationContext = false
                showWikiScopePicker = true
            },
            onOpenModel = {
                showConversationContext = false
                showModelPicker = true
            },
            onToggleWebSearch = { enabled ->
                if (enabled && !webSearchSettings.enabled) {
                    errorText = "请先在设置 -> 搜索能力启用联网搜索"
                } else {
                    errorText = null
                    webSearchEnabled = enabled
                }
            },
            onCompressContext = ::compressContextNow,
            onDismiss = { showConversationContext = false },
        )
    }

    if (showSessionConfig) {
        SessionConfigDialog(
            promptText = finalSessionPrompt.ifBlank { rawSessionPrompt },
            optimizedPrompt = optimizedSessionPrompt,
            status = sessionConfigStatus,
            isOptimizing = isOptimizingPrompt,
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
            title = { Text("沉淀到项目") },
            text = {
                Text(
                    "会先生成 Markdown 更新计划，你可以审核每个文件的 diff，并逐项保留或撤回。",
                )
            },
            confirmButton = {
                TextButton(enabled = !isPlanningMarkdownUpdates, onClick = { requestMarkdownReview(message) }) {
                    Text(if (isPlanningMarkdownUpdates) "生成中..." else "生成沉淀草稿")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingWriteBack = null }) { Text("取消") }
            },
        )
    }

    pendingDepositProjectSelection?.let { message ->
        AlertDialog(
            onDismissRequest = { pendingDepositProjectSelection = null },
            title = { Text("选择沉淀项目") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (projects.isEmpty()) {
                        Text("暂无可用项目，请先创建项目")
                    } else {
                        projects.forEach { project ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                onClick = {
                                    pendingDepositProjectSelection = null
                                    requestMarkdownReview(message, project.id)
                                },
                            ) {
                                Text(project.name)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingDepositProjectSelection = null }) { Text("取消") }
            },
        )
    }

    selectedProjectEvidence?.let { evidence ->
        ProjectEvidenceSnapshotSheet(
            evidence = evidence,
            revisionState = selectedProjectEvidenceRevision,
            onOpenCurrent = if (
                selectedProjectEvidenceRevision !in setOf(
                    ProjectFileRevisionState.CURRENT,
                    ProjectFileRevisionState.UPDATED,
                )
            ) {
                null
            } else if (evidence.relativePath != null) {
                {
                    selectedProjectEvidence = null
                    onOpenProjectFiles(evidence.projectId, evidence.relativePath)
                }
            } else {
                evidence.sourceMessageId?.let { sourceMessageId ->
                    {
                        selectedProjectEvidence = null
                        if (selectedProjectEvidenceConversationId == conversationId) {
                            messages.indexOfFirst { it.id == sourceMessageId }.takeIf { it >= 0 }?.let { index ->
                                highlightedMessageId = sourceMessageId
                                scope.launch { listState.animateScrollToItem(index) }
                            }
                        } else {
                            selectedProjectEvidenceConversationId?.let { sourceConversationId ->
                                onOpenConversationMessage(sourceConversationId, sourceMessageId)
                            }
                        }
                    }
                }
            },
            onDismiss = { selectedProjectEvidence = null },
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
        if (!identityState.mutable && identityState.selectedAgentId != null) {
            ResponsiveChatContentRail {
                ConversationIdentityPicker(
                    state = identityState,
                    onSelectAgentId = {},
                    onShowDetails = {
                        val agentId = conversation?.agentId
                        val version = conversation?.agentVersion
                        if (agentId == null || version == null) {
                            fixedVersionCoverage = null
                            showIdentityDetails = true
                        } else {
                            scope.launch {
                                fixedVersionCoverage = runCatching {
                                    container.agentRepository.versionCoverage(agentId, version)
                                }.getOrNull()
                                showIdentityDetails = true
                            }
                        }
                    },
                )
            }
        }
        errorText?.let { ResponsiveChatContentRail { InlineError(it) } }
        sessionStatus?.let { ResponsiveChatContentRail { InlineStatus(it) } }
        sourceLocationStatus?.let { ResponsiveChatContentRail { InlineStatus(it) } }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val contentMaxWidth = chatContentMaxWidthDp(maxWidth.value.toInt()).dp
            val bubbleMaxWidth = messageBubbleMaxWidthDp(contentMaxWidth.value.toInt()).dp
            val showJumpToBottom by remember(messages.lastIndex) {
                derivedStateOf {
                    messages.isNotEmpty() && !listState.canFollowStreaming(messages.lastIndex)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                emptyChatStateItem(
                    messageState = messageState,
                    contentMaxWidth = contentMaxWidth,
                    showProviderHint = !isAgentConversation,
                    agentOpening = agentOpening,
                )
                items(messages, key = { it.id }) { message ->
                    val persistedParts = messagePartsById[message.id].orEmpty()
                    val wikiCitations = wikiCitationsByMessageId[message.id].orEmpty()
                    val attachments = if (message.role == MessageRole.USER) {
                        attachmentsByMessageId[message.id].orEmpty()
                    } else {
                        emptyList()
                    }
                    ChatContentRail(contentMaxWidth = contentMaxWidth) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (message.role == MessageRole.SYSTEM) {
                                ContextEventLine(message.content)
                            } else {
                                val displayParts = messageDisplayParts(message, persistedParts)
                                val executionEntry = executionByUserMessageId[message.id]
                                    ?: executionByAssistantMessageId[message.id]
                                MessageBubble(
                                    message = message,
                                    parts = displayParts,
                                    wikiCitations = wikiCitations,
                                    executionEntry = executionEntry,
                                    attachments = attachments,
                                    imageStore = container.chatImageStore,
                                    maxBubbleWidth = bubbleMaxWidth,
                                    isAgentConversation = isAgentConversation,
                                    canWriteBack = message.role == MessageRole.ASSISTANT &&
                                        message.status == MessageStatus.SUCCEEDED &&
                                        message.content.isNotBlank(),
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
                                    onSteer = executionEntry?.takeIf {
                                        message.role == MessageRole.USER && it.status == ChatExecutionStatus.QUEUED
                                    }?.let { entry ->
                                        { container.chatExecutionCoordinator.steer(entry.id) }
                                    },
                                    onEditQueued = executionEntry?.takeIf {
                                        message.role == MessageRole.USER && it.status == ChatExecutionStatus.QUEUED
                                    }?.let { entry ->
                                        {
                                            scope.launch {
                                                val attachments = container.chatRepository.listAttachments(message.id)
                                                if (!container.chatExecutionRepository.deleteQueued(entry.id)) return@launch
                                                text = message.content
                                                selectedImages = attachments.map { attachment ->
                                                    PendingImageAttachment(Uri.parse(attachment.uri), attachment.mimeType)
                                                }.take(MAX_CHAT_IMAGE_ATTACHMENTS)
                                                syncActiveDraftSnapshot()
                                            }
                                        }
                                    },
                                    onDeleteQueued = executionEntry?.takeIf {
                                        message.role == MessageRole.USER && it.status == ChatExecutionStatus.QUEUED
                                    }?.let { entry ->
                                        { scope.launch { container.chatExecutionRepository.deleteQueued(entry.id) } }
                                    },
                                    onRetryFailed = executionEntry?.takeIf {
                                        message.role == MessageRole.ASSISTANT &&
                                            it.status == ChatExecutionStatus.FAILED &&
                                            it.id == latestExecutionId
                                    }?.let { entry ->
                                        { container.chatExecutionCoordinator.retryFailed(entry.id) }
                                    },
                                    onOpenBatterySettings = executionEntry?.takeIf {
                                        message.role == MessageRole.ASSISTANT && it.interruptionReason != null
                                    }?.let {
                                        {
                                            context.startActivity(
                                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                            )
                                        }
                                    },
                                    onOpenWikiCitation = onOpenWikiCitation,
                                    onOpenProjectSource = { evidenceId ->
                                        scope.launch {
                                            container.database.projectSearchDao().evidenceById(evidenceId)?.let { evidence ->
                                                selectedProjectEvidenceConversationId = null
                                                selectedProjectEvidenceRevision = evidence.relativePath?.let { path ->
                                                    container.projectRepository.projectFileRevisionState(
                                                        projectId = evidence.projectId,
                                                        relativePath = path,
                                                        expectedSha256 = evidence.sourceSha256,
                                                    )
                                                } ?: evidence.sourceMessageId?.let { sourceMessageId ->
                                                    val currentMessage = container.database.messageDao().findById(sourceMessageId)
                                                    selectedProjectEvidenceConversationId = currentMessage?.conversationId
                                                    when {
                                                        currentMessage == null -> ProjectFileRevisionState.DELETED
                                                        markdownOriginSha256(currentMessage.content)
                                                            .equals(evidence.sourceSha256, ignoreCase = true) ->
                                                            ProjectFileRevisionState.CURRENT
                                                        else -> ProjectFileRevisionState.UPDATED
                                                    }
                                                } ?: ProjectFileRevisionState.UNAVAILABLE
                                                selectedProjectEvidence = evidence
                                            }
                                        }
                                    },
                                    reasoningStreaming = message.status == MessageStatus.PENDING ||
                                        message.status == MessageStatus.STREAMING,
                                    highlighted = highlightedMessageId == message.id,
                                )
                                executionEntry?.takeIf { entry ->
                                    message.role == MessageRole.USER &&
                                        entry.automaticRetryCount > 0 &&
                                        entry.assistantMessageId == null
                                }?.let { entry ->
                                    RecoveryAnswerPlaceholder(
                                        entry = entry,
                                        onRetry = if (entry.status == ChatExecutionStatus.FAILED) {
                                            { container.chatExecutionCoordinator.retryFailed(entry.id) }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                            markdownFileChangeStates
                                .filter { state ->
                                    state.draft.assistantMessageId?.let { it == message.id }
                                        ?: (state.draft.sourceUserMessageId == message.id)
                                }
                                .forEach { state ->
                                    MarkdownFileChangeCard(
                                        state = state,
                                        isApplying = state.draft.id in applyingMarkdownDraftIds,
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
                                        onRetryFailed = { retryFailedMarkdownFileChanges(state) },
                                        onDismiss = {
                                            markdownDraftApplyController.invalidate(state.draft.id)
                                            applyingMarkdownDraftIds = applyingMarkdownDraftIds - state.draft.id
                                            upsertAndPersistMarkdownFileChangeState(
                                                markdownFileChangeController.dismiss(state),
                                            )
                                        },
                                        onOpenFiles = {
                                            onOpenProjectFiles(
                                                state.draft.projectId,
                                                state.appliedPaths.firstOrNull(),
                                            )
                                        },
                                        onOpenGit = { onOpenProjectGit(state.draft.projectId) },
                                    )
                                }
                        }
                    }
                }
            }
            if (showJumpToBottom) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 3.dp,
                    onClick = {
                        scope.launch {
                            streamingAutoScrollEnabled = true
                            listState.animateScrollToItem(messages.lastIndex)
                        }
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "回到底部",
                        )
                    }
                }
            }
        }

        val openExecutions = executionEntries.filter {
            it.status == ChatExecutionStatus.QUEUED || it.status == ChatExecutionStatus.RUNNING
        }
        if (openExecutions.isNotEmpty()) {
            ResponsiveChatContentRail {
                ChatQueueStrip(openExecutions)
            }
        }
        ResponsiveChatContentRail {
            ChatInputBar(
                text = text,
                onTextChange = {
                    updateActiveDraftSnapshot(it, selectedImages)
                    text = it
                },
                selectedImages = selectedImages,
                pendingDocuments = pendingDocuments,
                documentExtracting = documentExtracting,
                onPickDocument = { documentPicker.launch(DocumentTextExtractor.SUPPORTED_MIME_TYPES) },
                onRemoveDocument = { document ->
                    pendingDocuments = pendingDocuments.filterNot { it.uri == document.uri }
                },
                onTakePhoto = {
                    when (cameraAction(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)) {
                        ChatImageSourceAction.REQUEST_CAMERA_PERMISSION -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        ChatImageSourceAction.LAUNCH_CAMERA -> launchCamera()
                    }
                },
                onPickFromAlbum = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onRemoveImage = ::removeSelectedImage,
                onStartVoiceTranscription = {
                    errorText = null
                    onStartVoiceInput(text, voiceSettings.defaultTranscriptionLanguage)
                },
                isVoiceInputActive = voiceInputState.active,
                onStopVoiceTranscription = onStopVoiceInput,
                contextSummary = ConversationContextSummary(
                    projectName = projects.firstOrNull { it.id == selectedProjectId }?.name,
                    identityName = identityState.selectedName,
                    enabledWikiCount = wikiScopeState.options.count { it.enabled && !it.unavailable },
                    model = selectedModel,
                    reasoningEffortLabel = selectedReasoningEffort.label,
                    webSearchEnabled = webSearchEnabled,
                    contextPercent = contextWindowUsagePercent(contextStatus),
                ),
                onOpenContext = { showConversationContext = true },
                inputFocusRequester = inputFocusRequester,
                canSend = selectedProvider != null &&
                    selectedModel.isNotBlank() &&
                    !firstMessagePending &&
                    !sendSnapshotInFlight &&
                    identityController.canSend() &&
                    wikiScopeController.canApply() &&
                    canAcceptChatSend(
                        identityMessageStateKnown,
                        container.chatSendRecoveryStore.current(conversationId),
                    ) &&
                    (text.isNotBlank() || selectedImages.isNotEmpty()),
                isBusy = isAssistantBusy,
                onSend = {
                    handleSendIntent(
                        hasSelectedImage = selectedImages.isNotEmpty(),
                        dismissKeyboard = dismissKeyboard,
                        sendNow = ::sendNow,
                    )
                },
                onStop = ::stopNow,
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

@Composable
internal fun AgentMemoryConversationLeaveEffect(
    conversationId: String,
    onConversationLeft: (String) -> Unit,
) {
    val currentOnConversationLeft by rememberUpdatedState(onConversationLeft)
    DisposableEffect(conversationId) {
        onDispose {
            currentOnConversationLeft(conversationId)
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
        (
            current.lastMessageContentLength > previous.lastMessageContentLength ||
                current.lastMessageUpdatedAt > previous.lastMessageUpdatedAt
            ) -> ChatAutoScrollMode.STREAM_TO_BOTTOM
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

internal enum class ChatBubblePresentation {
    UNFRAMED,
    WARM_USER,
    NEUTRAL_EVENT,
}

internal fun chatBubblePresentation(role: MessageRole): ChatBubblePresentation = when (role) {
    MessageRole.ASSISTANT -> ChatBubblePresentation.UNFRAMED
    MessageRole.USER -> ChatBubblePresentation.WARM_USER
    MessageRole.SYSTEM,
    MessageRole.ERROR,
    -> ChatBubblePresentation.NEUTRAL_EVENT
}

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
    if (persistedParts.isNotEmpty()) {
        return if (message.status == MessageStatus.PENDING || message.status == MessageStatus.STREAMING) {
            StreamingMessageSnapshot(message.status, persistedParts).hideWikiCitationTokensForDisplay().parts
        } else {
            persistedParts
        }
    }
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

internal fun imagePartSource(part: UiMessagePartDraft): String = part.content.trim()

internal fun stripAgentCitationMarkers(markdown: String): String =
    agentCitationMarker.replace(markdown, "")

private val agentCitationMarker = Regex("""\s*\[资料\s*\d+\]""")

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
    hideAgentCitationMarkers: Boolean = false,
): String = markdownTextForCopy(
    message.errorMessage?.let(::errorCopyText)
        ?: parts.visibleText()
            .let { text -> if (hideAgentCitationMarkers) stripAgentCitationMarkers(text) else text }
            .takeIf { it.isNotBlank() }
        ?: assistantMessageDisplayText(message),
)

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
    ATTACH,
    SEND,
    STOP,
}

internal fun shouldShowCollapsedAttachmentEntry(
    text: String,
    hasSelectedImage: Boolean,
): Boolean = !hasSelectedImage

internal fun chatInputTrailingAction(
    text: String,
    hasSelectedImage: Boolean,
    isBusy: Boolean,
): ChatInputTrailingAction =
    when {
        isBusy -> ChatInputTrailingAction.STOP
        text.isNotBlank() || hasSelectedImage -> ChatInputTrailingAction.SEND
        else -> ChatInputTrailingAction.ATTACH
    }

internal fun executionStatusLabel(status: ChatExecutionStatus): String? = when (status) {
    ChatExecutionStatus.QUEUED -> "等待处理"
    ChatExecutionStatus.INTERRUPTED -> "已中断"
    ChatExecutionStatus.STEERED -> "已按引导结束"
    ChatExecutionStatus.FAILED -> "发送失败"
    ChatExecutionStatus.CANCELLED -> "已暂停"
    ChatExecutionStatus.RUNNING,
    ChatExecutionStatus.SUCCEEDED,
    -> null
}

internal fun hasRunningChatExecution(entries: List<ChatExecutionEntry>): Boolean =
    entries.any { it.status == ChatExecutionStatus.RUNNING }

internal fun executionActivityLabel(entry: ChatExecutionEntry): String? = when (entry.status) {
    ChatExecutionStatus.QUEUED -> if (entry.automaticRetryCount > 0) {
        "连接中断，准备重试 ${entry.automaticRetryCount}/2"
    } else {
        executionStatusLabel(entry.status)
    }
    ChatExecutionStatus.RUNNING -> when (entry.phase) {
        ChatExecutionPhase.PREPARING_CONTEXT, null -> "正在准备上下文"
        ChatExecutionPhase.SEARCHING_WEB -> "正在联网搜索"
        ChatExecutionPhase.RETRIEVING_KNOWLEDGE -> "正在检索知识库"
        ChatExecutionPhase.GENERATING -> if (entry.automaticRetryCount > 0) {
            "正在重新生成 ${entry.automaticRetryCount}/2"
        } else {
            "正在生成回答"
        }
        ChatExecutionPhase.FINALIZING -> "正在整理结果"
    }
    else -> executionStatusLabel(entry.status)
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

internal fun markdownFileChangeConversationContext(messages: List<ChatMessage>): MarkdownFileChangeConversationContext {
    val boundedMessages = messages
        .filter {
            it.content.isNotBlank() &&
                it.status == MessageStatus.SUCCEEDED &&
                (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT)
        }
        .takeLast(MAX_FILE_CHANGE_CONTEXT_MESSAGES)
    return MarkdownFileChangeConversationContext(
        text = boundedMessages.joinToString(separator = "\n\n") { message ->
            val role = if (message.role == MessageRole.USER) "用户" else "助手"
            "$role：${message.content.trim().take(MAX_FILE_CHANGE_CONTEXT_MESSAGE_CHARS)}"
        },
        messageIds = boundedMessages.map(ChatMessage::id),
    )
}

internal suspend fun loadProjectMarkdownWikiContext(
    messageIds: List<String>,
    loadContext: suspend (List<String>) -> WikiMarkdownSourceContext,
): WikiMarkdownSourceContext = try {
    loadContext(messageIds)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    throw MarkdownFileChangePlanningException("无法读取本轮引用，未生成文件变更", error)
}

internal fun markdownOriginSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }

internal fun sourceUserMessageIdForAssistant(messages: List<ChatMessage>, assistantMessageId: String): String? {
    val assistantIndex = messages.indexOfFirst { it.id == assistantMessageId && it.role == MessageRole.ASSISTANT }
    if (assistantIndex <= 0) return null
    return messages.subList(0, assistantIndex).asReversed()
        .firstOrNull { it.role == MessageRole.USER }
        ?.id
}

private fun isRootContextMarkdownPath(path: String): Boolean =
    path.trim().replace('\\', '/').removePrefix("./").equals("context.md", ignoreCase = true)

internal fun persistedMarkdownFileChangeState(
    record: MarkdownChangeDraftRecord,
    snapshots: List<MarkdownSnapshot>,
): MarkdownFileChangeState? {
    val conversationId = record.draft.conversationId ?: return null
    val status = runCatching { MarkdownFileChangeStatus.valueOf(record.draft.status) }.getOrNull() ?: return null
    val byPath = snapshots.associateBy(MarkdownSnapshot::path)
    val items = record.items.sortedBy { it.itemIndex }.map { item ->
        val diff = buildMarkdownDiff(
            oldMarkdown = byPath[item.relativePath]?.markdown.orEmpty(),
            newMarkdown = item.proposedMarkdown,
        )
        val stats = com.harnessapk.session.markdownDiffStats(diff)
        MarkdownFileChangeItem(
            draftId = record.draft.id,
            operation = MarkdownUpdateOperation.valueOf(item.operation),
            path = item.relativePath,
            title = item.title,
            reason = item.reason,
            markdown = item.proposedMarkdown,
            addedLineCount = stats.addedLineCount,
            removedLineCount = stats.removedLineCount,
            retained = item.retained,
            baselineSha256 = item.baselineSha256,
            expectedAbsent = item.expectedAbsent,
        )
    }
    val failuresByPath = record.items.filter { it.applyStatus == MarkdownFileApplyStatus.FAILED.name }
    return MarkdownFileChangeState(
        draft = com.harnessapk.session.MarkdownFileChangeDraft(
            id = record.draft.id,
            conversationId = conversationId,
            projectId = record.draft.projectId,
            sourceUserMessageId = record.draft.sourceUserMessageId,
            assistantMessageId = record.draft.assistantMessageId,
            status = status,
            summary = record.draft.summary,
            createdAt = record.draft.createdAt,
            updatedAt = record.draft.updatedAt,
        ),
        items = items,
        diffs = items.map { item ->
            buildMarkdownDiff(byPath[item.path]?.markdown.orEmpty(), item.markdown)
        },
        appliedPaths = record.items.filter { it.applyStatus == MarkdownFileApplyStatus.SUCCEEDED.name }
            .map { it.relativePath },
        applyFailures = failuresByPath.map { item ->
            MarkdownFileChangeFailure(
                proposal = MarkdownUpdateProposal(
                    operation = MarkdownUpdateOperation.valueOf(item.operation),
                    path = item.relativePath,
                    title = item.title,
                    reason = item.reason,
                    markdown = item.proposedMarkdown,
                    baselineSha256 = item.baselineSha256,
                    expectedAbsent = item.expectedAbsent,
                ),
                errorMessage = item.applyErrorMessage.orEmpty(),
            )
        },
    )
}

internal fun markdownFileChangeCardTitle(
    status: MarkdownFileChangeStatus,
    itemCount: Int,
): String = when (status) {
    MarkdownFileChangeStatus.PLANNING -> "正在生成 Markdown 文件变更..."
    MarkdownFileChangeStatus.READY -> "已生成 $itemCount 个 Markdown 文件变更"
    MarkdownFileChangeStatus.APPLYING -> "正在应用所选变更"
    MarkdownFileChangeStatus.APPLIED -> "已写入项目"
    MarkdownFileChangeStatus.PARTIALLY_APPLIED -> "部分文件已写入"
    MarkdownFileChangeStatus.NO_CHANGES -> "没有需要沉淀的稳定内容"
    MarkdownFileChangeStatus.DISMISSED -> "已撤回 Markdown 文件变更"
    MarkdownFileChangeStatus.FAILED -> "Markdown 文件变更失败"
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

internal data class MarkdownDraftApplyAttempt(
    val draftId: String,
    val generation: Int,
)

internal class MarkdownDraftApplyController {
    private var nextGeneration = 0
    private val activeAttempts = mutableMapOf<String, MarkdownDraftApplyAttempt>()

    fun begin(draftId: String): MarkdownDraftApplyAttempt? {
        if (draftId in activeAttempts) return null
        nextGeneration += 1
        return MarkdownDraftApplyAttempt(draftId, nextGeneration).also { attempt ->
            activeAttempts[draftId] = attempt
        }
    }

    fun complete(attempt: MarkdownDraftApplyAttempt): Boolean {
        if (activeAttempts[attempt.draftId] != attempt) return false
        activeAttempts.remove(attempt.draftId)
        return true
    }

    fun invalidate(draftId: String) {
        activeAttempts.remove(draftId)
    }

    fun isApplying(draftId: String): Boolean = draftId in activeAttempts
}

internal data class LegacyMarkdownReviewToken(
    val generation: Int,
)

internal data class LegacyMarkdownReviewApplyAttempt(
    val reviewToken: LegacyMarkdownReviewToken,
    val generation: Int,
)

internal class LegacyMarkdownReviewApplyController {
    private var nextGeneration = 0
    private var activeAttempt: LegacyMarkdownReviewApplyAttempt? = null

    fun createReviewToken(): LegacyMarkdownReviewToken =
        LegacyMarkdownReviewToken(++nextGeneration)

    fun begin(reviewToken: LegacyMarkdownReviewToken): LegacyMarkdownReviewApplyAttempt? {
        if (activeAttempt != null) return null
        return LegacyMarkdownReviewApplyAttempt(reviewToken, ++nextGeneration).also { attempt ->
            activeAttempt = attempt
        }
    }

    fun complete(attempt: LegacyMarkdownReviewApplyAttempt): Boolean {
        if (activeAttempt != attempt) return false
        activeAttempt = null
        return true
    }

    fun invalidate(reviewToken: LegacyMarkdownReviewToken) {
        if (activeAttempt?.reviewToken == reviewToken) activeAttempt = null
    }

    fun isApplying(reviewToken: LegacyMarkdownReviewToken): Boolean =
        activeAttempt?.reviewToken == reviewToken
}

internal fun isLegacyMarkdownReviewApplying(
    pendingToken: LegacyMarkdownReviewToken?,
    activeToken: LegacyMarkdownReviewToken?,
): Boolean = pendingToken != null && pendingToken == activeToken

internal suspend fun finalizeMarkdownWriteBackBeforeRefresh(
    finalize: () -> Unit,
    afterFinalize: suspend () -> Unit = {},
    refreshDeliverables: (suspend () -> Unit)?,
) {
    finalize()
    afterFinalize()
    val refresh = refreshDeliverables ?: return
    try {
        refresh()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        // The write result is already terminal; project files can be refreshed later.
    }
}

internal fun failedRetainedReviewIndexes(
    retainedIndexes: Set<Int>,
    result: MarkdownBatchApplyResult,
): Set<Int> {
    val orderedRetainedIndexes = retainedIndexes.sorted()
    return result.results.mapIndexedNotNull { resultIndex, itemResult ->
        orderedRetainedIndexes.getOrNull(resultIndex)
            ?.takeIf { itemResult.status == MarkdownFileApplyStatus.FAILED }
    }.toSet()
}

internal fun legacyPartialRetryReviewState(
    review: MarkdownUpdateReviewState,
    retainedIndexes: Set<Int>,
    result: MarkdownBatchApplyResult,
): MarkdownUpdateReviewState {
    val failedIndexes = failedRetainedReviewIndexes(retainedIndexes, result).sorted()
    return MarkdownUpdateReviewState(
        proposals = failedIndexes.map(review.proposals::get),
        diffs = failedIndexes.map { index -> review.diffs.getOrElse(index) { emptyList() } },
    )
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

internal data class MarkdownUpdateReviewState(
    val proposals: List<MarkdownUpdateProposal>,
    val diffs: List<List<MarkdownDiffLine>>,
)

@Composable
private fun MarkdownUpdateReviewDialog(
    review: MarkdownUpdateReviewState,
    retainedIndexes: Set<Int>,
    isApplying: Boolean,
    onToggleRetained: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isApplying) onDismiss() },
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
                        enabled = !isApplying,
                        onToggleRetained = onToggleRetained,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isApplying,
            ) { Text(markdownReviewConfirmText(retainedIndexes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isApplying) { Text("取消") }
        },
    )
}

@Composable
private fun MarkdownUpdateReviewItem(
    index: Int,
    proposal: MarkdownUpdateProposal,
    diff: List<MarkdownDiffLine>,
    retained: Boolean,
    enabled: Boolean,
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
                TextButton(onClick = { onToggleRetained(index) }, enabled = enabled) {
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
            MarkdownDraftDiff(lines = diff, maxLines = MAX_REVIEW_DIFF_LINES)
        }
    }
}

@Composable
private fun SessionConfigDialog(
    promptText: String,
    optimizedPrompt: String,
    status: String?,
    isOptimizing: Boolean,
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
internal const val MAX_CHAT_IMAGE_ATTACHMENTS = 4
internal const val MAX_CHAT_IMAGE_BYTES = 8L * 1024L * 1024L
private const val MAX_CHAT_CONTENT_WIDTH_DP = 760
private const val MAX_MESSAGE_BUBBLE_WIDTH_DP = 700
internal const val CHAT_SCROLL_TO_BOTTOM_OFFSET_PX = 1_000_000
private const val STREAMING_AUTO_SCROLL_BOTTOM_THRESHOLD_PX = 640
private const val MAX_FILE_CHANGE_CARD_ITEMS = 6
private const val MAX_FILE_CHANGE_CONTEXT_MESSAGES = 12
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

internal suspend fun persistMarkdownWriteBackResultEvent(
    result: MarkdownBatchApplyResult,
    insertEvent: suspend (String) -> Unit,
) {
    val event = markdownWriteBackResultEvent(result) ?: return
    try {
        insertEvent(event)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        // The file write result remains authoritative when optional event persistence fails.
    }
}

internal suspend fun persistMarkdownWriteBackLinks(
    result: MarkdownBatchApplyResult,
    linkMarkdown: suspend (String) -> Unit,
) {
    result.succeeded
        .asSequence()
        .filter { item -> item.proposal.operation == MarkdownUpdateOperation.CREATE }
        .mapNotNull { item -> item.writtenDeliverable?.path?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
        .forEach { path ->
            try {
                linkMarkdown(path)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // 文件写入已完成；无法保存弱关联时不应把写入结果改成失败。
            }
        }
}

internal fun markdownWriteBackResultEvent(result: MarkdownBatchApplyResult): String? {
    val succeeded = result.succeeded
        .mapNotNull { it.writtenDeliverable?.path }
        .filter { it.isNotBlank() }
        .take(MAX_WRITE_BACK_EVENT_PATHS)
    if (succeeded.isEmpty()) return null
    val successText = "已沉淀到项目：${succeeded.joinToString("、")}"
    val failedText = result.failed.take(MAX_WRITE_BACK_EVENT_PATHS).joinToString("、") { failed ->
        "${failed.proposal.path}（${failed.errorMessage.orEmpty().ifBlank { "文件写入失败" }}）"
    }
    return if (failedText.isBlank()) successText else "$successText；写入失败：$failedText"
}

internal fun markdownWriteBackResultStatus(result: MarkdownBatchApplyResult): String = when {
    result.failed.isEmpty() -> "已写入 ${result.succeeded.size} 项 Markdown 更新"
    result.succeeded.isEmpty() -> "${result.failed.size} 项 Markdown 更新写入失败"
    else -> "已写入 ${result.succeeded.size} 项 Markdown 更新，${result.failed.size} 项失败"
}

internal fun markdownWriteBackResultError(result: MarkdownBatchApplyResult): String? = when {
    result.failed.isEmpty() -> null
    result.succeeded.isEmpty() -> "${result.failed.size} 个文件写入失败，可重试失败项"
    else -> "${result.failed.size} 个文件写入失败，可仅重试失败项"
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
internal fun MarkdownFileChangeCard(
    state: MarkdownFileChangeState,
    isApplying: Boolean = false,
    onShowDiff: () -> Unit,
    onApply: () -> Unit,
    onRetry: () -> Unit,
    onRetryFailed: () -> Unit,
    onDismiss: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenGit: () -> Unit,
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
                val cardTitle = markdownFileChangeCardTitle(state.draft.status, state.items.size)
                Text(
                    text = cardTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.draft.summary.isNotBlank() && state.draft.summary != cardTitle) {
                    Text(
                        text = state.draft.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isApplying) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                when (state.draft.status) {
                    MarkdownFileChangeStatus.PLANNING,
                    MarkdownFileChangeStatus.APPLYING,
                    -> {
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
                            TextButton(onClick = onApply, enabled = !isApplying) {
                                Text(if (state.items.all { it.retained }) "应用全部" else "应用保留项")
                            }
                            TextButton(onClick = onDismiss, enabled = !isApplying) { Text("撤回") }
                        }
                    }
                    MarkdownFileChangeStatus.APPLIED -> {
                        AppliedPathList(state.appliedPaths)
                        Text(
                            text = "Git 工作区已更新（未提交）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = HarnessSpacing.minimumTouchTarget),
                            onClick = onOpenFiles,
                        ) {
                            Icon(Icons.Outlined.Folder, contentDescription = null)
                            Text("查看文件", modifier = Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = HarnessSpacing.minimumTouchTarget),
                            onClick = onOpenGit,
                        ) {
                            Icon(Icons.Outlined.AccountTree, contentDescription = null)
                            Text("查看 Git 变更", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    MarkdownFileChangeStatus.PARTIALLY_APPLIED -> {
                        Text(
                            "已写入",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        AppliedPathList(state.appliedPaths)
                        Text(
                            text = "Git 工作区已更新（未提交）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Text(
                            "写入失败",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        FailedPathList(state.applyFailures)
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = HarnessSpacing.minimumTouchTarget),
                            onClick = onOpenFiles,
                        ) {
                            Icon(Icons.Outlined.Folder, contentDescription = null)
                            Text("查看文件", modifier = Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = HarnessSpacing.minimumTouchTarget),
                            onClick = onOpenGit,
                        ) {
                            Icon(Icons.Outlined.AccountTree, contentDescription = null)
                            Text("查看 Git 变更", modifier = Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = HarnessSpacing.minimumTouchTarget),
                            onClick = onRetryFailed,
                            enabled = !isApplying,
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                            Text("仅重试失败项", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    MarkdownFileChangeStatus.FAILED -> {
                        if (state.applyFailures.isNotEmpty()) {
                            FailedPathList(state.applyFailures)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = if (state.applyFailures.isEmpty()) onRetry else onRetryFailed,
                                enabled = !isApplying,
                            ) {
                                Text(if (state.applyFailures.isEmpty()) "重试" else "重试失败项")
                            }
                            TextButton(onClick = onDismiss, enabled = !isApplying) { Text("撤回") }
                        }
                    }
                    MarkdownFileChangeStatus.NO_CHANGES,
                    MarkdownFileChangeStatus.DISMISSED,
                    -> Unit
                }
            }
        }
    }
}

@Composable
private fun AppliedPathList(paths: List<String>) {
    val visible = paths.take(3)
    visible.forEach { path ->
        Text(
            path,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
    if (paths.size > visible.size) {
        Text(
            "另有 ${paths.size - visible.size} 个文件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FailedPathList(failures: List<MarkdownFileChangeFailure>) {
    failures.take(3).forEach { failure ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(failure.proposal.path, style = MaterialTheme.typography.bodyMedium)
            Text(
                failure.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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
    wikiCitations: List<MessageWikiCitation>,
    textColor: androidx.compose.ui.graphics.Color,
    imageStore: ChatImageStore,
    hideAgentCitationMarkers: Boolean,
    onLinkClick: (String) -> Unit,
    onOpenWikiCitation: (String) -> Unit,
    onOpenProjectSource: (String) -> Unit,
    reasoningStreaming: Boolean,
    forceExpandProcess: Boolean,
    executionEntry: ChatExecutionEntry?,
) {
    val sourceState = remember(parts, wikiCitations) {
        messageSourcesUiState(parts = parts, citations = wikiCitations)
    }
    val contentParts = parts.filter { it.type in messageContentPartTypes }.map { part ->
        if (part.type == UiMessagePartType.TEXT) {
            part.copy(content = linkProjectCitationTokens(part.content, sourceState))
        } else {
            part
        }
    }
    val processParts = parts.filter { it.type in messageProcessPartTypes }
    var processExpanded by remember { mutableStateOf(reasoningStreaming) }
    LaunchedEffect(reasoningStreaming, forceExpandProcess) {
        processExpanded = reasoningStreaming || forceExpandProcess
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        contentParts.forEach { part ->
            key(part.index, part.type) {
                MessagePartView(
                    part = part,
                    textColor = textColor,
                    imageStore = imageStore,
                    hideAgentCitationMarkers = hideAgentCitationMarkers,
                    onLinkClick = onLinkClick,
                    autoExpandReasoning = shouldAutoExpandReasoningPart(
                        part = part,
                        parts = parts,
                        reasoningStreaming = reasoningStreaming,
                    ),
                )
            }
        }
        if (reasoningStreaming) {
            processParts.forEach { part ->
                key(part.index, part.type) {
                    MessagePartView(
                        part = part,
                        textColor = textColor,
                        imageStore = imageStore,
                        hideAgentCitationMarkers = hideAgentCitationMarkers,
                        onLinkClick = onLinkClick,
                        autoExpandReasoning = true,
                    )
                }
            }
            sourceState?.let { state ->
                MessageSourcesPart(
                    state = state,
                    onOpenWikiCitation = onOpenWikiCitation,
                    onOpenProjectSource = onOpenProjectSource,
                )
            }
        } else if (processParts.isNotEmpty() || sourceState != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { processExpanded = !processExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = completedProcessSummary(processParts, sourceState, executionEntry),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Icon(
                            imageVector = if (processExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (processExpanded) "收起过程与来源" else "展开过程与来源",
                        )
                    }
                    if (processExpanded) {
                        processParts.forEach { part ->
                            key(part.index, part.type) {
                                MessagePartView(
                                    part = part,
                                    textColor = textColor,
                                    imageStore = imageStore,
                                    hideAgentCitationMarkers = hideAgentCitationMarkers,
                                    onLinkClick = onLinkClick,
                                    autoExpandReasoning = true,
                                )
                            }
                        }
                        sourceState?.let { state ->
                            MessageSourcesPart(
                                state = state,
                                onOpenWikiCitation = onOpenWikiCitation,
                                onOpenProjectSource = onOpenProjectSource,
                                embedded = true,
                                expandedOverride = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun completedProcessSummary(
    processParts: List<UiMessagePartDraft>,
    sourceState: MessageSourcesUiState?,
    executionEntry: ChatExecutionEntry? = null,
): String = buildList {
    executionEntry?.let { entry ->
        executionStatusLabel(entry.status)?.let(::add)
        val elapsedMillis = (entry.updatedAt - entry.createdAt).coerceAtLeast(0L)
        if (entry.status !in setOf(ChatExecutionStatus.QUEUED, ChatExecutionStatus.RUNNING) && elapsedMillis > 0L) {
            add(if (elapsedMillis < 1_000L) "${elapsedMillis}ms" else "${elapsedMillis / 1_000L}s")
        }
    }
    val reasoningCount = processParts.count { it.type == UiMessagePartType.REASONING }
    val toolCount = processParts.count {
        it.type == UiMessagePartType.TOOL_CALL || it.type == UiMessagePartType.TOOL_RESULT
    }
    val searchCount = processParts.count { it.type == UiMessagePartType.SEARCH_RESULT }
    if (reasoningCount > 0) add("思考 $reasoningCount")
    if (toolCount > 0) add("工具 $toolCount")
    if (searchCount > 0) add("搜索 $searchCount")
    sourceState?.collapsedSummary?.takeIf(String::isNotBlank)?.let(::add)
    if (isEmpty()) add("过程与来源")
}.joinToString(" · ")

internal fun contextSnapshotSummary(snapshot: ContextSnapshotV2): String = buildList {
    add(snapshot.projectName ?: "临时会话")
    snapshot.agentId?.let { add("$it@v${snapshot.agentVersion ?: "?"}") }
    if (snapshot.wikiScope.isNotEmpty()) add("Wiki ${snapshot.wikiScope.size}")
    add(snapshot.model)
}.joinToString(" · ")

internal fun contextSnapshotDetails(snapshot: ContextSnapshotV2): String = buildList {
    add("项目：${snapshot.projectName ?: "无"}${snapshot.projectId?.let { " ($it)" }.orEmpty()}")
    snapshot.projectContextSha256?.let { add("项目上下文 SHA-256：$it") }
    add("智能体：${snapshot.agentId?.let { "$it@v${snapshot.agentVersion ?: "?"}" } ?: "普通助手"}")
    add(
        "Wiki：" + snapshot.wikiScope
            .joinToString { "${it.wikiId}@v${it.version}" }
            .ifBlank { "无" },
    )
    add("模型：${snapshot.providerId} / ${snapshot.model} / ${snapshot.reasoningEffort}")
    add("联网：${if (snapshot.webSearchEnabled) "开" else "关"}")
    add("附件：${snapshot.attachments.size}")
    snapshot.attachments.forEachIndexed { index, attachment ->
        add("附件 ${index + 1}：${attachment.mimeType} · ${attachment.sizeBytes} B · ${attachment.sha256}")
    }
}.joinToString("\n")

private val messageContentPartTypes = setOf(
    UiMessagePartType.TEXT,
    UiMessagePartType.IMAGE,
    UiMessagePartType.DOCUMENT,
    UiMessagePartType.SYSTEM_EVENT,
)

private val messageProcessPartTypes = setOf(
    UiMessagePartType.REASONING,
    UiMessagePartType.SEARCH_RESULT,
    UiMessagePartType.TOOL_CALL,
    UiMessagePartType.TOOL_RESULT,
    UiMessagePartType.ERROR_DETAIL,
    UiMessagePartType.FILE_CHANGE,
)

internal fun chatImageSizeBytes(context: android.content.Context, uri: Uri): Long? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
    } ?: context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
        descriptor.length.takeIf { it >= 0L }
    }
}.getOrNull()

@Composable
private fun MessagePartView(
    part: UiMessagePartDraft,
    textColor: androidx.compose.ui.graphics.Color,
    imageStore: ChatImageStore,
    hideAgentCitationMarkers: Boolean,
    onLinkClick: (String) -> Unit,
    autoExpandReasoning: Boolean,
) {
    when (part.type) {
        UiMessagePartType.TEXT -> MarkdownMessage(
            markdown = if (hideAgentCitationMarkers) stripAgentCitationMarkers(part.content) else part.content,
            textColor = textColor,
            onLinkClick = onLinkClick,
        )
        UiMessagePartType.REASONING -> ReasoningPart(part, autoExpand = autoExpandReasoning)
        UiMessagePartType.SEARCH_RESULT -> SearchResultPart(part)
        UiMessagePartType.TOOL_CALL -> MetadataPart(label = "工具调用", content = part.content)
        UiMessagePartType.TOOL_RESULT -> MetadataPart(label = "工具结果", content = part.content)
        UiMessagePartType.ERROR_DETAIL -> MetadataPart(label = "错误详情", content = part.content)
        UiMessagePartType.FILE_CHANGE -> MetadataPart(label = "文件变更", content = part.content)
        UiMessagePartType.IMAGE -> ChatMessageImage(
            source = imagePartSource(part),
            mimeType = part.metadata["mimeType"],
            imageStore = imageStore,
        )
        UiMessagePartType.DOCUMENT -> MetadataPart(label = "文档", content = part.content.ifBlank { "文档附件" })
        UiMessagePartType.SYSTEM_EVENT -> MetadataPart(label = "系统事件", content = part.content)
        UiMessagePartType.AGENT_SOURCES,
        UiMessagePartType.WIKI_SOURCES,
        UiMessagePartType.PROJECT_SOURCES,
        -> Unit
    }
}

@Composable
private fun ChatMessageImage(
    source: String,
    mimeType: String?,
    imageStore: ChatImageStore,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loadAttempt by remember(source, mimeType) { mutableStateOf(0) }
    var image by remember(source, mimeType) { mutableStateOf<ChatImageDisplay>(ChatImageDisplay.Loading) }
    var previewOpen by remember(source) { mutableStateOf(false) }
    var saveStatus by remember(source) { mutableStateOf<String?>(null) }

    fun saveReadyImage() {
        val readyImage = image as? ChatImageDisplay.Ready ?: return
        scope.launch {
            saveStatus = "正在保存图片..."
            saveStatus = runCatching {
                imageStore.saveToMediaStore(readyImage.uri, readyImage.mimeType)
            }.fold(
                onSuccess = { "已保存图片" },
                onFailure = { "保存图片失败：${it.toUserMessage()}" },
            )
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            saveReadyImage()
        } else {
            saveStatus = "未获得存储权限，无法保存图片"
        }
    }

    fun requestSave() {
        val needsStoragePermission = Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.P &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsStoragePermission) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveReadyImage()
        }
    }

    fun markImageDecodeFailed(uri: Uri, message: String) {
        val readyImage = image as? ChatImageDisplay.Ready ?: return
        if (readyImage.uri == uri) {
            image = ChatImageDisplay.Failed(message)
        }
    }

    LaunchedEffect(source, mimeType, loadAttempt) {
        saveStatus = null
        image = ChatImageDisplay.Loading
        image = runCatching {
            when (val displaySource = imageStore.resolveDisplaySource(source, mimeType)) {
                is ChatImageSource.Local -> ChatImageDisplay.Ready(displaySource.uri, displaySource.mimeType)
                is ChatImageSource.Data,
                is ChatImageSource.Remote,
                -> imageStore.materialize(displaySource).let { persisted ->
                    ChatImageDisplay.Ready(persisted.uri, persisted.mimeType)
                }
                is ChatImageSource.Invalid -> ChatImageDisplay.Failed(displaySource.reason)
            }
        }.getOrElse { error ->
            ChatImageDisplay.Failed(error.toUserMessage())
        }
    }

    ChatImageThumbnail(
        image = image,
        onOpen = { previewOpen = image is ChatImageDisplay.Ready },
        onRetry = { loadAttempt++ },
        onDecodeFailed = ::markImageDecodeFailed,
    )
    if (previewOpen) {
        ChatImagePreviewDialog(
            image = image,
            onDismiss = { previewOpen = false },
            onSave = ::requestSave,
            saveStatus = saveStatus,
            onRetry = { loadAttempt++ },
            onDecodeFailed = ::markImageDecodeFailed,
        )
    }
}

@Composable
internal fun ReasoningPart(
    part: UiMessagePartDraft,
    autoExpand: Boolean,
) {
    var userExpanded by remember(part.index) { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: autoExpand
    val toggleExpanded = { userExpanded = !expanded }
    val preview = reasoningCollapsedPreviewText(part.content)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = toggleExpanded),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "思考过程",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    modifier = Modifier.size(40.dp),
                    onClick = toggleExpanded,
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (expanded) {
                Text(
                    text = part.content,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (preview.isNotBlank()) {
                Text(
                    text = preview,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun reasoningCollapsedPreviewText(content: String): String =
    content
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .lastOrNull()
        .orEmpty()

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
    wikiCitations: List<MessageWikiCitation>,
    executionEntry: ChatExecutionEntry?,
    attachments: List<ChatAttachment>,
    imageStore: ChatImageStore,
    maxBubbleWidth: Dp,
    isAgentConversation: Boolean,
    canWriteBack: Boolean,
    onWriteBack: () -> Unit,
    onCopy: () -> Unit,
    onSelectCopy: () -> Unit,
    isSpeaking: Boolean,
    onSpeak: () -> Unit,
    onSteer: (() -> Unit)?,
    onEditQueued: (() -> Unit)?,
    onDeleteQueued: (() -> Unit)?,
    onRetryFailed: (() -> Unit)?,
    onOpenBatterySettings: (() -> Unit)?,
    onOpenWikiCitation: (String) -> Unit,
    onOpenProjectSource: (String) -> Unit,
    reasoningStreaming: Boolean,
    highlighted: Boolean,
) {
    val isUser = message.role == MessageRole.USER
    var queueMenuExpanded by remember(message.id) { mutableStateOf(false) }
    var actionMenuExpanded by remember(message.id) { mutableStateOf(false) }
    var contextSnapshotExpanded by remember(message.id) { mutableStateOf(false) }
    val presentation = chatBubblePresentation(message.role)
    val uriHandler = LocalUriHandler.current
    val hideAgentCitationMarkers = isAgentConversation && parts.any { it.type == UiMessagePartType.AGENT_SOURCES }
    val selectionCopyText = messageSelectionCopyText(
        message = message,
        parts = parts,
        hideAgentCitationMarkers = hideAgentCitationMarkers,
    )
    val containerColor = when (presentation) {
        ChatBubblePresentation.UNFRAMED -> MaterialTheme.colorScheme.surface.copy(alpha = 0f)
        ChatBubblePresentation.WARM_USER -> MaterialTheme.colorScheme.primaryContainer
        ChatBubblePresentation.NEUTRAL_EVENT -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (presentation) {
        ChatBubblePresentation.WARM_USER -> MaterialTheme.colorScheme.onPrimaryContainer
        ChatBubblePresentation.UNFRAMED,
        ChatBubblePresentation.NEUTRAL_EVENT,
        -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = when (messageBubbleSide(message.role)) {
            ChatBubbleSide.START -> Arrangement.Start
            ChatBubbleSide.END -> Arrangement.End
        },
    ) {
        val bubbleShape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 18.dp,
            )
        Surface(
            modifier = Modifier
                .widthIn(max = maxBubbleWidth)
                .then(
                    if (highlighted) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, bubbleShape)
                    } else {
                        Modifier
                    },
                ),
            shape = bubbleShape,
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = if (presentation == ChatBubblePresentation.UNFRAMED) 2.dp else 14.dp,
                    vertical = if (presentation == ChatBubblePresentation.UNFRAMED) 6.dp else 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = if (isUser) "你" else message.model ?: "助手",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (onSteer != null && onEditQueued != null && onDeleteQueued != null) {
                        Box {
                            IconButton(
                                modifier = Modifier.size(48.dp),
                                onClick = { queueMenuExpanded = true },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = "队列操作",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = queueMenuExpanded,
                                onDismissRequest = { queueMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("引导当前") },
                                    onClick = {
                                        queueMenuExpanded = false
                                        onSteer()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("编辑") },
                                    onClick = {
                                        queueMenuExpanded = false
                                        onEditQueued()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除") },
                                    onClick = {
                                        queueMenuExpanded = false
                                        onDeleteQueued()
                                    },
                                )
                            }
                        }
                    }
                }
                executionEntry?.let { entry ->
                    executionActivityLabel(entry)?.let { label ->
                        Text(
                            text = label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (isUser) {
                    executionEntry?.requestContext?.contextSnapshot?.let { snapshot ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp)
                                .clickable { contextSnapshotExpanded = !contextSnapshotExpanded },
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                modifier = Modifier.weight(1f),
                                text = "发送上下文 · ${contextSnapshotSummary(snapshot)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                imageVector = if (contextSnapshotExpanded) {
                                    Icons.Outlined.ExpandLess
                                } else {
                                    Icons.Outlined.ExpandMore
                                },
                                contentDescription = if (contextSnapshotExpanded) "收起发送上下文" else "展开发送上下文",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        if (contextSnapshotExpanded) {
                            SelectionContainer {
                                Text(
                                    text = contextSnapshotDetails(snapshot),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
                if (parts.isNotEmpty()) {
                    SelectionContainer {
                        MessagePartsColumn(
                            parts = parts,
                            wikiCitations = wikiCitations,
                            textColor = contentColor,
                            imageStore = imageStore,
                            hideAgentCitationMarkers = hideAgentCitationMarkers,
                            onLinkClick = { destination ->
                                when (val target = markdownLinkTarget(destination)) {
                                    is MarkdownLinkTarget.WikiCitation -> onOpenWikiCitation(target.citationId)
                                    is MarkdownLinkTarget.ProjectEvidence -> onOpenProjectSource(target.evidenceId)
                                    is MarkdownLinkTarget.ExternalUrl -> runCatching {
                                        uriHandler.openUri(target.url)
                                    }
                                    MarkdownLinkTarget.Ignored -> Unit
                                }
                            },
                            onOpenWikiCitation = onOpenWikiCitation,
                            onOpenProjectSource = onOpenProjectSource,
                            reasoningStreaming = reasoningStreaming,
                            forceExpandProcess = highlighted,
                            executionEntry = executionEntry,
                        )
                    }
                }
                attachments
                    .filter { it.type.equals("image", ignoreCase = true) }
                    .forEach { attachment ->
                        key(attachment.id) {
                            ChatMessageImage(
                                source = attachment.uri,
                                mimeType = attachment.mimeType,
                                imageStore = imageStore,
                            )
                        }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        onRetryFailed?.let { retry ->
                            TextButton(onClick = retry) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null)
                                Text("重试")
                            }
                        }
                        onOpenBatterySettings?.let { openSettings ->
                            TextButton(onClick = openSettings) {
                                Text("检查电池限制")
                            }
                        }
                    }
                }
                if (selectionCopyText.isNotBlank() || canWriteBack) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (selectionCopyText.isNotBlank()) {
                            IconButton(
                                modifier = Modifier.size(40.dp),
                                onClick = onCopy,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = "复制",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Box {
                            IconButton(
                                modifier = Modifier.size(40.dp),
                                onClick = { actionMenuExpanded = true },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = "更多消息操作",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = actionMenuExpanded,
                                onDismissRequest = { actionMenuExpanded = false },
                            ) {
                                if (message.role == MessageRole.ASSISTANT && selectionCopyText.isNotBlank()) {
                                    DropdownMenuItem(
                                        text = { Text(if (isSpeaking) "停止朗读" else "朗读回复") },
                                        leadingIcon = {
                                            Icon(
                                                if (isSpeaking) Icons.Filled.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            actionMenuExpanded = false
                                            onSpeak()
                                        },
                                    )
                                }
                                if (selectionCopyText.isNotBlank()) {
                                    DropdownMenuItem(
                                        text = { Text("选择复制") },
                                        leadingIcon = { Icon(Icons.Outlined.TextFields, contentDescription = null) },
                                        onClick = {
                                            actionMenuExpanded = false
                                            onSelectCopy()
                                        },
                                    )
                                }
                                if (canWriteBack) {
                                    DropdownMenuItem(
                                        text = { Text("沉淀到项目") },
                                        onClick = {
                                            actionMenuExpanded = false
                                            onWriteBack()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatQueueStrip(entries: List<ChatExecutionEntry>) {
    val running = entries.firstOrNull { it.status == ChatExecutionStatus.RUNNING }
    val queuedCount = entries.count { it.status == ChatExecutionStatus.QUEUED }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = running?.let(::executionActivityLabel) ?: "等待处理",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (queuedCount > 0) {
                Text(
                    text = "等待 $queuedCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun RecoveryAnswerPlaceholder(
    entry: ChatExecutionEntry,
    onRetry: (() -> Unit)?,
) {
    val label = when (entry.status) {
        ChatExecutionStatus.QUEUED -> "正在排队恢复回答"
        ChatExecutionStatus.RUNNING -> "正在恢复回答"
        ChatExecutionStatus.FAILED -> entry.errorMessage ?: "回答恢复失败"
        ChatExecutionStatus.CANCELLED -> "回答恢复已取消"
        ChatExecutionStatus.INTERRUPTED -> "回答恢复已中断"
        ChatExecutionStatus.SUCCEEDED -> "回答已恢复"
        ChatExecutionStatus.STEERED -> "恢复任务已调整"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.status == ChatExecutionStatus.QUEUED || entry.status == ChatExecutionStatus.RUNNING) {
                LinearProgressIndicator(modifier = Modifier.weight(1f))
            }
            Text(
                modifier = Modifier.weight(2f),
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            onRetry?.let { retry ->
                IconButton(modifier = Modifier.size(48.dp), onClick = retry) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "重试恢复回答")
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
    selectedImages: List<PendingImageAttachment>,
    onTakePhoto: () -> Unit,
    onPickFromAlbum: () -> Unit,
    onRemoveImage: (Uri) -> Unit,
    pendingDocuments: List<ExtractedDocument>,
    documentExtracting: Boolean,
    onPickDocument: () -> Unit,
    onRemoveDocument: (ExtractedDocument) -> Unit,
    onStartVoiceTranscription: () -> Unit,
    isVoiceInputActive: Boolean,
    onStopVoiceTranscription: () -> Unit,
    contextSummary: ConversationContextSummary,
    onOpenContext: () -> Unit,
    inputFocusRequester: FocusRequester,
    canSend: Boolean,
    isBusy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    showFileChangeSuggestion: Boolean,
    canSendFileChange: Boolean,
    onSendFileChange: () -> Unit,
) {
    val trailingAction = chatInputTrailingAction(
        text = text,
        hasSelectedImage = selectedImages.isNotEmpty(),
        isBusy = isBusy,
    )
    var showImageSourceSheet by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (pendingDocuments.isNotEmpty() || documentExtracting) {
                PendingDocumentsRow(
                    documents = pendingDocuments,
                    extracting = documentExtracting,
                    onRemove = onRemoveDocument,
                )
            }
            if (selectedImages.isNotEmpty()) {
                SelectedImagesPreview(
                    images = selectedImages,
                    onRemove = onRemoveImage,
                    onTakePhoto = onTakePhoto,
                    onPickFromAlbum = onPickFromAlbum,
                    onPickDocument = onPickDocument,
                )
            }
            ConversationContextBar(summary = contextSummary, onClick = onOpenContext)
            if (showFileChangeSuggestion) {
                TextButton(
                    enabled = canSendFileChange,
                    onClick = onSendFileChange,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Assignment, contentDescription = null)
                    Text("使用文件变更")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .focusRequester(inputFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (
                                shouldSendChatInputOnKeyEvent(
                                    key = event.key,
                                    eventType = event.type,
                                    shiftPressed = event.isShiftPressed,
                                    canSend = canSend,
                                )
                            ) {
                                onSend()
                                true
                            } else {
                                false
                            }
                        },
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = { Text("发消息") },
                    minLines = 1,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (canSend) {
                                onSend()
                            }
                        },
                    ),
                    trailingIcon = {
                        ChatInputVoiceAction(
                            voiceActive = isVoiceInputActive,
                            enabled = !isBusy,
                            onStart = onStartVoiceTranscription,
                            onStop = onStopVoiceTranscription,
                        )
                    },
                )
                ChatInputPrimaryAction(
                    action = trailingAction,
                    canSend = canSend,
                    voiceActive = isVoiceInputActive,
                    onAttach = { showImageSourceSheet = true },
                    onSend = onSend,
                    onStopGeneration = onStop,
                )
            }
            if (showImageSourceSheet) {
                ChatImageSourceSheet(
                    onDismiss = { showImageSourceSheet = false },
                    onTakePhoto = {
                        showImageSourceSheet = false
                        onTakePhoto()
                    },
                    onPickFromAlbum = {
                        showImageSourceSheet = false
                        onPickFromAlbum()
                    },
                    onPickDocument = {
                        showImageSourceSheet = false
                        onPickDocument()
                    },
                )
            }
        }
    }
}

@Composable
internal fun ChatInputPrimaryAction(
    action: ChatInputTrailingAction,
    canSend: Boolean,
    voiceActive: Boolean,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit,
) {
    FilledIconButton(
        modifier = Modifier.size(48.dp),
        enabled = !voiceActive && (action != ChatInputTrailingAction.SEND || canSend),
        onClick = when (action) {
            ChatInputTrailingAction.ATTACH -> onAttach
            ChatInputTrailingAction.SEND -> onSend
            ChatInputTrailingAction.STOP -> onStopGeneration
        },
    ) {
        when (action) {
            ChatInputTrailingAction.ATTACH -> Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "添加图片",
            )
            ChatInputTrailingAction.SEND -> Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = sendButtonContentDescription(isBusy = false),
            )
            ChatInputTrailingAction.STOP -> Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = sendButtonContentDescription(isBusy = true),
            )
        }
    }
}

@Composable
internal fun ChatInputVoiceAction(
    voiceActive: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    IconButton(
        modifier = Modifier.size(40.dp),
        enabled = enabled,
        onClick = if (voiceActive) onStop else onStart,
    ) {
        Icon(
            imageVector = if (voiceActive) Icons.Filled.Stop else Icons.Outlined.Mic,
            contentDescription = if (voiceActive) "停止语音输入" else "开始语音输入",
        )
    }
}

internal fun shouldSendChatInputOnKeyEvent(
    key: Key,
    eventType: KeyEventType,
    shiftPressed: Boolean,
    canSend: Boolean,
): Boolean = key == Key.Enter &&
    eventType == KeyEventType.KeyDown &&
    !shiftPressed &&
    canSend

@Composable
internal fun ConversationWikiTopBarAction(
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.MenuBook,
            contentDescription = "调整本会话可用知识库",
        )
    }
}

internal fun shouldAutoExpandReasoningPart(
    part: UiMessagePartDraft,
    parts: List<UiMessagePartDraft>,
    reasoningStreaming: Boolean,
): Boolean = reasoningStreaming &&
    part.type == UiMessagePartType.REASONING &&
    !part.stable &&
    parts.lastOrNull { it.type == UiMessagePartType.REASONING }?.index == part.index

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatImageSourceEntryMenu(
    onTakePhoto: () -> Unit,
    onPickFromAlbum: () -> Unit,
    onPickDocument: () -> Unit,
) {
    var showImageSourceSheet by remember { mutableStateOf(false) }

    IconButton(
        modifier = Modifier.size(56.dp),
        onClick = { showImageSourceSheet = true },
    ) {
        Icon(Icons.Outlined.Add, contentDescription = "添加图片")
    }

    if (showImageSourceSheet) {
        ChatImageSourceSheet(
            onDismiss = { showImageSourceSheet = false },
            onTakePhoto = {
                showImageSourceSheet = false
                onTakePhoto()
            },
            onPickFromAlbum = {
                showImageSourceSheet = false
                onPickFromAlbum()
            },
            onPickDocument = {
                showImageSourceSheet = false
                onPickDocument()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectEvidenceSnapshotSheet(
    evidence: ProjectEvidenceSnapshotEntity,
    revisionState: ProjectFileRevisionState,
    onOpenCurrent: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("项目来源 ${evidence.token}", style = MaterialTheme.typography.titleLarge)
            Text(evidence.title, style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(evidence.relativePath, evidence.locatorLabel).distinct().joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when (revisionState) {
                    ProjectFileRevisionState.CURRENT -> "来源与回答时版本一致"
                    ProjectFileRevisionState.UPDATED -> "来源已更新，以下显示回答时快照"
                    ProjectFileRevisionState.DELETED -> "来源已删除，以下显示回答时快照"
                    ProjectFileRevisionState.UNAVAILABLE -> "当前来源不可读取，以下显示回答时快照"
                },
                color = if (revisionState == ProjectFileRevisionState.CURRENT) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
            SelectionContainer {
                Text(evidence.excerpt, style = MaterialTheme.typography.bodyMedium)
            }
            if (onOpenCurrent != null) {
                Button(
                    modifier = Modifier.fillMaxWidth().heightIn(min = HarnessSpacing.minimumTouchTarget),
                    onClick = onOpenCurrent,
                ) {
                    Text(if (evidence.relativePath != null) "查看当前文件" else "跳到当前消息")
                }
            }
            Text(
                "SHA-256 ${evidence.sourceSha256}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.heightIn(min = 12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatImageSourceSheet(
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFromAlbum: () -> Unit,
    onPickDocument: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            TextButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                onClick = onTakePhoto,
            ) {
                Text("拍照")
            }
            TextButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                onClick = onPickFromAlbum,
            ) {
                Text("从相册选择")
            }
            TextButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                onClick = onPickDocument,
            ) {
                Text("选择文件（PDF / Word / Excel / TXT）")
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
        modifier = Modifier.heightIn(min = 48.dp),
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
            modifier = Modifier.heightIn(min = 48.dp),
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
private fun SelectedImagesPreview(
    images: List<PendingImageAttachment>,
    onRemove: (Uri) -> Unit,
    onTakePhoto: () -> Unit,
    onPickFromAlbum: () -> Unit,
    onPickDocument: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        images.forEachIndexed { index, image ->
            SelectedImageThumbnail(
                uri = image.uri,
                position = index + 1,
                total = images.size,
                onRemove = { onRemove(image.uri) },
            )
        }
        if (images.size < MAX_CHAT_IMAGE_ATTACHMENTS) {
            ChatImageSourceEntryMenu(
                onTakePhoto = onTakePhoto,
                onPickFromAlbum = onPickFromAlbum,
                onPickDocument = onPickDocument,
            )
        }
    }
}

@Composable
private fun PendingDocumentsRow(
    documents: List<ExtractedDocument>,
    extracting: Boolean,
    onRemove: (ExtractedDocument) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (extracting) {
            LinearProgressIndicator(Modifier.width(120.dp))
        }
        documents.forEach { document ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        document.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                    IconButton(onClick = { onRemove(document) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "移除文件", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedImageThumbnail(uri: Uri, position: Int, total: Int, onRemove: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(uri) {
        runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
    }
    Surface(
        modifier = Modifier.widthIn(min = 120.dp, max = 160.dp),
        shape = RoundedCornerShape(8.dp),
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
                    contentDescription = "已选择图片 $position，共 $total 张",
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
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, contentDescription = "移除第 $position 张图片")
            }
        }
    }
}

@Composable
private fun InlineError(text: String) {
    InlineStatusMessage(
        text = text,
        tone = StatusTone.ERROR,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun InlineStatus(text: String) {
    InlineStatusMessage(
        text = text,
        tone = StatusTone.INFO,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

internal fun LazyListScope.emptyChatStateItem(
    messageState: PersistedMessagesState,
    contentMaxWidth: Dp,
    showProviderHint: Boolean,
    agentOpening: String?,
) {
    if (!messageState.isLoadedEmpty()) return
    item {
        ChatContentRail(contentMaxWidth = contentMaxWidth) {
            EmptyChatState(
                showProviderHint = showProviderHint,
                agentOpening = agentOpening,
            )
        }
    }
}

@Composable
internal fun EmptyChatState(
    showProviderHint: Boolean,
    agentOpening: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = emptyChatPrimaryText(agentOpening),
            style = if (agentOpening.isNullOrBlank()) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.bodyLarge
            },
            fontWeight = if (agentOpening.isNullOrBlank()) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (showProviderHint) {
            Text(
                text = "支持多供应商和截图输入。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.heightIn(min = 8.dp))
        HorizontalDivider(
            modifier = Modifier.widthIn(max = 160.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

internal fun emptyChatPrimaryText(agentOpening: String?): String =
    agentOpening?.trim()?.takeIf(String::isNotBlank) ?: "开始一段对话"
