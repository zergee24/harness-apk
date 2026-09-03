package com.harnessapk.ui.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harnessapk.remote.WorkspaceCandidate
import com.harnessapk.remote.evaluateBindingChange
import com.harnessapk.remote.rankWorkspaceCandidates
import com.harnessapk.storage.ProjectRemoteBindingEntity
import com.harnessapk.ui.theme.HarnessSpacing
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectRemoteBindingSheet(
    projectName: String,
    hostName: String,
    candidates: List<WorkspaceCandidate>,
    candidatesLoaded: Boolean,
    existingBinding: ProjectRemoteBindingEntity?,
    onDismiss: () -> Unit,
    onBind: (WorkspaceCandidate, Boolean) -> Unit,
) {
    val ranked = remember(projectName, candidates) { rankWorkspaceCandidates(projectName, candidates) }
    var selectedWorkspaceId by remember(ranked) { mutableStateOf(ranked.firstOrNull()?.workspaceId) }
    var confirmationCandidate by remember { mutableStateOf<WorkspaceCandidate?>(null) }
    val selected = ranked.firstOrNull { it.workspaceId == selectedWorkspaceId }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("选择 Mac 工作区", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                text = hostName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                !candidatesLoaded -> Text("正在读取 Mac 最近打开的项目…")
                ranked.isEmpty() -> Text("先在 Mac Codex 中打开一次该项目")
                else -> ranked.forEach { candidate ->
                    WorkspaceCandidateRow(
                        candidate = candidate,
                        selected = selectedWorkspaceId == candidate.workspaceId,
                        onSelect = { selectedWorkspaceId = candidate.workspaceId },
                    )
                }
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = HarnessSpacing.primaryControlHeight)
                    .testTag("remote-binding-primary"),
                enabled = selected != null,
                onClick = {
                    val candidate = selected ?: return@Button
                    val evaluation = evaluateBindingChange(
                        existingFingerprint = existingBinding?.repositoryFingerprint,
                        candidate = candidate,
                        confirmed = false,
                    )
                    if (evaluation.requiresConfirmation) confirmationCandidate = candidate
                    else onBind(candidate, false)
                },
            ) {
                Text("绑定并继续")
            }
        }
    }

    confirmationCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { confirmationCandidate = null },
            title = { Text("仓库已变化") },
            text = {
                Text(
                    "当前绑定与所选工作区的仓库指纹不同。请核对仓库名称后重新绑定；历史任务不会被改写。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmationCandidate = null
                        onBind(candidate, true)
                    },
                ) { Text("重新绑定") }
            },
            dismissButton = {
                TextButton(onClick = { confirmationCandidate = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun WorkspaceCandidateRow(
    candidate: WorkspaceCandidate,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(candidate.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                text = workspacePathTail(candidate.cwd),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            safeRepositoryLabel(candidate.repositoryLabel)?.let { repository ->
                Text(
                    text = listOfNotNull(repository, candidate.branch?.takeIf(String::isNotBlank)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun workspacePathTail(cwd: String): String {
    val parts = cwd.trimEnd('/').split('/').filter(String::isNotBlank)
    return parts.takeLast(2).joinToString("/").ifBlank { "Mac 工作区" }
}

internal fun safeRepositoryLabel(label: String?): String? {
    val raw = label?.trim()?.takeIf(String::isNotBlank) ?: return null
    return runCatching {
        val normalized = if ("://" in raw) raw else "https://$raw"
        val uri = URI(normalized)
        val host = uri.host ?: return@runCatching null
        val path = uri.path.orEmpty().trim('/').removeSuffix(".git")
        listOf(host, path.takeIf(String::isNotBlank)).filterNotNull().joinToString("/")
    }.getOrNull()
}
