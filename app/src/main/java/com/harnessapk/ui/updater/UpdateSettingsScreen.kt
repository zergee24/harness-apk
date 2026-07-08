package com.harnessapk.ui.updater

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.harnessapk.BuildConfig
import com.harnessapk.common.AppContainer
import com.harnessapk.updater.ApkDownloadResult
import com.harnessapk.updater.UpdateCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun UpdateSettingsScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var downloaded by remember { mutableStateOf<ApkDownloadResult?>(null) }
    var launchedInstallForSha by remember { mutableStateOf<String?>(null) }

    fun openInstallerOrPermissionSettings(apk: ApkDownloadResult) {
        when (installLaunchTarget(container.apkInstaller.canRequestPackageInstalls())) {
            InstallLaunchTarget.INSTALLER -> {
                context.startActivity(container.apkInstaller.installIntent(apk.file))
                launchedInstallForSha = apk.sha256
                status = "已打开系统安装器，请按系统提示完成安装"
            }
            InstallLaunchTarget.UNKNOWN_SOURCES_SETTINGS -> {
                context.startActivity(container.apkInstaller.unknownSourcesSettingsIntent())
                status = "请允许安装未知应用，返回本页后会自动继续安装"
            }
        }
    }

    LaunchedEffect(Unit) {
        checking = true
        statusIsError = false
        status = "正在检查更新..."
        result = withContext(Dispatchers.IO) {
            runCatching { container.updateRepository.fetchManifest() }
        }.onFailure {
            statusIsError = true
            status = it.message
        }.getOrNull()
        checking = false
        if (result?.let(::shouldAutoDownload) == true) {
            checking = true
            statusIsError = false
            status = "发现新版本，正在下载..."
            val manifest = result?.manifest
            downloaded = if (manifest == null) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { container.updateRepository.downloadApk(manifest) }
                }.onFailure {
                    statusIsError = true
                    status = it.message
                }.getOrNull()
            }
            checking = false
            if (downloaded != null) {
                statusIsError = false
                status = "下载完成，正在打开系统安装器..."
            }
        } else if (result != null) {
            statusIsError = false
            status = "当前已是最新版本"
        }
    }

    LaunchedEffect(downloaded?.sha256) {
        val apk = downloaded ?: return@LaunchedEffect
        if (launchedInstallForSha == apk.sha256) return@LaunchedEffect
        openInstallerOrPermissionSettings(apk)
    }

    DisposableEffect(lifecycleOwner, downloaded?.sha256, launchedInstallForSha) {
        val observer = LifecycleEventObserver { _, event ->
            val apk = downloaded
            if (
                event == Lifecycle.Event.ON_RESUME &&
                apk != null &&
                launchedInstallForSha != apk.sha256 &&
                container.apkInstaller.canRequestPackageInstalls()
            ) {
                openInstallerOrPermissionSettings(apk)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "应用更新",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "当前版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }

        result?.manifest?.let { manifest ->
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    shadowElevation = 1.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "最新版本 ${manifest.versionName} (${manifest.versionCode})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (result?.forceUpdate == true) {
                                "需要强制更新"
                            } else if (result?.updateAvailable == true) {
                                "发现新版本"
                            } else {
                                "当前已是最新"
                            },
                            color = if (result?.updateAvailable == true || result?.forceUpdate == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        manifest.releaseNotes.forEach { note ->
                            Text("• $note", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        downloaded?.let { apk ->
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "安装包已准备好",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                when (installLaunchTarget(container.apkInstaller.canRequestPackageInstalls())) {
                                    InstallLaunchTarget.INSTALLER -> context.startActivity(container.apkInstaller.installIntent(apk.file))
                                    InstallLaunchTarget.UNKNOWN_SOURCES_SETTINGS -> context.startActivity(container.apkInstaller.unknownSourcesSettingsIntent())
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.InstallMobile, contentDescription = null)
                            Text("继续安装", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }

        status?.let {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = if (statusIsError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = it,
                            color = if (statusIsError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
