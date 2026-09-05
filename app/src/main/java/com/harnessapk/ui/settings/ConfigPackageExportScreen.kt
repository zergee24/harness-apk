package com.harnessapk.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.core.content.FileProvider
import com.harnessapk.common.AppContainer
import com.harnessapk.packageformat.CONFIG_PACKAGE_MIME_TYPE
import com.harnessapk.packageformat.ConfigPackageCodec
import com.harnessapk.packageformat.ConfigPackagePayload
import com.harnessapk.packageformat.ConfigPackageProvider
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.provider.ProviderApiProtocol
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val VALIDITY_OPTIONS_HOURS = listOf(12, 24, 72)

@Composable
fun ConfigPackageExportScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenImport: () -> Unit,
) {
    val profiles by container.providerRepository.observeEnabled().collectAsState(initial = emptyList())
    val voiceState by container.voiceCredentialStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var includeWebSearch by remember { mutableStateOf(true) }
    var includeTtsAutoRead by remember { mutableStateOf(false) }
    var includeSimpleMode by remember { mutableStateOf(true) }
    var validityHours by remember { mutableStateOf(12) }
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var exported by remember { mutableStateOf<ExportedPackageFile?>(null) }

    // 默认勾选当前默认 profile
    LaunchedEffect(profiles) {
        if (selectedIds.isEmpty() && profiles.isNotEmpty()) {
            selectedIds = setOf(profiles.first().id)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            // 避开手势导航区：贴底的按钮在部分设备上无法触达
            .padding(WindowInsets.navigationBars.asPaddingValues())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = "导入") {
            Text(
                "从家人发来的 .hconfig 配置包导入模型与语音配置。",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onOpenImport, modifier = Modifier.fillMaxWidth()) {
                Text("导入配置包")
            }
        }

        SectionCard(title = "模型配置") {
            if (profiles.isEmpty()) {
                Text(
                    "还没有可导出的模型配置，请先在「模型配置」中添加。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            profiles.forEach { profile ->
                ListItem(
                    headlineContent = { Text(profile.name) },
                    supportingContent = { Text("${profile.baseUrl} · ${profile.defaultModel}") },
                    leadingContent = {
                        Checkbox(
                            checked = profile.id in selectedIds,
                            onCheckedChange = { checked ->
                                selectedIds = if (checked) {
                                    selectedIds + profile.id
                                } else {
                                    selectedIds - profile.id
                                }
                            },
                        )
                    },
                )
            }
        }

        SectionCard(title = "语音凭证") {
            SettingToggleRow(
                title = "阿里云百炼 API Key",
                description = "容器等无系统识别的设备上作为默认语音输入",
                enabled = voiceState.hasAliyunApiKey,
                checked = voiceState.hasAliyunApiKey,
                onCheckedChange = {},
            )
            SettingToggleRow(
                title = "硅基流动 API Key",
                description = "可选的转写与朗读服务",
                enabled = voiceState.hasSiliconFlowApiKey,
                checked = voiceState.hasSiliconFlowApiKey,
                onCheckedChange = {},
            )
        }

        SectionCard(title = "家人模式") {
            SettingToggleRow(
                title = "开启联网搜索",
                description = "对方提问时模型可自动检索网页，适合查问题",
                enabled = true,
                checked = includeWebSearch,
                onCheckedChange = { includeWebSearch = it },
            )
            SettingToggleRow(
                title = "开启自动朗读回复",
                description = "对方不便看屏幕时由系统 TTS 读出回复，默认关闭",
                enabled = true,
                checked = includeTtsAutoRead,
                onCheckedChange = { includeTtsAutoRead = it },
            )
            SettingToggleRow(
                title = "开启生活简洁模式",
                description = "对方导入后生活页只保留新建对话和最近会话",
                enabled = true,
                checked = includeSimpleMode,
                onCheckedChange = { includeSimpleMode = it },
            )
        }

        SectionCard(title = "有效期") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VALIDITY_OPTIONS_HOURS.forEach { hours ->
                    FilterChip(
                        selected = validityHours == hours,
                        onClick = { validityHours = hours },
                        label = { Text("${hours}小时") },
                    )
                }
            }
            Text(
                "过期后配置包无法导入；密钥不放在微信明文里，口令请通过电话或当面告知。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "口令") {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("口令（至少 8 位）") },
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { passphrase = ConfigPackageCodec.generatePassphrase() }) {
                        Text("生成")
                    }
                },
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = confirmPassphrase,
                onValueChange = { confirmPassphrase = it },
                label = { Text("确认口令") },
                singleLine = true,
            )
        }

        val passphrasesValid = passphrase.length >= 8 && passphrase == confirmPassphrase
        if (!passphrase.isEmpty() && !passphrasesValid) {
            Text(
                if (passphrase.length < 8) "口令至少 8 位" else "两次输入的口令不一致",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            enabled = selectedIds.isNotEmpty() && passphrasesValid && !exporting,
            onClick = {
                exporting = true
                errorMessage = null
                scope.launch {
                    runCatching {
                        exportConfigPackage(
                            context = context,
                            container = container,
                            providerIds = selectedIds,
                            includeWebSearch = includeWebSearch,
                            includeTtsAutoRead = includeTtsAutoRead,
                            includeSimpleMode = includeSimpleMode,
                            validityHours = validityHours,
                            passphrase = passphrase,
                        )
                    }.onSuccess { result ->
                        exported = result
                        exporting = false
                    }.onFailure { error ->
                        errorMessage = error.message ?: "导出失败"
                        exporting = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (exporting) "正在生成…" else "生成配置包并分享")
        }

        exported?.let { result ->
            SectionCard(title = "已生成") {
                Text(result.fileName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "请在 ${result.validityHours} 小时内导入；口令请电话告知对方。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { shareConfigPackage(context, result.file) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("再次分享")
                }
            }
        }
    }
}

private data class ExportedPackageFile(
    val file: File,
    val fileName: String,
    val validityHours: Int,
)

private suspend fun exportConfigPackage(
    context: Context,
    container: AppContainer,
    providerIds: Set<String>,
    includeWebSearch: Boolean,
    includeTtsAutoRead: Boolean,
    includeSimpleMode: Boolean,
    validityHours: Int,
    passphrase: String,
): ExportedPackageFile = withContext(Dispatchers.Default) {
    val now = System.currentTimeMillis()
    val providers = providerIds.mapNotNull { id ->
        runCatching { container.providerRepository.providerWithKey(id) }.getOrNull()
    }.map { withKey ->
        ConfigPackageProvider(
            name = withKey.profile.name,
            baseUrl = withKey.profile.baseUrl,
            apiKey = withKey.apiKey,
            defaultModel = withKey.profile.defaultModel,
            defaultVisionModel = withKey.profile.defaultVisionModel,
            supportsVision = withKey.profile.supportsVision,
            nativeWebSearchMode = withKey.profile.nativeWebSearchMode.takeIf { it != NativeWebSearchMode.DISABLED }?.name,
            apiProtocol = withKey.profile.apiProtocol
                .takeIf { it != ProviderApiProtocol.OPENAI_COMPATIBLE }?.name,
            availableModels = withKey.profile.availableModels,
            customHeaders = withKey.profile.customHeaders,
            customBodyJson = withKey.profile.customBodyJson,
        )
    }
    if (providers.isEmpty()) {
        throw IllegalStateException("模型配置读取失败，无法导出")
    }
    val payload = ConfigPackagePayload(
        providers = providers,
        aliyunVoiceApiKey = container.voiceCredentialStore.aliyunApiKey(),
        siliconFlowVoiceApiKey = container.voiceCredentialStore.siliconFlowApiKey(),
        webSearchEnabled = includeWebSearch,
        simpleMode = includeSimpleMode,
        ttsAutoRead = includeTtsAutoRead,
        generatedFrom = com.harnessapk.BuildConfig.VERSION_NAME,
    )
    val bytes = ConfigPackageCodec.exportPackage(
        payload = payload,
        passphrase = passphrase,
        issuedAtMillis = now,
        expiresAtMillis = now + validityHours * 60L * 60L * 1000L,
    )
    val dir = File(context.cacheDir, "config-exports").apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
    val file = File(dir, "harness-config-$stamp.hconfig")
    file.writeBytes(bytes)
    ExportedPackageFile(file = file, fileName = file.name, validityHours = validityHours)
}

private fun shareConfigPackage(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = CONFIG_PACKAGE_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "发送配置包"))
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    description: String,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
    )
}
