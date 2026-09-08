package com.harnessapk.ui.conversation

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harnessapk.agent.Agent
import com.harnessapk.chat.Conversation
import com.harnessapk.chat.LifeConversationArchiveResult
import com.harnessapk.chat.LifeConversationDisplayStatus
import com.harnessapk.chat.LifeConversationOverviewItem
import com.harnessapk.chat.LifeConversationOverviewRepository
import com.harnessapk.chat.LifeConversationOverviewState
import com.harnessapk.common.AppContainer
import com.harnessapk.ui.components.ComfortListRow
import com.harnessapk.ui.theme.HarnessSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun conversationIdentityLabel(conversation: Conversation, agents: Map<String, Agent>): String? =
    conversation.agentId?.let { id ->
        "${agents[id]?.name ?: "已安装人物"} · 基于资料模拟"
    }

internal fun conversationMetadataLabel(
    conversation: Conversation,
    agents: Map<String, Agent>,
): String? = conversationIdentityLabel(conversation, agents)

private data class UndoArchiveNotice(
    val conversationId: String,
    val deadlineMillis: Long,
)

/**
 * The history screen keeps the existing callback surface while allowing the
 * parent to supply the batched life overview. Until that wiring is installed,
 * the legacy conversation stream remains a safe compatibility fallback.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ConversationListScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenChat: (String) -> Unit,
    onCreateConversation: () -> Unit,
    onCreatePhotoConversation: () -> Unit = {},
    onCreateVoiceConversation: () -> Unit = {},
    onOpenAgentPackages: () -> Unit = {},
    onOpenWikiLibrary: () -> Unit = {},
    onOpenGlobalSearch: () -> Unit = {},
    welcomeMessage: String? = null,
    onWelcomeDismissed: () -> Unit = {},
    lifeOverviewRepository: LifeConversationOverviewRepository? = null,
    onOpenArchive: (() -> Unit)? = null,
    creationInProgress: Boolean = false,
) {
    val conversations by container.chatRepository.observeConversations().collectAsState(initial = emptyList())
    val agents by container.agentRepository.observeAgents().collectAsState(initial = emptyList())
    val lifeOverviewFlow = remember(lifeOverviewRepository) {
        lifeOverviewRepository?.observe(includeArchived = false)
    }
    val lifeOverviewState = if (lifeOverviewFlow == null) {
        null
    } else {
        lifeOverviewFlow.collectAsState(initial = LifeConversationOverviewState.Loading)
            .value
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val undoDurationMillis = remember(context) { recommendedUndoDurationMillis(context) }
    var conversationToEditId by remember { mutableStateOf<String?>(null) }
    var titleDraft by remember { mutableStateOf("") }
    var undoNotice by remember { mutableStateOf<UndoArchiveNotice?>(null) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var historySearchExpanded by remember { mutableStateOf(false) }
    var historySearchQuery by remember { mutableStateOf("") }
    val agentsById = remember(agents) { agents.associateBy { it.id } }
    val visibleConversations = remember(conversations) { lifeConversations(conversations) }

    LaunchedEffect(undoNotice?.conversationId, undoNotice?.deadlineMillis) {
        val notice = undoNotice ?: return@LaunchedEffect
        val remaining = notice.deadlineMillis - System.currentTimeMillis()
        if (remaining > 0L) delay(remaining)
        if (undoNotice == notice) undoNotice = null
    }

    welcomeMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onWelcomeDismissed,
            title = { Text("配置完成") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onWelcomeDismissed) { Text("好的") }
            },
        )
    }

    conversationToEditId?.let { conversationId ->
        AlertDialog(
            onDismissRequest = { conversationToEditId = null },
            title = { Text("修改标题") },
            text = {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = titleDraft,
                    onValueChange = { titleDraft = it },
                    label = { Text("会话标题") },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = titleDraft.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val normalized = titleDraft.trim()
                            container.chatRepository.updateConversationTitle(conversationId, normalized)
                            lifeOverviewRepository?.setCustomTitle(conversationId, normalized)
                            conversationToEditId = null
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { conversationToEditId = null }) { Text("取消") }
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
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "history-actions") {
            HistoryActionRow(
                searchExpanded = historySearchExpanded,
                onToggleSearch = {
                    if (historySearchExpanded) historySearchQuery = ""
                    historySearchExpanded = !historySearchExpanded
                },
                onOpenArchive = onOpenArchive,
                onCreateConversation = onCreateConversation,
                creationInProgress = creationInProgress,
            )
            if (historySearchExpanded) {
                HistorySearchField(
                    query = historySearchQuery,
                    onQueryChange = { historySearchQuery = it },
                    onClear = { historySearchQuery = "" },
                    onClose = {
                        historySearchQuery = ""
                        historySearchExpanded = false
                    },
                )
            }
        }
        if (creationInProgress) {
            item(key = "life-creation-progress") {
                CreationProgressBanner()
            }
        }
        if (undoNotice != null) {
            item(key = "archive-undo") {
                ArchiveUndoBanner(
                    onUndo = {
                        val notice = undoNotice ?: return@ArchiveUndoBanner
                        scope.launch {
                            val restored = lifeOverviewRepository?.undoArchive(notice.conversationId) == true
                            if (restored) {
                                undoNotice = null
                                feedbackMessage = "已撤销归档"
                            } else {
                                feedbackMessage = "撤销窗口已结束，可在归档列表恢复"
                            }
                        }
                    },
                )
            }
        }
        feedbackMessage?.let { message ->
            item(key = "overview-feedback") {
                FeedbackBanner(message = message, onDismiss = { feedbackMessage = null })
            }
        }
        if (lifeOverviewRepository != null) {
            lifeOverviewItems(
                state = lifeOverviewState ?: LifeConversationOverviewState.Loading,
                agentsById = agentsById,
                searchQuery = historySearchQuery,
                onRetry = {
                    lifeOverviewRepository.refresh()
                    feedbackMessage = null
                },
                onOpenChat = onOpenChat,
                onEdit = { item ->
                    conversationToEditId = item.conversationId
                    titleDraft = item.title
                },
                onArchive = { item ->
                    scope.launch {
                        when (val result = lifeOverviewRepository.archive(item.conversationId)) {
                            is LifeConversationArchiveResult.Archived -> {
                                val accessibleDeadline = lifeOverviewRepository.extendUndoDeadline(
                                    conversationId = item.conversationId,
                                    durationMillis = undoDurationMillis,
                                ) ?: result.undoDeadlineMillis
                                undoNotice = UndoArchiveNotice(
                                    conversationId = item.conversationId,
                                    deadlineMillis = accessibleDeadline,
                                )
                                feedbackMessage = null
                            }

                            is LifeConversationArchiveResult.Blocked -> feedbackMessage = result.reason
                            LifeConversationArchiveResult.AlreadyArchived -> feedbackMessage = "已经在归档列表"
                            LifeConversationArchiveResult.NotFound -> feedbackMessage = "记录已不存在"
                        }
                    }
                },
            )
        } else {
            val filteredConversations = filterConversations(visibleConversations, historySearchQuery)
            conversationItems(
                conversations = filteredConversations,
                agentsById = agentsById,
                onOpenChat = onOpenChat,
                searchQuery = historySearchQuery,
                hasUnfilteredConversations = visibleConversations.isNotEmpty(),
                onEdit = {
                    conversationToEditId = it.id
                    titleDraft = it.title
                },
                onArchive = {
                    scope.launch {
                        container.chatRepository.archiveConversation(it.id)
                        feedbackMessage = "已移到归档"
                    }
                },
            )
        }
    }
}

@Composable
private fun HistoryActionRow(
    searchExpanded: Boolean,
    onToggleSearch: () -> Unit,
    onOpenArchive: (() -> Unit)?,
    onCreateConversation: () -> Unit,
    creationInProgress: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onToggleSearch,
            modifier = Modifier.size(HarnessSpacing.minimumTouchTarget),
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = if (searchExpanded) "关闭搜索" else "搜索记录",
            )
        }
        onOpenArchive?.let { openArchive ->
            IconButton(
                onClick = openArchive,
                modifier = Modifier.size(HarnessSpacing.minimumTouchTarget),
            ) {
                Icon(Icons.Outlined.Archive, contentDescription = "归档列表")
            }
        }
        TextButton(
            onClick = onCreateConversation,
            enabled = !creationInProgress,
            modifier = Modifier.heightIn(min = HarnessSpacing.minimumTouchTarget),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Icon(Icons.Outlined.Edit, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("新问题")
        }
    }
}

@Composable
private fun HistorySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("搜索标题或摘要") },
        singleLine = true,
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = null)
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(HarnessSpacing.minimumTouchTarget),
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "清空搜索")
                    }
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(HarnessSpacing.minimumTouchTarget),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭搜索")
                }
            }
        },
    )
}

@Composable
private fun CreationProgressBanner() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("正在打开问题…", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

internal fun LazyListScope.lifeOverviewItems(
    state: LifeConversationOverviewState,
    agentsById: Map<String, Agent>,
    onRetry: () -> Unit,
    onOpenChat: (String) -> Unit,
    onEdit: (LifeConversationOverviewItem) -> Unit,
    onArchive: (LifeConversationOverviewItem) -> Unit,
    searchQuery: String = "",
) {
    when (state) {
        LifeConversationOverviewState.Loading -> item(key = "overview-loading") {
            OverviewLoadingState()
        }

        is LifeConversationOverviewState.Error -> {
            item(key = "overview-error") {
                OverviewErrorState(message = state.message, onRetry = onRetry)
            }
            if (state.cachedItems.isNotEmpty()) {
                val visibleItems = filterLifeOverviewItems(state.cachedItems, searchQuery)
                if (visibleItems.isEmpty()) {
                    item(key = "overview-no-matches") { OverviewNoMatchesState() }
                } else {
                    lifeOverviewRows(
                        overviewItems = visibleItems,
                        agentsById = agentsById,
                        onOpenChat = onOpenChat,
                        onEdit = onEdit,
                        onArchive = onArchive,
                    )
                }
            }
        }

        is LifeConversationOverviewState.Content -> {
            if (state.items.isEmpty()) {
                item(key = "overview-empty") { OverviewEmptyState() }
            } else {
                val visibleItems = filterLifeOverviewItems(state.items, searchQuery)
                if (visibleItems.isEmpty()) {
                    item(key = "overview-no-matches") { OverviewNoMatchesState() }
                } else {
                    lifeOverviewRows(
                        overviewItems = visibleItems,
                        agentsById = agentsById,
                        onOpenChat = onOpenChat,
                        onEdit = onEdit,
                        onArchive = onArchive,
                    )
                }
            }
        }
    }
}

internal fun filterLifeOverviewItems(
    items: List<LifeConversationOverviewItem>,
    query: String,
): List<LifeConversationOverviewItem> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return items
    return items.filter { item ->
        item.title.contains(normalizedQuery, ignoreCase = true) ||
            item.summary.contains(normalizedQuery, ignoreCase = true)
    }
}

private fun filterConversations(
    conversations: List<Conversation>,
    query: String,
): List<Conversation> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return conversations
    return conversations.filter { it.title.contains(normalizedQuery, ignoreCase = true) }
}

@Composable
private fun OverviewNoMatchesState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("没有匹配的记录", style = MaterialTheme.typography.titleMedium)
        Text(
            "换个标题或摘要关键词试试。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun LazyListScope.lifeOverviewRows(
    overviewItems: List<LifeConversationOverviewItem>,
    agentsById: Map<String, Agent>,
    onOpenChat: (String) -> Unit,
    onEdit: (LifeConversationOverviewItem) -> Unit,
    onArchive: (LifeConversationOverviewItem) -> Unit,
) {
    var previousGroup: String? = null
    overviewItems.forEach { overviewItem ->
        val group = overviewItem.updatedAt.toDayGroup()
        if (group != previousGroup) {
            item(key = "overview-group-$group") {
                Text(
                    text = group,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            previousGroup = group
        }
        item(key = overviewItem.conversationId) {
            LifeOverviewRow(
                item = overviewItem,
                agentLabel = lifeAgentLabel(overviewItem, agentsById),
                timeLabel = overviewItem.updatedAt.toOverviewTime(includeDate = false),
                onOpen = { onOpenChat(overviewItem.conversationId) },
                onEdit = { onEdit(overviewItem) },
                onArchive = { onArchive(overviewItem) },
            )
        }
    }
}

@Composable
private fun OverviewLoadingState() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Text("正在读取历史记录…", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun OverviewErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("记录读取失败", style = MaterialTheme.typography.titleMedium)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = HarnessSpacing.minimumTouchTarget),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("重新读取")
            }
        }
    }
}

@Composable
private fun OverviewEmptyState() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("还没有历史记录", style = MaterialTheme.typography.titleMedium)
            Text(
                "先问一个天气、出行或购物清单问题，之后会在这里继续。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ArchiveUndoBanner(onUndo: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "已移到归档",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(
                onClick = onUndo,
                modifier = Modifier.heightIn(min = HarnessSpacing.minimumTouchTarget),
            ) { Text("撤销") }
        }
    }
}

@Composable
private fun FeedbackBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = HarnessSpacing.minimumTouchTarget),
            ) { Text("关闭") }
        }
    }
}

@Composable
private fun LifeOverviewRow(
    item: LifeConversationOverviewItem,
    agentLabel: String?,
    timeLabel: String,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    restoreAction: (() -> Unit)? = null,
    showEditAction: Boolean = true,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = item.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(HarnessSpacing.minimumTouchTarget),
                ) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (showEditAction) {
                        DropdownMenuItem(
                            text = { Text("修改标题") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                        )
                    }
                    if (restoreAction != null) {
                        DropdownMenuItem(
                            text = { Text("恢复到最近聊过") },
                            leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                restoreAction()
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (item.canArchive) "移到归档" else "暂不能归档",
                                    color = if (item.canArchive) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                            enabled = item.canArchive,
                            onClick = {
                                menuExpanded = false
                                onArchive()
                            },
                        )
                    }
                }
            }
        }
        Text(
            text = item.summary,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val showStatus = item.status != LifeConversationDisplayStatus.NONE &&
            item.status != LifeConversationDisplayStatus.COMPLETED
        val showDraft = item.hasDraft && item.status != LifeConversationDisplayStatus.DRAFT
        if (showStatus || showDraft) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showStatus) LifeStatusText(item)
                if (showDraft) {
                    Text(
                        text = "草稿",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        agentLabel?.let { label ->
            Text(
                text = label,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item.archiveBlockedReason?.let { reason ->
            if (!item.canArchive && !item.isArchived) {
                Text(
                    text = reason,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
}

@Composable
private fun LifeStatusText(item: LifeConversationOverviewItem) {
    if (item.status == LifeConversationDisplayStatus.NONE ||
        item.status == LifeConversationDisplayStatus.COMPLETED
    ) return
    val color = if (item.status == LifeConversationDisplayStatus.FAILED) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    Text(
        text = item.statusLabel,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun lifeAgentLabel(
    item: LifeConversationOverviewItem,
    agentsById: Map<String, Agent>,
): String? = item.agentId?.let { id ->
    "${agentsById[id]?.name ?: "已安装人物"} · 基于资料模拟"
}

/** Archive list with durable restore. It intentionally has no delete action. */
@Composable
fun ArchivedConversationListScreen(
    repository: LifeConversationOverviewRepository,
    contentPadding: PaddingValues,
    onOpenChat: (String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val overviewFlow = remember(repository) { repository.observe(includeArchived = true) }
    val state by overviewFlow
        .collectAsState(initial = LifeConversationOverviewState.Loading)
    val stateSnapshot = state
    val scope = rememberCoroutineScope()
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    val restoreConversation: (String) -> Unit = { conversationId ->
        scope.launch {
            val restored = try {
                repository.restoreFromArchiveList(conversationId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                false
            }
            feedbackMessage = archiveRestoreFeedbackMessage(restored)
        }
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
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "archive-title") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "归档列表",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                onBack?.let { back ->
                    TextButton(
                        onClick = back,
                        modifier = Modifier.heightIn(min = HarnessSpacing.minimumTouchTarget),
                    ) { Text("返回生活") }
                }
            }
        }
        feedbackMessage?.let { message ->
            item(key = "archive-feedback") {
                FeedbackBanner(message = message, onDismiss = { feedbackMessage = null })
            }
        }
        archivedConversationItems(
            state = stateSnapshot,
            onRetry = repository::refresh,
            onOpenChat = onOpenChat,
            onRestore = restoreConversation,
        )
    }
}

