package com.harnessapk.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.harnessapk.common.AppContainer
import com.harnessapk.remote.RemoteConnectionStatus
import com.harnessapk.remote.RemoteTimelineItem
import com.harnessapk.remote.RemoteThread
import com.harnessapk.remote.RemoteThreadExecution
import com.harnessapk.remote.RemoteThreadExecutionState
import com.harnessapk.remote.RemoteUiState
import com.harnessapk.remote.WorkspaceCandidate
import com.harnessapk.remote.isActive
import com.harnessapk.remote.remoteFeatureAvailability
import com.harnessapk.ui.markdown.MarkdownMessage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RemoteScreen(container: AppContainer, contentPadding: PaddingValues) {
    val state by container.remoteRepository.state.collectAsState()
    val profile by container.remoteProfileStore.profile.collectAsState()
    LaunchedEffect(profile) { if (profile != null) container.remoteRepository.connect() }
    if (profile == null) {
        Box(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp)) {
            Text("请先在设置中扫描 Mac Bridge 的配对二维码。")
        }
        return
    }
    BackHandler(enabled = state.selectedThreadId != null) {
        container.remoteRepository.clearSelection()
    }
    if (state.selectedThreadId == null) RemoteThreadList(container, state, contentPadding)
    else RemoteThreadDetail(container, state, contentPadding)
}

@Composable
private fun RemoteThreadList(container: AppContainer, state: RemoteUiState, padding: PaddingValues) {
    var showCreate by remember { mutableStateOf(false) }
    val profile by container.remoteProfileStore.profile.collectAsState()
    val featureAvailability = remoteFeatureAvailability(state.capabilities)
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
        RemoteThreadListHeader(
            hostName = profile?.hostName.orEmpty(),
            connectionStatus = state.connectionStatus,
            creating = state.isCreatingThread,
            onRefresh = container.remoteRepository::refreshThreads,
            onCreate = {
                showCreate = true
                container.remoteRepository.requestWorkspaceCandidates()
            },
        )
        state.errorMessage?.let {
            Text(
                it,
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
            )
        }
        val loading = state.connectionStatus == RemoteConnectionStatus.CONNECTING || state.isThreadListLoading
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        }
        if (state.threads.isNotEmpty()) {
            Text(
                "最近会话",
                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (loading && state.threads.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("正在读取 Mac 会话…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (state.connectionStatus == RemoteConnectionStatus.CONNECTED && state.threads.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Mac 上还没有会话", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 10.dp),
            ) {
                items(state.threads, key = { it.id }) { thread ->
                    RemoteThreadCard(
                        thread = thread,
                        latestUserMessageLoadingEnabled = featureAvailability.canLoadLatestUserMessage,
                        executionStatusLoadingEnabled = featureAvailability.canLoadThreadExecutionStatus,
                        onLoadLatestUserMessage = container.remoteRepository::loadThreadSummary,
                        onClick = { container.remoteRepository.selectThread(thread.id) },
                    )
                }
            }
        }
    }
    if (showCreate) {
        CreateThreadDialog(
            onDismiss = { if (!state.isCreatingThread) showCreate = false },
            onCreate = { cwd ->
                showCreate = false
                container.remoteRepository.createThread(cwd)
            },
            candidates = state.workspaceCandidates,
            candidatesLoaded = state.workspaceCandidatesLoaded,
            creating = state.isCreatingThread,
        )
    }
}

@Composable
internal fun RemoteThreadListHeader(
    hostName: String,
    connectionStatus: RemoteConnectionStatus,
    creating: Boolean,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                hostName.ifBlank { "Mac Bridge" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                connectionLabel(connectionStatus),
                style = MaterialTheme.typography.labelMedium,
                color = if (connectionStatus == RemoteConnectionStatus.CONNECTED) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新远程会话")
        }
        Button(onClick = onCreate, enabled = !creating) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text("新建会话")
        }
    }
}

