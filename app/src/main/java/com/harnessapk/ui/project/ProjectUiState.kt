package com.harnessapk.ui.project

import com.harnessapk.chat.Conversation
import com.harnessapk.git.GitStatusSummary
import com.harnessapk.project.ProjectArtifactType
import com.harnessapk.project.ProjectDeliverable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal enum class ProjectWorkbenchTab(val label: String) {
    CONVERSATIONS("会话"),
    FOLDER("文件夹"),
    GIT("Git"),
}

internal data class ProjectWorkbenchOverview(
    val conversationLabel: String,
    val deliverableLabel: String,
    val gitLabel: String,
)

internal fun projectWorkbenchOverview(
    conversationCount: Int,
    deliverableCount: Int,
    gitStatus: GitStatusSummary?,
): ProjectWorkbenchOverview = ProjectWorkbenchOverview(
    conversationLabel = "$conversationCount 个会话",
    deliverableLabel = "$deliverableCount 个文件",
    gitLabel = when {
        gitStatus == null -> "Git 状态未读取"
        gitStatus.isClean -> "${gitStatus.currentBranch} · 工作区干净"
        else -> "${gitStatus.currentBranch} · ${gitStatus.files.size} 项变更"
    },
)

internal fun projectWorkbenchTabGuidance(tab: ProjectWorkbenchTab): String = when (tab) {
    ProjectWorkbenchTab.CONVERSATIONS -> "在当前项目内开始或继续工作"
    ProjectWorkbenchTab.FOLDER -> "查看会话沉淀和已写入文件"
    ProjectWorkbenchTab.GIT -> "查看当前分支和工作区变更"
}

internal enum class ProjectWorkbenchDestination { FILES, GIT }

internal data class ProjectWorkbenchTarget(
    val projectId: String,
    val destination: ProjectWorkbenchDestination,
    val selectedPath: String?,
    val requestKey: Int,
)

internal fun projectWorkbenchTab(destination: ProjectWorkbenchDestination): ProjectWorkbenchTab =
    when (destination) {
        ProjectWorkbenchDestination.FILES -> ProjectWorkbenchTab.FOLDER
        ProjectWorkbenchDestination.GIT -> ProjectWorkbenchTab.GIT
    }

internal fun defaultProjectWorkbenchTab(): ProjectWorkbenchTab = ProjectWorkbenchTab.CONVERSATIONS

internal enum class ProjectHeaderAction {
    NEW_SESSION,
    RENAME,
    CLONE,
    IMPORT,
    EXPORT,
    SHARE,
    DELETE,
}

internal data class ProjectHeaderActionLayout(
    val showCreateProjectDirectly: Boolean,
    val directActions: List<ProjectHeaderAction>,
    val overflowActions: List<ProjectHeaderAction>,
)

internal fun projectHeaderActionLayout(hasProject: Boolean): ProjectHeaderActionLayout =
    if (hasProject) {
        ProjectHeaderActionLayout(
            showCreateProjectDirectly = true,
            directActions = listOf(ProjectHeaderAction.NEW_SESSION),
            overflowActions = listOf(
                ProjectHeaderAction.RENAME,
                ProjectHeaderAction.CLONE,
                ProjectHeaderAction.IMPORT,
                ProjectHeaderAction.EXPORT,
                ProjectHeaderAction.SHARE,
                ProjectHeaderAction.DELETE,
            ),
        )
    } else {
        ProjectHeaderActionLayout(
            showCreateProjectDirectly = false,
            directActions = emptyList(),
            overflowActions = listOf(ProjectHeaderAction.CLONE, ProjectHeaderAction.IMPORT),
        )
    }

internal enum class ProjectArtifactFilter(val label: String) {
    ALL("全部"),
    MARKDOWN("Markdown"),
    OFFICE("Office"),
    PDF("PDF"),
    CODE("代码"),
    IMAGES("图片"),
    OTHER("其他");

    fun matches(type: ProjectArtifactType): Boolean = when (this) {
        ALL -> true
        MARKDOWN -> type == ProjectArtifactType.MARKDOWN
        OFFICE -> type == ProjectArtifactType.DOCUMENT ||
            type == ProjectArtifactType.SPREADSHEET ||
            type == ProjectArtifactType.PRESENTATION
        PDF -> type == ProjectArtifactType.PDF
        CODE -> type == ProjectArtifactType.CODE || type == ProjectArtifactType.TEXT
        IMAGES -> type == ProjectArtifactType.IMAGE
        OTHER -> type == ProjectArtifactType.OTHER
    }
}

