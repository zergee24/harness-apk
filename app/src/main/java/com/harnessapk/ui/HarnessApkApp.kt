package com.harnessapk.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Settings
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
import com.harnessapk.chat.Conversation
import com.harnessapk.ui.agent.AgentScreen
import com.harnessapk.ui.chat.ChatScreen
import com.harnessapk.ui.components.WarmSegmentedControl
import com.harnessapk.ui.conversation.ConversationListScreen
import com.harnessapk.ui.git.GitSettingsScreen
import com.harnessapk.ui.project.ProjectWorkbenchDestination
import com.harnessapk.ui.project.ProjectScreen
import com.harnessapk.ui.project.ProjectWorkbenchTarget
import com.harnessapk.ui.project.projectSessionTitle
import com.harnessapk.ui.provider.ProviderSettingsScreen
import com.harnessapk.ui.search.SearchSettingsScreen
import com.harnessapk.ui.settings.SettingsScreen
import com.harnessapk.ui.skills.SkillsScreen
import com.harnessapk.ui.updater.StartupUpdateAction
import com.harnessapk.ui.updater.UpdateSettingsScreen
import com.harnessapk.ui.updater.startupUpdateAction
import com.harnessapk.ui.voice.VoiceSettingsScreen
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
    const val Updates = "updates"
    const val ChatPattern = "chat/{conversationId}?projectId={projectId}&focusInput={focusInput}"

    fun chat(
        conversationId: String,
        projectId: String? = null,
        focusInput: Boolean = false,
    ): String = buildString {
        append("chat/")
        append(Uri.encode(conversationId))
        append(chatRouteQuery(projectId = projectId, focusInput = focusInput, encode = Uri::encode))
    }
}

internal fun chatRouteQuery(
    projectId: String?,
    focusInput: Boolean,
    encode: (String) -> String,
): String {
    val query = listOfNotNull(
        projectId?.let { "projectId=${encode(it)}" },
        if (focusInput) "focusInput=true" else null,
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
fun HarnessApkApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val canGoBack = route != null && route != Routes.Conversations
    var mainMode by rememberSaveable { mutableStateOf(MainMode.SESSION) }
    var currentProjectName by rememberSaveable { mutableStateOf<String?>(null) }
    var chatSessionConfigRequestKey by remember { mutableStateOf(0) }
    var agentImportRequestKey by remember { mutableStateOf(0) }
    var workbenchTarget by remember { mutableStateOf<ProjectWorkbenchTarget?>(null) }
    var workbenchRequestKey by rememberSaveable { mutableStateOf(0) }
    val isHomeRoute = route == Routes.Conversations || route == null
    val container = (LocalContext.current.applicationContext as HarnessApkApplication).container
    val conversations by container.chatRepository.observeConversations().collectAsState(initial = emptyList())
    var updateCheckResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    val currentConversationId = backStackEntry?.arguments?.getString("conversationId")
    val showUpdateBadge = shouldShowUpdateBadge(updateCheckResult)

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
        Routes.Updates -> "更新"
        Routes.ChatPattern -> chatTopBarTitle(conversations, currentConversationId)
        else -> topLevelTitle(mainMode, currentProjectName)
    }
    val scope = rememberCoroutineScope()
    val onCreateConversation: () -> Unit = {
        scope.launch {
            navController.navigate(
                Routes.chat(
                    conversationId = container.chatRepository.createConversation(),
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
            TopAppBar(
                title = {
                    if (isHomeRoute) {
                        ModeSwitcher(
                            mode = mainMode,
                            onModeChange = { mainMode = it },
                        )
                    } else {
                        Text(title)
                    }
                },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (isHomeRoute) {
                        HomeTopBarActions(
                            primaryAction = homePrimaryAction(mainMode),
                            showUpdateBadge = showUpdateBadge,
                            onCreateConversation = onCreateConversation,
                            onImportAgent = { agentImportRequestKey += 1 },
                            onOpenSettings = { navController.navigate(Routes.Settings) },
                        )
                    } else if (route == Routes.ChatPattern) {
                        IconButton(onClick = { chatSessionConfigRequestKey += 1 }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "会话配置")
                        }
                    }
                },
            )
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
                        onCurrentProjectNameChange = { currentProjectName = it },
                        workbenchTarget = workbenchTarget,
                        onWorkbenchTargetConsumed = { requestKey ->
                            if (workbenchTarget?.requestKey == requestKey) workbenchTarget = null
                        },
                        onCreateSession = { project ->
                            scope.launch {
                                val conversationId = container.chatRepository.createConversation(
                                    title = projectSessionTitle(project.name, null),
                                    projectId = project.id,
                                )
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
                    MainMode.AGENT -> AgentScreen(
                        container = container,
                        contentPadding = padding,
                        importRequestKey = agentImportRequestKey,
                        onImportRequestConsumed = { agentImportRequestKey = 0 },
                        onStartConversation = { agent ->
                            scope.launch {
                                val conversationId = container.chatRepository.createConversation(
                                    title = agent.name,
                                    agentId = agent.id,
                                    agentVersion = agent.activeVersion,
                                )
                                navController.navigate(
                                    Routes.chat(
                                        conversationId = conversationId,
                                        focusInput = true,
                                    ),
                                )
                            }
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

internal fun chatTopBarTitle(
    conversations: List<Conversation>,
    conversationId: String?,
): String = conversations
    .firstOrNull { it.id == conversationId }
    ?.title
    ?.takeIf { it.isNotBlank() }
    ?: "对话"

@Composable
private fun ModeSwitcher(
    mode: MainMode,
    onModeChange: (MainMode) -> Unit,
) {
    WarmSegmentedControl(
        options = MainMode.entries.map { it.label },
        selectedIndex = MainMode.entries.indexOf(mode),
        onSelected = { index -> onModeChange(MainMode.entries[index]) },
    )
}

@Composable
private fun HomeTopBarActions(
    primaryAction: HomePrimaryAction,
    showUpdateBadge: Boolean,
    onCreateConversation: () -> Unit,
    onImportAgent: () -> Unit,
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
        val isImport = primaryAction == HomePrimaryAction.IMPORT_AGENT
        FilledIconButton(onClick = if (isImport) onImportAgent else onCreateConversation) {
            Icon(
                Icons.Filled.Add,
                contentDescription = if (isImport) "导入智能体" else "新建对话",
            )
        }
    }
}
