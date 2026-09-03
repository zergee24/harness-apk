package com.harnessapk.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.harnessapk.HarnessApkApplication
import com.harnessapk.agent.InitialConversationIdentity
import com.harnessapk.chat.Conversation
import com.harnessapk.project.Project
import com.harnessapk.remote.RemoteConnectionService
import com.harnessapk.ui.capture.CaptureDestinationSheet
import com.harnessapk.ui.capture.CaptureTransferOverlay
import com.harnessapk.ui.agent.AgentPackagesScreen
import com.harnessapk.ui.activity.RemoteRunObjectiveSheet
import com.harnessapk.ui.activity.ActivityScreen
import com.harnessapk.ui.activity.RunDetailScreen
import com.harnessapk.ui.chat.ChatScreen
import com.harnessapk.ui.chat.ConversationWikiTopBarAction
import com.harnessapk.ui.conversation.ConversationListScreen
import com.harnessapk.ui.git.GitSettingsScreen
import com.harnessapk.ui.project.ProjectWorkbenchDestination
import com.harnessapk.ui.project.ProjectScreen
import com.harnessapk.ui.project.ProjectWorkbenchTarget
import com.harnessapk.ui.provider.ProviderSettingsScreen
import com.harnessapk.ui.search.SearchSettingsScreen
import com.harnessapk.ui.search.GlobalSearchScreen
import com.harnessapk.ui.settings.SettingsScreen
import com.harnessapk.ui.settings.ConfigPackageExportScreen
import com.harnessapk.ui.settings.ConfigPackageImportScreen
import com.harnessapk.ui.skills.SkillsScreen
import com.harnessapk.ui.theme.ModeTheme
import com.harnessapk.ui.updater.StartupUpdateAction
import com.harnessapk.ui.updater.UpdateSettingsScreen
import com.harnessapk.ui.updater.startupUpdateAction
import com.harnessapk.ui.voice.VoiceSettingsScreen
import com.harnessapk.ui.voice.rememberVoiceInput
import com.harnessapk.ui.wiki.WikiLibraryScreen
import com.harnessapk.ui.wiki.WikiBrowserScreen
import com.harnessapk.ui.wiki.WikiCitationSourceScreen
import com.harnessapk.ui.wiki.WikiRecoveryState
import com.harnessapk.ui.wiki.WikiRoutes
import com.harnessapk.ui.wiki.WikiSearchScreen
import com.harnessapk.ui.wiki.WikiSourceReaderScreen
import com.harnessapk.ui.dashboard.DashboardActivity
import com.harnessapk.ui.remote.RemoteScreen
import com.harnessapk.ui.remote.RemoteSettingsScreen
import com.harnessapk.updater.UpdateCheckResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Routes {
    const val Conversations = "conversations"
    const val Providers = "providers"
    const val Search = "search"
    const val GlobalSearch = "global-search"
    const val Voice = "voice"
    const val Git = "git"
    const val Skills = "skills"
    const val AgentPackages = "agent-packages"
    const val WikiLibrary = WikiRoutes.Library
    const val Updates = "updates"
    const val RemoteSettings = "remote-settings"
    const val RemoteControl = "remote-control"
    const val Activity = "activity"
    const val RemoteRunPattern = "remote-run/{runId}"
    const val ChatPattern =
        "chat/{conversationId}?projectId={projectId}&focusInput={focusInput}&sourceMessageId={sourceMessageId}"

    fun chat(
        conversationId: String,
        projectId: String? = null,
        focusInput: Boolean = false,
        sourceMessageId: String? = null,
    ): String = buildString {
        append("chat/")
        append(Uri.encode(conversationId))
        append(
            chatRouteQuery(
                projectId = projectId,
                focusInput = focusInput,
                sourceMessageId = sourceMessageId,
                encode = Uri::encode,
            ),
        )
    }

    const val ConfigPackageExport = "config-package-export"
    const val ConfigPackageImportPattern = "config-package-import?uri={uri}"

    fun configPackageImport(uri: String?): String =
        if (uri.isNullOrBlank()) {
            "config-package-import"
        } else {
            "config-package-import?uri=${Uri.encode(uri)}"
        }

    fun remoteRun(runId: String): String = "remote-run/${Uri.encode(runId)}"
}

