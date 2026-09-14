package com.harnessapk.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import com.harnessapk.remote.DashboardHost
import com.harnessapk.remote.DashboardQuota
import com.harnessapk.remote.DashboardThread
import com.harnessapk.remote.DashboardViewedStore
import com.harnessapk.remote.RemoteConnectionStatus

private val ThreadTileWidth = 260.dp

// 一屏卡片工作台（上下结构）：上方两行横宽卡线程区（横滑翻页 + 页码点），
// 底部仪表带 = 余额环状卡 + Command Keys 预留卡；副屏只读不写，
// 点卡片 = Mac 主屏聚焦对应线程。
@Composable
fun DashboardScreen(
    container: AppContainer,
    viewedStore: DashboardViewedStore,
    onExit: () -> Unit,
) {
    val dashboard by container.remoteRepository.dashboard.collectAsState()
    val connection by container.remoteRepository.state.collectAsState()
    val profile by container.remoteProfileStore.profile.collectAsState()
    val hostName = profile?.hostName ?: "Mac"
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        // 副屏可能脱离远程页直接进入（进程被杀后冷启），先确保 WSS 连接。
        container.remoteRepository.connect()
        container.remoteRepository.requestDashboardSnapshot()
            container.remoteRepository.focusResults.collect { result ->
                snackbarHostState.showSnackbar(
                    // zcode 卡聚焦打开的是工作区，措辞不绑「线程」。
                    if (result.ok) "已在 Mac 前台打开" else "聚焦失败：${result.message ?: "未知原因"}",
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                IconButton(
                    onClick = { container.remoteRepository.requestDashboardSnapshot() },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "手动刷新",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onExit) { Text("退出") }
            }
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
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(
                modifier = Modifier.height(150.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuotaStripCard(modifier = Modifier.weight(1f), quota = dashboard.quota)
                HostCard(
                    modifier = Modifier.weight(1f),
                    host = dashboard.host,
                    hostName = hostName,
                )
                TodayCard(modifier = Modifier.weight(1f), host = dashboard.host)
            }
        }
    }
}