internal fun archiveRestoreFeedbackMessage(restored: Boolean): String =
    if (restored) "已恢复到最近聊过" else "恢复失败，请重试"

internal fun LazyListScope.archivedConversationItems(
    state: LifeConversationOverviewState,
    onRetry: () -> Unit,
    onOpenChat: (String) -> Unit,
    onRestore: (String) -> Unit,
) {
    val archivedItems = when (state) {
        LifeConversationOverviewState.Loading -> emptyList()
        is LifeConversationOverviewState.Content -> state.items.filter { it.isArchived }
        is LifeConversationOverviewState.Error -> state.cachedItems.filter { it.isArchived }
    }
    when (state) {
        LifeConversationOverviewState.Loading -> item(key = "archive-loading") { OverviewLoadingState() }
        is LifeConversationOverviewState.Error -> {
            item(key = "archive-error") {
                OverviewErrorState(message = state.message, onRetry = onRetry)
            }
            if (archivedItems.isNotEmpty()) {
                archivedConversationRows(
                    archivedItems = archivedItems,
                    onOpenChat = onOpenChat,
                    onRestore = onRestore,
                )
            }
        }
        is LifeConversationOverviewState.Content -> archivedConversationRows(
            archivedItems = archivedItems,
            onOpenChat = onOpenChat,
            onRestore = onRestore,
        )
    }
}

