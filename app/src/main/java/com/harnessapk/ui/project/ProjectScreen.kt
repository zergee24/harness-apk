package com.harnessapk.ui.project

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.harnessapk.chat.Conversation
import com.harnessapk.common.AppContainer
import com.harnessapk.common.toUserMessage
import com.harnessapk.git.GitBranchSummary
import com.harnessapk.git.GitCloneRequest
import com.harnessapk.git.GitStatusSummary
import com.harnessapk.markdownpdf.AndroidMarkdownPdfWriter
import com.harnessapk.project.ProjectArtifactType
import com.harnessapk.project.Project
import com.harnessapk.project.ProjectDeliverable
import com.harnessapk.ui.components.ActionableEmptyState
import com.harnessapk.ui.components.ComfortListRow
import com.harnessapk.ui.components.InlineStatusMessage
import com.harnessapk.ui.components.StatusTone
import com.harnessapk.ui.markdown.MarkdownMessage
import com.harnessapk.ui.theme.HarnessSpacing
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ProjectDeliverableRefresh(
    val generation: Int,
    val projectId: String,
    val preferredPath: String?,
    val query: String,
    val filter: ProjectArtifactFilter,
)

internal class ProjectDeliverableRefreshController {
    private var currentGeneration = 0
    private var ordinaryRefreshProjectIdToSkip: String? = null

    fun acceptWorkbenchTarget(
        target: ProjectWorkbenchTarget,
        selectedProjectId: String?,
        currentSearchQuery: String = "",
    ): ProjectDeliverableRefresh? {
        currentGeneration += 1
        ordinaryRefreshProjectIdToSkip = null
        if (target.destination != ProjectWorkbenchDestination.FILES) return null

        if (selectedProjectId != target.projectId || currentSearchQuery.isNotEmpty()) {
            ordinaryRefreshProjectIdToSkip = target.projectId
        }
        return ProjectDeliverableRefresh(
            generation = currentGeneration,
            projectId = target.projectId,
            preferredPath = target.selectedPath,
            query = "",
            filter = ProjectArtifactFilter.ALL,
        )
    }

    fun beginFilesRefresh(
        projectId: String,
        preferredPath: String?,
        query: String,
        filter: ProjectArtifactFilter,
    ): ProjectDeliverableRefresh {
        currentGeneration += 1
        return ProjectDeliverableRefresh(
            generation = currentGeneration,
            projectId = projectId,
            preferredPath = preferredPath,
            query = query,
            filter = filter,
        )
    }

    fun beginOrdinaryFilesRefresh(
        projectId: String,
        query: String,
        filter: ProjectArtifactFilter,
    ): ProjectDeliverableRefresh? {
        if (ordinaryRefreshProjectIdToSkip == projectId) {
            ordinaryRefreshProjectIdToSkip = null
            return null
        }
        return beginFilesRefresh(
            projectId = projectId,
            preferredPath = null,
            query = query,
            filter = filter,
        )
    }

    fun canPublish(refresh: ProjectDeliverableRefresh): Boolean =
        refresh.generation == currentGeneration

    fun invalidate() {
        currentGeneration += 1
        ordinaryRefreshProjectIdToSkip = null
    }
}

internal data class ProjectGitRefresh(
    val generation: Int,
    val projectId: String,
)

internal class ProjectGitRefreshController {
    private var currentGeneration = 0

    fun begin(projectId: String?): ProjectGitRefresh? {
        currentGeneration += 1
        return projectId?.let { ProjectGitRefresh(currentGeneration, it) }
    }

    fun canPublish(refresh: ProjectGitRefresh, selectedProjectId: String?): Boolean =
        refresh.generation == currentGeneration && refresh.projectId == selectedProjectId
}

internal fun selectedDeliverableIdForRefresh(
    preferredPath: String?,
    currentSelectedDeliverableId: String?,
    filteredDeliverables: List<ProjectDeliverable>,
): String? = when {
    preferredPath != null && filteredDeliverables.any { it.id == preferredPath } -> preferredPath
    currentSelectedDeliverableId != null && filteredDeliverables.any { it.id == currentSelectedDeliverableId } ->
        currentSelectedDeliverableId
    else -> filteredDeliverables.firstOrNull()?.id
}

internal fun shouldRefreshGitOnTabSelection(tab: ProjectWorkbenchTab): Boolean =
    tab == ProjectWorkbenchTab.GIT

internal fun shouldRefreshGitForProjectSelection(tab: ProjectWorkbenchTab, projectId: String?): Boolean =
    shouldRefreshGitOnTabSelection(tab) && projectId != null

internal fun shouldShowProjectWorkbenchTabGuidance(
    tab: ProjectWorkbenchTab,
    conversationsEmpty: Boolean,
    deliverablesEmpty: Boolean,
): Boolean = when (tab) {
    ProjectWorkbenchTab.CONVERSATIONS -> conversationsEmpty
    ProjectWorkbenchTab.FOLDER -> deliverablesEmpty
    ProjectWorkbenchTab.GIT -> false
}

internal data class ProjectWorkbenchContent(
    val deliverables: List<ProjectDeliverable>,
    val selectedDeliverableId: String?,
    val artifactText: String,
    val gitStatus: GitStatusSummary?,
    val gitBranches: List<GitBranchSummary>,
)

