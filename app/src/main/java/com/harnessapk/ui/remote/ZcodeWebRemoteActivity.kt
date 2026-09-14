package com.harnessapk.ui.remote

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.harnessapk.HarnessApkApplication
import com.harnessapk.common.AppContainer
import com.harnessapk.remote.RemoteConnectionStatus
import com.harnessapk.remote.RemoteConnectionService
import com.harnessapk.remote.ZcodeWebRemoteStore
import com.harnessapk.remote.looksLikeZcodeRemoteUrl
import com.harnessapk.remote.zcodeWebRemoteStageLabel
import kotlinx.coroutines.launch

/**
 * ZCode 远程（二维码套壳）：WebView 加载 ZCode 桌面端「移动端远程控制」配对
 * 网页；「重连」经 bridge 让 Mac 自动刷新二维码并把新链接回推回来。
 * 掉线的默认恢复是页面自刷新，重连按钮用于 relay 互踢/链接作废场景。
 */
class ZcodeWebRemoteActivity : ComponentActivity() {
    private val store by lazy { ZcodeWebRemoteStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 复用前台服务保活连接，与副屏同款语义。
        RemoteConnectionService.start(this)
        val container = (application as HarnessApkApplication).container
        setContent {
            MaterialTheme {
                ZcodeWebRemoteScreen(container = container, store = store, onExit = { finish() })
            }
        }
    }
}

@Composable
fun ZcodeWebRemoteScreen(container: AppContainer, store: ZcodeWebRemoteStore, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedUrl by store.url.collectAsState()
    val connection by container.remoteRepository.state.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var webError by remember { mutableStateOf(false) }
    var loadedUrl by remember { mutableStateOf<String?>(null) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var pairingText by remember { mutableStateOf("") }

    fun saveAndLoad(raw: String) {
        store.save(raw)
        pendingUrl = store.url.value
        feedback = null
        webError = false
    }

    fun reconnect(action: String) {
        if (busy) return
        busy = true
        feedback = if (action == "refresh") "已请求 Mac 刷新二维码…" else "已请求 Mac 发送链接…"
        container.remoteRepository.requestZcodeWebRemote(action)
        scope.launch {
            // 结果通常十几秒内回执；这里只做按钮防抖复位。
            kotlinx.coroutines.delay(45_000)
            busy = false
        }
    }

    // 回执：新链接直接换链重载；失败给 stage 引导文案。
    LaunchedEffect(Unit) {
        container.remoteRepository.webRemoteResults.collect { result ->
            busy = false
            if (result.ok && result.url != null) {
                saveAndLoad(result.url)
                feedback = "Mac 已返回新链接，正在重载"
            } else {
                feedback = zcodeWebRemoteStageLabel(result.stage) ?: result.message ?: "重连失败，请稍后重试"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text("ZCode 远程", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (connection.connectionStatus == RemoteConnectionStatus.CONNECTED) "Mac 已连接" else "Mac 未连接",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (connection.connectionStatus == RemoteConnectionStatus.CONNECTED) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            if (savedUrl != null) {
                Button(onClick = { reconnect("refresh") }, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp))
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                    }
                    Text("重连")
                }
            }
        }

        val target = pendingUrl ?: savedUrl
        if (feedback != null) {
            Text(
                feedback!!,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (target == null) {
            SetupView(
                pairingText = pairingText,
                onPairingTextChange = { pairingText = it },
                onSave = { saveAndLoad(pairingText) },
                onScan = { raw -> runCatching { saveAndLoad(raw) } },
                onRequestLink = { reconnect("link") },
                linkBusy = busy,
                linkEnabled = connection.connectionStatus == RemoteConnectionStatus.CONNECTED,
            )
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            @SuppressLint("SetJavaScriptEnabled")
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedError(
                                    view: WebView,
                                    errorCode: Int,
                                    description: String?,
                                    failingUrl: String?,
                                ) {
                                    webError = true
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        if (loadedUrl != target) {
                            loadedUrl = target
                            webError = false
                            view.loadUrl(target)
                        }
                    },
                    onRelease = { view -> view.destroy() },
                )
                if (webError) {
                    Surface(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(
                            Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("页面加载失败", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "链接可能已被踢出或作废；重连会让 Mac 刷新二维码并换新链接。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = { reconnect("refresh") }, enabled = !busy) {
                                Text("重连")
                            }
                        }
                    }
                }
            }
            Text(
                "同一时间只允许一个手机页面连接；在浏览器等其他地方打开会互踢。",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SetupView(
    pairingText: String,
    onPairingTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onScan: (String) -> Unit,
    onRequestLink: () -> Unit,
    linkBusy: Boolean,
    linkEnabled: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let(::decodeQr)?.fold(
            onSuccess = { raw ->
                if (looksLikeZcodeRemoteUrl(raw)) onScan(raw) else error = "二维码不是 ZCode 远程配对链接"
            },
            onFailure = { error = it.message },
        )
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selected ->
            scope.launch {
                runCatching { decodeQrImage(context, selected) }
                    .fold(
                        onSuccess = { raw ->
                            if (looksLikeZcodeRemoteUrl(raw)) onScan(raw) else error = "二维码不是 ZCode 远程配对链接"
                        },
                        onFailure = { error = it.message },
                    )
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("首次使用：在 Mac 的 ZCode 桌面端打开「移动端远程控制」，扫码或粘贴链接完成配对。", style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { cameraLauncher.launch(null) }, enabled = true) {
                        Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                        Text("扫描二维码")
                    }
                    OutlinedButton(onClick = { imageLauncher.launch("image/*") }, enabled = true) {
                        Icon(Icons.Outlined.Image, contentDescription = null)
                        Text("读取图片")
                    }
                }
                OutlinedButton(onClick = onRequestLink, enabled = !linkBusy && linkEnabled) {
                    if (linkBusy) CircularProgressIndicator(Modifier.padding(end = 8.dp))
                    Text("从 Mac 取链接")
                }
                OutlinedTextField(
                    value = pairingText,
                    onValueChange = onPairingTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("或粘贴配对链接") },
                    minLines = 2,
                )
                Button(onClick = onSave, enabled = looksLikeZcodeRemoteUrl(pairingText)) {
                    Text("保存并打开")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
