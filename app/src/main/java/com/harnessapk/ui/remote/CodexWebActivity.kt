package com.harnessapk.ui.remote

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.harnessapk.ui.MainMode
import com.harnessapk.ui.theme.ModeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Codex 云端网页与 Mac bridge 配对是两条独立通道。 */
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
        } catch (_: ActivityNotFoundException) {
            pendingFileChooser = null
            false
        }
    }

    private fun openInBrowser() {
        // Never forward an OAuth callback URL, cookies or credentials to another app.
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CodexWebUrl)).addCategory(Intent.CATEGORY_BROWSABLE))
            Toast.makeText(this, "请在浏览器中登录并使用；若提示不支持移动端，请开启桌面模式。浏览器登录不会同步到应用内。", Toast.LENGTH_LONG).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "没有可用的浏览器，请先安装或启用浏览器。", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "无法打开浏览器，请检查系统设置。", Toast.LENGTH_LONG).show()
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
                CodexWebScreen(
                    onFileChooser = ::onWebViewFileChooser,
                    onOpenBrowser = ::openInBrowser,
                    onExit = { finish() },
                )
            }
        }
    }
}

// dots 网页端只对桌面环境开放，保持既有桌面模式；不能保证绕过服务端验证。
private const val DesktopUa =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private const val DesktopSpoofJs =
    "try{Object.defineProperty(navigator,'platform',{get:function(){return 'MacIntel'}});}catch(e){}" +
        "try{Object.defineProperty(navigator,'userAgentData',{get:function(){return {mobile:false,platform:'macOS',brands:[]}}});}catch(e){}"

/** All cookie writes complete before navigating back to login. Other work shells stay signed in. */
internal suspend fun resetCodexWebSession() {
    val cookies = CookieManager.getInstance()
    val removals = codexCookieRemovals(cookies::getCookie)
    expireCodexCookies(removals) { removal, completed ->
        cookies.setCookie(removal.url, removal.cookie) { completed(it) }
    }
    cookies.flush()
    for (host in CodexSessionHosts) WebStorage.getInstance().deleteOrigin("https://$host")
}