internal fun clearedProjectWorkbenchContent(): ProjectWorkbenchContent = ProjectWorkbenchContent(
    deliverables = emptyList(),
    selectedDeliverableId = null,
    artifactText = "",
    gitStatus = null,
    gitBranches = emptyList(),
)

internal fun projectWorkbenchContentForProjectSelection(
    currentProjectId: String?,
    nextProjectId: String?,
    currentContent: ProjectWorkbenchContent,
): ProjectWorkbenchContent = if (currentProjectId == nextProjectId) {
    currentContent
} else {
    clearedProjectWorkbenchContent()
}

@Composable
internal fun ProjectScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onCurrentProjectNameChange: (String?) -> Unit,
    workbenchTarget: ProjectWorkbenchTarget? = null,
    onWorkbenchTargetConsumed: (requestKey: Int) -> Unit = {},
    onCreateSession: (Project) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val markdownPdfWriter = remember { AndroidMarkdownPdfWriter() }
    val conversations by container.chatRepository.observeConversations().collectAsState(initial = emptyList())
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var projectsLoaded by remember { mutableStateOf(false) }
    var deliverables by remember { mutableStateOf<List<ProjectDeliverable>>(emptyList()) }
    val deliverableRefreshController = remember { ProjectDeliverableRefreshController() }
    val gitRefreshController = remember { ProjectGitRefreshController() }
    var selectedProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDeliverableId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(defaultProjectWorkbenchTab()) }
    var artifactText by rememberSaveable { mutableStateOf("") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var artifactFilter by rememberSaveable { mutableStateOf(defaultProjectArtifactFilter()) }
    var collapsedDirectoryPaths by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var statusText by rememberSaveable { mutableStateOf<String?>(null) }
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var showNewProjectDialog by rememberSaveable { mutableStateOf(false) }
    var showCloneRepositoryDialog by rememberSaveable { mutableStateOf(false) }
    var showCommitDialog by rememberSaveable { mutableStateOf(false) }
    var showBranchDialog by rememberSaveable { mutableStateOf(false) }
    var projectToDelete by remember { mutableStateOf<Project?>(null) }
    var gitStatus by remember { mutableStateOf<GitStatusSummary?>(null) }
    var gitBranches by remember { mutableStateOf<List<GitBranchSummary>>(emptyList()) }

    val selectedProject = projects.firstOrNull { it.id == selectedProjectId }
    val visibleDeliverables = filterProjectArtifacts(deliverables, artifactFilter)
    val artifactTree = remember(visibleDeliverables) { buildProjectArtifactTree(visibleDeliverables) }
    val visibleTreeItems = remember(artifactTree, collapsedDirectoryPaths) {
        flattenProjectArtifactTree(artifactTree, collapsedDirectoryPaths)
    }
    val selectedDeliverable = visibleDeliverables.firstOrNull { it.id == selectedDeliverableId }
    val selectedProjectConversations = projectConversations(conversations, selectedProjectId)
    val workbenchOverview = projectWorkbenchOverview(
        conversationCount = selectedProjectConversations.size,
        deliverableCount = deliverables.size,
        gitStatus = gitStatus,
    )

    fun selectProject(projectId: String?, invalidateDeliverableRefresh: Boolean = true) {
        if (selectedProjectId == projectId) return
        val nextContent = projectWorkbenchContentForProjectSelection(
            currentProjectId = selectedProjectId,
            nextProjectId = projectId,
            currentContent = ProjectWorkbenchContent(
                deliverables = deliverables,
                selectedDeliverableId = selectedDeliverableId,
                artifactText = artifactText,
                gitStatus = gitStatus,
                gitBranches = gitBranches,
            ),
        )
        if (invalidateDeliverableRefresh) deliverableRefreshController.invalidate()
        gitRefreshController.begin(null)
        deliverables = nextContent.deliverables
        selectedDeliverableId = nextContent.selectedDeliverableId
        artifactText = nextContent.artifactText
        gitStatus = nextContent.gitStatus
        gitBranches = nextContent.gitBranches
        selectedProjectId = projectId
    }

    fun refreshProjects() {
        scope.launch {
            projects = withContext(container.dispatchers.io) {
                container.projectRepository.listProjects()
            }
            if (selectedProjectId == null || projects.none { it.id == selectedProjectId }) {
                selectProject(projects.firstOrNull()?.id)
            }
            onCurrentProjectNameChange(projects.firstOrNull { it.id == selectedProjectId }?.name)
        }
    }

    fun publishDeliverableRefresh(refresh: ProjectDeliverableRefresh) {
        scope.launch {
            val refreshedDeliverables = withContext(container.dispatchers.io) {
                if (refresh.query.isBlank()) {
                    container.projectRepository.listDeliverables(refresh.projectId)
                } else {
                    container.projectRepository.searchDeliverables(refresh.projectId, refresh.query)
                }
            }
            if (!deliverableRefreshController.canPublish(refresh)) return@launch

            deliverables = refreshedDeliverables
            val filtered = filterProjectArtifacts(deliverables, refresh.filter)
            if (refresh.preferredPath != null && filtered.none { it.id == refresh.preferredPath }) {
                statusText = "文件已写入，请刷新后查看"
            }
            selectedDeliverableId = selectedDeliverableIdForRefresh(
                preferredPath = refresh.preferredPath,
                currentSelectedDeliverableId = selectedDeliverableId,
                filteredDeliverables = filtered,
            )
        }
    }

    fun refreshDeliverables(
        projectId: String? = selectedProjectId,
        preferredPath: String? = null,
        query: String = searchQuery,
        filter: ProjectArtifactFilter = artifactFilter,
    ) {
        val resolvedProjectId = projectId ?: return
        publishDeliverableRefresh(
            deliverableRefreshController.beginFilesRefresh(
                projectId = resolvedProjectId,
                preferredPath = preferredPath,
                query = query,
                filter = filter,
            ),
        )
    }

    fun loadSelectedArtifactText() {
        val projectId = selectedProjectId
        val deliverable = selectedDeliverable
        if (projectId == null || deliverable == null || !deliverable.artifactType.isTextPreviewable) {
            artifactText = ""
            return
        }
        scope.launch {
            artifactText = withContext(container.dispatchers.io) {
                container.projectRepository.readDeliverable(projectId, deliverable.id)
            }
        }
    }

    fun exportProjectPackage(uri: Uri) {
        val projectId = selectedProjectId ?: return
        val projectName = selectedProject?.name ?: "项目"
        scope.launch {
            runCatching {
                withContext(container.dispatchers.io) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        container.projectRepository.exportProjectZip(projectId, output)
                    } ?: throw IllegalStateException("无法写入项目包")
                }
            }.onSuccess {
                statusText = "已导出项目包：$projectName"
            }.onFailure {
                statusText = it.toUserMessage()
            }
        }
    }

    fun importProjectPackage(uri: Uri) {
        scope.launch {
            runCatching {
                withContext(container.dispatchers.io) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        container.projectRepository.importProjectZip(input)
                    } ?: throw IllegalStateException("无法读取项目包")
                }
            }.onSuccess { project ->
                selectProject(project.id)
                searchQuery = ""
                artifactFilter = defaultProjectArtifactFilter()
                collapsedDirectoryPaths = emptySet()
                statusText = "已导入项目：${project.name}"
                refreshProjects()
                refreshDeliverables()
            }.onFailure {
                statusText = it.toUserMessage()
            }
        }
    }

    fun shareProjectPackage(project: Project) {
        scope.launch {
            runCatching {
                val zipFile = withContext(container.dispatchers.io) {
                    val exportDirectory = context.cacheDir.resolve("project-exports")
                    exportDirectory.mkdirs()
                    val file = exportDirectory.resolve(projectPackageFileName(project))
                    file.outputStream().use { output ->
                        container.projectRepository.exportProjectZip(project.id, output)
                    }
                    file
                }
                shareProjectPackage(context, project, zipFile)
            }.onSuccess {
                statusText = "已生成项目包：${project.name}"
            }.onFailure {
                statusText = it.toUserMessage()
            }
        }
    }

    fun deleteProject(project: Project) {
        scope.launch {
            runCatching {
                withContext(container.dispatchers.io) {
                    container.projectRepository.deleteProject(project.id)
                }
            }.onSuccess {
                if (selectedProjectId == project.id) {
                    selectProject(null)
                    searchQuery = ""
                    artifactFilter = defaultProjectArtifactFilter()
                    collapsedDirectoryPaths = emptySet()
                }
                statusText = "已删除项目：${project.name}"
                refreshProjects()
            }.onFailure {
                statusText = it.toUserMessage()
            }
        }
    }

    fun refreshGitState(project: Project? = selectedProject) {
        val refresh = gitRefreshController.begin(project?.id)
        gitStatus = null
        gitBranches = emptyList()
        if (project == null || refresh == null) return

        scope.launch {
            try {
                val (status, branches) = withContext(container.dispatchers.io) {
                    if (!container.gitEngine.isRepository(project.rootDirectory)) {
                        null to emptyList<GitBranchSummary>()
                    } else {
                        container.gitEngine.status(project.rootDirectory) to
                            container.gitEngine.branches(project.rootDirectory)
                    }
                }
                if (!gitRefreshController.canPublish(refresh, selectedProjectId)) return@launch
                gitStatus = status
                gitBranches = branches
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!gitRefreshController.canPublish(refresh, selectedProjectId)) return@launch
                statusText = error.toUserMessage()
            }
        }
    }

    fun cloneRepositoryAsProject(name: String, remoteUrl: String, branch: String) {
        scope.launch {
            runCatching {
                withContext(container.dispatchers.io) {
                    val credentials = container.gitCredentialStore.credentials()
                    container.projectRepository.createProjectFromPreparedDirectory(name) { directory ->
                        container.gitEngine.cloneRepository(
                            GitCloneRequest(
                                remoteUrl = remoteUrl,
                                branch = branch.ifBlank { "main" },
                                directory = directory,
                                credentials = credentials,
                            ),
                        )
                    }
                }
            }.onSuccess { project ->
                selectProject(project.id)
                searchQuery = ""
                selectedTab = ProjectWorkbenchTab.GIT
                showCloneRepositoryDialog = false
                statusText = "已克隆仓库：${project.name}"
                refreshProjects()
                refreshDeliverables()
                refreshGitState(project)
            }.onFailure {
                statusText = it.toUserMessage()
            }
        }
    }

    fun initGitRepository(project: Project) {
        scope.launch {
            runCatching {
                withContext(container.dispatchers.io) {
                    container.gitEngine.initRepository(project.rootDirectory)
                }
            }.onSuccess {
                statusText = "已初始化 Git 仓库"
                refreshGitState()
            }.onFailure {
                statusText = it.toUserMessage()
            }
        }
    }

    fun commitAll(project: Project, message: String) {
        scope.launch {
            runCatching {
                withContext(container.dispatchers.io) {
                    val author = container.gitCredentialStore.commitAuthor()
                    container.gitEngine.stageAllAndCommit(project.rootDirectory, message, author)
                }
            }.onSuccess { commit ->
                showCommitDialog = false
                statusText = "已提交：${commit.shortId}"
                refreshDeliverables()
                refreshGitState()
            }.onFailure {
                statusText = it.toUserMessage()
            }
        }
    }

    fun pushCurrentBranch(project: Project) {
        scope.launch {
            runCatching {
                withContext(container.dispatchers.io) {
                    container.gitEngine.push(project.rootDirectory, container.gitCredentialStore.credentials())
                }
            }.onSuccess {
                statusText = "已推送当前分支"
                refreshGitState()
            }.onFailure {
                statusText = it.toUserMessage()
            }
        }
    }

    fun fetchRemote(project: Project) {
        scope.launch {
            runCatching {
                withContext(container.dispatchers.io) {
                    container.gitEngine.fetch(project.rootDirectory, container.gitCredentialStore.credentials())
                }
            }.onSuccess {
                statusText = "已获取远端分支信息"
                refreshGitState()
            }.onFailure {
                statusText = it.toUserMessage()
            }
        }
    }

    fun pullFastForward(project: Project) {
        scope.launch {
            runCatching {
                withContext(container.dispatchers.io) {
                    container.gitEngine.pullFastForwardOnly(project.rootDirectory, container.gitCredentialStore.credentials())
                }
            }.onSuccess {
                statusText = "已快进拉取"
                refreshDeliverables()
                refreshGitState()
            }.onFailure {
                statusText = it.toUserMessage()
            }
        }
    }

    fun createBranch(project: Project, branch: String) {
        scope.launch {
            runCatching {
                withContext(container.dispatchers.io) {
                    container.gitEngine.createBranch(project.rootDirectory, branch, checkout = true)
                }
            }.onSuccess {
                showBranchDialog = false
                statusText = "已创建并切换分支：$branch"
                refreshGitState()
            }.onFailure {
                statusText = it.toUserMessage()
            }
        }
    }

    fun checkoutBranch(project: Project, branch: String) {
        scope.launch {
            runCatching {
                withContext(container.dispatchers.io) {
                    container.gitEngine.checkoutBranch(project.rootDirectory, branch)
                }
            }.onSuccess {
                statusText = "已切换分支：$branch"
                refreshDeliverables()
                refreshGitState()
            }.onFailure {
                statusText = it.toUserMessage()
            }
        }
    }

    val exportProjectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(projectZipMimeType),
    ) { uri ->
        if (uri != null) exportProjectPackage(uri)
    }
    val importProjectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importProjectPackage(uri)
    }

    LaunchedEffect(Unit) {
        projects = withContext(container.dispatchers.io) {
            container.projectRepository.listProjects()
        }
        projectsLoaded = true
        if (selectedProjectId == null) selectProject(projects.firstOrNull()?.id)
        onCurrentProjectNameChange(projects.firstOrNull { it.id == selectedProjectId }?.name)
    }

    LaunchedEffect(workbenchTarget?.requestKey, projectsLoaded) {
        val target = workbenchTarget ?: return@LaunchedEffect
        if (!projectsLoaded) return@LaunchedEffect
        val targetedFilesRefresh = deliverableRefreshController.acceptWorkbenchTarget(
            target = target,
            selectedProjectId = selectedProjectId,
            currentSearchQuery = searchQuery,
        )
        val project = projects.firstOrNull { it.id == target.projectId }
        if (project == null) {
            statusText = "项目不存在或已被删除"
            onWorkbenchTargetConsumed(target.requestKey)
            return@LaunchedEffect
        }

        val targetTab = projectWorkbenchTab(target.destination)
        val refreshAlreadySelectedGit = shouldRefreshGitForProjectSelection(targetTab, project.id) &&
            selectedProjectId == project.id && selectedTab == ProjectWorkbenchTab.GIT
        selectProject(project.id, invalidateDeliverableRefresh = false)
        selectedTab = targetTab
        searchQuery = ""
        artifactFilter = ProjectArtifactFilter.ALL
        collapsedDirectoryPaths = emptySet()
        if (target.destination == ProjectWorkbenchDestination.FILES) {
            publishDeliverableRefresh(checkNotNull(targetedFilesRefresh))
        } else if (refreshAlreadySelectedGit) {
            refreshGitState(project)
        }
        onWorkbenchTargetConsumed(target.requestKey)
    }

    LaunchedEffect(selectedProjectId, searchQuery) {
        onCurrentProjectNameChange(projects.firstOrNull { it.id == selectedProjectId }?.name)
        val projectId = selectedProjectId ?: return@LaunchedEffect
        val ordinaryRefresh = deliverableRefreshController.beginOrdinaryFilesRefresh(
            projectId = projectId,
            query = searchQuery,
            filter = artifactFilter,
        ) ?: return@LaunchedEffect
        publishDeliverableRefresh(ordinaryRefresh)
    }

    LaunchedEffect(selectedProject, selectedTab) {
        if (shouldRefreshGitForProjectSelection(selectedTab, selectedProject?.id)) {
            refreshGitState(selectedProject)
        } else if (selectedTab == ProjectWorkbenchTab.GIT) {
            refreshGitState(null)
        }
    }

    LaunchedEffect(selectedProjectId, selectedDeliverableId, artifactFilter, deliverables) {
        if (selectedDeliverableId != null && visibleDeliverables.none { it.id == selectedDeliverableId }) {
            selectedDeliverableId = visibleDeliverables.firstOrNull()?.id
        }
        loadSelectedArtifactText()
    }

    if (showNewProjectDialog) {
        NewProjectDialog(
            onDismiss = { showNewProjectDialog = false },
            onCreate = { name ->
                scope.launch {
                    val project = withContext(container.dispatchers.io) {
                        container.projectRepository.createProject(name)
                    }
                    selectProject(project.id)
                    searchQuery = ""
                    showNewProjectDialog = false
                    statusText = "已创建项目：${project.name}"
                    refreshProjects()
                }
            },
        )
    }

    if (showCloneRepositoryDialog) {
        CloneRepositoryDialog(
            onDismiss = { showCloneRepositoryDialog = false },
            onClone = ::cloneRepositoryAsProject,
        )
    }

    if (showCommitDialog && selectedProject != null) {
        CommitDialog(
            onDismiss = { showCommitDialog = false },
            onCommit = { message -> commitAll(selectedProject, message) },
        )
    }

    if (showBranchDialog && selectedProject != null) {
        BranchDialog(
            onDismiss = { showBranchDialog = false },
            onCreate = { branch -> createBranch(selectedProject, branch) },
        )
    }

    projectToDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { Text("删除项目") },
            text = { Text("删除“${project.name}”后，项目文件和交付物会从本机移除。历史会话仍会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        projectToDelete = null
                        deleteProject(project)
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }) { Text("取消") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ProjectHeader(
                selectedProject = selectedProject,
                projects = projects,
                overview = workbenchOverview,
                onSelectProject = {
                    selectProject(it.id)
                    searchQuery = ""
                },
                onCreateProject = { showNewProjectDialog = true },
                onCloneRepository = { showCloneRepositoryDialog = true },
                onCreateSession = {
                    selectedProject?.let { project ->
                        onCreateSession(project)
                    }
                },
                onImportProjectPackage = {
                    importProjectLauncher.launch(projectZipImportMimeTypes)
                },
                onExportProjectPackage = {
                    selectedProject?.let { project ->
                        exportProjectLauncher.launch(projectPackageFileName(project))
                    }
                },
                onShareProjectPackage = {
                    selectedProject?.let(::shareProjectPackage)
                },
                onDeleteProject = {
                    selectedProject?.let { projectToDelete = it }
                },
            )
        }

        statusText?.let { status ->
            item {
                InlineStatusMessage(
                    text = status,
                    tone = StatusTone.INFO,
                    onDismiss = { statusText = null },
                )
            }
        }

        if (selectedProject == null) {
            item { EmptyProjectState(onCreateProject = { showNewProjectDialog = true }) }
        } else {
            item {
                ProjectWorkbenchTabs(
                    selectedTab = selectedTab,
                    onSelectTab = { tab ->
                        val refreshAlreadySelectedGit = shouldRefreshGitOnTabSelection(tab) && tab == selectedTab
                        selectedTab = tab
                        if (refreshAlreadySelectedGit) refreshGitState()
                    },
                )
            }

            if (
                shouldShowProjectWorkbenchTabGuidance(
                    tab = selectedTab,
                    conversationsEmpty = selectedProjectConversations.isEmpty(),
                    deliverablesEmpty = deliverables.isEmpty(),
                )
            ) {
                item {
                    Text(
                        text = projectWorkbenchTabGuidance(selectedTab),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (selectedTab) {
                ProjectWorkbenchTab.CONVERSATIONS -> {
                    if (selectedProjectConversations.isEmpty()) {
                        item {
                            EmptyProjectConversationState(onCreateSession = {
                                onCreateSession(selectedProject)
                            })
                        }
                    } else {
                        items(selectedProjectConversations, key = { it.id }) { conversation ->
                            ProjectConversationRow(
                                conversation = conversation,
                                onClick = { onOpenSession(conversation.id) },
                            )
                        }
                    }
                }
                ProjectWorkbenchTab.FOLDER -> {
                    item {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("搜索项目交付物") },
                            singleLine = true,
                        )
                    }

                    item {
                        ProjectArtifactFilterBar(
                            selectedFilter = artifactFilter,
                            onSelectFilter = {
                                artifactFilter = it
                                selectedDeliverableId = null
                                collapsedDirectoryPaths = emptySet()
                            },
                        )
                    }

                    if (visibleTreeItems.isNotEmpty()) {
                        items(visibleTreeItems, key = { it.path }) { treeItem ->
                            ProjectArtifactTreeRow(
                                treeItem = treeItem,
                                selected = treeItem.deliverable?.id == selectedDeliverableId,
                                onClick = {
                                    if (treeItem.isDirectory) {
                                        collapsedDirectoryPaths = if (treeItem.path in collapsedDirectoryPaths) {
                                            collapsedDirectoryPaths - treeItem.path
                                        } else {
                                            collapsedDirectoryPaths + treeItem.path
                                        }
                                    } else {
                                        treeItem.deliverable?.let { selectedDeliverableId = it.id }
                                    }
                                },
                            )
                        }
                    }

                    item {
                        ArtifactPreviewPanel(
                            deliverable = selectedDeliverable,
                            artifactText = artifactText,
                            previewMode = previewMode,
                            onArtifactTextChange = { artifactText = it },
                            onTogglePreview = { previewMode = !previewMode },
                            onExportPdf = {
                                val projectId = selectedProjectId ?: return@ArtifactPreviewPanel
                                val deliverable = selectedDeliverable ?: return@ArtifactPreviewPanel
                                if (!deliverable.artifactType.rendersAsMarkdown) return@ArtifactPreviewPanel
                                val markdownToExport = artifactText
                                scope.launch {
                                    runCatching {
                                        withContext(container.dispatchers.io) {
                                            val exported = container.projectRepository.writePdfExport(projectId, deliverable.id) { output ->
                                                markdownPdfWriter.write(markdownToExport, output)
                                            }
                                            exported to container.projectRepository.listDeliverables(projectId)
                                        }
                                    }.onSuccess { (exported, nextDeliverables) ->
                                        statusText = "已导出 PDF：${exported.relativePath}"
                                        searchQuery = ""
                                        deliverables = nextDeliverables
                                        artifactFilter = ProjectArtifactFilter.ALL
                                        selectedDeliverableId = exported.id
                                    }.onFailure {
                                        statusText = it.toUserMessage()
                                    }
                                }
                            },
                            onSave = {
                                val projectId = selectedProjectId ?: return@ArtifactPreviewPanel
                                val deliverable = selectedDeliverable ?: return@ArtifactPreviewPanel
                                if (!deliverable.artifactType.isTextPreviewable) return@ArtifactPreviewPanel
                                scope.launch {
                                    withContext(container.dispatchers.io) {
                                        container.projectRepository.writeDeliverable(projectId, deliverable.id, artifactText)
                                    }
                                    statusText = "已保存：${deliverable.title}"
                                    refreshDeliverables()
                                }
                            },
                            onOpen = {
                                val projectId = selectedProjectId ?: return@ArtifactPreviewPanel
                                val deliverable = selectedDeliverable ?: return@ArtifactPreviewPanel
                                runCatching {
                                    val file = container.projectRepository.resolveDeliverableFile(projectId, deliverable.id)
                                    openProjectArtifact(context, file, deliverable.artifactType)
                                }.onFailure {
                                    statusText = it.toUserMessage()
                                }
                            },
                            onShareText = {
                                shareTextArtifact(
                                    context = context,
                                    title = selectedDeliverable?.title ?: "交付物",
                                    artifactText = artifactText,
                                    artifactType = selectedDeliverable?.artifactType ?: ProjectArtifactType.TEXT,
                                )
                            },
                        )
                    }
                }
                ProjectWorkbenchTab.GIT -> {
                    item {
                        ProjectGitPanel(
                            status = gitStatus,
                            branches = gitBranches,
                            onInitRepository = { initGitRepository(selectedProject) },
                            onCloneRepository = { showCloneRepositoryDialog = true },
                            onRefresh = { refreshGitState() },
                            onCommit = { showCommitDialog = true },
                            onPush = { pushCurrentBranch(selectedProject) },
                            onFetch = { fetchRemote(selectedProject) },
                            onPull = { pullFastForward(selectedProject) },
                            onCreateBranch = { showBranchDialog = true },
                            onCheckoutBranch = { checkoutBranch(selectedProject, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectWorkbenchTabs(
    selectedTab: ProjectWorkbenchTab,
    onSelectTab: (ProjectWorkbenchTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProjectWorkbenchTab.entries.forEach { tab ->
            FilterChip(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = HarnessSpacing.minimumTouchTarget),
                selected = tab == selectedTab,
                onClick = { onSelectTab(tab) },
                label = { Text(tab.label) },
            )
        }
    }
}

@Composable
private fun ProjectArtifactFilterBar(
    selectedFilter: ProjectArtifactFilter,
    onSelectFilter: (ProjectArtifactFilter) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ProjectArtifactFilter.entries.toList(), key = { it.name }) { filter ->
            FilterChip(
                selected = filter == selectedFilter,
                onClick = { onSelectFilter(filter) },
                label = { Text(filter.label) },
            )
        }
    }
}

@Composable
private fun ProjectHeader(
    selectedProject: Project?,
    projects: List<Project>,
    overview: ProjectWorkbenchOverview,
    onSelectProject: (Project) -> Unit,
    onCreateProject: () -> Unit,
    onCloneRepository: () -> Unit,
    onCreateSession: () -> Unit,
    onImportProjectPackage: () -> Unit,
    onExportProjectPackage: () -> Unit,
    onShareProjectPackage: () -> Unit,
    onDeleteProject: () -> Unit,
) {
    var projectMenuExpanded by remember { mutableStateOf(false) }
    var packageMenuExpanded by remember { mutableStateOf(false) }
    val actionLayout = projectHeaderActionLayout(hasProject = selectedProject != null)
    val overflowMenu: @Composable () -> Unit = {
        Box {
            IconButton(
                modifier = Modifier.size(HarnessSpacing.primaryControlHeight),
                onClick = { packageMenuExpanded = true },
            ) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
            }
            DropdownMenu(
                expanded = packageMenuExpanded,
                onDismissRequest = { packageMenuExpanded = false },
            ) {
                if (ProjectHeaderAction.CLONE in actionLayout.overflowActions) {
                    DropdownMenuItem(
                        text = { Text("克隆仓库") },
                        leadingIcon = { Icon(Icons.Outlined.AccountTree, contentDescription = null) },
                        onClick = {
                            packageMenuExpanded = false
                            onCloneRepository()
                        },
                    )
                }
                if (ProjectHeaderAction.IMPORT in actionLayout.overflowActions) {
                    DropdownMenuItem(
                        text = { Text("导入项目包") },
                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        onClick = {
                            packageMenuExpanded = false
                            onImportProjectPackage()
                        },
                    )
                }
                if (ProjectHeaderAction.EXPORT in actionLayout.overflowActions) {
                    DropdownMenuItem(
                        text = { Text("导出项目包") },
                        leadingIcon = { Icon(Icons.Outlined.Save, contentDescription = null) },
                        onClick = {
                            packageMenuExpanded = false
                            onExportProjectPackage()
                        },
                    )
                }
                if (ProjectHeaderAction.SHARE in actionLayout.overflowActions) {
                    DropdownMenuItem(
                        text = { Text("分享项目包") },
                        leadingIcon = { Icon(Icons.Outlined.IosShare, contentDescription = null) },
                        onClick = {
                            packageMenuExpanded = false
                            onShareProjectPackage()
                        },
                    )
                }
                if (ProjectHeaderAction.DELETE in actionLayout.overflowActions) {
                    DropdownMenuItem(
                        text = { Text("删除项目", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            packageMenuExpanded = false
                            onDeleteProject()
                        },
                    )
                }
            }
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        Box {
            if (selectedProject == null) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "未选择项目",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "创建项目后从会话开始长期工作",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        overflowMenu()
                    }
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = HarnessSpacing.primaryControlHeight),
                        onClick = onCreateProject,
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text("新建项目")
                    }
                }
            } else {
                ProjectWorkbenchHeader(
                    projectName = selectedProject.name,
                    overview = overview,
                    onSelectProject = { projectMenuExpanded = true },
                    onCreateSession = onCreateSession,
                    overflowContent = overflowMenu,
                )
            }

            DropdownMenu(
                expanded = projectMenuExpanded,
                onDismissRequest = { projectMenuExpanded = false },
            ) {
                projects.forEach { project ->
                    DropdownMenuItem(
                        text = { Text(project.name) },
                        onClick = {
                            projectMenuExpanded = false
                            onSelectProject(project)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProjectWorkbenchHeader(
    projectName: String,
    overview: ProjectWorkbenchOverview,
    onSelectProject: () -> Unit,
    onCreateSession: () -> Unit,
    overflowContent: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = HarnessSpacing.minimumTouchTarget)
                    .clickable(onClick = onSelectProject)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "切换项目"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = projectName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
            }
            overflowContent()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = overview.conversationLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                modifier = Modifier.weight(1f),
                text = overview.deliverableLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                modifier = Modifier.weight(1f),
                text = overview.gitLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HarnessSpacing.primaryControlHeight),
            onClick = onCreateSession,
        ) {
            Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null)
            Text("新建项目会话")
        }
    }
}

@Composable
private fun ProjectConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ComfortListRow(
            title = conversation.title,
            supportingText = projectConversationSupportingText(conversation),
            onClick = onClick,
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Outlined.Chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
    }
}

@Composable
private fun ProjectArtifactTreeRow(
    treeItem: ProjectArtifactTreeItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val deliverable = treeItem.deliverable
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (treeItem.depth * 16).dp),
        onClick = onClick,
        color = if (selected && deliverable != null) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (treeItem.isDirectory) {
                Icon(
                    imageVector = if (treeItem.isCollapsed) {
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight
                    } else {
                        Icons.Outlined.KeyboardArrowDown
                    },
                    contentDescription = if (treeItem.isCollapsed) "展开文件夹" else "折叠文件夹",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(Icons.Outlined.Folder, contentDescription = null)
            } else {
                Spacer(modifier = Modifier.width(16.dp))
                Icon(Icons.Outlined.Description, contentDescription = null)
            }
            Column(modifier = Modifier.weight(1f)) {
                if (treeItem.isDirectory) {
                    Text(
                        text = treeItem.name,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = treeItem.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                } else if (deliverable != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            modifier = Modifier.weight(1f, fill = false),
                            text = treeItem.name,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text(deliverable.artifactType.label) },
                        )
                    }
                    Text(
                        text = projectDeliverableSupportingText(deliverable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtifactPreviewPanel(
    deliverable: ProjectDeliverable?,
    artifactText: String,
    previewMode: Boolean,
    onArtifactTextChange: (String) -> Unit,
    onTogglePreview: () -> Unit,
    onExportPdf: () -> Unit,
    onSave: () -> Unit,
    onOpen: () -> Unit,
    onShareText: () -> Unit,
) {
    val isTextPreviewable = deliverable?.artifactType?.isTextPreviewable == true
    val rendersAsMarkdown = deliverable?.artifactType?.rendersAsMarkdown == true
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deliverable?.title ?: "未选择交付物",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = deliverable?.relativePath ?: "从项目会话写回或导入后会出现在这里",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(enabled = rendersAsMarkdown, onClick = onTogglePreview) {
                    Icon(
                        if (previewMode) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (previewMode) "编辑" else "预览",
                    )
                }
                IconButton(enabled = rendersAsMarkdown, onClick = onExportPdf) {
                    Icon(Icons.Outlined.PictureAsPdf, contentDescription = "导出 PDF")
                }
                IconButton(enabled = isTextPreviewable, onClick = onShareText) {
                    Icon(Icons.Outlined.IosShare, contentDescription = "分享")
                }
                IconButton(enabled = deliverable != null, onClick = onOpen) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "打开")
                }
                IconButton(enabled = isTextPreviewable, onClick = onSave) {
                    Icon(Icons.Outlined.Save, contentDescription = "保存")
                }
            }
            HorizontalDivider()
            if (deliverable == null) {
                Text(
                    text = "选择已有交付物，或从项目会话生成新的文件沉淀。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (rendersAsMarkdown && previewMode) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp, max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MarkdownMessage(markdown = artifactText)
                }
            } else if (isTextPreviewable) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp),
                    value = artifactText,
                    onValueChange = onArtifactTextChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    label = { Text(deliverable.artifactType.label) },
                )
            } else {
                BinaryArtifactPreview(deliverable = deliverable, onOpen = onOpen)
            }
        }
    }
}

