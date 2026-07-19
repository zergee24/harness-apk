package com.harnessapk.ui.agent

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.harnessapk.agent.AgentStatus
import com.harnessapk.agent.H_BUNDLE_MIME_TYPE
import com.harnessapk.common.AppContainer
import kotlinx.coroutines.launch

@Composable
fun AgentPackagesScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    sourceProjectId: String?,
    externalImportUri: Uri?,
    onExternalImportConsumed: () -> Unit,
    onStartConversation: (Agent, String?) -> Unit,
    onDone: () -> Unit,
) {
    val agents by container.agentRepository.observeAgents().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val previewViewModel = remember {
        AgentImportPreviewViewModel(container.agentRepository::discardImport)
    }
    val importSession by previewViewModel.session.collectAsState()
    var installedAgent by remember { mutableStateOf<Agent?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun prepareImport(uri: Uri) {
        scope.launch {
            isWorking = true
            errorText = null
            try {
                val session = container.agentRepository.prepareImport(uri.lastPathSegment ?: "智能体包") {
                    context.contentResolver.openInputStream(uri)
                        ?: throw AgentBundleException("无法读取所选文件")
                }
                previewViewModel.replace(session)
            } catch (error: Throwable) {
                errorText = error.message ?: "智能体包读取失败"
            }
            isWorking = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::prepareImport)
    }

    fun requestImport() {
        filePicker.launch(
            arrayOf(
                H_BUNDLE_MIME_TYPE,
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
            ),
        )
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
        if (agents.isEmpty() && !isWorking) {
            AgentPackagesEmptyState(
                errorText = errorText,
                onRequestImport = ::requestImport,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                item {
                    Button(onClick = ::requestImport) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("导入智能体包")
                    }
                }
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
                    AgentPackageRow(agent)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                scope.launch {
                    runCatching { previewViewModel.discardIfCurrent(session) }
                }
            },
            onInstall = {
                scope.launch {
                    isWorking = true
                    errorText = null
                    runCatching { container.agentRepository.install(session) }
                        .onSuccess { result ->
                            previewViewModel.clearIfCurrent(session)
                            installedAgent = result.agent
                        }
                        .onFailure { error -> errorText = error.message ?: "智能体安装失败" }
                    isWorking = false
                }
            },
        )
    }

    installedAgent?.let { agent ->
        AgentInstallSuccessDialog(
            agent = agent,
            sourceProjectId = sourceProjectId,
            onStartConversation = onStartConversation,
            onDone = onDone,
        )
    }
}

@Composable
internal fun AgentPackagesEmptyState(
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
            Text(text = message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Text(text = "还没有智能体包", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text = "从系统文件中选择 .hbundle 安装包，确认后即可开始对话。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRequestImport) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("导入智能体包")
        }
    }
}

@Composable
private fun AgentPackageRow(agent: Agent) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(agent.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("基于资料模拟 · v${agent.activeVersion}", style = MaterialTheme.typography.bodySmall)
        Text(
            text = "${agentStatusLabel(agent.status)} · ${agentCorpusCoverage(agent)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                if (isInstalling) {
                    AgentInstallProgress()
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

@Composable
internal fun AgentInstallProgress() {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Text(
        text = "正在安装智能体并建立资料索引，请保持应用开启。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun AgentInstallSuccessDialog(
    agent: Agent,
    sourceProjectId: String?,
    onStartConversation: (Agent, String?) -> Unit,
    onDone: () -> Unit,
) {
    val canStartConversation = agent.status == AgentStatus.READY
    AlertDialog(
        onDismissRequest = {},
        title = { Text("智能体已安装") },
        text = {
            Text(
                if (canStartConversation) {
                    "${agent.name} 已可用于新对话。"
                } else {
                    "仍缺少资料，补齐后可开始对话。"
                },
            )
        },
        confirmButton = {
            if (canStartConversation) {
                TextButton(onClick = { onStartConversation(agent, sourceProjectId) }) {
                    Text("开始对话")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDone) {
                Text("完成")
            }
        },
    )
}