@Composable
internal fun RemoteThreadCard(
    thread: RemoteThread,
    onClick: () -> Unit,
    nowMillis: Long = System.currentTimeMillis(),
    latestUserMessageLoadingEnabled: Boolean = true,
    executionStatusLoadingEnabled: Boolean = false,
    onLoadLatestUserMessage: (String) -> Unit = {},
) {
    RemoteThreadSummaryLoader(
        thread = thread,
        latestUserMessageLoadingEnabled = latestUserMessageLoadingEnabled,
        executionStatusLoadingEnabled = executionStatusLoadingEnabled,
        onLoadThreadSummary = onLoadLatestUserMessage,
    )
    val preview = remoteThreadPreviewText(thread.latestUserMessage ?: thread.preview)
    val workspace = remoteWorkspaceLabel(thread.cwd)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "打开远程会话：${thread.title}" },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    thread.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                RemoteExecutionStatusBadge(thread.execution)
            }
            if (preview.isNotBlank() && preview != thread.title) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatRemoteUpdatedAt(thread.updatedAt, nowMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text(
                    workspace,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun RemoteThreadSummaryLoader(
    thread: RemoteThread?,
    latestUserMessageLoadingEnabled: Boolean,
    executionStatusLoadingEnabled: Boolean,
    retryUnknownExecution: Boolean = false,
    onLoadThreadSummary: (String) -> Unit,
) {
    LaunchedEffect(
        thread?.id,
        thread?.updatedAt,
        thread?.latestUserMessage,
        thread?.execution,
        latestUserMessageLoadingEnabled,
        executionStatusLoadingEnabled,
        retryUnknownExecution,
    ) {
        thread ?: return@LaunchedEffect
        val needsSummary = latestUserMessageLoadingEnabled && thread.latestUserMessage == null
        val needsExecution = executionStatusLoadingEnabled &&
            (thread.execution.state == RemoteThreadExecutionState.UNKNOWN || thread.execution.state.isActive)
        if (needsSummary || needsExecution) {
            onLoadThreadSummary(thread.id)
        }
        if (executionStatusLoadingEnabled && thread.execution.state.isActive) {
            while (true) {
                delay(3_000L)
                onLoadThreadSummary(thread.id)
            }
        }
        if (executionStatusLoadingEnabled && retryUnknownExecution &&
            thread.execution.state == RemoteThreadExecutionState.UNKNOWN
        ) {
            while (true) {
                delay(10_000L)
                onLoadThreadSummary(thread.id)
            }
        }
    }
}

@Composable
internal fun RemoteExecutionStatusBadge(execution: RemoteThreadExecution) {
    val label = remoteExecutionStatusLabel(execution.state)
    val active = execution.state.isActive
    val containerColor = when (execution.state) {
        RemoteThreadExecutionState.FAILED -> MaterialTheme.colorScheme.errorContainer
        RemoteThreadExecutionState.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
        else -> if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when (execution.state) {
        RemoteThreadExecutionState.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        RemoteThreadExecutionState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        modifier = Modifier.semantics { contentDescription = "会话状态：$label" },
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (active) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

internal fun remoteExecutionStatusLabel(state: RemoteThreadExecutionState): String = when (state) {
    RemoteThreadExecutionState.RUNNING -> "执行中"
    RemoteThreadExecutionState.WAITING_APPROVAL -> "等待审批"
    RemoteThreadExecutionState.WAITING_USER -> "等待回复"
    RemoteThreadExecutionState.COMPLETED -> "已完成"
    RemoteThreadExecutionState.FAILED -> "失败"
    RemoteThreadExecutionState.INTERRUPTED -> "已中断"
    RemoteThreadExecutionState.UNKNOWN -> "状态未同步"
}

internal fun remoteThreadPreviewText(raw: String): String {
    val delegation = raw.contains("<codex_delegation", ignoreCase = true)
    val inputMarker = Regex("<input>", RegexOption.IGNORE_CASE).find(raw)
    if (delegation && inputMarker == null) return ""
    val scoped = inputMarker?.let { marker ->
        raw.substring(marker.range.last + 1).substringBefore("</input>")
    } ?: raw
    val readable = if (delegation) scoped.replace(Regex("<[^>]*>?"), " ") else scoped
    val firstMeaningfulLine = readable.lineSequence()
        .map(String::trim)
        .firstOrNull(String::isNotBlank)
        .orEmpty()
    return firstMeaningfulLine.replace(Regex("\\s+"), " ").take(180).trimEnd()
}

internal fun remoteWorkspaceLabel(cwd: String?): String {
    val parts = cwd.orEmpty().trim().trimEnd('/').split('/').filter(String::isNotBlank)
    return parts.takeLast(2).joinToString(" / ").ifBlank { "工作目录未知" }
}

internal fun formatRemoteUpdatedAt(updatedAt: Long, nowMillis: Long): String {
    if (updatedAt <= 0L) return "时间未知"
    val elapsed = (nowMillis - updatedAt).coerceAtLeast(0L)
    return when {
        elapsed < 60_000L -> "刚刚"
        elapsed < 3_600_000L -> "${elapsed / 60_000L} 分钟前"
        elapsed < 86_400_000L -> "${elapsed / 3_600_000L} 小时前"
        elapsed < 172_800_000L -> "昨天"
        else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(updatedAt))
    }
}

@Composable
private fun RemoteThreadDetail(container: AppContainer, state: RemoteUiState, padding: PaddingValues) {
    val context = LocalContext.current
    val selectedThread = state.threads.firstOrNull { it.id == state.selectedThreadId }
    val selectedExecution = selectedThread
        ?.execution
        ?.takeUnless { it.state == RemoteThreadExecutionState.UNKNOWN && state.isWorking }
        ?: RemoteThreadExecution(
            state = if (state.isWorking) RemoteThreadExecutionState.RUNNING else RemoteThreadExecutionState.UNKNOWN,
            turnId = state.activeTurnId,
        )
    val executionStatusLoadingEnabled = remoteFeatureAvailability(state.capabilities).canLoadThreadExecutionStatus
    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier)
            if (selectedExecution.state.isActive) {
                IconButton(onClick = container.remoteRepository::interrupt) { Icon(Icons.Outlined.Cancel, "停止") }
            }
        }
        RemoteThreadDetailStatus(
            thread = selectedThread,
            execution = selectedExecution,
            executionStatusLoadingEnabled = executionStatusLoadingEnabled,
            onLoadThreadSummary = container.remoteRepository::loadThreadSummary,
        )
        state.errorMessage?.let { RemoteThreadErrorBanner(it) }
        RemoteTimelineList(
            threadId = state.selectedThreadId.orEmpty(),
            items = state.timeline,
            loading = state.isTimelineLoading,
            canLoadOlder = state.olderTimelineCursor != null,
            loadingOlder = state.isOlderTimelineLoading,
            onLoadOlder = container.remoteRepository::loadOlderHistory,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        state.approvals.forEach { approval ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("等待审批", style = MaterialTheme.typography.titleSmall)
                    Text(approval.reason)
                    approval.command?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { container.remoteRepository.respondToApproval(approval, "allow") }) { Text("允许一次") }
                        OutlinedButton(onClick = { container.remoteRepository.respondToApproval(approval, "allowAlways") }) { Text("总是允许") }
                        OutlinedButton(onClick = { container.remoteRepository.respondToApproval(approval, "deny") }) { Text("拒绝") }
                    }
                }
            }
        }
        RemoteComposer(isWorking = state.isWorking, onSubmit = { text ->
            com.harnessapk.remote.RemoteConnectionService.start(context)
            if (state.isWorking) container.remoteRepository.steer(text) else container.remoteRepository.startTurn(text)
        })
    }
}

