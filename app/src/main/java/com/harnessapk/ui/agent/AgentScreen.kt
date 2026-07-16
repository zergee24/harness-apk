package com.harnessapk.ui.agent

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.harnessapk.common.AppContainer
import kotlinx.coroutines.launch

@Composable
fun AgentScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    importRequestKey: Int,
    onImportRequestConsumed: () -> Unit,
    onStartConversation: (Agent) -> Unit,
) {
    val agents by container.agentRepository.observeAgents().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importSession by remember { mutableStateOf<AgentImportSession?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
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
    }

    LaunchedEffect(importRequestKey) {
        if (importRequestKey > 0) {
            onImportRequestConsumed()
            filePicker.launch(
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                ),
            )
        }
    }

    DisposableEffect(importSession?.id) {
        val session = importSession
        onDispose {
            if (session != null && session.stagedFile.exists()) {
                container.agentRepository.discardImport(session)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        when {
            agents.isEmpty() && !isWorking -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp),
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
                        text = "尚未安装智能体",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                        AgentRow(agent = agent, onStartConversation = { onStartConversation(agent) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        if (isWorking) {
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
private fun AgentRow(
    agent: Agent,
    onStartConversation: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
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
            onClick = onStartConversation,
        ) {
            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("开始对话")
        }
    }
}

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
            }
        },
        confirmButton = {
            TextButton(enabled = !isInstalling, onClick = onInstall) {
                Text("安装")
            }
        },
        dismissButton = {
            TextButton(enabled = !isInstalling, onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
