package com.harnessapk.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.harnessapk.chat.Conversation
import com.harnessapk.agent.Agent
import com.harnessapk.common.AppContainer
import com.harnessapk.ui.components.ComfortListRow
import com.harnessapk.ui.theme.HarnessSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

internal fun conversationIdentityLabel(conversation: Conversation, agents: Map<String, Agent>): String? =
    conversation.agentId?.let { id ->
        "${agents[id]?.name ?: "已安装人物"} · 基于资料模拟"
    }

internal fun conversationMetadataLabel(
    conversation: Conversation,
    agents: Map<String, Agent>,
): String? = conversationIdentityLabel(conversation, agents)

@Composable
fun ConversationListScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenChat: (String) -> Unit,
    onCreateConversation: () -> Unit,
    onOpenAgentPackages: () -> Unit = {},
    onOpenWikiLibrary: () -> Unit = {},
) {
    val conversations by container.chatRepository.observeConversations().collectAsState(initial = emptyList())
    val agents by container.agentRepository.observeAgents().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var conversationToEdit by remember { mutableStateOf<Conversation?>(null) }
    var titleDraft by remember { mutableStateOf("") }
    val visibleConversations = remember(conversations) { lifeConversations(conversations) }
    val agentsById = remember(agents) { agents.associateBy { it.id } }

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
        item {
            QuickEntryRow(
                onOpenAgentPackages = onOpenAgentPackages,
                onOpenWikiLibrary = onOpenWikiLibrary,
                onCreateConversation = onCreateConversation,
            )
        }
        conversationItems(
            conversations = visibleConversations,
            agentsById = agentsById,
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

private fun androidx.compose.foundation.lazy.LazyListScope.conversationItems(
    conversations: List<Conversation>,
    agentsById: Map<String, Agent>,
    onOpenChat: (String) -> Unit,
    onEdit: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
) {
    items(conversations, key = { it.id }) { conversation ->
        ConversationRow(
            conversation = conversation,
            metadata = conversationMetadataLabel(conversation, agentsById),
            onOpen = { onOpenChat(conversation.id) },
            onEdit = { onEdit(conversation) },
            onDelete = { onDelete(conversation) },
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    metadata: String?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        ComfortListRow(
            title = conversation.title,
            supportingText = "更新于 ${conversation.updatedAt.toDisplayTime()}",
            metadata = metadata,
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
private fun QuickEntryRow(
    onOpenAgentPackages: () -> Unit,
    onOpenWikiLibrary: () -> Unit,
    onCreateConversation: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onOpenAgentPackages,
            label = { Text("智能体") },
            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
        )
        AssistChip(
            onClick = onOpenWikiLibrary,
            label = { Text("知识库") },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null) },
        )
        Spacer(modifier = Modifier.weight(1f))
        FilledIconButton(
            modifier = Modifier.size(48.dp),
            onClick = onCreateConversation,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "新建对话")
        }
    }
}

@Composable
private fun Long.toDisplayTime(): String {
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    return formatter.format(Date(this))
}
