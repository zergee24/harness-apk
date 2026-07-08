package com.harnessapk.ui.updater

import com.harnessapk.updater.UpdateCheckResult

enum class InstallLaunchTarget {
    INSTALLER,
    UNKNOWN_SOURCES_SETTINGS,
}

internal fun shouldAutoDownload(result: UpdateCheckResult?): Boolean =
    result?.updateAvailable == true || result?.forceUpdate == true

internal fun installLaunchTarget(canRequestPackageInstalls: Boolean): InstallLaunchTarget =
    if (canRequestPackageInstalls) {
        InstallLaunchTarget.INSTALLER
    } else {
        InstallLaunchTarget.UNKNOWN_SOURCES_SETTINGS
    }
