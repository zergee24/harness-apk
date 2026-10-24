package com.harnessapk.ui.remote

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal fun systemWebViewAvailable(): Boolean = try {
    WebView.getCurrentWebViewPackage() != null
} catch (_: RuntimeException) {
    false
}

// A disabled or broken system provider can still fail between the check and creation.
internal fun createSystemWebView(context: Context): WebView? = try {
    WebView(context)
} catch (_: RuntimeException) {
    null
}

@Composable
internal fun WebViewUnavailableScreen(onRetry: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    var settingsError by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text("网页组件不可用", style = MaterialTheme.typography.titleLarge)
            Text("请在系统应用设置中安装、启用或更新 Android System WebView，然后返回重试。")
            if (settingsError) Text("无法打开系统设置，请手动前往设置中的应用管理。")
            Button(onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: RuntimeException) {
                    settingsError = true
                }
            }) { Text("打开系统设置") }
            TextButton(onClick = onRetry) { Text("重试") }
            TextButton(onClick = onExit) { Text("返回") }
        }
    }
}
