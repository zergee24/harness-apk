package com.harnessapk.ui.agent

import android.net.Uri
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.harnessapk.agent.AgentImportPreview
import com.harnessapk.agent.AgentPackageClassCount
import com.harnessapk.agent.AgentPackageDetail
import com.harnessapk.agent.AgentPackageImportSession
import com.harnessapk.agent.H_BUNDLE_MIME_TYPE
import com.harnessapk.agent.V2Bundle
import com.harnessapk.agent.V2Corpus
import com.harnessapk.agent.V2Source
import com.harnessapk.agent.V2SourceRecord
import com.harnessapk.common.AppContainer
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AgentPackagesScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    sourceProjectId: String?,
    externalImportUri: Uri?,
    onExternalImportConsumed: () -> Unit,
    onStartConversation: (Agent, String?) -> Unit,
) {
    val agents by container.agentRepository.observeAgents().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val previewViewModel = remember {
        AgentImportPreviewViewModel<AgentPackageImportSession>(
            discardImport = container.agentRepository::discardPackageImport,
            stagedFile = AgentPackageImportSession::stagedFile,
        )
    }
    val importSession by previewViewModel.session.collectAsState()
    var expandedAgentId by remember { mutableStateOf<String?>(null) }
    var detailsRevision by remember { mutableIntStateOf(0) }
    val installedDetails by produceState<Map<String, AgentPackageDetailUiState>>(
        initialValue = emptyMap(),
        agents,
        detailsRevision,
    ) {
        value = agents.mapNotNull { agent ->
            container.agentRepository.packageDetail(agent.id, agent.activeVersion)?.let { agent.id to it }
        }.toMap()
    }
    var previewAvailableBytes by remember { mutableStateOf(Long.MAX_VALUE) }
    var isWorking by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun prepareImport(uri: Uri) {
        scope.launch {
            isWorking = true
            errorText = null
            try {
                val session = container.agentRepository.preparePackageImport(uri.lastPathSegment ?: "智能体包") {
                    context.contentResolver.openInputStream(uri)
                        ?: throw AgentBundleException("无法读取所选文件")
                }
                previewViewModel.replace(session)
                previewAvailableBytes = privateInstallAvailableBytes(context.filesDir.absolutePath)
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
                    AgentPackageRow(
                        agent = agent,
                        detail = installedDetails[agent.id],
                        expanded = expandedAgentId == agent.id,
                        onToggleDetail = {
                            expandedAgentId = if (expandedAgentId == agent.id) null else agent.id
                        },
                        onStartConversation = { onStartConversation(agent, sourceProjectId) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        if (isWorking && importSession == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }

    importSession?.let { session ->
        val dismiss = {
            scope.launch { runCatching { previewViewModel.discardIfCurrent(session) } }
            Unit
        }
        val install: (String) -> Unit = { profileId ->
            scope.launch {
                val availableBytes = privateInstallAvailableBytes(context.filesDir.absolutePath)
                previewAvailableBytes = availableBytes
                val plan = (session.parsedPackage as? V2Bundle)?.toInstallationPlan()
                if (plan != null && installationDecision(plan, availableBytes, profileId) !is AgentInstallationDecision.InstallDirectly) {
                    return@launch
                }
                isWorking = true
                errorText = null
                runCatching { container.agentRepository.installPackage(session, profileId) }
                    .onSuccess { result ->
                        previewViewModel.clearIfCurrent(session)
                        detailsRevision += 1
                        expandedAgentId = result.agent.id
                    }
                    .onFailure { error -> errorText = error.message ?: "智能体安装失败" }
                isWorking = false
            }
        }
        when (val parsed = session.parsedPackage) {
            is V2Bundle -> AgentV2InstallPreview(
                name = parsed.agent.manifest.name,
                version = parsed.agent.manifest.version,
                publisherFingerprint = parsed.publisherFingerprint,
                plan = parsed.toInstallationPlan(),
                availableBytes = previewAvailableBytes,
                sourceRecords = parsed.corpora.flatMap(V2Corpus::sources),
                isInstalling = isWorking,
                onDismiss = dismiss,
                onInstall = install,
            )
            else -> AgentImportDialog(
                preview = session.preview,
                sourceReadOnly = parsed is V2Source,
                isInstalling = isWorking,
                onDismiss = dismiss,
                onInstall = { install(DEFAULT_INSTALLATION_PROFILE_ID) },
            )
        }
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
internal fun AgentPackageRow(
    agent: Agent,
    detail: AgentPackageDetailUiState?,
    expanded: Boolean,
    onToggleDetail: () -> Unit,
    onStartConversation: () -> Unit,
) {
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
        TextButton(onClick = onToggleDetail) { Text(if (expanded) "收起详情" else "查看详情") }
        if (expanded) {
            AgentPackageDetail(agent, detail, onStartConversation)
        }
    }
}

@Composable
private fun AgentImportDialog(
    preview: AgentImportPreview,
    sourceReadOnly: Boolean,
    isInstalling: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isInstalling) onDismiss() },
        title = { Text("安装智能体") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(preview.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("基于资料模拟 · v${preview.version}")
                Text("资料：${preview.corpora.joinToString("、").ifBlank { "未包含" }}")
                Text("大小：${formatAgentPackageSize(preview.compressedSizeBytes)}")
                Text("发布者指纹：${preview.publisherFingerprint}", style = MaterialTheme.typography.bodySmall)
                if (preview.includesOriginalSources) {
                    Text(
                        if (sourceReadOnly) sourceParticipationLabel() else "包含原始资料文件",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
internal fun AgentV2InstallPreview(
    name: String,
    version: Int,
    publisherFingerprint: String,
    plan: AgentInstallationPlan,
    availableBytes: Long,
    sourceRecords: List<V2SourceRecord>,
    isInstalling: Boolean,
    initialProfileId: String = DEFAULT_INSTALLATION_PROFILE_ID,
    onDismiss: () -> Unit,
    onInstall: (String) -> Unit,
) {
    var selectedProfileId by remember(plan, initialProfileId) { mutableStateOf(initialProfileId) }
    var showAdjustment by remember(plan) { mutableStateOf(false) }
    val decision = installationDecision(plan, availableBytes, selectedProfileId)
    val installEnabled = !isInstalling && decision is AgentInstallationDecision.InstallDirectly
    val exactBytes = exactInstallationBytes(plan, selectedProfileId)

    AlertDialog(
        onDismissRequest = { if (!isInstalling) onDismiss() },
        title = { Text("推荐安装") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("基于资料模拟 · v$version", style = MaterialTheme.typography.bodySmall)
                Text("人物身份 · 必装")
                Text("核心证据 · 覆盖核心立场与评测")
                Text("推荐资料 · 补充谈话、时期和体裁")
                Text(
                    "准确安装大小：${exactBytes?.let(::formatAgentPackageSize) ?: "不可用"}",
                    fontWeight = FontWeight.SemiBold,
                )
                when (decision) {
                    is AgentInstallationDecision.ShowAdjustment -> Text(
                        decision.reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is AgentInstallationDecision.BlockMissingRequired -> Text(
                        if (decision.missingPackageIds.isEmpty()) {
                            "安装计划无效，已阻止安装"
                        } else {
                            "必装资料不可用：${decision.missingPackageIds.joinToString()}"
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is AgentInstallationDecision.BlockUnavailableProfile -> Text(
                        "当前安装包不包含此档位：${decision.missingPackageIds.joinToString()}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is AgentInstallationDecision.InstallDirectly -> Unit
                }
                if (selectedProfileId == "source") {
                    Text(
                        sourceParticipationLabel(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (shouldShowWrittenPersonaWarning(sourceRecords)) {
                    Text(
                        "书面人格，对话还原度有限",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "发布者指纹：$publisherFingerprint",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (showAdjustment) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        INSTALLATION_PROFILE_ORDER.forEachIndexed { index, profileId ->
                            SegmentedButton(
                                selected = selectedProfileId == profileId,
                                onClick = { selectedProfileId = profileId },
                                enabled = profileAvailableInBundle(plan, profileId),
                                shape = SegmentedButtonDefaults.itemShape(index, INSTALLATION_PROFILE_ORDER.size),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 56.dp),
                                label = {
                                    Text(
                                        text = INSTALLATION_PROFILE_LABELS.getValue(profileId),
                                        maxLines = 2,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                        }
                    }
                }
                if (isInstalling) AgentInstallProgress()
            }
        },
        confirmButton = {
            TextButton(
                enabled = installEnabled,
                onClick = { onInstall(selectedProfileId) },
            ) {
                Text(if (isInstalling) "正在安装" else "安装")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isInstalling,
                onClick = {
                    if (showAdjustment) showAdjustment = false else onDismiss()
                },
            ) {
                Text(if (showAdjustment) "收起" else "取消")
            }
            TextButton(
                enabled = !isInstalling,
                onClick = { showAdjustment = true },
            ) {
                Text("调整资料")
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
private fun AgentPackageDetail(
    agent: Agent,
    detail: AgentPackageDetailUiState?,
    onStartConversation: () -> Unit,
) {
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
    Text("schema/version：${detail?.schemaVersion ?: "-"} / ${agent.activeVersion}", style = MaterialTheme.typography.bodySmall)
    Text("发布者指纹：${agent.publisherFingerprint}", style = MaterialTheme.typography.bodySmall, color = bodyColor)
    Text("运行状态：${agent.status.name}", style = MaterialTheme.typography.bodySmall)
    detail?.let { state ->
        Text("必装：${state.required.installed}/${state.required.planned}", style = MaterialTheme.typography.bodySmall)
        Text("推荐：${state.recommended.installed}/${state.recommended.planned}", style = MaterialTheme.typography.bodySmall)
        Text("可选：${state.optional.installed}/${state.optional.planned}", style = MaterialTheme.typography.bodySmall)
        Text("原文：${state.source.installed}/${state.source.planned}", style = MaterialTheme.typography.bodySmall)
        Text("安装档位：${INSTALLATION_PROFILE_LABELS[state.selectedProfileId] ?: state.selectedProfileId}", style = MaterialTheme.typography.bodySmall)
        Text("准确安装大小：${formatAgentPackageSize(state.exactInstalledBytes)}", style = MaterialTheme.typography.bodySmall)
        state.lastEvidenceExpandedAt?.let { timestamp ->
            Text("最近资料扩展：${formatExpansionTime(timestamp)}", style = MaterialTheme.typography.bodySmall)
        }
        if (state.missingRequiredPackageIds.isNotEmpty()) {
            Text(
                "缺少必装资料：${state.missingRequiredPackageIds.joinToString()}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    TextButton(enabled = canStartAgent(agent), onClick = onStartConversation) {
        Text("开始对话")
    }
}

private val INSTALLATION_PROFILE_LABELS = mapOf(
    "lite" to "轻量",
    "balanced" to "推荐",
    "complete" to "完整证据",
    "source" to "包含原文",
)

internal typealias AgentPackageCount = AgentPackageClassCount
internal typealias AgentPackageDetailUiState = AgentPackageDetail

private fun V2Bundle.toInstallationPlan(): AgentInstallationPlan = AgentInstallationPlan(
    agentPackageId = manifest.agent.fileName,
    agentSizeBytes = manifest.agent.sizeBytes,
    packages = agent.installPlan.packages.map { declaration ->
        AgentInstallationPackage(
            id = declaration.id,
            type = declaration.type,
            installClass = declaration.installClass,
            sizeBytes = declaration.sizeBytes,
        )
    },
    profiles = agent.installPlan.profiles.map { profile ->
        AgentInstallationProfile(profile.id, profile.packageIds)
    },
    requiredPackageIds = agent.installPlan.requiredCorpusIds,
    availablePackageIds = manifest.selectedPackageIds,
)

private fun privateInstallAvailableBytes(path: String): Long =
    runCatching { StatFs(path).availableBytes }.getOrDefault(-1L)

private fun formatExpansionTime(timestamp: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(timestamp))
