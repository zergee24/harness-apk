package com.harnessapk.ui.updater

import com.harnessapk.updater.UpdateCheckResult
import com.harnessapk.updater.UpdateDownloadState

enum class InstallLaunchTarget {
    INSTALLER,
    UNKNOWN_SOURCES_SETTINGS,
}

enum class StartupUpdateAction {
    NONE,
    DOWNLOAD_APK,
}

internal fun shouldAutoDownload(result: UpdateCheckResult?): Boolean =
    result?.updateAvailable == true || result?.forceUpdate == true

internal fun startupUpdateAction(result: UpdateCheckResult?): StartupUpdateAction =
    if (shouldAutoDownload(result)) {
        StartupUpdateAction.DOWNLOAD_APK
    } else {
        StartupUpdateAction.NONE
    }

internal fun shouldStartUpdateDownload(
    manifestVersionCode: Int,
    state: UpdateDownloadState,
): Boolean = when (state) {
    UpdateDownloadState.Idle -> true
    is UpdateDownloadState.Downloading -> state.versionCode != manifestVersionCode
    is UpdateDownloadState.Ready -> state.versionCode != manifestVersionCode
    is UpdateDownloadState.Failed -> state.versionCode != manifestVersionCode
}

internal fun updateDownloadStatusText(state: UpdateDownloadState): String? = when (state) {
    UpdateDownloadState.Idle -> null
    is UpdateDownloadState.Downloading -> {
        val total = state.totalBytes
        val base = if (total != null && total > 0) {
            val percent = ((state.downloadedBytes * 100) / total).coerceIn(0, 100)
            "正在后台下载更新… $percent%"
        } else {
            "正在后台下载更新…"
        }
        "$base（${formatMegabytes(state.downloadedBytes)}" +
            (total?.let { "/${formatMegabytes(it)}" } ?: "") +
            "）"
    }
    is UpdateDownloadState.Ready -> "下载完成，正在打开系统安装器..."
    is UpdateDownloadState.Failed -> state.message
}

internal fun formatMegabytes(bytes: Long): String {
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 100 || megabytes == 0.0) {
        "${megabytes.toInt()}MB"
    } else {
        String.format(java.util.Locale.ROOT, "%.1fMB", megabytes)
    }
}

internal fun canRetryUpdateDownload(state: UpdateDownloadState): Boolean =
    state is UpdateDownloadState.Failed

internal fun installLaunchTarget(canRequestPackageInstalls: Boolean): InstallLaunchTarget =
    if (canRequestPackageInstalls) {
        InstallLaunchTarget.INSTALLER
    } else {
        InstallLaunchTarget.UNKNOWN_SOURCES_SETTINGS
    }
