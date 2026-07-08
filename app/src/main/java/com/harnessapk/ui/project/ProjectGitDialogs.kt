package com.harnessapk.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CloneRepositoryDialog(
    onDismiss: () -> Unit,
    onClone: (name: String, remoteUrl: String, branch: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var remoteUrl by rememberSaveable { mutableStateOf("") }
    var branch by rememberSaveable { mutableStateOf("main") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("克隆仓库") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("项目名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = remoteUrl,
                    onValueChange = { remoteUrl = it },
                    label = { Text("仓库 HTTPS 地址") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = branch,
                    onValueChange = { branch = it },
                    label = { Text("分支") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && remoteUrl.isNotBlank() && branch.isNotBlank(),
                onClick = { onClone(name, remoteUrl, branch) },
            ) {
                Text("克隆")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun CommitDialog(
    onDismiss: () -> Unit,
    onCommit: (String) -> Unit,
) {
    var message by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提交全部变更") },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = message,
                onValueChange = { message = it },
                label = { Text("Commit message") },
                minLines = 3,
            )
        },
        confirmButton = {
            TextButton(enabled = message.isNotBlank(), onClick = { onCommit(message) }) { Text("提交") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun BranchDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var branch by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建分支") },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = branch,
                onValueChange = { branch = it },
                label = { Text("分支名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = branch.isNotBlank(), onClick = { onCreate(branch) }) { Text("创建并切换") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
