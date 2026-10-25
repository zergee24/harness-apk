package com.harnessapk.ui.remote

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
 * 网页；网络错误可重试当前链接，链接失效时手动粘贴新链接。
 * 首次配对仍可在 Mac 已连接时主动请求现有链接。
 *
 * 视觉基准是页面本体（深色、全出血）：套壳只提供同色底和两个图标位
 * （关闭/重连），不再叠加标题、常驻提示条等自己的 chrome。
 */
class ZcodeWebRemoteActivity : ComponentActivity() {
    private val store by lazy { ZcodeWebRemoteStore(this) }

    // 网页文件选择器：WebView 不实现 onShowFileChooser 时，页面的附件/上传按钮
    // 是静默无效的（不弹窗、不报错）。
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileChooser
            pendingFileChooser = null
            callback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
            )
        }

    fun onWebViewFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        pendingFileChooser?.onReceiveValue(null)
        pendingFileChooser = callback
        return try {
            fileChooserLauncher.launch(params.createIntent())
            true
        } catch (error: ActivityNotFoundException) {
            pendingFileChooser = null
            false
        }
    }

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
        setContent {
            // 工作模式的科技深色方案：与 zcode.z.ai 页面底色同族，chrome 不再割裂。
            ModeTheme(MainMode.WORK) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ZcodeWebRemoteScreen(
                    container = container,
                    store = store,
                    onFileChooser = ::onWebViewFileChooser,
                    onExit = { finish() },
                )
                }
            }
        }
    }
}

@Composable
fun ZcodeWebRemoteScreen(
    container: AppContainer,
    store: ZcodeWebRemoteStore,
    onFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    onExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val savedUrl by store.url.collectAsState()
    val connection by container.remoteRepository.state.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var webUnavailable by remember { mutableStateOf(!systemWebViewAvailable()) }
    if (webUnavailable) {
        WebViewUnavailableScreen(onRetry = { webUnavailable = !systemWebViewAvailable() }, onExit = onExit)
        return
    }
    var webError by remember { mutableStateOf<String?>(null) }
    var loadedUrl by remember { mutableStateOf<String?>(null) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var pairingText by remember { mutableStateOf("") }
    var editingLink by remember { mutableStateOf(false) }
    var newLinkText by remember { mutableStateOf("") }
    var pageZoom by rememberSaveable { mutableFloatStateOf(1f) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun applyZoom(value: Float) {
        val clamped = value.coerceIn(0.5f, 2f)
        pageZoom = clamped
        // textZoom 是 WebSettings 级常驻值：跨页面跳转不丢，也不碰固定底栏定位。
        webViewRef?.settings?.let { it.textZoom = (clamped * 100).toInt() }
    }

    fun saveAndLoad(raw: String) {
        if (!store.save(raw)) {
            feedback = "未找到有效的远程连接，请重新粘贴"
            return
        }
        pendingUrl = store.url.value
        feedback = null
        webError = null
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

    BackHandler(enabled = editingLink) { editingLink = false; newLinkText = "" }

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
                        // 页面缩放：文字缩放步进 0.1，范围 50%–200%（布局与底栏不动）。
                        IconButton(
                            onClick = { applyZoom(pageZoom - 0.1f) },
                            enabled = pageZoom > 0.55f,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Remove,
                                contentDescription = "缩小页面",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "${(pageZoom * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(
                            onClick = { applyZoom(pageZoom + 0.1f) },
                            enabled = pageZoom < 1.95f,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = "放大页面",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                newLinkText = ""
                                editingLink = true
                            },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "输入新的远程连接",
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { ctx ->
                            createSystemWebView(ctx)?.apply {
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
                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                        if (request.isForMainFrame) webError = "网络连接失败，请检查网络后重试。"
                                    }

                                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                        if (request.isForMainFrame) webError = "服务返回 ${response.statusCode}，可重试；链接失效时请更换连接。"
                                    }

                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onShowFileChooser(
                                        webView: WebView,
                                        callback: ValueCallback<Array<Uri>>,
                                        params: FileChooserParams,
                                    ): Boolean = onFileChooser(callback, params)
                                }
                                webViewRef = this
                            } ?: android.widget.FrameLayout(ctx).also { webUnavailable = true }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { view ->
                            if (loadedUrl != target) {
                                loadedUrl = target
                                webError = null
                                (view as? WebView)?.loadUrl(target)
                            }
                        },
                        onRelease = { view ->
                            if (webViewRef === view) webViewRef = null
                            (view as? WebView)?.destroy()
                        },
                    )
                    if (editingLink) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("输入新的远程连接", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(
                                value = newLinkText,
                                onValueChange = { newLinkText = it },
                                label = { Text("粘贴 zcode 远程连接") },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                TextButton(onClick = { editingLink = false; newLinkText = "" }) {
                                    Text("取消")
                                }
                                Button(
                                    onClick = {
                                        saveAndLoad(newLinkText)
                                        newLinkText = ""
                                        editingLink = false
                                    },
                                    enabled = looksLikeZcodeRemoteUrl(newLinkText),
                                ) {
                                    Text("保存并打开")
                                }
                            }
                        }
                    }
                }
                if (webError != null && !editingLink) {
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
                                    webError.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { newLinkText = ""; editingLink = true }) { Text("更换链接") }
                                    Button(onClick = { webError = null; webViewRef?.reload() }) { Text("重试当前链接") }
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
