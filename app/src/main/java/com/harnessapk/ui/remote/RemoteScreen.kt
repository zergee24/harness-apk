package com.harnessapk.ui.remote

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import com.harnessapk.remote.RemoteConnectionStatus
import com.harnessapk.remote.RemoteTimelineItem
import com.harnessapk.remote.RemoteUiState
import com.harnessapk.remote.formatRelativeTime
import com.harnessapk.ui.markdown.MarkdownMessage

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
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("${container.remoteProfileStore.profile.value?.hostName} · ${connectionLabel(state.connectionStatus)}", style = MaterialTheme.typography.titleMedium)
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Row {
                IconButton(onClick = container.remoteRepository::refreshThreads) { Icon(Icons.Outlined.Refresh, "刷新") }
                FilledIconButton(onClick = { showCreate = true }) { Icon(Icons.Outlined.Add, "新建线程") }
            }
        }
        if (state.connectionStatus == RemoteConnectionStatus.CONNECTING) CircularProgressIndicator()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(state.threads, key = { it.id }) { thread ->
                Card(onClick = { container.remoteRepository.selectThread(thread.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                thread.title, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (state.activeThreadId == thread.id) {
                                Text("进行中", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (thread.preview.isNotBlank()) {
                            Text(thread.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                thread.cwd ?: "", style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                formatRelativeTime(System.currentTimeMillis(), thread.updatedAt),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    if (showCreate) CreateThreadDialog(onDismiss = { showCreate = false }) { cwd ->
        showCreate = false; container.remoteRepository.createThread(cwd)
    }
}

@Composable
private fun RemoteThreadDetail(container: AppContainer, state: RemoteUiState, padding: PaddingValues) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            if (state.isWorking) IconButton(onClick = container.remoteRepository::interrupt) { Icon(Icons.Outlined.Cancel, "停止") }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.timeline, key = { it.id }) { TimelineCard(it) }
        }
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
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), label = { Text(if (state.isWorking) "引导当前任务" else "发送给 Codex") })
            FilledIconButton(onClick = {
                val text = input.trim(); if (text.isNotEmpty()) {
                    com.harnessapk.remote.RemoteConnectionService.start(context)
                    if (state.isWorking) container.remoteRepository.steer(text) else container.remoteRepository.startTurn(text)
                    input = ""
                }
            }) { Icon(Icons.Outlined.Send, "发送") }
        }
    }
}

private val timelineKindLabels = mapOf(
    "userMessage" to "用户",
    "agentMessage" to "助手",
    "reasoning" to "思考",
    "commandExecution" to "命令",
    "fileChange" to "文件变更",
)

@Composable
private fun TimelineCard(item: RemoteTimelineItem) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(timelineKindLabels[item.kind] ?: item.kind, style = MaterialTheme.typography.labelMedium)
            if (item.kind == "agentMessage" || item.kind == "userMessage") {
                MarkdownMessage(item.text)
            } else {
                Text(item.text)
            }
            item.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun CreateThreadDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var cwd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("新建远程线程") },
        text = { OutlinedTextField(value = cwd, onValueChange = { cwd = it }, label = { Text("Mac 上的项目绝对路径") }) },
        confirmButton = { TextButton(onClick = { if (cwd.isNotBlank()) onCreate(cwd) }) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun connectionLabel(status: RemoteConnectionStatus): String = when (status) {
    RemoteConnectionStatus.CONNECTED -> "在线"
    RemoteConnectionStatus.CONNECTING -> "连接中"
    RemoteConnectionStatus.ERROR -> "连接异常"
    RemoteConnectionStatus.DISCONNECTED -> "离线"
}
