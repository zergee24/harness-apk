package com.harnessapk.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.harnessapk.activity.ActivityItem
import com.harnessapk.activity.ActivityState
import com.harnessapk.activity.ActivityTarget
import com.harnessapk.common.AppContainer
import com.harnessapk.ui.theme.HarnessSpacing

@Composable
fun ActivityScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenChat: (String) -> Unit,
    onOpenRun: (String) -> Unit,
) {
    val state by container.activityRepository.state.collectAsState(initial = ActivityState())
    ActivityScreenContent(
        state = state,
        contentPadding = contentPadding,
        onOpenChat = onOpenChat,
        onOpenRun = onOpenRun,
    )
}

@Composable
internal fun ActivityScreenContent(
    state: ActivityState,
    onOpenChat: (String) -> Unit,
    onOpenRun: (String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = HarnessSpacing.pageHorizontal,
            end = HarnessSpacing.pageHorizontal,
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        activitySection(
            title = "需要处理",
            emptyLabel = "暂无待处理任务",
            items = state.needsAction,
            groupDescription = "需要处理",
            onOpenChat = onOpenChat,
            onOpenRun = onOpenRun,
        )
        activitySection(
            title = "进行中",
            emptyLabel = "暂无进行中任务",
            items = state.inProgress,
            groupDescription = "进行中",
            onOpenChat = onOpenChat,
            onOpenRun = onOpenRun,
        )
        activitySection(
            title = "最近完成",
            emptyLabel = "最近 7 天暂无完成任务",
            items = state.recentCompleted,
            groupDescription = "最近完成",
            onOpenChat = onOpenChat,
            onOpenRun = onOpenRun,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.activitySection(
    title: String,
    emptyLabel: String,
    items: List<ActivityItem>,
    groupDescription: String,
    onOpenChat: (String) -> Unit,
    onOpenRun: (String) -> Unit,
) {
    item(key = "section:$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (items.isEmpty()) {
        item(key = "empty:$title") {
            Text(
                text = emptyLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        items(items, key = ActivityItem::id) { item ->
            ActivityRow(
                item = item,
                groupDescription = groupDescription,
                onClick = {
                    when (item.target) {
                        ActivityTarget.CHAT -> onOpenChat(item.targetId)
                        ActivityTarget.REMOTE_RUN -> onOpenRun(item.targetId)
                    }
                },
            )
        }
    }
}

@Composable
private fun ActivityRow(
    item: ActivityItem,
    groupDescription: String,
    onClick: () -> Unit,
) {
    val sourceDescription = if (item.target == ActivityTarget.REMOTE_RUN) "远程任务" else "本地对话"
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics {
                    contentDescription = "${item.title}，$sourceDescription，$groupDescription"
                },
            )
            Text(item.summary, style = MaterialTheme.typography.bodyMedium)
            Text(
                item.statusLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
