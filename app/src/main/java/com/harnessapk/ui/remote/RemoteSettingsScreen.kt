package com.harnessapk.ui.remote

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.harnessapk.common.AppContainer
import com.harnessapk.remote.remoteFeatureAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RemoteSettingsScreen(container: AppContainer, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by container.remoteProfileStore.profile.collectAsState()
    val remoteState by container.remoteRepository.state.collectAsState()
    val pushTarget by container.aliyunPushManager.deviceId.collectAsState()
    var pairingText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var syncedPushTarget by remember(profile?.deviceId) { mutableStateOf<String?>(null) }

    LaunchedEffect(profile?.deviceId, pushTarget) {
        val currentProfile = profile ?: return@LaunchedEffect
        val target = pushTarget?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        if (syncedPushTarget == target) return@LaunchedEffect
        runCatching { container.remoteEnrollmentClient.updatePushTarget(currentProfile, target) }
            .onSuccess { syncedPushTarget = target }
            .onFailure { error = it.message ?: "推送设备同步失败" }
    }

    fun enroll(raw: String) {
        if (raw.isBlank() || busy) return
        busy = true; error = null
        scope.launch {
            runCatching { container.remoteEnrollmentClient.enroll(raw, pushTarget) }
                .onSuccess { enrolled ->
                    container.remoteRepository.disconnect()
                    container.remoteProfileStore.save(enrolled)
                    container.remoteRepository.connect()
                    pairingText = ""
                }
                .onFailure { error = it.message ?: "无法添加远程节点" }
            busy = false
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let(::decodeQr)?.fold(onSuccess = { pairingText = it; enroll(it) }, onFailure = { error = it.message })
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selected ->
            scope.launch {
                runCatching { decodeQrImage(context, selected) }
                    .fold(onSuccess = { pairingText = it; enroll(it) }, onFailure = { error = it.message })
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Codex 远程节点", style = MaterialTheme.typography.titleLarge)
        if (profile != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(profile!!.hostName, style = MaterialTheme.typography.titleMedium)
                    Text(profile!!.relayUrl, style = MaterialTheme.typography.bodyMedium)
                    Text("设备 ${profile!!.deviceId.take(10)}…", style = MaterialTheme.typography.bodySmall)
                    Text("连接状态：${connectionLabel(remoteState.connectionStatus)}", style = MaterialTheme.typography.bodySmall)
                    val availability = remoteFeatureAvailability(remoteState.capabilities)
                    Text(
                        when {
                            remoteState.connectionStatus != com.harnessapk.remote.RemoteConnectionStatus.CONNECTED -> "Remote Run：等待 Mac 连接"
                            availability.canStartM2Run && availability.canUseM3CompletionEvidence -> "Remote Run：已就绪（含完成证据）"
                            availability.canStartM2Run -> "Remote Run：可用，Mac Bridge 需升级以支持完成证据"
                            else -> "Remote Run：Mac Bridge 能力未就绪"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        when {
                            pushTarget == null -> "阿里云推送未配置，前台通知仍可用"
                            syncedPushTarget == pushTarget -> "阿里云推送已连接"
                            else -> "正在同步阿里云推送"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(context, com.harnessapk.ui.dashboard.DashboardActivity::class.java))
                    }) {
                        Text("副屏模式（常亮）")
                    }
                    OutlinedButton(onClick = { container.remoteRepository.disconnect(); container.remoteProfileStore.clear() }) {
                        Icon(Icons.Outlined.Delete, contentDescription = null); Text("移除节点")
                    }
                }
            }
        } else {
            Text("在 Mac Bridge 上运行 pair 命令，然后扫描生成的二维码。节点默认不接受公开注册。")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { cameraLauncher.launch(null) }, enabled = !busy) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null); Text("扫描二维码")
                }
                OutlinedButton(onClick = { imageLauncher.launch("image/*") }, enabled = !busy) {
                    Icon(Icons.Outlined.Image, contentDescription = null); Text("读取图片")
                }
            }
            OutlinedTextField(
                value = pairingText, onValueChange = { pairingText = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("配对 JSON") }, minLines = 4,
            )
            Button(onClick = { enroll(pairingText) }, enabled = pairingText.isNotBlank() && !busy) {
                if (busy) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text("添加节点")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private fun decodeQr(bitmap: Bitmap): Result<String> = runCatching { decodeQrText(bitmap) }

internal suspend fun decodeQrImage(context: android.content.Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        // 相册选择器返回的 URI 可能是一次性授权，流只能读一次：先把字节读进内存，
        // 边界与完整解码都从字节数组进行。
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取图片")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("无法读取图片")

        val sampleSizes = buildList {
            add(qrDecodeSampleSize(bounds.outWidth, bounds.outHeight))
            add(1)
            add(2)
        }.distinct()

        var lastFailure: Throwable? = null
        sampleSizes.forEach { sample ->
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@forEach
            // ZXing 的 getPixels 只能读软件位图，部分 ROM/图片格式可能给出其他配置。
            val software = if (decoded.config == Bitmap.Config.ARGB_8888) {
                decoded
            } else {
                val copy = decoded.copy(Bitmap.Config.ARGB_8888, false)
                decoded.recycle()
                copy ?: return@forEach
            }
            try {
                return@withContext decodeQrText(software)
            } catch (throwable: Throwable) {
                lastFailure = throwable
            } finally {
                software.recycle()
            }
        }
        throw IllegalStateException("无法读取图片中的二维码", lastFailure)
    }

internal fun qrDecodeSampleSize(width: Int, height: Int, maxDimension: Int = 2048): Int {
    var sample = 1
    while (maxOf(width, height) / sample > maxDimension) sample *= 2
    return sample
}

private fun decodeQrText(bitmap: Bitmap): String {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )
    return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
}
