package com.harnessapk.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.harnessapk.ui.components.ActionableEmptyState
import com.harnessapk.ui.components.ComfortListRow
import com.harnessapk.ui.theme.HarnessSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun ConversationListScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenChat: (String) -> Unit,
    onCreateConversation: () -> Unit,
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
        contentPadding = PaddingValues(
            horizontal = HarnessSpacing.pageHorizontal,
            vertical = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(HarnessSpacing.item),
    ) {
        if (conversations.isEmpty()) {
            item { EmptyConversationState(onCreateConversation) }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (group.isCollapsed) {
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight
                    } else {
                        Icons.Outlined.KeyboardArrowDown
                    },
                    contentDescription = if (group.isCollapsed) "展开项目会话" else "折叠项目会话",
                )
                Text(
                    text = group.projectName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (collapsed) {
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight
                    } else {
                        Icons.Outlined.KeyboardArrowDown
                    },
                    contentDescription = if (collapsed) "展开全部项目会话" else "折叠全部项目会话",
                )
                Text(
                    text = if (collapsed) "项目会话已折叠" else "项目会话",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
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
    Column(modifier = Modifier.fillMaxWidth()) {
        ComfortListRow(
            title = conversation.title,
            supportingText = "更新于 ${conversation.updatedAt.toDisplayTime()}",
            metadata = projectName,
            onClick = onOpen,
            trailingContent = {
                Box {
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
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
    }
}

@Composable
private fun EmptyConversationState(onCreateConversation: () -> Unit) {
    ActionableEmptyState(
        title = "还没有会话",
        message = "新建会话后，内容会保存在本机，并在长会话中自动整理记忆摘要。",
        actionLabel = "新建会话",
        onAction = onCreateConversation,
        icon = Icons.AutoMirrored.Outlined.Chat,
    )
}

@Composable
private fun Long.toDisplayTime(): String {
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    return formatter.format(Date(this))
}
