package com.harnessapk.ui.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harnessapk.capture.CaptureDraft
import com.harnessapk.capture.CaptureItemKind
import com.harnessapk.capture.CaptureTransferState
import com.harnessapk.chat.Conversation
import com.harnessapk.project.Project

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureDestinationSheet(
    draft: CaptureDraft,
    conversations: List<Conversation>,
    projects: List<Project>,
    busy: Boolean,
    errorMessage: String?,
    onConversation: (String) -> Unit,
    onProjectConversation: (Project) -> Unit,
    onImportToProject: (String) -> Unit,
    onNewConversation: () -> Unit,
    onDiscard: () -> Unit,
) {
    val hasOrdinaryFile = draft.stagedItems.any { it.kind == CaptureItemKind.FILE }
    ModalBottomSheet(onDismissRequest = {}) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("选择分享目的地", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = captureDraftSummary(draft),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDiscard, enabled = !busy) {
                    Icon(Icons.Outlined.Close, contentDescription = "取消分享")
                }
            }
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            errorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            HorizontalDivider()
            if (hasOrdinaryFile) {
                Text(
                    "导入项目 files/",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                projects.take(8).forEach { project ->
                    DestinationRow(
                        title = project.name,
                        subtitle = "普通文件只保存到项目，不进入模型上下文",
                        enabled = !busy,
                        icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        onClick = { onImportToProject(project.id) },
                    )
                }
                if (projects.isEmpty()) {
                    Text(
                        "请先在工作页创建项目",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "最近会话",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                conversations.take(5).forEach { conversation ->
                    DestinationRow(
                        title = conversation.title.ifBlank { "新会话" },
                        subtitle = "继续现有会话",
                        enabled = !busy,
                        icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) },
                        onClick = { onConversation(conversation.id) },
                    )
                }
                DestinationRow(
                    title = "新建临时会话",
                    subtitle = "不关联项目",
                    enabled = !busy,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = onNewConversation,
                )
                if (projects.isNotEmpty()) {
                    Text(
                        "最近项目",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    projects.take(5).forEach { project ->
                        DestinationRow(
                            title = project.name,
                            subtitle = "在项目中新建会话",
                            enabled = !busy,
                            icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                            onClick = { onProjectConversation(project) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CaptureTransferOverlay(
    state: CaptureTransferState,
    onDismissError: () -> Unit,
) {
    when {
        state.active -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("正在安全暂存分享内容") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val total = state.totalBytes
                    if (total != null && total > 0L) {
                        LinearProgressIndicator(
                            progress = { (state.completedBytes.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${state.completedBytes / 1024} / ${total / 1024} KiB")
                    } else {
                        CircularProgressIndicator()
                    }
                }
            },
        )
        state.errorMessage != null -> AlertDialog(
            onDismissRequest = onDismissError,
            confirmButton = { Button(onClick = onDismissError) { Text("知道了") } },
            title = { Text("分享失败") },
            text = { Text(state.errorMessage) },
        )
    }
}

@Composable
private fun DestinationRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = icon,
    )
}

internal fun captureDraftSummary(draft: CaptureDraft): String = buildList {
    draft.text.trim().takeIf(String::isNotBlank)?.let { add(it) }
    if (draft.stagedItems.isNotEmpty()) {
        val images = draft.stagedItems.count { it.kind == CaptureItemKind.IMAGE }
        val files = draft.stagedItems.size - images
        add(buildList {
            if (images > 0) add("图片 $images 张")
            if (files > 0) add("文件 $files 个")
        }.joinToString("，"))
    }
}.joinToString(" · ").ifBlank { "空分享" }
