package com.harnessapk.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.harnessapk.ui.agent.AgentPackagesScreen
import com.harnessapk.ui.chat.ChatScreen
import com.harnessapk.ui.components.WarmSegmentedControl
import com.harnessapk.ui.conversation.ConversationListScreen
import com.harnessapk.ui.git.GitSettingsScreen
import com.harnessapk.ui.project.ProjectWorkbenchDestination
import com.harnessapk.ui.project.ProjectScreen
import com.harnessapk.ui.project.ProjectWorkbenchTarget
import com.harnessapk.ui.provider.ProviderSettingsScreen
import com.harnessapk.ui.search.SearchSettingsScreen
import com.harnessapk.ui.settings.SettingsScreen
import com.harnessapk.ui.skills.SkillsScreen
import com.harnessapk.ui.updater.StartupUpdateAction
import com.harnessapk.ui.updater.UpdateSettingsScreen
import com.harnessapk.ui.updater.startupUpdateAction
import com.harnessapk.ui.voice.VoiceSettingsScreen
import com.harnessapk.ui.wiki.WikiLibraryScreen
import com.harnessapk.ui.wiki.WikiBrowserScreen
import com.harnessapk.ui.wiki.WikiCitationSourceScreen
import com.harnessapk.ui.wiki.WikiRecoveryState
import com.harnessapk.ui.wiki.WikiRoutes
import com.harnessapk.ui.wiki.WikiSearchScreen
import com.harnessapk.ui.wiki.WikiSourceReaderScreen
import com.harnessapk.updater.UpdateCheckResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Routes {
    const val Conversations = "conversations"
    const val Settings = "settings"
    const val Providers = "providers"
    const val Search = "search"
    const val Voice = "voice"
    const val Git = "git"
    const val Skills = "skills"
    const val AgentPackages = "agent-packages"
    const val WikiLibrary = WikiRoutes.Library
    const val Updates = "updates"
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
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val canGoBack = route != null && route != Routes.Conversations
    var mainMode by rememberSaveable { mutableStateOf(MainMode.SESSION) }
    var currentProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentProjectName by rememberSaveable { mutableStateOf<String?>(null) }
    var agentImportSourceProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var consumedExternalAgentBundleUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingWikiImportUri by rememberSaveable { mutableStateOf<String?>(null) }
    var wikiImportError by remember { mutableStateOf<String?>(null) }
    var browserWikiTitle by remember { mutableStateOf<String?>(null) }
    var chatSessionConfigRequestKey by remember { mutableStateOf(0) }
    var wikiImportPickerRequestKey by remember { mutableIntStateOf(0) }
    var workbenchTarget by remember { mutableStateOf<ProjectWorkbenchTarget?>(null) }
    var workbenchRequestKey by rememberSaveable { mutableStateOf(0) }
    val isHomeRoute = route == Routes.Conversations || route == null
    val container = (LocalContext.current.applicationContext as HarnessApkApplication).container
    val conversations by container.chatRepository.observeConversations().collectAsState(initial = emptyList())
    var updateCheckResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    val currentConversationId = backStackEntry?.arguments?.getString("conversationId")
    val showUpdateBadge = shouldShowUpdateBadge(updateCheckResult)

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
        Routes.Settings -> "设置"
        Routes.Providers -> "模型配置"
        Routes.Search -> "搜索能力"
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
        mainMode = MainMode.PROJECT
        navController.popBackStack(Routes.Conversations, inclusive = false)
    }
    Scaffold(
        topBar = {
            if (isHomeRoute) {
                HomeTopBar(
                    mode = mainMode,
                    onModeChange = { mainMode = it },
                    primaryAction = homePrimaryAction(mainMode),
                    showUpdateBadge = showUpdateBadge,
                    onCreateConversation = onCreateConversation,
                    onOpenSettings = { navController.navigate(Routes.Settings) },
                )
            } else {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (canGoBack) {
                            IconButton(
                                onClick = {
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
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Conversations,
        ) {
            composable(Routes.Conversations) {
                when (mainMode) {
                    MainMode.SESSION -> ConversationListScreen(
                        container = container,
                        contentPadding = padding,
                        onOpenChat = { navController.navigate(Routes.chat(it)) },
                        onCreateConversation = onCreateConversation,
                    )
                    MainMode.PROJECT -> ProjectScreen(
                        container = container,
                        contentPadding = padding,
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
                ChatScreen(
                    container = container,
                    conversationId = entry.arguments?.getString("conversationId").orEmpty(),
                    initialProjectId = entry.arguments?.getString("projectId"),
                    autoFocusInput = entry.arguments?.getBoolean("focusInput") == true,
                    sessionConfigRequestKey = chatSessionConfigRequestKey,
                    onSessionConfigRequestConsumed = { chatSessionConfigRequestKey = 0 },
                    onOpenProjectFiles = { projectId, path ->
                        openWorkbench(projectId, ProjectWorkbenchDestination.FILES, path)
                    },
                    onOpenProjectGit = { projectId ->
                        openWorkbench(projectId, ProjectWorkbenchDestination.GIT)
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
                    contentPadding = padding,
                )
            }
            composable(Routes.Providers) {
                ProviderSettingsScreen(container = container, contentPadding = padding)
            }
            composable(Routes.Settings) {
                SettingsScreen(
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
                    showUpdateBadge = showUpdateBadge,
                )
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

@Composable
private fun HomeTopBar(
    mode: MainMode,
    onModeChange: (MainMode) -> Unit,
    primaryAction: HomePrimaryAction,
    showUpdateBadge: Boolean,
    onCreateConversation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(64.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSwitcher(
            mode = mode,
            onModeChange = onModeChange,
            modifier = Modifier
                .weight(1f, fill = false)
                .widthIn(max = 216.dp),
        )
        HomeTopBarActions(
            primaryAction = primaryAction,
            showUpdateBadge = showUpdateBadge,
            onCreateConversation = onCreateConversation,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun ModeSwitcher(
    mode: MainMode,
    onModeChange: (MainMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    WarmSegmentedControl(
        options = MainMode.entries.map { it.label },
        selectedIndex = MainMode.entries.indexOf(mode),
        onSelected = { index -> onModeChange(MainMode.entries[index]) },
        modifier = modifier,
    )
}

@Composable
private fun HomeTopBarActions(
    primaryAction: HomePrimaryAction,
    showUpdateBadge: Boolean,
    onCreateConversation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box {
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = "设置")
        }
        if (showUpdateBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(9.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
            )
        }
    }
    if (primaryAction != HomePrimaryAction.NONE) {
        FilledIconButton(onClick = onCreateConversation) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "新建对话",
            )
        }
    }
}