internal fun chatRouteQuery(
    projectId: String?,
    focusInput: Boolean,
    sourceMessageId: String? = null,
    encode: (String) -> String,
): String {
    val query = listOfNotNull(
        projectId?.let { "projectId=${encode(it)}" },
        if (focusInput) "focusInput=true" else null,
        sourceMessageId?.let { "sourceMessageId=${encode(it)}" },
    )
    return if (query.isEmpty()) "" else "?${query.joinToString("&")}"
}

internal fun projectWorkbenchTarget(
    projectId: String,
    destination: ProjectWorkbenchDestination,
    selectedPath: String?,
    requestKey: Int,
): ProjectWorkbenchTarget = ProjectWorkbenchTarget(
    projectId = projectId,
    destination = destination,
    selectedPath = selectedPath,
    requestKey = requestKey,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarnessApkApp(
    incomingAgentBundleUri: Uri? = null,
    onIncomingAgentBundleUriConsumed: () -> Unit = {},
    incomingWikiPackageUri: Uri? = null,
    onIncomingWikiPackageUriConsumed: () -> Unit = {},
    incomingConfigPackageUri: Uri? = null,
    onIncomingConfigPackageUriConsumed: () -> Unit = {},
    incomingRemoteRunId: String? = null,
    onIncomingRemoteRunConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val canGoBack = route != null && route != Routes.Conversations
    var currentProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentProjectName by rememberSaveable { mutableStateOf<String?>(null) }
    var agentImportSourceProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var consumedExternalAgentBundleUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingWikiImportUri by rememberSaveable { mutableStateOf<String?>(null) }
    var wikiImportError by remember { mutableStateOf<String?>(null) }
    var browserWikiTitle by remember { mutableStateOf<String?>(null) }
    var chatSessionConfigRequestKey by remember { mutableStateOf(0) }
    var chatWikiScopeRequestKey by remember { mutableStateOf(0) }
    var chatSearchRequestKey by remember { mutableStateOf(0) }
    var wikiImportPickerRequestKey by remember { mutableIntStateOf(0) }
    var configImportWelcome by remember { mutableStateOf<String?>(null) }
    var workbenchTarget by remember { mutableStateOf<ProjectWorkbenchTarget?>(null) }
    var workbenchRequestKey by rememberSaveable { mutableStateOf(0) }
    var remoteProjectToStart by remember { mutableStateOf<Project?>(null) }
    var remoteRunStartBusy by remember { mutableStateOf(false) }
    var remoteRunStartError by remember { mutableStateOf<String?>(null) }
    val isHomeRoute = route == Routes.Conversations || route == null
    val context = LocalContext.current
    val dashboardLaunch = {
        context.startActivity(android.content.Intent(context, com.harnessapk.ui.dashboard.DashboardActivity::class.java))
    }
    val container = (context.applicationContext as HarnessApkApplication).container
    val homeModeStore = container.homeModeStore
    var mainMode by rememberSaveable { mutableStateOf(homeModeStore.mode.value) }
    var themeSourceMode by rememberSaveable { mutableStateOf(homeModeStore.themeSourceMode.value) }
    LaunchedEffect(mainMode, themeSourceMode) {
        if (
            homeModeStore.mode.value != mainMode ||
            homeModeStore.themeSourceMode.value != themeSourceMode
        ) {
            homeModeStore.save(mainMode, themeSourceMode)
        }
    }
    val conversations by container.chatRepository.observeConversations().collectAsState(initial = emptyList())
    val simpleMode by container.settingsStore.simpleMode.collectAsState(initial = false)
    val captureDraft by container.captureDraftRepository.activeDraft.collectAsState()
    val captureTransferState by container.captureImportCoordinator.transferState.collectAsState()
    var captureProjects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var captureActionBusy by remember { mutableStateOf(false) }
    var captureActionError by remember { mutableStateOf<String?>(null) }
    val remoteProfile by container.remoteProfileStore.profile.collectAsState()
    val remoteUiState by container.remoteRepository.state.collectAsState()
    val activityState by container.activityRepository.state.collectAsState(
        initial = com.harnessapk.activity.ActivityState(),
    )
    var updateCheckResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    val currentConversationId = backStackEntry?.arguments?.getString("conversationId")
    val showUpdateBadge = shouldShowUpdateBadge(updateCheckResult)

    LaunchedEffect(captureDraft?.id) {
        if (captureDraft != null) {
            captureProjects = withContext(container.dispatchers.io) {
                container.projectRepository.listProjects()
            }
        }
    }

    fun dispatchAgentPackageImport(event: AgentPackageImportEvent) {
        val transition = reduceAgentPackageImport(
            state = AgentPackageImportState(
                sourceProjectId = agentImportSourceProjectId,
                consumedExternalBundleUri = consumedExternalAgentBundleUri,
            ),
            event = event,
        )
        agentImportSourceProjectId = transition.state.sourceProjectId
        consumedExternalAgentBundleUri = transition.state.consumedExternalBundleUri
        if (transition.navigateToPackages) navController.navigate(Routes.AgentPackages)
    }

    fun dispatchWikiPackageImport(event: WikiPackageImportEvent) {
        val transition = reduceWikiPackageImport(
            state = WikiPackageImportState(pendingUri = pendingWikiImportUri),
            event = event,
        )
        pendingWikiImportUri = transition.state.pendingUri
        wikiImportError = transition.errorMessage
        if (transition.navigateToLibrary) {
            navController.navigate(Routes.WikiLibrary) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(route) {
        dispatchAgentPackageImport(
            AgentPackageImportEvent.RouteChanged(route == Routes.AgentPackages),
        )
        if (route != WikiRoutes.BrowserPattern) browserWikiTitle = null
    }

    LaunchedEffect(Unit) {
        dispatchWikiPackageImport(WikiPackageImportEvent.RestorePendingImport)
    }

    LaunchedEffect(container) {
        val result = runCatching {
            withContext(container.dispatchers.io) {
                container.updateRepository.fetchManifest()
            }
        }.getOrNull()
        updateCheckResult = result
        if (startupUpdateAction(result) == StartupUpdateAction.DOWNLOAD_APK) {
            result?.manifest?.let { manifest ->
                container.updateDownloadCoordinator.startDownload(manifest)
            }
        }
    }

    val title = when (route) {
        Routes.Providers -> "模型配置"
        Routes.Search -> "搜索能力"
        Routes.GlobalSearch -> "全局搜索"
        Routes.Voice -> "语音能力"
        Routes.Git -> "Git / Gitee"
        Routes.Skills -> "技能 / 插件"
        Routes.AgentPackages -> "智能体包"
        Routes.WikiLibrary -> "Wiki 知识库"
        WikiRoutes.BrowserPattern -> browserWikiTitle ?: "Wiki 知识库"
        WikiRoutes.SearchPattern -> "搜索"
        WikiRoutes.SourcePattern -> "原文"
        WikiRoutes.CitationPattern -> "引用原文"
        Routes.Updates -> "更新"
        Routes.ConfigPackageExport -> "配置包"
        Routes.ConfigPackageImportPattern -> "导入配置包"
        Routes.RemoteSettings -> "Codex 远程节点"
        Routes.RemoteControl -> "远程控制"
        Routes.Activity -> "任务动态"
        Routes.ChatPattern -> chatTopBarTitle(conversations, currentConversationId)
        else -> topLevelTitle(mainMode, currentProjectName)
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(incomingAgentBundleUri) {
        val uri = incomingAgentBundleUri?.toString()
        if (uri == null) {
            dispatchAgentPackageImport(AgentPackageImportEvent.ExternalBundleConsumed)
        } else {
            dispatchAgentPackageImport(
                AgentPackageImportEvent.ExternalBundleReceived(
                    uri = uri,
                    mainMode = mainMode,
                    currentProjectId = currentProjectId,
                ),
            )
        }
    }
    LaunchedEffect(incomingWikiPackageUri) {
        incomingWikiPackageUri?.toString()?.let { uri ->
            dispatchWikiPackageImport(WikiPackageImportEvent.ExternalPackageReceived(uri))
            onIncomingWikiPackageUriConsumed()
        }
    }
    LaunchedEffect(incomingConfigPackageUri) {
        incomingConfigPackageUri?.toString()?.let { uri ->
            navController.navigate(Routes.configPackageImport(uri)) { launchSingleTop = true }
            onIncomingConfigPackageUriConsumed()
        }
    }
    LaunchedEffect(incomingRemoteRunId) {
        incomingRemoteRunId?.let { runId ->
            navController.navigate(Routes.remoteRun(runId)) { launchSingleTop = true }
            onIncomingRemoteRunConsumed()
        }
    }
    val onCreateConversation: () -> Unit = {
        scope.launch {
            navController.navigate(
                Routes.chat(
                    conversationId = container.newConversationUseCase.create(homeConversationRequest()),
                    focusInput = true,
                ),
            )
        }
    }
    fun openWorkbench(
        projectId: String,
        destination: ProjectWorkbenchDestination,
        selectedPath: String? = null,
    ) {
        workbenchRequestKey += 1
        workbenchTarget = projectWorkbenchTarget(
            projectId = projectId,
            destination = destination,
            selectedPath = selectedPath,
            requestKey = workbenchRequestKey,
        )
        themeSourceMode = MainMode.WORK
        mainMode = MainMode.WORK
        navController.popBackStack(Routes.Conversations, inclusive = false)
    }
    fun deliverCaptureToConversation(draftId: String, conversationId: String, projectId: String? = null) {
        scope.launch {
            captureActionBusy = true
            captureActionError = null
            runCatching {
                container.captureImportCoordinator.deliverToConversation(draftId, conversationId)
            }.onSuccess {
                navController.navigate(
                    Routes.chat(conversationId = conversationId, projectId = projectId, focusInput = true),
                )
            }.onFailure { error ->
                captureActionError = error.message ?: "分享内容写入会话失败"
            }
            captureActionBusy = false
        }
    }
    fun createCaptureConversation(draftId: String, project: Project?) {
        scope.launch {
            captureActionBusy = true
            captureActionError = null
            runCatching {
                val request = project?.let { projectConversationRequest(it.id, it.name) } ?: homeConversationRequest()
                val conversationId = container.newConversationUseCase.create(request)
                container.captureImportCoordinator.deliverToConversation(draftId, conversationId)
                conversationId
            }.onSuccess { conversationId ->
                navController.navigate(
                    Routes.chat(
                        conversationId = conversationId,
                        projectId = project?.id,
                        focusInput = true,
                    ),
                )
            }.onFailure { error ->
                captureActionError = error.message ?: "创建分享会话失败"
            }
            captureActionBusy = false
        }
    }
    val effectiveThemeMode = resolveThemeMode(mainMode, themeSourceMode)
    ModeTheme(effectiveThemeMode) {
    Scaffold(
        modifier = Modifier.testTag("theme-${effectiveThemeMode.name}"),
        topBar = {
            if (isHomeRoute) {
                TopAppBar(
                    title = { Text(topLevelTitle(mainMode, currentProjectName)) },
                    actions = {
                        if (mainMode == MainMode.WORK && remoteProfile != null) {
                            TextButton(onClick = { navController.navigate(Routes.RemoteControl) }) {
                                Icon(Icons.Outlined.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("远程")
                            }
                            TextButton(onClick = dashboardLaunch) {
                                Text("副屏")
                            }
                        }
                        if (mainMode == MainMode.LIFE || mainMode == MainMode.WORK) {
                            IconButton(
                                onClick = { navController.navigate(Routes.Activity) },
                                modifier = Modifier.semantics {
                                    contentDescription = "${activityState.pendingCount} 个待处理任务"
                                },
                            ) {
                                BadgedBox(
                                    badge = {
                                        if (activityState.pendingCount > 0) {
                                            Badge { Text(activityState.pendingCount.coerceAtMost(99).toString()) }
                                        }
                                    },
                                ) {
                                    Icon(Icons.Outlined.Notifications, contentDescription = null)
                                }
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (canGoBack) {
                            IconButton(
                                onClick = {
                                    if (route == Routes.RemoteControl && remoteUiState.selectedThreadId != null) {
                                        container.remoteRepository.clearSelection()
                                        return@IconButton
                                    }
                                    when (route) {
                                        Routes.AgentPackages -> {
                                            dispatchAgentPackageImport(
                                                AgentPackageImportEvent.RouteChanged(isAgentPackagesRoute = false),
                                            )
                                        }
                                        Routes.WikiLibrary -> {
                                            dispatchWikiPackageImport(WikiPackageImportEvent.ImportCancelled)
                                        }
                                    }
                                    navController.popBackStack()
                                },
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                    actions = {
                        when (route) {
                            Routes.ChatPattern -> {
                                IconButton(onClick = { chatSearchRequestKey += 1 }) {
                                    Icon(Icons.Outlined.Search, contentDescription = "查找消息")
                                }
                                ConversationWikiTopBarAction(
                                    onClick = { chatWikiScopeRequestKey += 1 },
                                )
                                IconButton(onClick = { chatSessionConfigRequestKey += 1 }) {
                                    Icon(Icons.Outlined.Settings, contentDescription = "会话配置")
                                }
                            }
                            Routes.WikiLibrary -> {
                                IconButton(onClick = { wikiImportPickerRequestKey += 1 }) {
                                    Icon(Icons.Filled.Add, contentDescription = "导入 Wiki 知识库")
                                }
                            }
                            WikiRoutes.BrowserPattern -> {
                                val wikiRef = wikiRouteRef(backStackEntry)
                                if (wikiRef != null) {
                                    IconButton(onClick = { navController.navigate(WikiRoutes.search(wikiRef)) }) {
                                        Icon(Icons.Outlined.Search, contentDescription = "搜索原文")
                                    }
                                }
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (isHomeRoute) {
                NavigationBar {
                    MainMode.entries.forEach { mode ->
                        NavigationBarItem(
                            selected = mainMode == mode,
                            onClick = {
                                themeSourceMode = nextThemeSource(themeSourceMode, mode)
                                mainMode = mode
                            },
                            modifier = Modifier.testTag("nav-${mode.name}"),
                            icon = { Icon(homeModeIcon(mode), contentDescription = null) },
                            label = { Text(mode.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Conversations,
        ) {
            composable(Routes.Conversations) {
                when (mainMode) {
                    MainMode.LIFE -> ConversationListScreen(
                        container = container,
                        contentPadding = padding,
                        onOpenChat = { navController.navigate(Routes.chat(it)) },
                        onCreateConversation = onCreateConversation,
                        onOpenAgentPackages = { navController.navigate(Routes.AgentPackages) },
                        onOpenWikiLibrary = { navController.navigate(Routes.WikiLibrary) },
                        onOpenGlobalSearch = { navController.navigate(Routes.GlobalSearch) },
                        welcomeMessage = configImportWelcome,
                        onWelcomeDismissed = { configImportWelcome = null },
                    )
                    MainMode.WORK -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = padding.calculateTopPadding()),
                    ) {
                        ProjectScreen(
                            container = container,
                            contentPadding = PaddingValues(
                                start = padding.calculateStartPadding(LocalLayoutDirection.current),
                                end = padding.calculateEndPadding(LocalLayoutDirection.current),
                                bottom = padding.calculateBottomPadding(),
                            ),
                            onCurrentProjectChange = { project ->
                                currentProjectId = project?.id
                                currentProjectName = project?.name
                            },
                            workbenchTarget = workbenchTarget,
                            onWorkbenchTargetConsumed = { requestKey ->
                                if (workbenchTarget?.requestKey == requestKey) workbenchTarget = null
                            },
                            onCreateSession = { project ->
                                scope.launch {
                                    val request = projectConversationRequest(project.id, project.name)
                                    val conversationId = container.newConversationUseCase.create(request)
                                    navController.navigate(
                                        Routes.chat(
                                            conversationId = conversationId,
                                            projectId = project.id,
                                            focusInput = true,
                                        ),
                                    )
                                }
                            },
                            onOpenSession = { conversationId ->
                                navController.navigate(Routes.chat(conversationId = conversationId))
                            },
                            onStartRemoteRun = { project ->
                                remoteRunStartError = null
                                remoteProjectToStart = project
                            },
                            onOpenRemoteRun = { runId -> navController.navigate(Routes.remoteRun(runId)) },
                            onOpenGlobalSearch = { navController.navigate(Routes.GlobalSearch) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    MainMode.ME -> SettingsScreen(
                        contentPadding = padding,
                        onOpenProviders = { navController.navigate(Routes.Providers) },
                        onOpenSearch = { navController.navigate(Routes.Search) },
                        onOpenVoice = { navController.navigate(Routes.Voice) },
                        onOpenGit = { navController.navigate(Routes.Git) },
                        onOpenSkills = { navController.navigate(Routes.Skills) },
                        onOpenAgentPackages = {
                            dispatchAgentPackageImport(AgentPackageImportEvent.SettingsOpened)
                            navController.navigate(Routes.AgentPackages)
                        },
                        onOpenWikiLibrary = { navController.navigate(Routes.WikiLibrary) },
                        onOpenUpdates = { navController.navigate(Routes.Updates) },
                        onOpenRemote = { navController.navigate(Routes.RemoteSettings) },
                        onOpenConfigPackage = { navController.navigate(Routes.ConfigPackageExport) },
                        simpleMode = simpleMode,
                        onSimpleModeChange = { value ->
                            scope.launch { container.settingsStore.setSimpleMode(value) }
                        },
                        showUpdateBadge = showUpdateBadge,
                    )
                }
            }
            composable(
                route = Routes.ChatPattern,
                arguments = listOf(
                    navArgument("conversationId") { type = NavType.StringType },
                    navArgument("projectId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("focusInput") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                    navArgument("sourceMessageId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val voiceInput = rememberVoiceInput(container)
                ChatScreen(
                    container = container,
                    conversationId = entry.arguments?.getString("conversationId").orEmpty(),
                    initialProjectId = entry.arguments?.getString("projectId"),
                    autoFocusInput = entry.arguments?.getBoolean("focusInput") == true,
                    sessionConfigRequestKey = chatSessionConfigRequestKey,
                    onSessionConfigRequestConsumed = { chatSessionConfigRequestKey = 0 },
                    wikiScopeRequestKey = chatWikiScopeRequestKey,
                    onWikiScopeRequestConsumed = { chatWikiScopeRequestKey = 0 },
                    searchRequestKey = chatSearchRequestKey,
                    onSearchRequestConsumed = { chatSearchRequestKey = 0 },
                    onOpenProjectFiles = { projectId, path ->
                        openWorkbench(projectId, ProjectWorkbenchDestination.FILES, path)
                    },
                    onOpenProjectGit = { projectId ->
                        openWorkbench(projectId, ProjectWorkbenchDestination.GIT)
                    },
                    onContinueInProject = { continuedConversationId, projectId ->
                        navController.navigate(
                            Routes.chat(
                                conversationId = continuedConversationId,
                                projectId = projectId,
                                focusInput = true,
                            ),
                        )
                    },
                    initialSourceMessageId = entry.arguments?.getString("sourceMessageId"),
                    onOpenConversationMessage = { sourceConversationId, sourceMessageId ->
                        navController.navigate(
                            Routes.chat(
                                conversationId = sourceConversationId,
                                sourceMessageId = sourceMessageId,
                            ),
                        ) {
                            launchSingleTop = true
                        }
                    },
                    onOpenWikiCitation = { citationId ->
                        navController.navigate(WikiRoutes.citation(citationId))
                    },
                    voiceInputState = voiceInput.state,
                    onStartVoiceInput = voiceInput.start,
                    onStopVoiceInput = voiceInput.stop,
                    onVoiceInputConsumed = voiceInput.consume,
                    contentPadding = padding,
                )
            }
            composable(Routes.ConfigPackageExport) {
                ConfigPackageExportScreen(
                    container = container,
                    contentPadding = padding,
                    onOpenImport = { navController.navigate(Routes.configPackageImport(uri = null)) },
                )
            }
            composable(
                route = Routes.ConfigPackageImportPattern,
                arguments = listOf(
                    navArgument("uri") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                ConfigPackageImportScreen(
                    container = container,
                    contentPadding = padding,
                    packageUri = entry.arguments?.getString("uri"),
                    onApplied = { message ->
                        configImportWelcome = message
                        navController.popBackStack(Routes.Conversations, inclusive = false)
                    },
                )
            }
            composable(Routes.Providers) {
                ProviderSettingsScreen(container = container, contentPadding = padding)
            }
            composable(Routes.Search) {
                SearchSettingsScreen(container = container, contentPadding = padding)
            }
            composable(Routes.Voice) {
                VoiceSettingsScreen(container = container, contentPadding = padding)
            }
            composable(Routes.Git) {
                GitSettingsScreen(container = container, contentPadding = padding)
            }
            composable(Routes.Skills) {
                SkillsScreen(container = container, contentPadding = padding)
            }
            composable(Routes.AgentPackages) {
                AgentPackagesScreen(
                    container = container,
                    contentPadding = padding,
                    sourceProjectId = agentImportSourceProjectId,
                    externalImportUri = incomingAgentBundleUri,
                    onExternalImportConsumed = onIncomingAgentBundleUriConsumed,
                    onStartConversation = { agent, sourceProjectId ->
                        val request = installedAgentConversationRequest(agent, sourceProjectId)
                        dispatchAgentPackageImport(AgentPackageImportEvent.StartConversation)
                        scope.launch {
                            val conversationId = container.newConversationUseCase.create(request)
                            navController.navigate(
                                Routes.chat(
                                    conversationId = conversationId,
                                    projectId = request.projectId,
                                    focusInput = true,
                                ),
                            )
                        }
                    },
                )
            }
            composable(Routes.WikiLibrary) {
                WikiLibraryScreen(
                    container = container,
                    contentPadding = padding,
                    pendingImportUri = pendingWikiImportUri,
                    importError = wikiImportError,
                    importRequestKey = wikiImportPickerRequestKey,
                    onImportRequestConsumed = { wikiImportPickerRequestKey = 0 },
                    onPickerPackageSelected = { uri ->
                        dispatchWikiPackageImport(WikiPackageImportEvent.PickerPackageSelected(uri))
                    },
                    onImportCancelled = {
                        dispatchWikiPackageImport(WikiPackageImportEvent.ImportCancelled)
                    },
                    onImportRejected = { message ->
                        dispatchWikiPackageImport(WikiPackageImportEvent.ImportRejected(message))
                    },
                    onImportCompleted = {
                        dispatchWikiPackageImport(WikiPackageImportEvent.ImportCompleted)
                    },
                    onOpenBrowser = { ref -> navController.navigate(WikiRoutes.browser(ref)) },
                )
            }
            composable(
                route = WikiRoutes.BrowserPattern,
                arguments = wikiRefNavArguments(),
            ) { entry ->
                val wikiRef = wikiRouteRef(entry)
                if (wikiRef == null) {
                    WikiRecoveryState("Wiki 路由参数无效，请返回知识库重新选择。", Modifier.padding(padding))
                } else {
                    WikiBrowserScreen(
                        container = container,
                        ref = wikiRef,
                        contentPadding = padding,
                        onOpenSource = { chunkId -> navController.navigate(WikiRoutes.source(wikiRef, chunkId)) },
                        onTitleLoaded = { title -> browserWikiTitle = title },
                        onUseInNewConversation = {
                            val projectId = currentProjectId
                            val conversationId = container.newConversationUseCase.create(
                                title = "新会话",
                                projectId = projectId,
                                identity = InitialConversationIdentity.Assistant,
                                wikiScope = listOf(wikiRef),
                            )
                            navController.navigate(
                                Routes.chat(
                                    conversationId = conversationId,
                                    projectId = projectId,
                                    focusInput = true,
                                ),
                            )
                        },
                    )
                }
            }
            composable(
                route = WikiRoutes.SearchPattern,
                arguments = wikiRefNavArguments(),
            ) { entry ->
                val wikiRef = wikiRouteRef(entry)
                if (wikiRef == null) {
                    WikiRecoveryState("Wiki 路由参数无效，请返回知识库重新选择。", Modifier.padding(padding))
                } else {
                    WikiSearchScreen(
                        container = container,
                        ref = wikiRef,
                        contentPadding = padding,
                        onOpenSource = { chunkId -> navController.navigate(WikiRoutes.source(wikiRef, chunkId)) },
                    )
                }
            }
            composable(
                route = WikiRoutes.CitationPattern,
                arguments = listOf(navArgument("citationId") { type = NavType.StringType }),
            ) { entry ->
                val citationId = WikiRoutes.decodeCitationId(entry.arguments?.getString("citationId"))
                if (citationId == null) {
                    WikiRecoveryState("引用路由参数无效，请返回会话重新打开。", Modifier.padding(padding))
                } else {
                    WikiCitationSourceScreen(
                        container = container,
                        citationId = citationId,
                        contentPadding = padding,
                        onOpenSource = { ref, chunkId ->
                            navController.navigate(WikiRoutes.source(ref, chunkId))
                        },
                    )
                }
            }
            composable(
                route = WikiRoutes.SourcePattern,
                arguments = wikiRefNavArguments() + navArgument("chunkId") { type = NavType.StringType },
            ) { entry ->
                val wikiRef = wikiRouteRef(entry)
                val chunkId = WikiRoutes.decodeChunkId(entry.arguments?.getString("chunkId"))
                if (wikiRef == null || chunkId == null) {
                    WikiRecoveryState("原文路由参数无效，请返回知识库重新选择。", Modifier.padding(padding))
                } else {
                    WikiSourceReaderScreen(
                        container = container,
                        ref = wikiRef,
                        chunkId = chunkId,
                        contentPadding = padding,
                        onOpenSource = { nextChunkId ->
                            navController.navigate(WikiRoutes.source(wikiRef, nextChunkId)) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }
            composable(Routes.Updates) {
                UpdateSettingsScreen(
                    container = container,
                    contentPadding = padding,
                    initialResult = updateCheckResult,
                )
            }
            composable(Routes.RemoteSettings) {
                RemoteSettingsScreen(container = container, contentPadding = padding)
            }
            composable(Routes.RemoteControl) {
                RemoteScreen(container = container, contentPadding = padding)
            }
            composable(Routes.Activity) {
                ActivityScreen(
                    container = container,
                    contentPadding = padding,
                    onOpenChat = { navController.navigate(Routes.chat(it)) },
                    onOpenRun = { navController.navigate(Routes.remoteRun(it)) },
                )
            }
            composable(
                route = Routes.RemoteRunPattern,
                arguments = listOf(navArgument("runId") { type = NavType.StringType }),
            ) { entry ->
                RunDetailScreen(
                    container = container,
                    runId = entry.arguments?.getString("runId").orEmpty(),
                    contentPadding = padding,
                    onBack = navController::popBackStack,
                )
            }
            composable(Routes.GlobalSearch) {
                GlobalSearchScreen(
                    container = container,
                    contentPadding = padding,
                    onOpenMessage = { conversationId, messageId ->
                        navController.navigate(
                            Routes.chat(
                                conversationId = conversationId,
                                sourceMessageId = messageId,
                            ),
                        )
                    },
                    onOpenConversation = { conversationId ->
                        navController.navigate(Routes.chat(conversationId))
                    },
                    onOpenProject = { projectId ->
                        openWorkbench(projectId, ProjectWorkbenchDestination.CONVERSATIONS)
                    },
                )
            }
        }
    }
    CaptureTransferOverlay(
        state = captureTransferState,
        onDismissError = container.captureImportCoordinator::clearTransferError,
    )
    remoteProjectToStart?.let { project ->
        RemoteRunObjectiveSheet(
            projectName = project.name,
            busy = remoteRunStartBusy,
            errorMessage = remoteRunStartError,
            onDismiss = {
                if (!remoteRunStartBusy) remoteProjectToStart = null
            },
            onSend = { objective ->
                scope.launch {
                    remoteRunStartBusy = true
                    remoteRunStartError = null
                    runCatching {
                        val binding = withContext(container.dispatchers.io) {
                            requireNotNull(
                                container.remoteBindingRepository.bindingForProject(
                                    project.id,
                                    remoteUiState.selectedBackendId,
                                ),
                            ) {
                                "项目尚未绑定 Mac 工作区"
                            }
                        }
                        container.remoteRunLauncher.launch(project, binding, objective)
                    }.onSuccess { launched ->
                        remoteProjectToStart = null
                        RemoteConnectionService.start(context)
                        navController.navigate(Routes.remoteRun(launched.run.id))
                        scope.launch(container.dispatchers.io) {
                            container.remoteTransport.flush()
                        }
                    }.onFailure { error ->
                        remoteRunStartError = error.message ?: "任务排队失败"
                    }
                    remoteRunStartBusy = false
                }
            },
        )
    }
    captureDraft?.let { draft ->
        CaptureDestinationSheet(
            draft = draft,
            conversations = conversations,
            projects = captureProjects,
            busy = captureActionBusy,
            errorMessage = captureActionError,
            onConversation = { conversationId ->
                deliverCaptureToConversation(draft.id, conversationId)
            },
            onProjectConversation = { project -> createCaptureConversation(draft.id, project) },
            onImportToProject = { projectId ->
                scope.launch {
                    captureActionBusy = true
                    captureActionError = null
                    runCatching {
                        container.captureImportCoordinator.importFilesToProject(draft.id, projectId)
                    }.onSuccess { paths ->
                        openWorkbench(projectId, ProjectWorkbenchDestination.FILES, paths.firstOrNull())
                    }.onFailure { error ->
                        captureActionError = error.message ?: "导入项目文件失败"
                    }
                    captureActionBusy = false
                }
            },
            onNewConversation = { createCaptureConversation(draft.id, null) },
            onDiscard = {
                scope.launch { container.captureImportCoordinator.discard(draft.id) }
            },
        )
    }
    }
}

private fun wikiRefNavArguments() = listOf(
    navArgument("wikiId") { type = NavType.StringType },
    navArgument("version") { type = NavType.StringType },
)

private fun wikiRouteRef(entry: androidx.navigation.NavBackStackEntry?): com.harnessapk.wiki.WikiRef? =
    WikiRoutes.decodeRef(
        wikiId = entry?.arguments?.getString("wikiId"),
        version = entry?.arguments?.getString("version"),
    )

internal fun chatTopBarTitle(
    conversations: List<Conversation>,
    conversationId: String?,
): String = conversations
    .firstOrNull { it.id == conversationId }
    ?.title
    ?.takeIf { it.isNotBlank() }
    ?: "对话"