@Composable
private fun BinaryArtifactPreview(
    deliverable: ProjectDeliverable,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AssistChip(
            onClick = {},
            label = { Text(deliverable.artifactType.label) },
        )
        Text(
            text = binaryArtifactPreviewText(deliverable),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onOpen) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
            Text("打开")
        }
    }
}

private fun binaryArtifactPreviewText(deliverable: ProjectDeliverable): String = when (deliverable.artifactType) {
    ProjectArtifactType.DOCUMENT -> "Word 文档 · ${deliverable.relativePath}"
    ProjectArtifactType.SPREADSHEET -> "Excel 表格 · ${deliverable.relativePath}"
    ProjectArtifactType.PRESENTATION -> "PPT 演示 · ${deliverable.relativePath}"
    ProjectArtifactType.PDF -> "PDF · ${deliverable.relativePath}"
    ProjectArtifactType.IMAGE -> "图片 · ${deliverable.relativePath}"
    ProjectArtifactType.OTHER -> "文件 · ${deliverable.relativePath}"
    ProjectArtifactType.MARKDOWN,
    ProjectArtifactType.CODE,
    ProjectArtifactType.TEXT,
    -> "${deliverable.artifactType.label} · ${deliverable.relativePath}"
}

@Composable
private fun EmptyProjectConversationState(onCreateSession: () -> Unit) {
    ActionableEmptyState(
        title = "还没有项目会话",
        message = "从会话开始推进项目，后续再把结果沉淀到文件夹。",
        actionLabel = "新建会话",
        onAction = onCreateSession,
        icon = Icons.AutoMirrored.Outlined.Chat,
    )
}

