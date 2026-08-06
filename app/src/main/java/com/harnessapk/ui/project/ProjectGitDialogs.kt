package com.harnessapk.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.harnessapk.git.GitDiffStat

@Composable
internal fun CloneRepositoryDialog(
    onDismiss: () -> Unit,
    onClone: (name: String, remoteUrl: String, branch: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var remoteUrl by rememberSaveable { mutableStateOf("") }
    var branch by rememberSaveable { mutableStateOf("main") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("克隆仓库") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("项目名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = remoteUrl,
                    onValueChange = { remoteUrl = it },
                    label = { Text("仓库 HTTPS 地址") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = branch,
                    onValueChange = { branch = it },
                    label = { Text("分支") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && remoteUrl.isNotBlank() && branch.isNotBlank(),
                onClick = { onClone(name, remoteUrl, branch) },
            ) {
                Text("克隆")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun CommitDialog(
    onDismiss: () -> Unit,
    onCommit: (String) -> Unit,
) {
    var message by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提交全部变更") },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = message,
                onValueChange = { message = it },
                label = { Text("Commit message") },
                minLines = 3,
            )
        },
        confirmButton = {
            TextButton(enabled = message.isNotBlank(), onClick = { onCommit(message) }) { Text("提交") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun BranchDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var branch by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建分支") },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = branch,
                onValueChange = { branch = it },
                label = { Text("分支名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = branch.isNotBlank(), onClick = { onCreate(branch) }) { Text("创建并切换") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun PendingCommitDialog(
    paths: List<String>,
    diffStats: List<GitDiffStat>,
    onCommit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultMessage = remember(paths) { pendingCommitDefaultMessage(paths) }
    var message by rememberSaveable(defaultMessage) { mutableStateOf(defaultMessage) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("待提交（${paths.size} 个写回文件）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                paths.take(20).forEach { path ->
                    val stat = diffStats.firstOrNull { it.path == path }
                    val counts = stat?.takeIf { it.added > 0 || it.deleted > 0 }?.let { "+${it.added} / -${it.deleted}" }
                    Text(
                        text = if (counts.isNullOrBlank()) path else "$path   $counts",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (paths.size > 20) {
                    Text("…还有 ${paths.size - 20} 个", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Commit message（可编辑）") },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = paths.isNotEmpty() && message.isNotBlank(), onClick = { onCommit(message) }) {
                Text("提交")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

internal fun pendingCommitDefaultMessage(paths: List<String>): String {
    if (paths.isEmpty()) return ""
    val allMd = paths.all { it.endsWith(".md", ignoreCase = true) }
    val prefix = if (allMd) "docs" else "chore"
    val names = paths.map { it.substringAfterLast('/') }
    val tail = if (paths.size > 2) " 等" else ""
    val listed = names.take(2).joinToString("、")
    return "$prefix: 更新 ${paths.size} 个文件（$listed$tail）"
}

@Composable
internal fun PushPromptDialog(
    branch: String,
    aheadCount: Int,
    behindCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("推送到远端？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("已提交。是否立即推送当前分支？")
                Text(
                    text = "分支：$branch · 领先 $aheadCount · 落后 $behindCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (behindCount > 0) {
                    Text(
                        text = "远端有新提交（非快进）。手机不会自动拉取/合并，请在桌面处理后再推送。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = behindCount == 0, onClick = onConfirm) { Text("推送") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后") }
        },
    )
}