internal fun defaultProjectArtifactFilter(): ProjectArtifactFilter = ProjectArtifactFilter.ALL

internal fun filterProjectArtifacts(
    deliverables: List<ProjectDeliverable>,
    filter: ProjectArtifactFilter,
): List<ProjectDeliverable> = deliverables.filter { filter.matches(it.artifactType) }

internal data class ProjectArtifactTreeNode(
    val name: String,
    val path: String,
    val deliverable: ProjectDeliverable?,
    val children: List<ProjectArtifactTreeNode> = emptyList(),
) {
    val isDirectory: Boolean = deliverable == null
}

internal data class ProjectArtifactTreeItem(
    val node: ProjectArtifactTreeNode,
    val depth: Int,
    val isCollapsed: Boolean,
) {
    val name: String = node.name
    val path: String = node.path
    val deliverable: ProjectDeliverable? = node.deliverable
    val isDirectory: Boolean = node.isDirectory
}

internal fun buildProjectArtifactTree(deliverables: List<ProjectDeliverable>): List<ProjectArtifactTreeNode> {
    val root = MutableProjectArtifactTreeNode(name = "", path = "")
    deliverables.forEach { deliverable ->
        val segments = deliverable.relativePath
            .split('/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (segments.isEmpty()) return@forEach

        var current = root
        segments.forEachIndexed { index, segment ->
            val path = segments.take(index + 1).joinToString("/")
            current = current.children.getOrPut(segment) {
                MutableProjectArtifactTreeNode(name = segment, path = path)
            }
        }
        current.deliverable = deliverable
    }
    return root.toImmutable().children
}

internal fun flattenProjectArtifactTree(
    nodes: List<ProjectArtifactTreeNode>,
    collapsedDirectoryPaths: Set<String>,
): List<ProjectArtifactTreeItem> {
    val flattened = mutableListOf<ProjectArtifactTreeItem>()
    fun append(node: ProjectArtifactTreeNode, depth: Int) {
        val collapsed = node.isDirectory && node.path in collapsedDirectoryPaths
        flattened += ProjectArtifactTreeItem(
            node = node,
            depth = depth,
            isCollapsed = collapsed,
        )
        if (node.isDirectory && !collapsed) {
            node.children.forEach { append(it, depth + 1) }
        }
    }
    nodes.forEach { append(it, depth = 0) }
    return flattened
}

internal fun projectSessionTitle(
    projectName: String,
    deliverableTitle: String?,
): String {
    val normalizedProjectName = projectName.trim().ifBlank { "项目" }
    val normalizedDeliverableTitle = deliverableTitle?.trim().orEmpty()
    return if (normalizedDeliverableTitle.isBlank()) {
        "$normalizedProjectName · 项目会话"
    } else {
        "$normalizedProjectName · $normalizedDeliverableTitle"
    }
}

internal fun projectDeliverableSupportingText(deliverable: ProjectDeliverable): String =
    deliverable.relativePath

internal fun projectConversations(
    conversations: List<Conversation>,
    projectId: String?,
): List<Conversation> {
    val normalizedProjectId = projectId?.trim().orEmpty()
    if (normalizedProjectId.isBlank()) return emptyList()
    return conversations
        .filter { it.projectId == normalizedProjectId }
        .sortedByDescending { it.updatedAt }
}

internal fun projectConversationSupportingText(conversation: Conversation): String {
    val updatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date(conversation.updatedAt))
    val promptState = if (
        conversation.promptFinal.isNotBlank() ||
        conversation.promptOptimized.isNotBlank() ||
        conversation.promptOriginal.isNotBlank()
    ) {
        " · 已设提示词"
    } else {
        ""
    }
    return updatedAt + promptState
}

private class MutableProjectArtifactTreeNode(
    val name: String,
    val path: String,
) {
    var deliverable: ProjectDeliverable? = null
    val children: MutableMap<String, MutableProjectArtifactTreeNode> = linkedMapOf()

    fun toImmutable(): ProjectArtifactTreeNode = ProjectArtifactTreeNode(
        name = name,
        path = path,
        deliverable = deliverable,
        children = children.values
            .sortedWith(
                compareBy<MutableProjectArtifactTreeNode> { it.deliverable != null }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            )
            .map { it.toImmutable() },
    )
}