@Composable
private fun EmptyProjectState(onCreateProject: () -> Unit) {
    ActionableEmptyState(
        title = "还没有项目",
        message = "项目用于长期沉淀上下文、会话和交付物。",
        actionLabel = "新建项目",
        onAction = onCreateProject,
        icon = Icons.Outlined.Folder,
    )
}

@Composable
private fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建项目") },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = name,
                onValueChange = { name = it },
                label = { Text("项目名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name) }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun shareTextArtifact(
    context: Context,
    title: String,
    artifactText: String,
    artifactType: ProjectArtifactType,
) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = artifactType.defaultMimeType
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, artifactText)
    }
    context.startActivity(Intent.createChooser(intent, "分享交付物"))
}

private fun shareProjectPackage(context: Context, project: Project, zipFile: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        zipFile,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = projectZipMimeType
        putExtra(Intent.EXTRA_SUBJECT, "${project.name} 项目包")
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享项目包"))
}

private fun openProjectArtifact(context: Context, file: File, artifactType: ProjectArtifactType) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, artifactType.defaultMimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "打开交付物"))
    } catch (error: ActivityNotFoundException) {
        throw ActivityNotFoundException("没有可打开此文件的应用")
    }
}

private fun projectPackageFileName(project: Project): String {
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "${safeProjectPackageName(project.name)}-$timestamp.zip"
}

private fun safeProjectPackageName(value: String): String {
    val normalized = value
        .trim()
        .map { char ->
            when {
                char.isLetterOrDigit() -> char
                char == '-' || char == '_' -> char
                else -> '-'
            }
        }
        .joinToString("")
        .trim('-')
    return normalized.ifBlank { "project" }
}

private const val projectZipMimeType = "application/zip"

private val projectZipImportMimeTypes = arrayOf(
    projectZipMimeType,
    "application/x-zip-compressed",
    "application/octet-stream",
    "*/*",
)
