package com.harnessapk.ui.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

// 一屏卡片工作台：所有线程以紧凑卡片铺满一屏、不滚动；
// 卡片唯一动作 = 点按让 Mac 主屏聚焦对应线程；副屏只读不写。
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

    val threads = remember(dashboard.threads) { sortDashboardThreadsForConsole(dashboard.threads) }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Agent 副屏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        dashboardSummaryLabel(threads),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val connected = connection.connectionStatus == RemoteConnectionStatus.CONNECTED
                Text(
                    if (connected) "Mac 已连接" else "Mac 未连接",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (connected) Color(0xFF34C77B) else MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onExit) { Text("退出") }
            }
            if (threads.isEmpty()) {
                Text(
                    "暂无活跃线程。在 Mac 上开始一个 Codex 任务后，这里会实时亮起。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(threads, key = { it.threadId }) { thread ->
                    ConsoleTile(
                        thread = thread,
                        unread = isDashboardUnread(thread, viewedStore.lastViewedAt(thread.threadId)),
                        onClick = { container.remoteRepository.focusThread(thread.threadId) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsoleTile(thread: DashboardThread, unread: Boolean, onClick: () -> Unit) {
    val accent = Color(dashboardToneArgb(dashboardTone(thread.status)))
    Card(modifier = Modifier.combinedClickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(modifier = Modifier.width(5.dp).fillMaxHeight().background(accent))
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        thread.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (unread) {
                        Box(
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF5C5C)),
                        )
                    }
                }
                Text(
                    dashboardStatusLabel(thread),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    maxLines = 1,
                )
                Text(
                    dashboardRelativeTime(System.currentTimeMillis(), thread.updatedAtMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
