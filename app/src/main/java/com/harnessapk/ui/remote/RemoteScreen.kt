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
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.harnessapk.common.AppContainer
import com.harnessapk.remote.RemoteConnectionStatus
import com.harnessapk.remote.RemoteTimelineItem
import com.harnessapk.remote.RemoteUiState
import com.harnessapk.remote.WorkspaceCandidate
import com.harnessapk.ui.markdown.MarkdownMessage
import kotlinx.coroutines.delay

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
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("${profile?.hostName} · ${connectionLabel(state.connectionStatus)}", style = MaterialTheme.typography.titleMedium)
                state.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
            }
            Row {
                IconButton(onClick = container.remoteRepository::refreshThreads) { Icon(Icons.Outlined.Refresh, "刷新") }
                FilledIconButton(
                    onClick = {
                        showCreate = true
                        container.remoteRepository.requestWorkspaceCandidates()
                    },
                    enabled = !state.isCreatingThread,
                ) { Icon(Icons.Outlined.Add, "新建线程") }
            }
        }
        if (state.connectionStatus == RemoteConnectionStatus.CONNECTING || state.isThreadListLoading) {
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            Text("正在读取 Mac 会话…", modifier = Modifier.fillMaxWidth().wrapContentSize())
        } else if (state.connectionStatus == RemoteConnectionStatus.CONNECTED && state.threads.isEmpty()) {
            Text("Mac 上还没有会话", modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).wrapContentSize())
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(state.threads, key = { it.id }) { thread ->
                Card(onClick = { container.remoteRepository.selectThread(thread.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(thread.title, style = MaterialTheme.typography.titleMedium)
                        if (thread.preview.isNotBlank()) Text(thread.preview, maxLines = 2)
                        Text(thread.cwd ?: thread.status, style = MaterialTheme.typography.bodySmall)
                    }
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
private fun RemoteThreadDetail(container: AppContainer, state: RemoteUiState, padding: PaddingValues) {
    val context = LocalContext.current
    var workingStartedAtMillis by remember(state.selectedThreadId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.selectedThreadId, state.isWorking) {
        workingStartedAtMillis = if (state.isWorking) {
            workingStartedAtMillis ?: System.currentTimeMillis()
        } else {
            null
        }
    }
    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier)
            if (state.isWorking) IconButton(onClick = container.remoteRepository::interrupt) { Icon(Icons.Outlined.Cancel, "停止") }
        }
        workingStartedAtMillis?.let { RemoteWorkingBanner(startedAtMillis = it) }
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
    else -> "远程事件"
}

internal fun remoteTimelineStatusLabel(status: String?): String? = when (status) {
    null, "" -> null
    "sending" -> "发送中"
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
        onDismissRequest = onDismiss, title = { Text("新建远程线程") },
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
