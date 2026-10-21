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
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.harnessapk.ui.MainMode
import com.harnessapk.ui.theme.ModeTheme

/**
 * Codex 浏览器套壳：WebView 加载 Codex 网页入口（chatgpt.com/codex）。
 * 手机上没有官方 Codex App（依赖 Google Play），浏览器登录同一 ChatGPT
 * 账号即可用云端任务；与 Mac 上 Codex 的远程连接（bridge 配对）是两条
 * 独立通道。顶栏 ✕/↻ + 页面缩放，交互同 ZCode 套壳。
 */
class CodexWebActivity : ComponentActivity() {
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            ModeTheme(MainMode.WORK) {
                CodexWebScreen(onFileChooser = ::onWebViewFileChooser, onExit = { finish() })
            }
        }
    }
}

private const val CodexWebUrl = "https://chatgpt.com/codex"

@Composable
private fun CodexWebScreen(
    onFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    onExit: () -> Unit,
) {
    var webError by remember { mutableStateOf(false) }
    var reloadKey by rememberSaveable { mutableIntStateOf(0) }
    var appliedKey by remember { mutableIntStateOf(0) }
    var pageZoom by rememberSaveable { mutableFloatStateOf(1f) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun applyZoom(value: Float) {
        val clamped = value.coerceIn(0.5f, 2f)
        pageZoom = clamped
        webViewRef?.evaluateJavascript("document.documentElement.style.zoom='$clamped'", null)
    }

    fun reload() {
        reloadKey += 1
        webError = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 24.dp),
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
                Text(
                    "Codex 网页",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
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
                IconButton(onClick = ::reload, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "重新加载",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            @SuppressLint("SetJavaScriptEnabled")
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
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

                                // 页面每次加载完重放当前缩放（登录跳转/刷新不丢）。
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
                            loadUrl(CodexWebUrl)
                        }
                    },
                    update = { view ->
                        if (appliedKey != reloadKey) {
                            appliedKey = reloadKey
                            webError = false
                            view.reload()
                        }
                    },
                    onRelease = { view ->
                        if (webViewRef === view) webViewRef = null
                        view.destroy()
                    },
                )
                if (webError) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text("页面加载失败", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "chatgpt.com 需要国际网络可达；浏览器登录同一 ChatGPT 账号后即可使用 Codex 云端任务（与 Mac 远程连接是两条独立通道）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            Button(onClick = ::reload, modifier = Modifier.padding(top = 10.dp)) {
                                Text("重试")
                            }
                        }
                    }
                }
            }
        }
    }
}
