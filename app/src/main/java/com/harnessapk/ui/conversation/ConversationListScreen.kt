package com.harnessapk.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.harnessapk.chat.Conversation
import com.harnessapk.common.AppContainer
import com.harnessapk.session.WorkspaceProject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun ConversationListScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenChat: (String) -> Unit,
) {
    val conversations by container.chatRepository.observeConversations().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var conversationToEdit by remember { mutableStateOf<Conversation?>(null) }
    var titleDraft by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf<List<WorkspaceProject>>(emptyList()) }
    var groupedByProject by rememberSaveable { mutableStateOf(false) }
    var allProjectSessionsCollapsed by rememberSaveable { mutableStateOf(false) }
    var collapsedProjectIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        projects = runCatching { container.projectWorkspaceGateway.listProjects() }
            .getOrDefault(emptyList())
    }

    val projectsById = remember(projects) { projects.associateBy { it.id } }
    val groupedState = remember(
        conversations,
        projects,
        allProjectSessionsCollapsed,
        collapsedProjectIds,
    ) {
        buildProjectGroupedConversationState(
            conversations = conversations,
            projects = projects,
            allProjectSessionsCollapsed = allProjectSessionsCollapsed,
            collapsedProjectIds = collapsedProjectIds,
        )
    }

    LaunchedEffect(groupedState.canGroupByProject) {
        if (!groupedState.canGroupByProject) {
            groupedByProject = false
        }
    }

    conversationToEdit?.let { conversation ->
        AlertDialog(
            onDismissRequest = { conversationToEdit = null },
            title = { Text("修改标题") },
            text = {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = titleDraft,
                    onValueChange = { titleDraft = it },
                    label = { Text("会话标题") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = titleDraft.isNotBlank(),
                    onClick = {
                        scope.launch {
                            container.chatRepository.updateConversationTitle(conversation.id, titleDraft)
                            conversationToEdit = null
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { conversationToEdit = null }) { Text("取消") }
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
        if (conversations.isEmpty()) {
            item { EmptyConversationState() }
        } else {
            if (groupedState.canGroupByProject) {
                item {
                    ConversationListHeader(
                        groupedByProject = groupedByProject,
                        onShowRecent = { groupedByProject = false },
                        onShowGrouped = { groupedByProject = true },
                    )
                }
            }
            if (groupedByProject) {
                groupedConversationDisplaySections(groupedState).forEach { section ->
                    when (section) {
                        is ConversationGroupedDisplaySection.ProjectGroupsToggle -> {
                            item(key = "project-sessions-toggle") {
                                ProjectSessionsToggleRow(
                                    collapsed = section.collapsed,
                                    count = section.count,
                                    onToggle = {
                                        allProjectSessionsCollapsed = !allProjectSessionsCollapsed
                                        if (!allProjectSessionsCollapsed) {
                                            collapsedProjectIds = emptySet()
                                        }
                                    },
                                )
                            }
                        }
                        is ConversationGroupedDisplaySection.ProjectGroup -> {
                            val group = section.group
                            item(key = "project-${group.projectId}") {
                                ProjectConversationGroupHeader(
                                    group = group,
                                    onToggle = {
                                        collapsedProjectIds = if (group.projectId in collapsedProjectIds) {
                                            collapsedProjectIds - group.projectId
                                        } else {
                                            collapsedProjectIds + group.projectId
                                        }
                                    },
                                )
                            }
                            conversationItems(
                                conversations = group.visibleConversations,
                                projectsById = projectsById,
                                onOpenChat = onOpenChat,
                                onEdit = {
                                    conversationToEdit = it
                                    titleDraft = it.title
                                },
                                onDelete = {
                                    scope.launch { container.chatRepository.archiveConversation(it.id) }
                                },
                            )
                        }
                        is ConversationGroupedDisplaySection.Regular -> {
                            item { ConversationSectionHeader("普通会话") }
                            conversationItems(
                                conversations = section.conversations,
                                projectsById = projectsById,
                                onOpenChat = onOpenChat,
                                onEdit = {
                                    conversationToEdit = it
                                    titleDraft = it.title
                                },
                                onDelete = {
                                    scope.launch { container.chatRepository.archiveConversation(it.id) }
                                },
                            )
                        }
                    }
                }
            } else {
                conversationItems(
                    conversations = conversations,
                    projectsById = projectsById,
                    onOpenChat = onOpenChat,
                    onEdit = {
                        conversationToEdit = it
                        titleDraft = it.title
                    },
                    onDelete = {
                        scope.launch { container.chatRepository.archiveConversation(it.id) }
                    },
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.conversationItems(
    conversations: List<Conversation>,
    projectsById: Map<String, WorkspaceProject>,
    onOpenChat: (String) -> Unit,
    onEdit: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
) {
    items(conversations, key = { it.id }) { conversation ->
        ConversationRow(
            conversation = conversation,
            projectName = conversation.projectId?.let { projectsById[it]?.name ?: "未知项目" },
            onOpen = { onOpenChat(conversation.id) },
            onEdit = { onEdit(conversation) },
            onDelete = { onDelete(conversation) },
        )
    }
}

@Composable
private fun ConversationListHeader(
    groupedByProject: Boolean,
    onShowRecent: () -> Unit,
    onShowGrouped: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConversationListTab(
                text = "最近会话",
                selected = !groupedByProject,
                onClick = onShowRecent,
            )
            ConversationListTab(
                text = "按项目分组",
                selected = groupedByProject,
                onClick = onShowGrouped,
            )
        }
    }
}

@Composable
private fun ConversationListTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun ConversationSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ProjectConversationGroupHeader(
    group: ConversationProjectGroup,
    onToggle: () -> Unit,
) {
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${if (group.isCollapsed) ">" else "v"} ${group.projectName}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${group.conversations.size} 个会话",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProjectSessionsToggleRow(
    collapsed: Boolean,
    count: Int,
    onToggle: () -> Unit,
) {
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (collapsed) "> 项目会话已折叠" else "v 项目会话",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "$count 个会话",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    projectName: String?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "更新于 ${conversation.updatedAt.toDisplayTime()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (projectName != null) {
                    Text(
                        text = projectName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("改名") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyConversationState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "还没有会话",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "新建对话后，历史会保存在本机。长会话会自动生成本地记忆摘要。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Long.toDisplayTime(): String {
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    return formatter.format(Date(this))
}