internal fun LazyListScope.archivedConversationRows(
    archivedItems: List<LifeConversationOverviewItem>,
    onOpenChat: (String) -> Unit,
    onRestore: (String) -> Unit,
) {
    if (archivedItems.isEmpty()) {
        item(key = "archive-empty") {
            Text(
                "这里还没有归档记录。",
                modifier = Modifier.padding(vertical = 16.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        items(archivedItems, key = { it.conversationId }) { item ->
            LifeOverviewRow(
                item = item,
                agentLabel = lifeAgentLabel(item, emptyMap()),
                timeLabel = item.updatedAt.toOverviewTime(),
                onOpen = { onOpenChat(item.conversationId) },
                onEdit = {},
                onArchive = {},
                restoreAction = { onRestore(item.conversationId) },
                showEditAction = false,
            )
        }
    }
}

private fun LazyListScope.conversationItems(
    conversations: List<Conversation>,
    agentsById: Map<String, Agent>,
    onOpenChat: (String) -> Unit,
    onEdit: (Conversation) -> Unit,
    onArchive: (Conversation) -> Unit,
    searchQuery: String = "",
    hasUnfilteredConversations: Boolean = conversations.isNotEmpty(),
) {
    if (conversations.isEmpty()) {
        item(key = "legacy-empty") {
            if (searchQuery.trim().isNotEmpty() && hasUnfilteredConversations) {
                OverviewNoMatchesState()
            } else {
                OverviewEmptyState()
            }
        }
    } else {
        items(conversations, key = { it.id }) { conversation ->
            ConversationRow(
                conversation = conversation,
                metadata = conversationMetadataLabel(conversation, agentsById),
                onOpen = { onOpenChat(conversation.id) },
                onEdit = { onEdit(conversation) },
                onArchive = { onArchive(conversation) },
            )
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    metadata: String?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
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
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(HarnessSpacing.minimumTouchTarget),
                    ) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("修改标题") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("移到归档") },
                            leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onArchive()
                            },
                        )
                    }
                }
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
    }
}

