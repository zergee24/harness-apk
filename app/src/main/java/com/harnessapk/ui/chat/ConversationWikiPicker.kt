package com.harnessapk.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harnessapk.wiki.ConversationWikiMountSelection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationWikiPicker(
    state: ConversationWikiUiState,
    controllerState: ConversationWikiControllerState,
    onApply: (List<ConversationWikiMountSelection>) -> Unit,
    onRestoreDefaults: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(state.options) { mutableStateOf(initialConversationWikiScopeDraft(state)) }
    var versionMenuWikiId by remember { mutableStateOf<String?>(null) }
    val pending = controllerState.writePending
    val failure = controllerState.failure?.let(::conversationWikiScopeErrorMessage)

    ModalBottomSheet(
        onDismissRequest = { if (!pending) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("本会话知识库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                state.toolbarLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.options.isEmpty()) {
                Text(
                    "安装知识库后，可在这里选择本会话能使用的原文资料。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                state.options.forEach { option ->
                    val draftEntry = draft.entries.first { it.wikiId == option.wikiId }
                    val selectedRef = draftEntry.ref ?: option.suggestedReadyRef
                    val canEnable = draftEntry.enabled || option.suggestedReadyRef != null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = draftEntry.enabled,
                            enabled = !pending && canEnable,
                            onCheckedChange = { enabled -> draft = draft.setEnabled(option, enabled) },
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                option.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            when {
                                option.unavailable -> Text(
                                    "已挂载版本不可用",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                selectedRef == null -> Text(
                                    "暂无可用版本",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                else -> Text(
                                    "v${selectedRef.version}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (option.readyVersions.size > 1) {
                            Box {
                                OutlinedButton(
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    enabled = !pending,
                                    onClick = { versionMenuWikiId = option.wikiId },
                                ) {
                                    Text("版本")
                                }
                                DropdownMenu(
                                    expanded = versionMenuWikiId == option.wikiId,
                                    onDismissRequest = { versionMenuWikiId = null },
                                ) {
                                    option.readyVersions.forEach { version ->
                                        DropdownMenuItem(
                                            text = { Text("v${version.ref.version}") },
                                            onClick = {
                                                versionMenuWikiId = null
                                                draft = draft.selectVersion(option.wikiId, version.ref)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            failure?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = !pending,
                    onClick = onRestoreDefaults,
                ) {
                    Text("恢复新会话默认范围")
                }
                Button(
                    modifier = Modifier.widthIn(min = 88.dp).heightIn(min = 48.dp),
                    enabled = !pending,
                    onClick = { onApply(draft.mountSelections()) },
                ) {
                    Text(if (pending) "应用中" else "应用")
                }
            }
        }
    }
}

private fun conversationWikiScopeErrorMessage(error: Throwable): String = error.message
    ?.takeIf(String::isNotBlank)
    ?.replace(Regex("[\\r\\n\\t]+"), " ")
    ?.take(160)
    ?: "无法更新本会话知识库范围"
