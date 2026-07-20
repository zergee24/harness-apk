package com.harnessapk.ui.agent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.harnessapk.agentmemory.AgentMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentMemorySheet(
    agentId: String,
    version: Int?,
    installedCorpusCount: Int?,
    requiredCorpusCount: Int?,
    publisherFingerprint: String?,
    memories: List<AgentMemory>,
    sourceAvailable: suspend (AgentMemory) -> Boolean,
    onOpenSource: (AgentMemory) -> Unit,
    onEdit: suspend (AgentMemory, String) -> Boolean,
    onDelete: suspend (AgentMemory) -> Boolean,
    onClear: suspend () -> Boolean,
    onDismiss: () -> Unit,
) {
    val items = remember(agentId, memories) { agentMemoryUiItems(agentId, memories) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sourceAvailability = remember(agentId) { mutableStateMapOf<String, Boolean?>() }
    var editingId by remember(agentId) { mutableStateOf<String?>(null) }
    var editDraft by remember(agentId) { mutableStateOf("") }
    var editError by remember(agentId) { mutableStateOf<String?>(null) }
    var sectionError by remember(agentId) { mutableStateOf<String?>(null) }
    var busyMemoryId by remember(agentId) { mutableStateOf<String?>(null) }
    var overflowExpanded by remember(agentId) { mutableStateOf(false) }
    var confirmClear by remember(agentId) { mutableStateOf(false) }
    var clearing by remember(agentId) { mutableStateOf(false) }

    LaunchedEffect(items.map { "${it.memory.id}:${it.memory.sourceConversationId}:${it.memory.sourceMessageId}" }) {
        val currentIds = items.mapTo(mutableSetOf()) { it.memory.id }
        sourceAvailability.keys.retainAll(currentIds)
        items.forEach { item ->
            sourceAvailability[item.memory.id] = null
        }
        items.forEach { item ->
            sourceAvailability[item.memory.id] = try {
                sourceAvailable(item.memory)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }
    LaunchedEffect(items.map { it.memory.id }) {
        if (editingId != null && items.none { it.memory.id == editingId }) {
            editingId = null
            editDraft = ""
            editError = null
        }
    }

    fun cancelEditing() {
        editingId = null
        editDraft = ""
        editError = null
    }

    BackHandler(enabled = editingId != null) {
        cancelEditing()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "人物资料",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭人物资料")
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .testTag("关系记忆列表")
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 24.dp,
                ),
            ) {
                item(key = "identity-metadata") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("固定版本：v${version ?: "未知"}")
                        Text("资料覆盖：${installedCorpusCount ?: "未知"}/${requiredCorpusCount ?: "未知"}")
                        Text(
                            text = "发布者指纹：${publisherFingerprint ?: "未知"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
                item(key = "relationship-header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "关系记忆",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (items.isNotEmpty()) {
                            androidx.compose.foundation.layout.Box {
                                IconButton(
                                    enabled = busyMemoryId == null && !clearing,
                                    onClick = { overflowExpanded = true },
                                ) {
                                    Icon(
                                        Icons.Outlined.MoreVert,
                                        contentDescription = "关系记忆更多",
                                    )
                                }
                                DropdownMenu(
                                    expanded = overflowExpanded,
                                    onDismissRequest = { overflowExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("清空关系记忆") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Delete, contentDescription = null)
                                        },
                                        onClick = {
                                            overflowExpanded = false
                                            confirmClear = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                    sectionError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (items.isEmpty()) {
                        Text(
                            text = "还没有关系记忆",
                            modifier = Modifier.padding(vertical = 20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(
                    items = items,
                    key = { it.memory.id },
                ) { item ->
                    val memory = item.memory
                    AgentMemoryRow(
                        item = item,
                        editing = editingId == memory.id,
                        editDraft = editDraft,
                        editError = editError,
                        sourceAvailable = sourceAvailability[memory.id],
                        enabled = busyMemoryId == null && !clearing,
                        onEditDraftChange = {
                            editDraft = it
                            editError = null
                        },
                        onStartEdit = {
                            editingId = memory.id
                            editDraft = memory.content
                            editError = null
                            sectionError = null
                        },
                        onCancelEdit = ::cancelEditing,
                        onSaveEdit = {
                            when (val validation = validateAgentMemoryEdit(editDraft)) {
                                is AgentMemoryEditValidation.Invalid -> {
                                    editError = validation.message
                                }
                                is AgentMemoryEditValidation.Valid -> {
                                    if (canOperateAgentMemory(agentId, memory)) {
                                        scope.launch {
                                            busyMemoryId = memory.id
                                            editError = null
                                            try {
                                                if (onEdit(memory, validation.content)) {
                                                    cancelEditing()
                                                } else {
                                                    editError = "保存失败，请重试"
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                editError = "保存失败，请重试"
                                            } finally {
                                                busyMemoryId = null
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        onDelete = {
                            if (canOperateAgentMemory(agentId, memory)) {
                                scope.launch {
                                    busyMemoryId = memory.id
                                    sectionError = null
                                    try {
                                        if (!onDelete(memory)) {
                                            sectionError = "删除失败，请重试"
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        sectionError = "删除失败，请重试"
                                    } finally {
                                        busyMemoryId = null
                                    }
                                }
                            }
                        },
                        onOpenSource = {
                            onDismiss()
                            onOpenSource(memory)
                        },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = {
                if (!clearing) confirmClear = false
            },
            title = { Text("清空关系记忆") },
            text = { Text(agentMemoryClearConfirmationText()) },
            confirmButton = {
                TextButton(
                    enabled = !clearing,
                    onClick = {
                        scope.launch {
                            clearing = true
                            sectionError = null
                            try {
                                if (onClear()) {
                                    confirmClear = false
                                    cancelEditing()
                                } else {
                                    confirmClear = false
                                    sectionError = "清空失败，请重试"
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                confirmClear = false
                                sectionError = "清空失败，请重试"
                            } finally {
                                clearing = false
                            }
                        }
                    },
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !clearing,
                    onClick = { confirmClear = false },
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun AgentMemoryRow(
    item: AgentMemoryUiItem,
    editing: Boolean,
    editDraft: String,
    editError: String?,
    sourceAvailable: Boolean?,
    enabled: Boolean,
    onEditDraftChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenSource: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.typeLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(
                enabled = enabled && !editing,
                onClick = onStartEdit,
            ) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "编辑 ${item.typeLabel}",
                )
            }
            IconButton(
                enabled = enabled && !editing,
                onClick = onDelete,
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除 ${item.typeLabel}",
                )
            }
        }
        if (editing) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "编辑关系记忆内容" },
                value = editDraft,
                onValueChange = onEditDraftChange,
                label = { Text(item.typeLabel) },
                supportingText = editError?.let { error ->
                    {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                isError = editError != null,
                minLines = 3,
                maxLines = 8,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    enabled = enabled,
                    onClick = onCancelEdit,
                ) {
                    Text("取消")
                }
                TextButton(
                    enabled = enabled,
                    onClick = onSaveEdit,
                ) {
                    Text("保存")
                }
            }
        } else {
            Text(
                text = item.memory.content,
                style = MaterialTheme.typography.bodyMedium,
            )
            when (sourceAvailable) {
                true -> TextButton(
                    enabled = enabled,
                    onClick = onOpenSource,
                ) {
                    Text("查看来源")
                }
                false -> Text(
                    text = "来源不可用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                null -> Spacer(Modifier.height(40.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