private fun Long.toOverviewTime(includeDate: Boolean = true): String {
    val formatter = SimpleDateFormat(
        if (includeDate) "MM-dd HH:mm" else "HH:mm",
        Locale.getDefault(),
    )
    return formatter.format(Date(this))
}

private fun recommendedUndoDurationMillis(context: Context): Long {
    val defaultDuration = LifeConversationOverviewRepository.DEFAULT_UNDO_WINDOW_MILLIS
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return defaultDuration
    val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
        as? AccessibilityManager
        ?: return defaultDuration
    val recommended = accessibilityManager.getRecommendedTimeoutMillis(
        defaultDuration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
    ).toLong()
    return recommended.coerceAtLeast(defaultDuration)
}

@Composable
private fun Long.toDisplayTime(): String = toOverviewTime()

private fun Long.toDayGroup(): String {
    val value = java.util.Calendar.getInstance().apply { timeInMillis = this@toDayGroup }
    val today = java.util.Calendar.getInstance()
    if (value.sameDayAs(today)) return "今天"
    val yesterday = today.clone() as java.util.Calendar
    yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1)
    if (value.sameDayAs(yesterday)) return "昨天"
    return SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(this))
}

private fun java.util.Calendar.sameDayAs(other: java.util.Calendar): Boolean =
    get(java.util.Calendar.ERA) == other.get(java.util.Calendar.ERA) &&
        get(java.util.Calendar.YEAR) == other.get(java.util.Calendar.YEAR) &&
        get(java.util.Calendar.DAY_OF_YEAR) == other.get(java.util.Calendar.DAY_OF_YEAR)
