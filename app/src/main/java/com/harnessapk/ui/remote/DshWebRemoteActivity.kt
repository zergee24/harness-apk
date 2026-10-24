package com.harnessapk.ui.remote

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.HttpAuthHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.harnessapk.remote.DshWebSetup
import com.harnessapk.remote.DshWebRemoteStore
import com.harnessapk.remote.parseDshWebSetup
import com.harnessapk.ui.MainMode
import com.harnessapk.ui.theme.ModeTheme
import kotlinx.coroutines.delay

/**
 * DSH 网页远程：WebView 直接加载 Mac 上 `dsh web` 的公网入口。
 *
 * 与 ZCode 远程同款壳（只有关闭/缩放/换设置三个图标位），不叠加标题或
 * 说明栏。relay 的 basic auth 由 WebView 自动应答；dsh 的启动 token 由
 * Mac 侧代理注入，不出现在手机 URL 里。
 */
class DshWebRemoteActivity : ComponentActivity() {
    private val store by lazy { DshWebRemoteStore(this) }
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileChooser
            pendingFileChooser = null
            callback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
            )
        }

    private fun systemWebViewMajorVersion(): Int? = try {
        WebView.getCurrentWebViewPackage()?.versionName
            ?.substringBefore('.')
            ?.toIntOrNull()
    } catch (_: RuntimeException) {
        null
    }

    /**
     * Open the token-free public URL in the user's browser. The URL carries the
     * Basic-auth pair so a modern browser can authenticate without an extra
     * prompt where it supports userinfo; otherwise the browser prompts and the
     * same pair from `~/.dsh-web-remote/setup.txt` works.
     */
    private fun openInSystemBrowser(setup: DshWebSetup) {
        val authenticated = setup.url.replaceFirst(
            "https://",
            "https://${Uri.encode(setup.username)}:${Uri.encode(setup.password)}@",
        )
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(authenticated))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val quark = Intent(view).setPackage("com.quark.browser")
        try {
            startActivity(quark)
            return
        } catch (_: ActivityNotFoundException) {
            // Quark is not installed; fall through to the default browser.
        }
        try {
            startActivity(view)
        } catch (_: ActivityNotFoundException) {
            android.widget.Toast.makeText(this, "没有可用的浏览器", android.widget.Toast.LENGTH_LONG).show()
        }
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
        } catch (_: ActivityNotFoundException) {
            pendingFileChooser = null
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 10 devices often ship WebView 79, which cannot parse the
        // modern DSH frontend bundle (optional chaining / nullish coalescing).
        // Fall back to the system browser there instead of a guaranteed white
        // screen; modern devices keep the in-app ZCode-style shell.
        val setup = store.setup.value
        val webViewMajor = systemWebViewMajorVersion()
        if (setup != null && (webViewMajor == null || webViewMajor < 80)) {
            openInSystemBrowser(setup)
            finish()
            return
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            ModeTheme(MainMode.WORK) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DshWebRemoteScreen(
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
fun DshWebRemoteScreen(
    store: DshWebRemoteStore,
    onFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    onExit: () -> Unit,
) {
    val savedSetup by store.setup.collectAsState()
    var webUnavailable by remember { mutableStateOf(!systemWebViewAvailable()) }
    var webError by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var loadedUrl by remember { mutableStateOf<String?>(null) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var setupText by remember { mutableStateOf("") }
    var editingLink by remember { mutableStateOf(false) }
    var newLinkText by remember { mutableStateOf("") }
    var pageZoom by rememberSaveable { mutableFloatStateOf(1f) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun applyZoom(value: Float) {
        val clamped = value.coerceIn(0.5f, 2f)
        pageZoom = clamped
        webViewRef?.evaluateJavascript("document.documentElement.style.zoom='$clamped'", null)
    }

    fun saveAndLoad(raw: String) {
        if (!store.save(raw)) {
            feedback = "设置串无效：需要 https://user:pass@relay.zerg.work/dsh-…/ 形式"
            return
        }
        pendingUrl = store.setup.value?.url
        feedback = "设置已保存，正在打开"
        webError = null
    }

    if (webUnavailable) {
        WebViewUnavailableScreen(
            onRetry = { webUnavailable = !systemWebViewAvailable() },
            onExit = onExit,
        )
        return
    }

    BackHandler(enabled = editingLink) {
        editingLink = false
        newLinkText = ""
    }

    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(4_500)
            feedback = null
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val target = pendingUrl ?: savedSetup?.url
        if (target == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "首次使用：在 Mac 上运行 DSH 网页服务，把生成的设置串粘贴到下面。设置串只保存在本机应用私有存储。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = setupText,
                            onValueChange = { setupText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("粘贴 DSH 网页设置串") },
                            minLines = 3,
                        )
                        Button(
                            onClick = { saveAndLoad(setupText) },
                            enabled = parseDshWebSetup(setupText) != null,
                        ) {
                            Text("保存并打开")
                        }
                        Text(
                            "形如 https://user:pass@relay.zerg.work/dsh-…/；DSH 的启动 token 不会进入手机 URL。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onExit) { Text("返回") }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
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
                            contentDescription = "更换 DSH 网页设置",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { context ->
                            createSystemWebView(context)?.apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                @SuppressLint("SetJavaScriptEnabled")
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    mediaPlaybackRequiresUserGesture = false
                                }
                                setBackgroundColor(android.graphics.Color.parseColor("#101417"))
                                webViewClient = object : WebViewClient() {
                                    override fun onReceivedHttpAuthRequest(
                                        view: WebView,
                                        handler: HttpAuthHandler,
                                        host: String,
                                        realm: String,
                                    ) {
                                        val current = store.setup.value
                                        if (current == null) {
                                            handler.cancel()
                                        } else {
                                            handler.proceed(current.username, current.password)
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        error: WebResourceError,
                                    ) {
                                        if (request.isForMainFrame) {
                                            webError = "网络连接失败，请检查网络后重试。"
                                        }
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        response: WebResourceResponse,
                                    ) {
                                        if (request.isForMainFrame) {
                                            webError = "服务返回 ${response.statusCode}，可重试；设置失效时请更换设置串。"
                                        }
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        view.evaluateJavascript(
                                            "document.documentElement.style.zoom='$pageZoom'",
                                            null,
                                        )
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
                            } ?: android.widget.FrameLayout(context).also { webUnavailable = true }
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
                                Text("更换 DSH 网页设置", style = MaterialTheme.typography.titleSmall)
                                OutlinedTextField(
                                    value = newLinkText,
                                    onValueChange = { newLinkText = it },
                                    label = { Text("粘贴新的设置串") },
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
                                        enabled = parseDshWebSetup(newLinkText) != null,
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
                                Text(webError.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { newLinkText = ""; editingLink = true }) {
                                        Text("更换设置")
                                    }
                                    Button(onClick = { webError = null; webViewRef?.reload() }) {
                                        Text("重试当前链接")
                                    }
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