@Composable
internal fun RemoteThreadErrorBanner(message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("消息发送未完成", style = MaterialTheme.typography.labelLarge)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun RemoteThreadDetailStatus(
    thread: RemoteThread?,
    execution: RemoteThreadExecution,
    executionStatusLoadingEnabled: Boolean,
    onLoadThreadSummary: (String) -> Unit,
) {
    RemoteThreadSummaryLoader(
        thread = thread,
        latestUserMessageLoadingEnabled = false,
        executionStatusLoadingEnabled = executionStatusLoadingEnabled,
        retryUnknownExecution = true,
        onLoadThreadSummary = onLoadThreadSummary,
    )
    var workingStartedAtMillis by remember(thread?.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(thread?.id, execution) {
        workingStartedAtMillis = if (execution.state.isActive) {
            execution.startedAtMillis ?: workingStartedAtMillis ?: System.currentTimeMillis()
        } else {
            null
        }
    }
    RemoteExecutionStatusBanner(
        execution = execution,
        startedAtMillis = workingStartedAtMillis,
    )
}

@Composable
internal fun RemoteExecutionStatusBanner(
    execution: RemoteThreadExecution,
    startedAtMillis: Long? = execution.startedAtMillis,
) {
    val label = remoteExecutionStatusLabel(execution.state)
    if (execution.state == RemoteThreadExecutionState.RUNNING) {
        Box(Modifier.semantics { contentDescription = "当前会话状态：$label" }) {
            RemoteWorkingBanner(startedAtMillis = startedAtMillis ?: System.currentTimeMillis())
        }
        return
    }
    val (title, description) = when (execution.state) {
        RemoteThreadExecutionState.WAITING_APPROVAL -> "等待审批" to "需要你处理后，Codex 才会继续"
        RemoteThreadExecutionState.WAITING_USER -> "等待你的回复" to "补充信息后，Codex 会继续当前任务"
        RemoteThreadExecutionState.COMPLETED -> "任务已完成" to "可以继续发送消息开始下一轮"
        RemoteThreadExecutionState.FAILED -> "执行失败" to "查看最后一条消息后可继续重试"
        RemoteThreadExecutionState.INTERRUPTED -> "任务已中断" to "可以继续发送消息重新开始"
        RemoteThreadExecutionState.UNKNOWN -> "状态尚未同步" to "正在从 Mac 获取最新执行状态"
        RemoteThreadExecutionState.RUNNING -> error("handled above")
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { contentDescription = "当前会话状态：$label" },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (execution.state.isActive) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun RemoteWorkingBanner(
    startedAtMillis: Long,
    nowMillis: Long? = null,
) {
    var clockMillis by remember(startedAtMillis, nowMillis) {
        mutableLongStateOf(nowMillis ?: System.currentTimeMillis())
    }
    LaunchedEffect(startedAtMillis, nowMillis) {
        if (nowMillis != null) {
            clockMillis = nowMillis
            return@LaunchedEffect
        }
        while (true) {
            clockMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val elapsedSeconds = ((clockMillis - startedAtMillis) / 1_000L).coerceAtLeast(0L)
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Column {
                Text("Codex 正在处理 · 已等待 $elapsedSeconds 秒", style = MaterialTheme.typography.labelLarge)
                Text(
                    "收到新内容后会继续实时显示",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun RemoteComposer(
    isWorking: Boolean,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    fun submit() {
        val text = input.trim()
        if (text.isEmpty()) return
        onSubmit(text)
        input = ""
    }
    Row(
        modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f).testTag("remote-composer-input"),
            label = { Text(if (isWorking) "引导当前任务" else "发送给 Codex") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            maxLines = 5,
        )
        FilledIconButton(onClick = ::submit) { Icon(Icons.AutoMirrored.Outlined.Send, "发送") }
    }
}

@Composable
internal fun RemoteTimelineList(
    threadId: String,
    items: List<RemoteTimelineItem>,
    loading: Boolean,
    canLoadOlder: Boolean = false,
    loadingOlder: Boolean = false,
    onLoadOlder: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
) {
    if (loading && items.isEmpty()) {
        Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("正在读取 Mac 会话…", modifier = Modifier.padding(top = 12.dp))
            }
        }
        return
    }
    if (!loading && items.isEmpty() && !canLoadOlder && !loadingOlder) {
        Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("这个会话还没有消息", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    var positionedAtLatest by remember(threadId) { mutableStateOf(false) }
    var previousItemCount by remember(threadId) { mutableIntStateOf(0) }
    var previousLastItemId by remember(threadId) { mutableStateOf<String?>(null) }
    var previousLastItemRevision by remember(threadId) { mutableStateOf<String?>(null) }
    var prependAnchorId by remember(threadId) { mutableStateOf<String?>(null) }
    var prependAnchorOffset by remember(threadId) { mutableIntStateOf(0) }
    val lastItemRevision = items.lastOrNull()?.let { "${it.id}:${it.status}:${it.text.length}" }
    LaunchedEffect(threadId, items.size, lastItemRevision, loading, loadingOlder) {
        val historyHeaderCount = if (canLoadOlder || loadingOlder) 1 else 0
        if (loadingOlder && prependAnchorId == null) {
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index >= historyHeaderCount }?.let { visible ->
                prependAnchorId = items.getOrNull(visible.index - historyHeaderCount)?.id
                prependAnchorOffset = visible.offset
            }
        }
        if (!loading && items.isNotEmpty()) {
            val anchorId = prependAnchorId
            if (!loadingOlder && anchorId != null) {
                val anchorIndex = items.indexOfFirst { it.id == anchorId }
                if (anchorIndex >= 0) {
                    listState.scrollToItem(historyHeaderCount + anchorIndex, -prependAnchorOffset)
                }
                prependAnchorId = null
                previousItemCount = items.size
                previousLastItemId = items.lastOrNull()?.id
                previousLastItemRevision = lastItemRevision
                return@LaunchedEffect
            }
            val wasNearLatest = previousItemCount == 0 ||
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index.orZero() >= historyHeaderCount + previousItemCount - 2
            val olderPageWasPrepended = items.size > previousItemCount &&
                previousLastItemId != null && items.lastOrNull()?.id == previousLastItemId
            if (!positionedAtLatest) {
                listState.scrollToItem(historyHeaderCount + items.lastIndex, listState.latestViewportOffset())
                positionedAtLatest = true
            } else if (
                !olderPageWasPrepended && wasNearLatest &&
                (items.size > previousItemCount || lastItemRevision != previousLastItemRevision)
            ) {
                listState.animateScrollToItem(
                    historyHeaderCount + items.lastIndex,
                    listState.latestViewportOffset(),
                )
            }
            previousItemCount = items.size
            previousLastItemId = items.lastOrNull()?.id
            previousLastItemRevision = lastItemRevision
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (canLoadOlder || loadingOlder) {
            item(key = "load-older-history") {
                OutlinedButton(
                    onClick = onLoadOlder,
                    enabled = canLoadOlder && !loadingOlder,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (loadingOlder) "正在加载更早内容…" else "加载更早内容")
                }
            }
        }
        items(items, key = { it.id }) { TimelineCard(it) }
    }
}

private fun Int?.orZero(): Int = this ?: 0

private fun LazyListState.latestViewportOffset(): Int =
    (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(0)

@Composable
internal fun TimelineCard(item: RemoteTimelineItem) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(remoteTimelineKindLabel(item.kind), style = MaterialTheme.typography.labelMedium)
            when (item.kind) {
                "agentMessage", "userMessage" -> MarkdownMessage(item.text)
                "commandExecution" -> RemoteCommandText(item)
                "fileChange" -> MarkdownMessage(item.text)
                else -> Text(item.text)
            }
            remoteTimelineStatusLabel(item.status)?.let { status ->
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RemoteCommandText(item: RemoteTimelineItem) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val canExpand = item.text.length > 160 || item.text.lineSequence().count() > 3
    Text(
        item.text,
        fontFamily = FontFamily.Monospace,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
    )
    if (canExpand) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起命令" else "查看完整命令")
        }
    }
}

internal fun remoteTimelineKindLabel(kind: String): String = when (kind) {
    "userMessage" -> "你"
    "agentMessage" -> "Codex"
    "commandExecution" -> "命令"
    "fileChange" -> "文件变更"
    "reasoning" -> "思考"
    "continuation" -> "大会话续聊"
    else -> "远程事件"
}

internal fun remoteTimelineStatusLabel(status: String?): String? = when (status) {
    null, "" -> null
    "sending" -> "发送中"
    "sent" -> null
    "reconciling" -> "发送结果待确认"
    "sendFailed" -> "发送失败"
    "streaming", "inProgress", "running" -> "进行中"
    "completed", "succeeded" -> "已完成"
    "failed" -> "失败"
    "cancelled", "canceled" -> "已停止"
    else -> status
}

@Composable
internal fun CreateThreadDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    candidates: List<WorkspaceCandidate>,
    candidatesLoaded: Boolean,
    creating: Boolean,
) {
    var cwd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("新建远程会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("最近使用的 Mac 工作区", style = MaterialTheme.typography.labelLarge)
                if (!candidatesLoaded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text("正在读取…")
                    }
                } else if (candidates.isEmpty()) {
                    Text("没有可用记录，也可以手动输入路径", style = MaterialTheme.typography.bodySmall)
                } else {
                    candidates.take(5).forEach { candidate ->
                        OutlinedButton(
                            onClick = { onCreate(candidate.cwd) },
                            enabled = !creating,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(candidate.displayName)
                                Text(candidate.cwd, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    enabled = !creating,
                    label = { Text("或输入 Mac 项目绝对路径") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (cwd.isNotBlank()) onCreate(cwd) }, enabled = cwd.isNotBlank() && !creating) {
                Text(if (creating) "创建中…" else "创建")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !creating) { Text("取消") } },
    )
}

internal fun connectionLabel(status: RemoteConnectionStatus): String = when (status) {
    RemoteConnectionStatus.CONNECTED -> "在线"
    RemoteConnectionStatus.CONNECTING -> "连接中"
    RemoteConnectionStatus.ERROR -> "连接异常"
    RemoteConnectionStatus.DISCONNECTED -> "离线"
}
