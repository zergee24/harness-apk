package com.harnessapk.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import com.harnessapk.configpackage.ConfigPackageApplier
import com.harnessapk.packageformat.ConfigPackageCodec
import com.harnessapk.packageformat.ConfigPackageEnvelope
import com.harnessapk.packageformat.ConfigPackageException
import com.harnessapk.packageformat.ConfigPackagePayload
import com.harnessapk.security.ResilientStringCipher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CONFIG_PACKAGE_MAX_BYTES = 512 * 1024

@Composable
fun ConfigPackageImportScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    packageUri: String?,
    onApplied: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var envelope by remember { mutableStateOf<LoadedEnvelope?>(null) }
    var payload by remember { mutableStateOf<ConfigPackagePayload?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 微信「用其他应用打开」等 VIEW/SEND 路由带来的 uri：进入页面即自动加载
    LaunchedEffect(packageUri) {
        val uri = packageUri?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { loadEnvelope(context, Uri.parse(uri)) }
        result.fold(
            onSuccess = { loaded ->
                envelope = loaded
                passphrase = ""
            },
            onFailure = { error ->
                errorMessage = error.userMessage()
            },
        )
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            payload = null
            errorMessage = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { loadEnvelope(context, uri) }
                result.fold(
                    onSuccess = { loaded ->
                        envelope = loaded
                        passphrase = ""
                    },
                    onFailure = { error ->
                        envelope = null
                        errorMessage = error.userMessage()
                    },
                )
            }
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
        SectionCard(title = "第 1 步 · 选择配置包") {
            val loaded = envelope
            if (loaded == null) {
                Text(
                    "请选择家人发来的 .hconfig 文件（微信里点开文件 → 用其他应用 → Harness）。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { filePicker.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("选择文件")
                }
            } else {
                Text(loaded.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "有效期至 ${formatTime(loaded.envelope.expiresAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    envelope = null
                    payload = null
                }) {
                    Text("重新选择文件")
                }
            }
        }

        if (envelope != null && payload == null) {
            SectionCard(title = "第 2 步 · 输入口令") {
                Text(
                    "口令由发送配置包的家人提供。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("口令") },
                    singleLine = true,
                )
                Button(
                    enabled = passphrase.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        errorMessage = null
                        scope.launch {
                            val result = withContext(Dispatchers.Default) {
                                runCatching {
                                    ConfigPackageCodec.decryptPayload(
                                        envelope = envelope!!.envelope,
                                        passphrase = passphrase,
                                        nowMillis = System.currentTimeMillis(),
                                    )
                                }
                            }
                            busy = false
                            result.fold(
                                onSuccess = { decrypted -> payload = decrypted },
                                onFailure = { error -> errorMessage = error.userMessage() },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "正在解密…" else "解密并预览")
                }
            }
        }

        payload?.let { decrypted ->
            SectionCard(title = "第 3 步 · 确认导入") {
                if (decrypted.providers.isEmpty()) {
                    Text("配置包内没有模型配置。", style = MaterialTheme.typography.bodyMedium)
                }
                decrypted.providers.forEach { provider ->
                    ListItem(
                        headlineContent = { Text(provider.name) },
                        supportingContent = { Text("${provider.baseUrl} · ${provider.defaultModel}") },
                    )
                }
                if (decrypted.aliyunVoiceApiKey != null) {
                    Text("将配置阿里云语音输入", style = MaterialTheme.typography.bodyMedium)
                }
                if (decrypted.siliconFlowVoiceApiKey != null) {
                    Text("将配置硅基流动语音", style = MaterialTheme.typography.bodyMedium)
                }
                if (decrypted.webSearchEnabled) {
                    Text("将开启联网搜索", style = MaterialTheme.typography.bodyMedium)
                }
                if (decrypted.simpleMode) {
                    Text("将开启生活简洁模式", style = MaterialTheme.typography.bodyMedium)
                }
                if (decrypted.ttsAutoRead) {
                    Text("将开启自动朗读回复", style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    enabled = decrypted.providers.isNotEmpty() && !busy,
                    onClick = {
                        busy = true
                        errorMessage = null
                        scope.launch {
                            runCatching {
                                container.configPackageApplier.apply(decrypted)
                            }.onSuccess { summary ->
                                // 明文配置只保留在内存中，应用完成立即释放
                                payload = null
                                envelope = null
                                busy = false
                                onApplied(welcomeMessage(container, summary))
                            }.onFailure { error ->
                                busy = false
                                errorMessage = error.userMessage()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "正在应用…" else "应用配置")
                }
            }
        }

        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private data class LoadedEnvelope(
    val envelope: ConfigPackageEnvelope,
    val displayName: String,
)

private fun loadEnvelope(context: Context, uri: Uri): Result<LoadedEnvelope> = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
        stream.readBytes()
    } ?: throw ConfigPackageException.malformed("无法读取文件")
    if (bytes.size > CONFIG_PACKAGE_MAX_BYTES) {
        throw ConfigPackageException.malformed("文件过大，不是有效的配置包")
    }
    LoadedEnvelope(
        envelope = ConfigPackageCodec.parseEnvelope(bytes),
        displayName = uri.lastPathSegment ?: "配置包",
    )
}

private fun welcomeMessage(
    container: AppContainer,
    summary: ConfigPackageApplier.AppliedSummary,
): String = buildString {
    append("配置完成。点下方 + 试试问一个问题。")
    if (summary.webSearchApplied) {
        append(" 联网搜索已开启，问问题会自动查资料。")
    }
    if (summary.speechProviderForcedToAliyun) {
        append(" 这台设备没有系统语音识别，已默认使用阿里云语音输入。")
    }
    val cipher = container.apiKeyCipher as? ResilientStringCipher
    if (cipher?.usingSoftwareFallback == true) {
        append(" 密钥已按兼容模式保存。")
    }
}

private fun Throwable.userMessage(): String = when (this) {
    is ConfigPackageException -> userMessage
    else -> message ?: "导入失败，请重试"
}

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

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