// 两行横滑线程区。非 Lazy（Row+horizontalScroll+整页组合）：
// 墨水屏上 Lazy 网格的 item 文本层会随复用丢刷新（标题/状态偶发整块
// 不显示），全量组合整体重画才是面板刷新友好的；页列定高、页内两行
// weight 等高，末页不足补空白撑位。
@Composable
private fun ThreadPagingGrid(
    threads: List<DashboardThread>,
    onTap: (DashboardThread) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val scrollState = rememberScrollState()
        val columns = (maxWidth / (ThreadTileWidth + 8.dp)).toInt().coerceAtLeast(1)
        val pages = threads.chunked(columns * 2)
        val pageWidth = ThreadTileWidth * columns + 8.dp * (columns - 1)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(scrollState),
        ) {
            pages.forEach { pageThreads ->
                Column(
                    // 右侧 8dp 充当页间距（Column 无横向 arrangement）。
                    modifier = Modifier.padding(end = 8.dp).width(pageWidth).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pageThreads.chunked(columns).forEach { rowThreads ->
                        Row(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowThreads.forEach { thread ->
                                ConsoleTile(
                                    thread = thread,
                                    unread = false,
                                    onClick = { onTap(thread) },
                                )
                            }
                            repeat(columns - rowThreads.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        if (pages.size > 1) {
            val pageStepPx = with(LocalDensity.current) { (pageWidth + 8.dp).toPx() }
            val page = (scrollState.value / pageStepPx).toInt().coerceIn(0, pages.size - 1)
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(pages.size) { index ->
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

@Composable
private fun ConsoleTile(thread: DashboardThread, unread: Boolean, onClick: () -> Unit) {
    val accent = Color(dashboardToneArgb(dashboardTone(thread.status)))
    Card(modifier = Modifier.width(ThreadTileWidth).combinedClickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight().heightIn(min = 96.dp)) {
            Box(modifier = Modifier.width(14.dp).fillMaxHeight().background(accent))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        thread.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        // 两行截断：单行会把长标题（CRM 业务名/zcode 会话名）
                        // 压到七八个字就省略，卡片纵向有富余。
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (unread) {
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF5C5C)),
                        )
                    }
                    dashboardSourceLabel(thread).takeIf { it.isNotEmpty() }?.let { badge ->
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        dashboardStatusLabel(thread),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
Row(verticalAlignment = Alignment.CenterVertically) {
                    val place = thread.cwd?.substringAfterLast('/') ?: ""
                    Text(
                        listOf(place, dashboardRelativeTime(System.currentTimeMillis(), thread.updatedAtMs))
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (thread.contextPercent > 0) {
                        Text(
                            "上下文 ${thread.contextPercent}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                // 摘要与上方元信息之间用细线隔离（墨水屏上比留白更清晰）。
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    thread.lastActivity?.takeIf(String::isNotBlank)
                        ?: "等待 agent 输出…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    // 网格两行定高、卡片等高：weight 填充剩余空间，放不下自动省略。
                    modifier = Modifier.weight(1f),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// 底部仪表带左格：余额环（环 + 大数字）横排剩余额度与重置时间。
@Composable
private fun QuotaStripCard(modifier: Modifier = Modifier, quota: DashboardQuota?) {
    val remaining = quota?.remainingPercent
    val ringColor = when {
        remaining == null -> Color(0xFF5A5A5E)
        remaining >= 50 -> Color(0xFF34C77B)
        remaining >= 20 -> Color(0xFFFFB020)
        else -> Color(0xFFFF5C5C)
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(modifier = Modifier.size(78.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 8.dp.toPx()
                    drawArc(
                        color = Color(0xFF3A3A3E),
                        startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    if (remaining != null && remaining > 0) {
                        drawArc(
                            color = ringColor,
                            startAngle = -90f, sweepAngle = 360f * (minOf(remaining, 100) / 100f), useCenter = false,
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
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("配额", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when {
                        quota == null -> "余额未知"
                        else -> "剩余额度" + (quota.planType?.let { " · $it" } ?: "")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (quota != null && quota.resetsAtMs > 0) {
                    val now = System.currentTimeMillis()
                    val resetLabel = if (quota.resetsAtMs > now) {
                        val hours = (quota.resetsAtMs - now) / 3_600_000L
                        if (hours >= 24) "${hours / 24} 天后" else "$hours 小时后"
                    } else {
                        "已可"
                    }
                    Text(
                        "$resetLabel 重置",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// 底部仪表带右格：主机与今日卡（今日 tokens / 连续 streak / 今日 turn /
// 内存 / 磁盘 / load，全部来自 dashboard.host 帧）。
// 底部仪表带中格：主机卡（资源指标来自 dashboard.host 帧）。
@Composable
private fun HostCard(
    modifier: Modifier = Modifier,
    host: DashboardHost?,
    hostName: String,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                "主机 · $hostName",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            InfoRow(
                "内存",
                if (host == null || host.memUsedPercent <= 0) "—" else "${host.memUsedPercent}%",
            )
            InfoRow(
                "磁盘",
                if (host == null || host.diskUsedPercent <= 0) "—" else "${host.diskUsedPercent}%",
            )
            InfoRow("load", host?.load1?.takeIf { it.isNotBlank() } ?: "—")
        }
    }
}

// 底部仪表带右格：今日卡（今日 turn / 连续 streak / 近 7 天 tokens）。
@Composable
private fun TodayCard(modifier: Modifier = Modifier, host: DashboardHost?) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("今日", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoRow("turn", if (host != null && host.todayTurns > 0) "${host.todayTurns}" else "0")
            InfoRow("连续 streak", if (host != null && host.streakDays > 0) "${host.streakDays} 天" else "—")
            InfoRow(
                "近 7 天 tokens",
                if (host == null || host.weekTokens <= 0) "—" else formatTokenCount(host.weekTokens),
            )
        }
    }
}

private fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000_000 -> "%.1fB".format(tokens / 1_000_000_000.0)
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fk".format(tokens / 1_000.0)
    else -> tokens.toString()
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
