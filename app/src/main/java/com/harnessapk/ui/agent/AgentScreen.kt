package com.harnessapk.ui.agent

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentBundleException
import com.harnessapk.agent.AgentImportSession
import com.harnessapk.agent.H_BUNDLE_MIME_TYPE
import com.harnessapk.chat.Conversation
import com.harnessapk.common.AppContainer
import kotlinx.coroutines.launch

@Composable
fun AgentScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    importRequestKey: Int,
    onImportRequestConsumed: () -> Unit,
    onRequestImport: () -> Unit,
    externalImportUri: Uri?,
    onExternalImportConsumed: () -> Unit,
    onCreateTopic: (Agent) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val agents by container.agentRepository.observeAgents().collectAsState(initial = emptyList())
    val conversations by container.chatRepository.observeConversations().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importSession by remember { mutableStateOf<AgentImportSession?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun prepareImport(uri: Uri) {
        scope.launch {
            isWorking = true
            errorText = null
            runCatching {
                container.agentRepository.prepareImport(uri.lastPathSegment ?: "智能体包") {
                    context.contentResolver.openInputStream(uri)
                        ?: throw AgentBundleException("无法读取所选文件")
                }
            }.onSuccess { session ->
                importSession?.let(container.agentRepository::discardImport)
                importSession = session
            }.onFailure { error ->
                errorText = error.message ?: "智能体包读取失败"
            }
            isWorking = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::prepareImport)
    }

    LaunchedEffect(importRequestKey) {
        if (importRequestKey > 0) {
            onImportRequestConsumed()
            filePicker.launch(
                arrayOf(
                    H_BUNDLE_MIME_TYPE,
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                ),
            )
        }
    }

    LaunchedEffect(externalImportUri) {
        externalImportUri?.let { uri ->
            onExternalImportConsumed()
            prepareImport(uri)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        when {
            agents.isEmpty() && !isWorking -> {
                AgentEmptyState(
                    errorText = errorText,
                    onRequestImport = onRequestImport,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    errorText?.let { message ->
                        item(key = "error") {
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                    items(agents, key = Agent::id) { agent ->
                        AgentRow(
                            agent = agent,
                            conversations = agentTopicConversations(agent, conversations),
                            onCreateTopic = { onCreateTopic(agent) },
                            onOpenConversation = onOpenConversation,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        if (isWorking && importSession == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }

    importSession?.let { session ->
        AgentImportDialog(
            session = session,
            isInstalling = isWorking,
            onDismiss = {
                container.agentRepository.discardImport(session)
                importSession = null
            },
            onInstall = {
                scope.launch {
                    isWorking = true
                    errorText = null
                    runCatching { container.agentRepository.install(session) }
                        .onSuccess { importSession = null }
                        .onFailure { error -> errorText = error.message ?: "智能体安装失败" }
                    isWorking = false
                }
            },
        )
    }
}

@Composable
internal fun AgentEmptyState(
    errorText: String?,
    onRequestImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        errorText?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = "还没有智能体",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "从系统文件中选择 .hbundle 安装包，确认后即可开始对话。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRequestImport) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("添加智能体")
        }
    }
}

internal fun agentTopicConversations(
    agent: Agent,
    conversations: List<Conversation>,
): List<Conversation> = conversations
    .filter { conversation -> conversation.agentId == agent.id }
    .sortedByDescending(Conversation::updatedAt)

@Composable
private fun AgentRow(
    agent: Agent,
    conversations: List<Conversation>,
    onCreateTopic: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    var showAllTopics by remember(agent.id) { mutableStateOf(false) }
    val visibleTopics = if (showAllTopics) conversations else conversations.take(MAX_VISIBLE_AGENT_TOPICS)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "基于资料模拟 · v${agent.activeVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${agentStatusLabel(agent.status)} · ${agentCorpusCoverage(agent)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (canStartAgent(agent)) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Button(
                enabled = canStartAgent(agent),
                onClick = onCreateTopic,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("新话题")
            }
        }
        if (conversations.isNotEmpty()) {
            Text(
                text = "${conversations.size} 个话题",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            visibleTopics.forEach { conversation ->
                AgentTopicRow(
                    conversation = conversation,
                    onClick = { onOpenConversation(conversation.id) },
                )
            }
            if (conversations.size > MAX_VISIBLE_AGENT_TOPICS) {
                TextButton(onClick = { showAllTopics = !showAllTopics }) {
                    Text(if (showAllTopics) "收起话题" else "查看全部话题")
                }
            }
        }
    }
}

@Composable
private fun AgentTopicRow(
    conversation: Conversation,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.ChatBubbleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            modifier = Modifier.weight(1f),
            text = conversation.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
    }
}

private const val MAX_VISIBLE_AGENT_TOPICS = 3

@Composable
private fun AgentImportDialog(
    session: AgentImportSession,
    isInstalling: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    val preview = session.preview
    AlertDialog(
        onDismissRequest = { if (!isInstalling) onDismiss() },
        title = { Text("安装智能体") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(preview.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("基于资料模拟 · v${preview.version}")
                Text("资料：${preview.corpora.joinToString("、").ifBlank { "未包含" }}")
                Text("大小：${formatAgentPackageSize(preview.compressedSizeBytes)}")
                Text("发布者指纹：${preview.publisherFingerprint}", style = MaterialTheme.typography.bodySmall)
                if (preview.includesOriginalSources) {
                    Text("包含原始资料文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isInstalling) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "正在安装智能体并建立资料索引，请保持应用开启。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !isInstalling, onClick = onInstall) {
                Text(if (isInstalling) "正在安装" else "安装")
            }
        },
        dismissButton = {
            TextButton(enabled = !isInstalling, onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
