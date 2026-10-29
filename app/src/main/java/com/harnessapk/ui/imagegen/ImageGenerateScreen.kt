package com.harnessapk.ui.imagegen

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.harnessapk.common.AppContainer
import com.harnessapk.remote.BRIDGE_IMAGE_GENERATE_BUDGET_MILLIS
import com.harnessapk.remote.DEFAULT_IMAGE_HISTORY_LIMIT
import com.harnessapk.remote.ImageGenerateParams
import com.harnessapk.remote.ImageGeneratePhase
import com.harnessapk.remote.ImageGenerateResult
import com.harnessapk.remote.ImageModel
import com.harnessapk.remote.ImageQuality
import com.harnessapk.remote.ImageSize
import com.harnessapk.remote.MAX_IMAGE_PROMPT_CHARS
import com.harnessapk.remote.RemoteConnectionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 生图：输入 prompt → 经已配对的 Mac Bridge 调用 OpenAI 生图 → 图片回传后展示/保存/分享。
 * 命令与事件协议见 remote/docs/image-generate.md；传输复用 Codex 远程节点链路。
 */
@Composable
fun ImageGenerateScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = container.imageGenerateStore
    val viewModel = remember(store) {
        ImageGenerateViewModel(store, ioDispatcher = container.dispatchers.io)
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.close() }
    }
    val uiState by viewModel.uiState.collectAsState()
    val remoteState by container.remoteRepository.state.collectAsState()
    val connected = remoteState.connectionStatus == RemoteConnectionStatus.CONNECTED

    // 表单：rememberSaveable 保证旋转/进程内返回时还在；首次进入时回填上次提交过的参数。
    val initialParams = store.state.value.request?.params
    var prompt by rememberSaveable { mutableStateOf(initialParams?.prompt.orEmpty()) }
    var sizeWire by rememberSaveable {
        mutableStateOf((initialParams?.size ?: ImageSize.SQUARE).wireValue)
    }
    var qualityWire by rememberSaveable {
        mutableStateOf((initialParams?.quality ?: ImageQuality.MEDIUM).wireValue)
    }
    var modelWire by rememberSaveable {
        mutableStateOf((initialParams?.model ?: ImageModel.FLARE).wireValue)
    }
    val size = ImageSize.fromWire(sizeWire) ?: ImageSize.SQUARE
    val quality = ImageQuality.fromWire(qualityWire) ?: ImageQuality.MEDIUM
    val model = ImageModel.fromWire(modelWire) ?: ImageModel.FLARE
    val promptTooLong = prompt.length > MAX_IMAGE_PROMPT_CHARS
    val currentParams = ImageGenerateParams(prompt = prompt, size = size, quality = quality, model = model)
    val busy = uiState.phase == ImageGeneratePhase.SUBMITTING || uiState.phase == ImageGeneratePhase.GENERATING
    val currentResult = uiState.result

    var elapsedMillis by remember { mutableLongStateOf(0L) }
    LaunchedEffect(uiState.phase, uiState.request?.startedAtMillis) {
        val startedAt = uiState.request?.startedAtMillis
        elapsedMillis = 0L
        if (busy && startedAt != null) {
            while (true) {
                elapsedMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                delay(500)
            }
        }
    }

    var actionMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var pendingSaveResult by remember { mutableStateOf<ImageGenerateResult?>(null) }

    fun performSave(result: ImageGenerateResult) {
        if (isSaving) return
        actionMessage = null
        isSaving = true
        scope.launch {
            val outcome = runCatching {
                withContext(container.dispatchers.io) { saveGeneratedImageToAlbum(context, result) }
            }
            isSaving = false
            actionMessage = outcome.fold(
                onSuccess = { name -> "已保存到相册：$IMAGE_GENERATE_ALBUM_RELATIVE_PATH/$name" },
                onFailure = { error -> "保存失败：${error.message ?: "未知错误"}" },
            )
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingSaveResult
        pendingSaveResult = null
        when {
            !granted -> actionMessage = "没有存储权限，无法保存到相册"
            pending != null -> performSave(pending)
        }
    }

    fun saveCurrent(result: ImageGenerateResult) {
        actionMessage = null
        if (imageGenerateAlbumNeedsPermission(context)) {
            pendingSaveResult = result
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        performSave(result)
    }

    fun shareCurrent(result: ImageGenerateResult) {
        actionMessage = null
        scope.launch {
            runCatching {
                val intent = withContext(container.dispatchers.io) {
                    buildGeneratedImageShareIntent(context, result)
                }
                context.startActivity(Intent.createChooser(intent, "分享生成的图片"))
            }.onFailure { error ->
                actionMessage = "分享失败：${error.message ?: "没有可用的分享应用"}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("image-generate-screen")
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "用文字描述画面，命令会经加密通道发到已配对的 Mac，由 Mac 调用 OpenAI 生图后再回传到手机。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!connected) ConnectionHint()

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("画面描述", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    placeholder = { Text("例如：一只戴宇航头盔的橘猫，扁平插画风，米白背景，正中心构图") },
                    minLines = 4,
                    maxLines = 8,
                    isError = promptTooLong,
                    supportingText = {
                        Text(
                            if (promptTooLong) {
                                "描述不能超过 $MAX_IMAGE_PROMPT_CHARS 字（当前 ${prompt.length} 字）"
                            } else {
                                "${prompt.length} / $MAX_IMAGE_PROMPT_CHARS 字"
                            },
                        )
                    },
                    shape = MaterialTheme.shapes.medium,
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SelectorRow(
                    title = "尺寸",
                    options = ImageSize.entries.map { it.wireValue to it.label },
                    selected = size.wireValue,
                    onSelect = { sizeWire = it },
                )
                SelectorRow(
                    title = "质量",
                    options = ImageQuality.entries.map { it.wireValue to it.label },
                    selected = quality.wireValue,
                    onSelect = { qualityWire = it },
                )
                SelectorRow(
                    title = "模型",
                    options = ImageModel.entries.map { it.wireValue to it.label },
                    selected = model.wireValue,
                    onSelect = { modelWire = it },
                )
            }
        }

        Button(
            onClick = {
                actionMessage = null
                viewModel.generate(currentParams)
            },
            enabled = !busy && prompt.isNotBlank() && !promptTooLong,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (uiState.phase == ImageGeneratePhase.SUBMITTING) "正在提交…" else "正在生成…")
            } else {
                Text(if (currentResult == null) "开始生成" else "再生成一张")
            }
        }

        if (!busy) {
            Text(
                text = "每台 Mac 同时只处理 1 张，约每分钟 4 次、每天 200 次；生成中按钮会保持禁用，避免触发限流。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (busy) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            val bridgeOverBudget = elapsedMillis > BRIDGE_IMAGE_GENERATE_BUDGET_MILLIS
                            Text(
                                text = when {
                                    uiState.phase == ImageGeneratePhase.SUBMITTING -> "正在提交生图命令…"
                                    bridgeOverBudget -> "桥上仍在生成…"
                                    else -> "正在生成图片…"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (bridgeOverBudget) {
                                    "已等待 ${formatElapsed(elapsedMillis)}：超过 bridge 的 ${BRIDGE_IMAGE_GENERATE_BUDGET_MILLIS / 1000} 秒预算，稍后可能收到桥上超时事件"
                                } else {
                                    "已等待 ${formatElapsed(elapsedMillis)}；高质量模型通常需要 5–30 秒"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (uiState.phase == ImageGeneratePhase.ERROR) {
            val presentation = errorPresentation(uiState.errorCode)
            ErrorCard(
                title = presentation.first,
                message = uiState.errorMessage ?: "生图失败，请稍后重试",
                note = presentation.second,
                onDismiss = { viewModel.dismissError() },
                onRetry = {
                    actionMessage = null
                    viewModel.generate(currentParams)
                },
            )
        }

        if (currentResult != null) {
            ResultCard(
                result = currentResult,
                bitmap = uiState.bitmap,
                isDecoding = uiState.isDecoding,
                decodeError = uiState.decodeError,
                isSaving = isSaving,
                busy = busy,
                onSave = { saveCurrent(currentResult) },
                onShare = { shareCurrent(currentResult) },
                onRegenerate = {
                    actionMessage = null
                    viewModel.generate(currentParams)
                },
            )
            actionMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (uiState.history.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "最近生成",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    uiState.history.forEach { entry ->
                        FilterChip(
                            selected = entry.requestId == currentResult?.requestId,
                            onClick = { viewModel.previewHistory(entry) },
                            label = { Text(historyLabel(entry)) },
                        )
                    }
                }
                Text(
                    text = "仅为本次打开期间保留最近 $DEFAULT_IMAGE_HISTORY_LIMIT 张，退出应用后需重新生成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConnectionHint() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Mac 尚未连接", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "生图依赖 Mac Bridge 在线；请先在「我的 → Codex 远程节点」完成配对并保持连接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SelectorRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (wireValue, label) ->
                FilterChip(
                    selected = selected == wireValue,
                    onClick = { onSelect(wireValue) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(
    title: String,
    message: String,
    note: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("关闭") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

@Composable
private fun ResultCard(
    result: ImageGenerateResult,
    bitmap: Bitmap?,
    isDecoding: Boolean,
    decodeError: String?,
    isSaving: Boolean,
    busy: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: () -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("生成结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${result.mimeType.substringAfter('/', "image").uppercase(Locale.US)} · ${formatBytes(result.byteCount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val imageBitmap = bitmap?.asImageBitmap()
            when {
                imageBitmap != null -> Image(
                    bitmap = imageBitmap,
                    contentDescription = "生成的图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()),
                )
                isDecoding -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("正在解码图片…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                decodeError != null -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Image, contentDescription = null)
                    Text(
                        text = decodeError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            MetadataRow("模型", modelLabel(result.model))
            MetadataRow("尺寸", "${result.width}×${result.height}（${sizeLabel(result.size)}）")
            MetadataRow("质量", qualityLabel(result.quality))
            MetadataRow("耗时", formatDuration(result.durationMs))
            result.costEstimateUsd?.let { cost -> MetadataRow("估算成本", formatCost(cost)) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onSave,
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isSaving) "保存中…" else "保存到相册")
                }
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("分享")
                }
            }
            TextButton(
                onClick = onRegenerate,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("重新生成")
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

/** 区分「本地等待超时」「桥上仍在生成/限流」等不同错误态，文案与 bridge 的 code 枚举对齐。 */
private fun errorPresentation(code: String?): Pair<String, String?> = when (code) {
    "TIMEOUT" -> "本地等待超时" to "图片可能仍在 Mac 上生成：bridge 预算 ${BRIDGE_IMAGE_GENERATE_BUDGET_MILLIS / 1000} 秒，回包到达后会自动显示。重新生成会占用新的每日额度。"
    "IMAGE_TIMEOUT" -> "桥上生图超时" to "Mac Bridge 在 ${BRIDGE_IMAGE_GENERATE_BUDGET_MILLIS / 1000} 秒预算内没拿到图片；可以换更低质量或更小尺寸重试。"
    "IMAGE_BUSY" -> "Mac 仍在生成上一张" to "bridge 每台设备同时只处理 1 个生图请求，等上一张完成后再试。"
    "IMAGE_RATE_LIMITED", "RATE_LIMITED", "OPENAI_HTTP_429" ->
        "生图请求太频繁" to "bridge 限制每分钟最多 4 次、每天 200 次，请稍后再试。"
    "IMAGE_DAILY_LIMIT" -> "今日额度已用完" to "每天最多 200 张，明天再试。"
    "IMAGE_TOO_LARGE" -> "图片超过 8MB" to "bridge 没有回传超大图片，可以换更小尺寸或更低质量重试。"
    "BAD_PAYLOAD" -> "图片数据异常" to "回包无法解析，可以重试；若反复失败请检查 Mac bridge 是否为最新版本。"
    "NOT_CONNECTED" -> "Mac 尚未连接" to "请先在「我的 → Codex 远程节点」完成配对并保持连接。"
    else -> "生图失败" to null
}

private fun modelLabel(wireValue: String): String =
    ImageModel.fromWire(wireValue)?.label ?: wireValue.ifBlank { "未知模型" }

private fun qualityLabel(wireValue: String): String =
    ImageQuality.fromWire(wireValue)?.label ?: wireValue.ifBlank { "未知" }

private fun sizeLabel(wireValue: String): String =
    ImageSize.fromWire(wireValue)?.label?.substringAfterLast(' ') ?: wireValue.ifBlank { "未知" }

private fun historyLabel(result: ImageGenerateResult): String {
    val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(result.completedAtMillis))
    val model = ImageModel.fromWire(result.model)?.label?.substringBefore('（') ?: "图片"
    return "$time · $model"
}

private fun formatElapsed(millis: Long): String {
    val seconds = (millis / 1000L).coerceAtLeast(0L)
    return if (seconds < 60) "${seconds} 秒" else "${seconds / 60} 分 ${seconds % 60} 秒"
}

private fun formatDuration(millis: Long): String = when {
    millis <= 0L -> "—"
    millis < 1000L -> "${millis} 毫秒"
    else -> "${"%.1f".format(Locale.US, millis / 1000.0)} 秒"
}

private fun formatCost(usd: Double): String = "约 $${"%.3f".format(Locale.US, usd)}"

private fun formatBytes(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(Locale.US, bytes / 1024.0)} KB"
    else -> "${"%.1f".format(Locale.US, bytes / (1024.0 * 1024.0))} MB"
}