@Composable
internal fun CodexWebScreen(
    onFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    onOpenBrowser: () -> Unit,
    onExit: () -> Unit,
    webViewFactory: (Context) -> WebView? = ::createSystemWebView,
) {
    var webUnavailable by remember { mutableStateOf(!systemWebViewAvailable()) }
    if (webUnavailable) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = onOpenBrowser, modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Text("在浏览器中打开 Codex")
            }
            Box(Modifier.weight(1f)) {
                WebViewUnavailableScreen(onRetry = { webUnavailable = !systemWebViewAvailable() }, onExit = onExit)
            }
        }
        return
    }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(CodexWebLoadState()) }
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var mainFrameUrl by remember { mutableStateOf<String?>(null) }
    var pageZoom by rememberSaveable { mutableFloatStateOf(1f) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun applyZoom(value: Float) {
        pageZoom = value.coerceIn(0.5f, 2f)
        webViewRef?.evaluateJavascript("document.documentElement.style.zoom='$pageZoom'", null)
    }

    fun navigate(url: String) {
        if (resetting) return
        page = page.started()
        webViewRef?.stopLoading()
        webViewRef?.loadUrl(url)
    }

    fun reload() {
        if (resetting) return
        page = page.started()
        webViewRef?.reload()
    }

    fun resetLogin() {
        val view = webViewRef ?: return
        if (resetting) return
        resetting = true
        page = page.started()
        // Retire the old document before touching its cookies/storage.
        view.settings.javaScriptEnabled = false
        view.stopLoading()
        view.loadUrl("about:blank")
        scope.launch {
            try {
                withTimeout(15_000) { resetCodexWebSession() }
                if (webViewRef === view) {
                    view.settings.javaScriptEnabled = true
                    view.clearHistory()
                    page = page.started()
                    view.loadUrl(CodexLoginUrl)
                }
            } catch (_: TimeoutCancellationException) {
                page = page.failed("重置登录状态超时，请重试或在浏览器中打开。")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                page = page.failed("重置登录状态未完成，请重试或在浏览器中打开。")
            } finally {
                if (webViewRef === view) view.settings.javaScriptEnabled = true
                resetting = false
            }
        }
    }

    BackHandler(enabled = canGoBack && !resetting) { webViewRef?.goBack() }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("重置 Codex 登录状态？") },
            text = { Text("将清理应用内可识别的 ChatGPT/OpenAI 登录 Cookie 和网站存储，再打开登录页。不影响 ZCode、DSH 或 Mac 远程配对；浏览器中的登录不受影响。403 也可能由网络或网页验证引起，重置不保证解除访问限制。") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; resetLogin() }) { Text("重置并登录") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).background(MaterialTheme.colorScheme.surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Close, "关闭", Modifier.size(22.dp))
            }
            Text("Codex 网页", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { applyZoom(pageZoom - 0.1f) }, enabled = pageZoom > 0.55f, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Remove, "缩小页面", Modifier.size(20.dp))
            }
            Text("${(pageZoom * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = { applyZoom(pageZoom + 0.1f) }, enabled = pageZoom < 1.95f, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Add, "放大页面", Modifier.size(20.dp))
            }
            IconButton(onClick = ::reload, enabled = !resetting, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Refresh, "重新加载", Modifier.size(22.dp))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.MoreVert, "Codex 登录与打开方式", Modifier.size(22.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("重新登录") }, enabled = !resetting, onClick = {
                        menuExpanded = false
                        navigate(CodexLoginUrl)
                    })
                    DropdownMenuItem(text = { Text("返回 Codex 首页") }, enabled = !resetting, onClick = {
                        menuExpanded = false
                        navigate(CodexWebUrl)
                    })
                    DropdownMenuItem(text = { Text("重置 Codex 登录状态…") }, enabled = !resetting, onClick = {
                        menuExpanded = false
                        confirmReset = true
                    })
                    DropdownMenuItem(text = { Text("在浏览器中打开") }, onClick = {
                        menuExpanded = false
                        onOpenBrowser()
                    })
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    webViewFactory(ctx)?.apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        @SuppressLint("SetJavaScriptEnabled")
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = DesktopUa
                        setBackgroundColor(android.graphics.Color.parseColor("#101417"))
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                // getUrl() can still report the previous document before a failed load commits.
                                mainFrameUrl = url
                                page = page.started()
                                canGoBack = view.canGoBack()
                                view.evaluateJavascript(DesktopSpoofJs, null)
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame && codexSameDocumentUrl(request.url.toString(), mainFrameUrl)) {
                                    page = page.failed("网络连接失败，请检查网络后重试。")
                                }
                            }

                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame && codexSameDocumentUrl(request.url.toString(), mainFrameUrl)) {
                                    page = page.failed(codexHttpErrorMessage(response.statusCode))
                                }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                if (!codexSameDocumentUrl(url, mainFrameUrl)) return
                                page = page.finished()
                                canGoBack = view.canGoBack()
                                view.evaluateJavascript("document.documentElement.style.zoom='$pageZoom'", null)
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onShowFileChooser(
                                webView: WebView,
                                callback: ValueCallback<Array<Uri>>,
                                params: FileChooserParams,
                            ): Boolean = onFileChooser(callback, params)
                        }
                        installWebShellDownload(ctx)
                        webViewRef = this
                        loadUrl(CodexWebUrl)
                    } ?: android.widget.FrameLayout(ctx).also { webUnavailable = true }
                },
                onRelease = { view ->
                    if (webViewRef === view) webViewRef = null
                    (view as? WebView)?.let { it.stopLoading(); it.destroy() }
                },
            )
            if (resetting || (page.loading && page.error == null)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            page.error?.let { error ->
                Surface(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                        Text("页面加载失败", style = MaterialTheme.typography.titleSmall)
                        Text(error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        Button(onClick = { navigate(CodexLoginUrl) }, enabled = !resetting, modifier = Modifier.padding(top = 10.dp)) {
                            Text("重新登录")
                        }
                        TextButton(onClick = ::reload, enabled = !resetting) { Text("重试当前页") }
                        TextButton(onClick = { page = page.copy(error = null) }) { Text("查看原网页") }
                        TextButton(onClick = onOpenBrowser) { Text("在浏览器中打开") }
                    }
                }
            }
        }
    }
}
