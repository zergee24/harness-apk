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

internal fun updateDownloadStatusText(state: UpdateDownloadState): String? = when (state) {
    UpdateDownloadState.Idle -> null
    is UpdateDownloadState.Downloading -> "正在后台下载更新..."
    is UpdateDownloadState.Ready -> "下载完成，正在打开系统安装器..."
    is UpdateDownloadState.Failed -> state.message
}

internal fun canRetryUpdateDownload(state: UpdateDownloadState): Boolean =
    state is UpdateDownloadState.Failed

internal fun installLaunchTarget(canRequestPackageInstalls: Boolean): InstallLaunchTarget =
    if (canRequestPackageInstalls) {
        InstallLaunchTarget.INSTALLER
    } else {
        InstallLaunchTarget.UNKNOWN_SOURCES_SETTINGS
    }
