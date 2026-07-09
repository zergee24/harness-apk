package com.harnessapk.ui.updater

import com.harnessapk.updater.UpdateCheckResult

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

internal fun installLaunchTarget(canRequestPackageInstalls: Boolean): InstallLaunchTarget =
    if (canRequestPackageInstalls) {
        InstallLaunchTarget.INSTALLER
    } else {
        InstallLaunchTarget.UNKNOWN_SOURCES_SETTINGS
    }
