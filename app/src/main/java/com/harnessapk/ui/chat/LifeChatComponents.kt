package com.harnessapk.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harnessapk.chat.ChatDocumentPresentation

@Composable
internal fun LifeChatStatus(
    message: String,
    action: String? = null,
    enabled: Boolean = true,
    error: Boolean = false,
    onAction: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(action)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LifeVoicePanel(
    label: String,
    transcript: String?,
    reviewing: Boolean,
    listening: Boolean,
    onTranscriptChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onRestart: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth().imePadding(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (reviewing) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = transcript.orEmpty(), onValueChange = onTranscriptChange,
                    label = { Text("识别的文字") }, minLines = 1, maxLines = 4,
                )
                Text("确认使用后，还需点击发送", style = MaterialTheme.typography.bodyMedium)
            } else if (listening && !transcript.isNullOrBlank()) {
                Text(transcript, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (reviewing) {
                    Button(onClick = onConfirm, enabled = !transcript.isNullOrBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("确认使用") }
                    TextButton(onClick = onRestart, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新说") }
                } else if (listening) {
                    Button(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) { Text("结束录音") }
                }
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (reviewing || listening) "取消" else "取消整理") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LifeChatMoreSheet(
    identity: String?,
    onSearch: () -> Unit,
    onRename: () -> Unit,
    onSettings: () -> Unit,
    onDetails: () -> Unit,
    onVoiceSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("更多", style = MaterialTheme.typography.titleLarge)
            identity?.let { Text(it, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
            listOf(
                "查找消息" to onSearch, "修改标题" to onRename,
                "本次提问设置" to onSettings, "提问详情" to onDetails, "朗读设置" to onVoiceSettings,
            ).forEach { (label, callback) ->
                TextButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = { onDismiss(); callback() }) { Text(label) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun ChatDocumentCards(documents: List<ChatDocumentPresentation>) {
    documents.forEach { document ->
        var expanded by remember(document.id) { mutableStateOf(false) }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(document.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(document.mimeType.substringAfterLast('/').uppercase(), document.sizeBytes.takeIf { it > 0 }?.let { "${(it + 1023) / 1024} KB" }).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (expanded) "收起文件内容" else "查看文件内容")
                }
                if (document.truncated) Text("文件较长，仅发送已提取的部分", style = MaterialTheme.typography.bodyMedium)
                if (expanded) SelectionContainer {
                    Text(document.extractedText, fontSize = 17.sp, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                }
            }
        }
    }
}
