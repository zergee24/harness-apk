package com.harnessapk.ui.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import com.harnessapk.common.toUserMessage
import com.harnessapk.git.GitSettings
import com.harnessapk.git.GitSettingsDraft
import kotlinx.coroutines.launch

@Composable
fun GitSettingsScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
) {
    val scope = rememberCoroutineScope()
    val settings by container.gitCredentialStore.settings.collectAsState(initial = GitSettings())
    var giteeUsername by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var authorName by remember { mutableStateOf("") }
    var authorEmail by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings) {
        giteeUsername = settings.giteeUsername
        authorName = settings.authorName
        authorEmail = settings.authorEmail
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Gitee 认证", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = giteeUsername,
                    onValueChange = { giteeUsername = it },
                    label = { Text("Gitee 用户名") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(if (settings.hasToken) "私人令牌（留空保留现有）" else "私人令牌") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                AssistChip(
                    onClick = {},
                    label = { Text(if (settings.hasToken) "已保存 Token" else "未保存 Token") },
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("提交身份", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = authorName,
                    onValueChange = { authorName = it },
                    label = { Text("作者名") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = authorEmail,
                    onValueChange = { authorEmail = it },
                    label = { Text("作者邮箱") },
                    singleLine = true,
                )
            }
        }

        Button(
            enabled = giteeUsername.isNotBlank() && authorName.isNotBlank() && authorEmail.isNotBlank(),
            onClick = {
                scope.launch {
                    runCatching {
                        container.gitCredentialStore.saveSettings(
                            GitSettingsDraft(
                                giteeUsername = giteeUsername,
                                token = token.takeIf { it.isNotBlank() },
                                authorName = authorName,
                                authorEmail = authorEmail,
                            ),
                        )
                        token = ""
                    }.onSuccess {
                        statusText = "已保存 Git 设置"
                    }.onFailure {
                        statusText = it.toUserMessage()
                    }
                }
            },
        ) {
            Text("保存 Git 设置")
        }

        statusText?.let { status ->
            AssistChip(onClick = { statusText = null }, label = { Text(status) })
        }
    }
}
