package com.harnessapk.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class ConversationContextSummary(
    val projectName: String?,
    val identityName: String,
    val enabledWikiCount: Int,
    val model: String,
    val webSearchEnabled: Boolean,
    val contextPercent: Int,
) {
    fun primaryText(): String = listOf(
        projectName ?: "临时会话",
        identityName,
        if (enabledWikiCount > 0) "Wiki $enabledWikiCount" else "无 Wiki",
        model.ifBlank { "未配置模型" },
    ).joinToString(" · ")

    fun secondaryText(): String = buildList {
        if (webSearchEnabled) add("联网")
        add("上下文 ${contextPercent.coerceIn(0, 100)}%")
    }.joinToString(" · ")
}

internal enum class ConversationProjectChange {
    KEEP_CURRENT,
    UPDATE_CURRENT,
    CONTINUE_IN_NEW,
}

internal fun conversationProjectChange(
    currentProjectId: String?,
    targetProjectId: String?,
    hasUserMessage: Boolean,
): ConversationProjectChange = when {
    currentProjectId == targetProjectId -> ConversationProjectChange.KEEP_CURRENT
    hasUserMessage -> ConversationProjectChange.CONTINUE_IN_NEW
    else -> ConversationProjectChange.UPDATE_CURRENT
}

internal data class ContextProjectOption(val id: String, val name: String)

@Composable
internal fun ConversationContextBar(
    summary: ConversationContextSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("conversation_context_bar")
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.primaryText(),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary.secondaryText(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "调整会话上下文",
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationContextSheet(
    summary: ConversationContextSummary,
    projects: List<ContextProjectOption>,
    selectedProjectId: String?,
    projectLocked: Boolean,
    identityState: ConversationIdentityUiState,
    wikiLabel: String,
    showWebSearch: Boolean,
    webSearchEnabled: Boolean,
    canCompressContext: Boolean,
    isCompressingContext: Boolean,
    onSelectProject: (String?) -> Unit,
    onSelectIdentity: (String?) -> Unit,
    onOpenWiki: () -> Unit,
    onOpenModel: () -> Unit,
    onToggleWebSearch: (Boolean) -> Unit,
    onCompressContext: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "会话上下文",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = summary.primaryText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            SectionLabel("项目")
            ProjectOptionRow(
                name = "临时会话",
                selected = selectedProjectId == null,
                locked = projectLocked && selectedProjectId != null,
                onClick = { onSelectProject(null) },
            )
            projects.forEach { project ->
                ProjectOptionRow(
                    name = project.name,
                    selected = selectedProjectId == project.id,
                    locked = projectLocked && selectedProjectId != project.id,
                    onClick = { onSelectProject(project.id) },
                )
            }
            if (projectLocked) {
                Text(
                    text = "切换项目会在目标项目创建新会话，当前历史不会移动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            HorizontalDivider()
            SectionLabel("身份")
            identityState.options.forEach { option ->
                ListItem(
                    modifier = Modifier.clickable(
                        enabled = identityState.mutable,
                        onClick = { onSelectIdentity(option.agentId) },
                    ),
                    headlineContent = { Text(option.name) },
                    supportingContent = option.version?.let { version -> ({ Text("版本 $version") }) },
                    leadingContent = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    trailingContent = {
                        RadioButton(
                            selected = identityState.selectedAgentId == option.agentId,
                            enabled = identityState.mutable,
                            onClick = null,
                        )
                    },
                )
            }
            HorizontalDivider()
            ContextActionRow("Wiki", wikiLabel, onOpenWiki)
            ContextActionRow("模型", summary.model.ifBlank { "未配置" }, onOpenModel)
            if (showWebSearch) {
                ListItem(
                    headlineContent = { Text("联网搜索") },
                    supportingContent = { Text(if (webSearchEnabled) "已开启" else "已关闭") },
                    trailingContent = {
                        Switch(
                            checked = webSearchEnabled,
                            onCheckedChange = onToggleWebSearch,
                        )
                    },
                )
            }
            ListItem(
                headlineContent = { Text("上下文") },
                supportingContent = { Text("已使用 ${summary.contextPercent.coerceIn(0, 100)}%") },
                trailingContent = {
                    if (canCompressContext || isCompressingContext) {
                        Button(
                            enabled = !isCompressingContext,
                            onClick = onCompressContext,
                        ) {
                            Text(if (isCompressingContext) "压缩中" else "压缩")
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun ProjectOptionRow(
    name: String,
    selected: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(name) },
        supportingContent = if (locked) ({ Text("在此项目继续") }) else null,
        leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
    )
}

@Composable
private fun ContextActionRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = {
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "调整$title",
            )
        },
    )
}
