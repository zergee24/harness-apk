package com.harnessapk.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import com.harnessapk.remote.DashboardQuota
import com.harnessapk.remote.DashboardThread
import com.harnessapk.remote.DashboardViewedStore
import com.harnessapk.remote.RemoteConnectionStatus
import kotlin.math.ceil
import kotlin.math.min

private val TileMinWidth = 150.dp
private val RailWidth = 150.dp

// 一屏卡片工作台：左侧两行横滑的线程网格（页码点指示），右栏常驻
// 余额环状卡 + Command Keys 预留卡；副屏只读不写，点卡片 = Mac 聚焦。
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
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (threads.isEmpty()) {
                        Text(
                            "暂无活跃线程。在 Mac 上开始一个 Codex 任务后，这里会实时亮起。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ThreadPagingGrid(
                        threads = threads,
                        onTap = { container.remoteRepository.focusThread(it.threadId) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Column(
                    modifier = Modifier.width(RailWidth),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuotaRingCard(modifier = Modifier.weight(1f), quota = dashboard.quota)
                    CommandKeysCard(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// 两行横滑网格 + 底部页码点：保持固定两行高度，超出部分左右翻页。
@Composable
private fun ThreadPagingGrid(
    threads: List<DashboardThread>,
    onTap: (DashboardThread) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val gridState = rememberLazyGridState()
        val columns = (maxWidth / (TileMinWidth + 8.dp)).toInt().coerceAtLeast(1)
        val pageSize = (columns * 2).coerceAtLeast(1)
        val pages = ceil(threads.size.toDouble() / pageSize).toInt().coerceAtLeast(1)
        val page = (gridState.firstVisibleItemIndex / pageSize).coerceIn(0, pages - 1)

        LazyHorizontalGrid(
            rows = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 4.dp),
        ) {
            items(threads, key = { it.threadId }) { thread ->
                ConsoleTile(
                    thread = thread,
                    unread = false,
                    onClick = { onTap(thread) },
                )
            }
        }
        if (pages > 1) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(pages) { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == page) 7.dp else 5.dp)
                            .clip(CircleShape)
                            .background(if (index == page) Color(0xFF4C8DFF) else Color(0xFF5A5A5E)),
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConsoleTile(thread: DashboardThread, unread: Boolean, onClick: () -> Unit) {
    val accent = Color(dashboardToneArgb(dashboardTone(thread.status)))
    Card(modifier = Modifier.width(172.dp).combinedClickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(modifier = Modifier.width(5.dp).fillMaxHeight().background(accent))
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    thread.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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

// 右栏：余额环状卡（剩余百分比 + 重置时间）。
@Composable
private fun QuotaRingCard(modifier: Modifier = Modifier, quota: DashboardQuota?) {
    val remaining = quota?.remainingPercent
    val ringColor = when {
        remaining == null -> Color(0xFF5A5A5E)
        remaining >= 50 -> Color(0xFF34C77B)
        remaining >= 20 -> Color(0xFFFFB020)
        else -> Color(0xFFFF5C5C)
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("余额", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(modifier = Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 9.dp.toPx()
                    drawArc(
                        color = Color(0xFF3A3A3E),
                        startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    if (remaining != null && remaining > 0) {
                        drawArc(
                            color = ringColor,
                            startAngle = -90f, sweepAngle = 360f * (min(remaining, 100) / 100f), useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        quota?.remainingPercent?.toString() ?: "--",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                when {
                    quota == null -> "余额未知"
                    else -> "剩余额度" + (quota.planType?.let { " · $it" } ?: "")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (quota != null && quota.resetsAtMs > 0) {
                val now = System.currentTimeMillis()
                val resetLabel = if (quota.resetsAtMs > now) {
                    val hours = (quota.resetsAtMs - now) / 3_600_000L
                    if (hours >= 24) "${hours / 24} 天后" else "${hours} 小时后"
                } else {
                    "已可"
                }
                Text(
                    "$resetLabel 重置",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// 右栏：Command Keys 预留卡（功能规划中，先占位）。
@Composable
private fun CommandKeysCard(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Command Keys", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                }
            }
            Text(
                "预留 · 功能规划中",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
