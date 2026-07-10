package com.harnessapk.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harnessapk.git.GitBranchSummary
import com.harnessapk.git.GitFileChange
import com.harnessapk.git.GitStatusSummary
import com.harnessapk.ui.theme.HarnessSpacing

@Composable
internal fun ProjectGitPanel(
    status: GitStatusSummary?,
    branches: List<GitBranchSummary>,
    onInitRepository: () -> Unit,
    onCloneRepository: () -> Unit,
    onRefresh: () -> Unit,
    onCommit: () -> Unit,
    onPush: () -> Unit,
    onFetch: () -> Unit,
    onPull: () -> Unit,
    onCreateBranch: () -> Unit,
    onCheckoutBranch: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AccountTree, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Git", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = status?.let(::gitStatusSummaryText) ?: "当前项目还不是 Git 仓库",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (status == null) {
                Text(
                    text = "可以把当前项目初始化为本地 Git 仓库，或从 Gitee 私有仓库克隆一个新项目。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onInitRepository) { Text("初始化 Git") }
                    OutlinedButton(onClick = onCloneRepository) { Text("克隆仓库") }
                }
                return@Column
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { AssistChip(onClick = {}, label = { Text(status.currentBranch) }) }
                item { AssistChip(onClick = {}, label = { Text(if (status.isClean) "干净" else "有改动") }) }
                if (status.aheadCount > 0) item { AssistChip(onClick = {}, label = { Text("领先 ${status.aheadCount}") }) }
                if (status.behindCount > 0) item { AssistChip(onClick = {}, label = { Text("落后 ${status.behindCount}") }) }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Button(
                        modifier = Modifier.heightIn(min = HarnessSpacing.minimumTouchTarget),
                        enabled = !status.isClean,
                        onClick = onCommit,
                    ) { Text("提交全部") }
                }
                item {
                    OutlinedButton(
                        modifier = Modifier.heightIn(min = HarnessSpacing.minimumTouchTarget),
                        onClick = onPush,
                    ) { Text("推送") }
                }
                item {
                    OutlinedButton(
                        modifier = Modifier.heightIn(min = HarnessSpacing.minimumTouchTarget),
                        onClick = onFetch,
                    ) { Text("Fetch") }
                }
                item {
                    OutlinedButton(
                        modifier = Modifier.heightIn(min = HarnessSpacing.minimumTouchTarget),
                        onClick = onPull,
                    ) { Text("快进拉取") }
                }
                item {
                    OutlinedButton(
                        modifier = Modifier.heightIn(min = HarnessSpacing.minimumTouchTarget),
                        onClick = onCreateBranch,
                    ) { Text("新建分支") }
                }
                item {
                    OutlinedButton(
                        modifier = Modifier.heightIn(min = HarnessSpacing.minimumTouchTarget),
                        onClick = onRefresh,
                    ) { Text("刷新") }
                }
            }

            if (branches.isNotEmpty()) {
                HorizontalDivider()
                Text("分支", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                branches.take(12).forEach { branch ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(branch.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            Text(
                                if (branch.isRemote) "远端分支" else "本地分支",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (branch.isCurrent) {
                            AssistChip(onClick = {}, label = { Text("当前") })
                        } else {
                            TextButton(onClick = { onCheckoutBranch(branch.name) }) { Text("切换") }
                        }
                    }
                }
            }

            HorizontalDivider()
            Text("变更文件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (status.files.isEmpty()) {
                Text("没有本地变更", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                status.files.take(30).forEach { file ->
                    GitFileChangeRow(file)
                }
            }
        }
    }
}

@Composable
private fun GitFileChangeRow(file: GitFileChange) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(onClick = {}, label = { Text(file.type.label) })
        Text(
            modifier = Modifier.weight(1f),
            text = file.path,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

private fun gitStatusSummaryText(status: GitStatusSummary): String {
    val changes = listOf(
        status.stagedCount.takeIf { it > 0 }?.let { "暂存 $it" },
        status.unstagedCount.takeIf { it > 0 }?.let { "未暂存 $it" },
        status.untrackedCount.takeIf { it > 0 }?.let { "未跟踪 $it" },
    ).filterNotNull().joinToString("，")
    val sync = listOf(
        status.aheadCount.takeIf { it > 0 }?.let { "领先 $it" },
        status.behindCount.takeIf { it > 0 }?.let { "落后 $it" },
    ).filterNotNull().joinToString("，")
    return listOf(status.currentBranch, changes.ifBlank { "无本地改动" }, sync)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}
