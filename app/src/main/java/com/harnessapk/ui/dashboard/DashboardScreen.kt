package com.harnessapk.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import com.harnessapk.remote.DashboardThread
import com.harnessapk.remote.DashboardViewedStore
import com.harnessapk.remote.RemoteConnectionStatus

// 副屏 dashboard：只读不写、无二级界面、无跳转。
// 卡片的唯一动作 = 点击后让 Mac 主屏聚焦对应线程（thread.focus 深链）。
@Composable
fun DashboardScreen(
    container: AppContainer,
    viewedStore: DashboardViewedStore,
    onExit: () -> Unit,
) {
    val dashboard by container.remoteRepository.dashboard.collectAsState()
    val connection by container.remoteRepository.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        container.remoteRepository.requestDashboardSnapshot()
        container.remoteRepository.focusResults.collect { result ->
            snackbarHostState.showSnackbar(
                if (result.ok) "已在 Mac 主屏打开该线程" else "聚焦失败：${result.message ?: "未知原因"}",
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // 离开副屏时记录查看时间：此后到达的 done 才算未读。
            container.remoteRepository.dashboard.value.threads.let { threads ->
                viewedStore.markAllViewed(threads.map { it.threadId }, System.currentTimeMillis())
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Agent 副屏", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                val connected = connection.connectionStatus == RemoteConnectionStatus.CONNECTED
                Text(
                    if (connected) "Mac 已连接" else "Mac 未连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) Color(0xFF34C77B) else MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onExit) { Text("退出") }
            }
            val threads = dashboard.threads
            if (threads.isEmpty()) {
                Text(
                    "暂无活跃线程。在 Mac 上开始一个 Codex 任务后，这里会实时亮起。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(threads, key = { it.threadId }) { thread ->
                    DashboardCard(
                        thread = thread,
                        unread = isDashboardUnread(thread, viewedStore.lastViewedAt(thread.threadId)),
                        onClick = { container.remoteRepository.focusThread(thread.threadId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardCard(thread: DashboardThread, unread: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tone = dashboardTone(thread.status)
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color(dashboardToneArgb(tone))),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    thread.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = buildString {
                    append(dashboardRelativeTime(System.currentTimeMillis(), thread.updatedAtMs))
                    thread.cwd?.takeIf(String::isNotBlank)?.let { append(" · ").append(it) }
                    thread.gitBranch?.takeIf(String::isNotBlank)?.let { append(" · ").append(it) }
                }
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (thread.approx) {
                    Text(
                        dashboardStatusLabel(thread),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFB020),
                    )
                } else {
                    Text(dashboardStatusLabel(thread), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (unread) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF5C5C)),
                )
            }
        }
    }
}
