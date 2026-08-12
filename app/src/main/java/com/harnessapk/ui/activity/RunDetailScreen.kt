package com.harnessapk.ui.activity

import android.app.KeyguardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import com.harnessapk.remote.ApprovalDecision
import com.harnessapk.remote.RemoteApprovalRisk
import com.harnessapk.remote.RemoteConnectionService
import com.harnessapk.remote.RemoteCompletionEvidence
import com.harnessapk.remote.RemoteCompletionVerification
import com.harnessapk.remote.RemoteSyncPosition
import com.harnessapk.remote.RemoteTimelinePresentation
import com.harnessapk.remote.collapseRemoteTimeline
import com.harnessapk.remote.isRemoteApprovalActionEnabled
import com.harnessapk.remote.parseRemoteCompletionEvidence
import com.harnessapk.remote.parseRemoteApprovalRisk
import com.harnessapk.remote.remoteApprovalPolicy
import com.harnessapk.session.MarkdownDraftOrigin
import com.harnessapk.session.MarkdownDraftOriginType
import com.harnessapk.session.MarkdownDraftOwner
import com.harnessapk.session.MarkdownUpdateOperation
import com.harnessapk.session.MarkdownUpdateProposal
import com.harnessapk.session.buildMarkdownDiff
import com.harnessapk.session.remoteCompletionMarkdownPlan
import com.harnessapk.storage.MarkdownChangeDraftItemEntity
import com.harnessapk.storage.RemoteApprovalEntity
import com.harnessapk.storage.RemoteRunEventEntity
import com.harnessapk.ui.theme.HarnessSpacing
import com.harnessapk.ui.components.MarkdownDraftDiff
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import java.security.MessageDigest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDetailScreen(
    container: AppContainer,
    runId: String,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val run by container.database.remoteDao().observeRun(runId).collectAsState(initial = null)
    val approvals by container.database.remoteDao().observeApprovalsForRun(runId).collectAsState(initial = emptyList())
    val recentEvents by container.database.remoteDao().observeRecentEvents(runId, 100).collectAsState(initial = emptyList())
    val openCommands by container.database.remoteDao().observeOpenCommandsForRun(runId).collectAsState(initial = emptyList())
    val profile by container.remoteProfileStore.profile.collectAsState()
    val cursor by container.database.remoteDao().observeCursor(
        hostId = run?.hostId.orEmpty(),
        deviceId = profile?.deviceId.orEmpty(),
    ).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmingApproval by remember { mutableStateOf<RemoteApprovalEntity?>(null) }
    var confirmingStop by rememberSaveable { mutableStateOf(false) }
    var steerText by rememberSaveable { mutableStateOf("") }
    var olderEvents by remember(runId) { mutableStateOf<List<RemoteRunEventEntity>>(emptyList()) }
    var loadingOlder by rememberSaveable(runId) { mutableStateOf(false) }
    var reachedTimelineStart by rememberSaveable(runId) { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var remoteDraftId by rememberSaveable(runId) { mutableStateOf<String?>(null) }
    var remoteDraftStatus by rememberSaveable(runId) { mutableStateOf<String?>(null) }
    var remoteDraftItems by remember(runId) { mutableStateOf<List<MarkdownChangeDraftItemEntity>>(emptyList()) }
    LaunchedEffect(runId) {
        container.database.projectSearchDao()
            .draftOriginForSource(MarkdownDraftOriginType.REMOTE_RUN.name, runId)
            ?.let { origin ->
                remoteDraftId = origin.draftId
                val dao = container.database.markdownChangeDraftDao()
                val draft = dao.findDraft(origin.draftId)
                if (draft?.status == "APPLYING") {
                    dao.updateDraft(
                        draft.copy(
                            status = "FAILED",
                            summary = "上次应用被中断，请核对文件后重试",
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    remoteDraftStatus = "FAILED"
                } else {
                    remoteDraftStatus = draft?.status
                }
                remoteDraftItems = dao.listItems(origin.draftId)
            }
    }
    fun enqueue(approval: RemoteApprovalEntity, decision: ApprovalDecision) {
        scope.launch {
            actionError = null
            runCatching {
                container.remoteApprovalCommandCoordinator.enqueue(approval, decision)
                RemoteConnectionService.start(context)
            }.onFailure { actionError = it.message ?: "审批命令入队失败" }
        }
    }
    confirmingApproval?.let { approval ->
        AlertDialog(
            onDismissRequest = { confirmingApproval = null },
            title = { Text("确认高风险操作") },
            text = { Text(approval.target) },
            confirmButton = {
                Button(onClick = {
                    confirmingApproval = null
                    enqueue(approval, ApprovalDecision.ALLOW_ONCE)
                }) { Text("确认允许一次") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmingApproval = null }) { Text("取消") }
            },
        )
    }
    if (confirmingStop) {
        AlertDialog(
            onDismissRequest = { confirmingStop = false },
            title = { Text("停止这个任务？") },
            text = { Text("已完成的工作会保留；Mac 确认停止前，任务仍显示为运行中。") },
            confirmButton = {
                Button(onClick = {
                    confirmingStop = false
                    scope.launch {
                        actionError = null
                        runCatching {
                            container.remoteRunCommandCoordinator.interrupt(runId)
                            RemoteConnectionService.start(context)
                        }.onFailure { actionError = it.message ?: "停止命令入队失败" }
                    }
                }) { Text("停止任务") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmingStop = false }) { Text("继续运行") }
            },
        )
    }
    Scaffold(
        modifier = Modifier.padding(contentPadding),
        topBar = {
            TopAppBar(
                title = { Text(run?.projectNameSnapshot ?: "远程任务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val active = run?.status in setOf("RUNNING", "WAITING_APPROVAL", "WAITING_USER", "RECONCILING")
                    if (active) {
                        IconButton(
                            onClick = { confirmingStop = true },
                            enabled = openCommands.none { it.type == "run.interrupt" },
                            modifier = Modifier.semantics {
                                contentDescription = if (openCommands.any { it.type == "run.interrupt" }) "正在停止任务" else "停止任务"
                            },
                        ) {
                            Icon(Icons.Outlined.StopCircle, contentDescription = null)
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val current = run
            if (current == null) {
                Text("任务记录不存在或已删除")
            } else {
                Text(remoteRunStatusLabel(current.status), style = MaterialTheme.typography.titleMedium)
                Text(current.latestLine, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("任务目标", style = MaterialTheme.typography.labelLarge)
                Text(current.objective, style = MaterialTheme.typography.bodyLarge)
                if (current.threadId != null) {
                    Text(
                        "Mac 已创建 Codex 任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val timeline = remember(recentEvents, olderEvents) {
                    collapseRemoteTimeline(
                        (olderEvents + recentEvents.asReversed()).distinctBy { it.logicalEventId },
                    )
                }
                if (timeline.isNotEmpty()) {
                    val earliestSequence = olderEvents.firstOrNull()?.sequence
                        ?: recentEvents.lastOrNull()?.sequence
                    RemoteTimelineSection(
                        items = timeline,
                        canLoadEarlier = !reachedTimelineStart && earliestSequence != null,
                        loadingEarlier = loadingOlder,
                        onLoadEarlier = {
                            val before = earliestSequence ?: return@RemoteTimelineSection
                            scope.launch {
                                loadingOlder = true
                                runCatching {
                                    container.database.remoteDao().eventsBefore(runId, before, 100)
                                }.onSuccess { page ->
                                    val chronological = page.asReversed()
                                    olderEvents = (chronological + olderEvents).distinctBy { it.logicalEventId }
                                    reachedTimelineStart = page.size < 100
                                }.onFailure { actionError = it.message ?: "加载更早进展失败" }
                                loadingOlder = false
                            }
                        },
                    )
                }
                val completion = remember(current.completionJson) {
                    current.completionJson?.let { raw -> runCatching { parseRemoteCompletionEvidence(raw) }.getOrNull() }
                }
                completion?.let { evidence ->
                    RemoteCompletionCard(
                        evidence = evidence,
                        depositStatus = remoteDraftStatus,
                        onDepositToProject = if (
                            current.completionJson == null ||
                            evidence.verification != RemoteCompletionVerification.VERIFIED_V2
                        ) null else {
                            {
                                scope.launch {
                                    actionError = null
                                    runCatching {
                                        val existing = container.database.projectSearchDao()
                                            .draftOriginForSource(MarkdownDraftOriginType.REMOTE_RUN.name, runId)
                                        val record = if (existing != null) {
                                            val draft = requireNotNull(
                                                container.database.markdownChangeDraftDao().findDraft(existing.draftId),
                                            )
                                            com.harnessapk.session.PersistedMarkdownDraft(
                                                draft = draft,
                                                items = container.database.markdownChangeDraftDao().listItems(existing.draftId),
                                                origin = existing,
                                            )
                                        } else {
                                            container.markdownDraftCoordinator.persistPlan(
                                                owner = MarkdownDraftOwner(projectId = current.projectId),
                                                origin = MarkdownDraftOrigin(
                                                    type = MarkdownDraftOriginType.REMOTE_RUN,
                                                    sourceId = runId,
                                                    sourceSha256 = requireNotNull(current.completionJson).sha256(),
                                                    sourceProjectId = current.projectId,
                                                ),
                                                plan = remoteCompletionMarkdownPlan(runId, evidence),
                                                snapshots = emptyList(),
                                                rawResponse = current.completionJson,
                                            )
                                        }
                                        remoteDraftId = record.draft.id
                                        remoteDraftStatus = record.draft.status
                                        remoteDraftItems = record.items
                                    }.onFailure { actionError = it.message ?: "沉淀到项目失败" }
                                }
                            }
                        },
                    )
                    if (remoteDraftItems.isNotEmpty()) {
                        RemoteMarkdownDraftReviewCard(
                            items = remoteDraftItems,
                            status = remoteDraftStatus.orEmpty(),
                            onRetainedChanged = { itemId, retained ->
                                scope.launch {
                                    container.database.markdownChangeDraftDao().updateItemRetained(itemId, retained)
                                    remoteDraftItems = remoteDraftItems.map { item ->
                                        if (item.id == itemId) item.copy(retained = retained) else item
                                    }
                                }
                            },
                            onApply = { selectedItemIds ->
                                scope.launch {
                                    actionError = null
                                    runCatching {
                                        val draftId = requireNotNull(remoteDraftId)
                                        val dao = container.database.markdownChangeDraftDao()
                                        remoteDraftStatus = "APPLYING"
                                        val result = container.markdownDraftApplyCoordinator.apply(
                                            draftId = draftId,
                                            projectId = current.projectId,
                                            selectedItemIds = selectedItemIds,
                                        )
                                        remoteDraftStatus = dao.findDraft(draftId)?.status
                                        remoteDraftItems = dao.listItems(draftId)
                                        val written = result.succeeded.mapNotNull { it.writtenDeliverable?.path }
                                        if (written.isNotEmpty()) {
                                            container.projectAppliedPaths.update { currentMap ->
                                                currentMap + (current.projectId to (currentMap[current.projectId].orEmpty() + written).distinct())
                                            }
                                            container.projectContentInvalidation.emit(current.projectId)
                                        }
                                        if (result.failed.isNotEmpty()) {
                                            actionError = result.failed.joinToString("；") { it.errorMessage.orEmpty() }
                                        }
                                    }.onFailure { error ->
                                        actionError = error.message ?: "应用项目变更失败"
                                        remoteDraftId?.let { draftId ->
                                            remoteDraftStatus = container.database.markdownChangeDraftDao()
                                                .findDraft(draftId)?.status
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
                actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                approvals.filter { it.status == "PENDING" }.forEach { approval ->
                    val position = RemoteSyncPosition(
                        highestContiguousSequence = cursor?.lastContiguousSequence ?: 0L,
                        gapFromSequence = cursor?.gapFromSequence,
                        reconciliationState = cursor?.reconciliationState ?: "IN_SYNC",
                    )
                    val risk = parseRemoteApprovalRisk(approval.risk)
                    val deviceLocked = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
                    val policy = remoteApprovalPolicy(risk, deviceLocked)
                    val enabled = approval.responseCommandId == null &&
                        isRemoteApprovalActionEnabled(approval.status, position)
                    ApprovalDetailCard(
                        approval = approval,
                        enabled = enabled,
                        allowEnabled = enabled && policy.canApproveNow,
                        onAllow = {
                            if (policy.requiresDetailConfirmation) confirmingApproval = approval
                            else enqueue(approval, ApprovalDecision.ALLOW_ONCE)
                        },
                        onDecline = { enqueue(approval, ApprovalDecision.DENY) },
                    )
                }
                if (current.status == "RUNNING") {
                    val sending = openCommands.any { it.type == "run.steer" }
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = steerText,
                        onValueChange = { steerText = it },
                        label = { Text("补充方向") },
                        enabled = !sending,
                        minLines = 2,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = steerText.isNotBlank() && !sending,
                        onClick = {
                            val text = steerText
                            scope.launch {
                                actionError = null
                                runCatching {
                                    container.remoteRunCommandCoordinator.steer(runId, text)
                                    RemoteConnectionService.start(context)
                                }.onSuccess { steerText = "" }
                                    .onFailure { actionError = it.message ?: "补充方向入队失败" }
                            }
                        },
                    ) { Text(if (sending) "发送中…" else "发送补充方向") }
                }
            }
        }
    }
}

@Composable
internal fun RemoteTimelineSection(
    items: List<RemoteTimelinePresentation>,
    canLoadEarlier: Boolean = false,
    loadingEarlier: Boolean = false,
    onLoadEarlier: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("任务进展", style = MaterialTheme.typography.titleMedium)
        if (canLoadEarlier) {
            TextButton(
                onClick = onLoadEarlier,
                enabled = !loadingEarlier,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (loadingEarlier) "加载中…" else "加载更早进展") }
        }
        items.forEach { item ->
            var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(item.title, style = MaterialTheme.typography.labelLarge)
                    if (item.detail.isNotBlank()) {
                        Text(item.detail, style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (expanded) "收起诊断信息" else "查看诊断信息")
                    }
                    if (expanded) {
                        Text(
                            item.diagnosticPayload,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun RemoteCompletionCard(
    evidence: RemoteCompletionEvidence,
    depositStatus: String? = null,
    onDepositToProject: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "任务完成证据" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("已完成", style = MaterialTheme.typography.titleLarge)
            Text(evidence.summary, style = MaterialTheme.typography.bodyLarge)
            CompletionEvidenceRow("文件", evidence.fileSummary)
            CompletionEvidenceRow("测试", evidence.testSummary)
            CompletionEvidenceRow("Git", evidence.gitSummary)
            CompletionEvidenceRow(
                "取证",
                when (evidence.verification) {
                    RemoteCompletionVerification.VERIFIED_V2 -> "已冻结验证"
                    RemoteCompletionVerification.UNVERIFIED_V2 -> "v2 证据未完整验证"
                    RemoteCompletionVerification.LEGACY_UNVERIFIED -> "旧版结果未验证"
                },
            )
            CompletionEvidenceRow(
                "遗留",
                if (evidence.unresolved.isEmpty()) "0 项" else "${evidence.unresolved.size} 项",
            )
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (expanded) "收起结果" else "查看结果") }
            onDepositToProject?.let { deposit ->
                Button(
                    onClick = deposit,
                    enabled = depositStatus == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(if (depositStatus == null) "沉淀到项目" else "已生成待审核 Diff")
                }
            }
            if (evidence.verification != RemoteCompletionVerification.VERIFIED_V2) {
                Text(
                    "此结果可查看，但不能作为 M3 项目沉淀证据。请升级 Mac Bridge 后运行新任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                if (evidence.changedFiles.isNotEmpty()) {
                    Text(evidence.changedFiles.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
                evidence.tests.forEach { test ->
                    Text("${test.status} · ${test.command}", style = MaterialTheme.typography.bodySmall)
                }
                evidence.unresolved.forEach { item ->
                    Text("遗留 · $item", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun RemoteMarkdownDraftReviewCard(
    items: List<MarkdownChangeDraftItemEntity>,
    status: String,
    onRetainedChanged: (itemId: String, retained: Boolean) -> Unit = { _, _ -> },
    onApply: (selectedItemIds: Set<String>) -> Unit,
) {
    var expanded by rememberSaveable(items.firstOrNull()?.draftId) { mutableStateOf(false) }
    val selectedIds = items.filter { it.retained }.mapTo(linkedSetOf(), MarkdownChangeDraftItemEntity::id)
    Card(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Remote 项目变更 Diff" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("审核 Diff", style = MaterialTheme.typography.titleMedium)
            items.forEach { item ->
                Row {
                    Checkbox(
                        checked = item.retained,
                        onCheckedChange = { retained -> onRetainedChanged(item.id, retained) },
                        enabled = status != "APPLYING" && item.applyStatus != "SUCCEEDED",
                    )
                    Text(
                        "${if (item.operation == "CREATE") "新增" else "更新"} · ${item.relativePath}" +
                            if (item.applyStatus == "SUCCEEDED") " · 已应用" else "",
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (expanded) "收起完整变更" else "查看完整变更")
            }
            if (expanded) {
                items.forEach { item ->
                    MarkdownDraftDiff(buildMarkdownDiff("", item.proposedMarkdown))
                }
            }
            if (status == "APPLYING") {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("正在应用所选变更")
            }
            if (status == "READY" || status == "PARTIALLY_APPLIED" || status == "FAILED") {
                Button(
                    onClick = { onApply(selectedIds) },
                    enabled = selectedIds.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(if (status == "PARTIALLY_APPLIED" || status == "FAILED") "重试应用" else "应用所选")
                }
            } else if (status == "APPLIED") {
                Text("已写入项目，尚未 Commit", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CompletionEvidenceRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value)
    }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }

@Composable
private fun ApprovalDetailCard(
    approval: RemoteApprovalEntity,
    enabled: Boolean,
    allowEnabled: Boolean,
    onAllow: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("等待审批", style = MaterialTheme.typography.titleMedium)
        Text(approval.target, style = MaterialTheme.typography.bodyMedium)
        Text(
            when (parseRemoteApprovalRisk(approval.risk)) {
                RemoteApprovalRisk.HIGH -> "高风险 · 需在详情页确认"
                RemoteApprovalRisk.MEDIUM -> "中风险"
                RemoteApprovalRisk.LOW -> "低风险"
                RemoteApprovalRisk.UNKNOWN -> "风险待确认"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onDecline,
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(if (approval.responseCommandId == null) "拒绝" else "发送中") }
            Button(
                onClick = onAllow,
                enabled = allowEnabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(if (!allowEnabled && enabled) "请先解锁" else "允许一次") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteRunObjectiveSheet(
    projectName: String,
    busy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var objective by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("交给 Mac", style = MaterialTheme.typography.titleLarge)
            Text(projectName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = objective,
                onValueChange = { objective = it },
                label = { Text("这次要完成什么？") },
                minLines = 3,
                enabled = !busy,
            )
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = HarnessSpacing.primaryControlHeight),
                enabled = objective.isNotBlank() && !busy,
                onClick = { onSend(objective) },
            ) {
                Text(if (busy) "正在排队…" else "发送")
            }
        }
    }
}

internal fun remoteRunStatusLabel(status: String): String = when (status) {
    "QUEUED" -> "等待 Mac 接收"
    "STARTING" -> "Mac 正在启动"
    "RUNNING" -> "正在运行"
    "WAITING_APPROVAL" -> "等待审批"
    "WAITING_USER" -> "等待用户输入"
    "RECONCILING" -> "正在核对状态"
    "COMPLETED" -> "已完成"
    "FAILED" -> "失败"
    "CANCELLED" -> "已停止"
    else -> "状态未知"
}
