package com.harnessapk.ui.activity

import android.app.KeyguardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import com.harnessapk.remote.ApprovalDecision
import com.harnessapk.remote.RemoteApprovalRisk
import com.harnessapk.remote.RemoteConnectionService
import com.harnessapk.remote.RemoteSyncPosition
import com.harnessapk.remote.isRemoteApprovalActionEnabled
import com.harnessapk.remote.parseRemoteApprovalRisk
import com.harnessapk.remote.remoteApprovalPolicy
import com.harnessapk.storage.RemoteApprovalEntity
import com.harnessapk.ui.theme.HarnessSpacing
import kotlinx.coroutines.launch

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
    val profile by container.remoteProfileStore.profile.collectAsState()
    val cursor by container.database.remoteDao().observeCursor(
        hostId = run?.hostId.orEmpty(),
        deviceId = profile?.deviceId.orEmpty(),
    ).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmingApproval by remember { mutableStateOf<RemoteApprovalEntity?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
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
            }
        }
    }
}

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
