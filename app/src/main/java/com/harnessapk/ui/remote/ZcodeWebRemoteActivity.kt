package com.harnessapk.ui.remote

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.harnessapk.HarnessApkApplication
import com.harnessapk.common.AppContainer
import com.harnessapk.remote.RemoteConnectionStatus
import com.harnessapk.remote.RemoteConnectionService
import com.harnessapk.remote.ZcodeWebRemoteStore
import com.harnessapk.remote.looksLikeZcodeRemoteUrl
import com.harnessapk.remote.zcodeWebRemoteStageLabel
import com.harnessapk.ui.MainMode
import com.harnessapk.ui.theme.ModeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ZCode 远程（二维码套壳）：WebView 加载 ZCode 桌面端「移动端远程控制」配对
 * 网页；「重连」经 bridge 让 Mac 自动刷新二维码并把新链接回推回来。
 * 掉线的默认恢复是页面自刷新，重连按钮用于 relay 互踢/链接作废场景。
 *
 * 视觉基准是页面本体（深色、全出血）：套壳只提供同色底和两个图标位
 * （关闭/重连），不再叠加标题、常驻提示条等自己的 chrome。
 */
class ZcodeWebRemoteActivity : ComponentActivity() {
    private val store by lazy { ZcodeWebRemoteStore(this) }
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 复用前台服务保活连接，与副屏同款语义。
        RemoteConnectionService.start(this)
        // 页面是深色 UI：系统栏强制深色样式（浅色前景），避免随系统亮色模式
        // 翻成深色图标压在深底上；scrim 透明，由 Compose 侧统一铺深色底。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val container = (application as HarnessApkApplication).container
        // 旋转由 configChanges 兜住不重建；系统回收后重建走到这里时，带上
        // WebView 历史栈，让页面续上原会话链接而不是重开最初配对链接。
        val restoredWebViewState = savedInstanceState?.getBundle(KEY_WEB_VIEW_STATE)
        setContent {
            // 工作模式的科技深色方案：与 zcode.z.ai 页面底色同族，chrome 不再割裂。
            ModeTheme(MainMode.WORK) {
                ZcodeWebRemoteScreen(
                    container = container,
                    store = store,
                    initialWebViewState = restoredWebViewState,
                    onWebViewChange = { webView = it },
                    onExit = { finish() },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.let { outState.putBundle(KEY_WEB_VIEW_STATE, Bundle().also { bundle -> it.saveState(bundle) }) }
    }

    private companion object {
        const val KEY_WEB_VIEW_STATE = "webBackForwardState"
    }
}

@Composable
fun ZcodeWebRemoteScreen(
    container: AppContainer,
    store: ZcodeWebRemoteStore,
    initialWebViewState: Bundle?,
    onWebViewChange: (WebView?) -> Unit,
    onExit: () -> Unit,
) {
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

    // 回执：新链接直接换链重载；Mac 端解不出时原图兜底给手机端 zxing；
    // 失败给 stage 引导文案。
    LaunchedEffect(Unit) {
        container.remoteRepository.webRemoteResults.collect { result ->
            busy = false
            if (result.ok && result.url != null) {
                saveAndLoad(result.url)
                feedback = "Mac 已返回新链接，正在重载"
            } else if (result.imageB64 != null) {
                val raw = android.util.Base64.decode(result.imageB64, android.util.Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
                if (bitmap != null) {
                    val text = decodeQr(bitmap).getOrNull()
                    bitmap.recycle()
                    if (text != null && looksLikeZcodeRemoteUrl(text)) {
                        saveAndLoad(text)
                        feedback = "已从 Mac 截图解析出链接，正在重载"
                        return@collect
                    }
                }
                feedback = zcodeWebRemoteStageLabel(result.stage) ?: result.message ?: "截图解析失败，请稍后重试"
            } else {
                feedback = zcodeWebRemoteStageLabel(result.stage) ?: result.message ?: "重连失败，请稍后重试"
            }
        }
    }

    // 反馈浮层化：出现后数秒自动消散，不再常驻挤压页面。
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(4_500)
            feedback = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val target = pendingUrl ?: savedUrl
        if (target == null) {
            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                SetupView(
                    pairingText = pairingText,
                    onPairingTextChange = { pairingText = it },
                    onSave = { saveAndLoad(pairingText) },
                    onRequestLink = { reconnect("link") },
                    linkBusy = busy,
                    linkEnabled = connection.connectionStatus == RemoteConnectionStatus.CONNECTED,
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                // 顶栏只留关闭与重连两个图标位；标题/地址交给页面自己的头部。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onExit, modifier = Modifier.size(44.dp)) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(Modifier.weight(1f))
                    if (savedUrl != null) {
                        IconButton(onClick = { reconnect("refresh") }, enabled = !busy, modifier = Modifier.size(44.dp)) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(18.dp))
                            } else {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    contentDescription = "重连：让 Mac 刷新二维码并返回新链接",
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

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
                                // 深底消白闪：页面首帧绘制前 WebView 默认白底会割裂。
                                setBackgroundColor(android.graphics.Color.parseColor("#101417"))
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
                                // 重建恢复：restoreState 会自行加载历史栈当前条目
                                // （续的是会话页 URL），标记 loadedUrl 以免下方 update
                                // 再用最初配对链接 loadUrl 把恢复冲掉。
                                initialWebViewState?.let { state ->
                                    restoreState(state)
                                    if (copyBackForwardList().currentItem != null) {
                                        loadedUrl = target
                                    }
                                }
                                onWebViewChange(this)
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
                        onRelease = { view ->
                            onWebViewChange(null)
                            view.destroy()
                        },
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
            }
        }

        feedback?.let { message ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 12.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp,
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SetupView(
    pairingText: String,
    onPairingTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onRequestLink: () -> Unit,
    linkBusy: Boolean,
    linkEnabled: Boolean,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "首次使用：在 Mac 的 ZCode 桌面端「移动端远程控制」对话框点「复制链接」，粘贴到下面；Mac 在线时也可直接点「从 Mac 取链接」。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRequestLink, enabled = !linkBusy && linkEnabled) {
                    if (linkBusy) CircularProgressIndicator(Modifier.padding(end = 8.dp))
                    Text("从 Mac 取链接")
                }
                if (!linkEnabled) {
                    Text(
                        "Mac 未连接（Codex 节点离线），可手动粘贴链接",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = pairingText,
                    onValueChange = onPairingTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("粘贴配对链接") },
                    minLines = 2,
                )
                Button(onClick = onSave, enabled = looksLikeZcodeRemoteUrl(pairingText)) {
                    Text("保存并打开")
                }
                Text(
                    "同一时间只允许一个手机页面连接，在浏览器等其他地方打开会互踢。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
