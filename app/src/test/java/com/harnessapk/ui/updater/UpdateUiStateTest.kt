package com.harnessapk.ui.updater

import com.harnessapk.updater.UpdateCheckResult
import com.harnessapk.updater.UpdateDownloadState
import com.harnessapk.updater.UpdateManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUiStateTest {
    @Test
    fun autoDownloadStartsWhenUpdateIsAvailable() {
        assertTrue(shouldAutoDownload(updateResult(updateAvailable = true, forceUpdate = false)))
    }

    @Test
    fun autoDownloadStartsWhenForceUpdateIsRequired() {
        assertTrue(shouldAutoDownload(updateResult(updateAvailable = false, forceUpdate = true)))
    }

    @Test
    fun autoDownloadDoesNotStartWhenAlreadyLatest() {
        assertFalse(shouldAutoDownload(updateResult(updateAvailable = false, forceUpdate = false)))
    }

    @Test
    fun startupUpdateActionDownloadsSilentlyWhenUpdateIsAvailable() {
        assertEquals(
            StartupUpdateAction.DOWNLOAD_APK,
            startupUpdateAction(updateResult(updateAvailable = true, forceUpdate = false)),
        )
    }

    @Test
    fun startupUpdateActionDoesNotDownloadWhenAlreadyLatest() {
        assertEquals(
            StartupUpdateAction.NONE,
            startupUpdateAction(updateResult(updateAvailable = false, forceUpdate = false)),
        )
    }

    @Test
    fun installerLaunchesDirectlyWhenPermissionExists() {
        assertEquals(InstallLaunchTarget.INSTALLER, installLaunchTarget(canRequestPackageInstalls = true))
    }

    @Test
    fun installerOpensPermissionSettingsWhenPermissionIsMissing() {
        assertEquals(InstallLaunchTarget.UNKNOWN_SOURCES_SETTINGS, installLaunchTarget(canRequestPackageInstalls = false))
    }

    @Test
    fun failedBackgroundDownloadShowsReasonAndRetry() {
        val state = UpdateDownloadState.Failed(6, "安装包分片 2/23 下载失败：HTTP 500")

        assertEquals(
            "安装包分片 2/23 下载失败：HTTP 500",
            updateDownloadStatusText(state),
        )
        assertTrue(canRetryUpdateDownload(state))
    }

    @Test
    fun downloadingAndIdleStatesAreNotRetryable() {
        assertFalse(canRetryUpdateDownload(UpdateDownloadState.Downloading(6)))
        assertFalse(canRetryUpdateDownload(UpdateDownloadState.Idle))
    }

    private fun updateResult(updateAvailable: Boolean, forceUpdate: Boolean): UpdateCheckResult =
        UpdateCheckResult(
            manifest = UpdateManifest(
                versionCode = 6,
                versionName = "0.1.5",
                minSupportedVersionCode = 1,
                apkUrl = "https://example.com/app.apk",
                sha256 = "sha",
                releaseNotes = emptyList(),
                publishedAt = "2026-07-05T00:00:00Z",
            ),
            updateAvailable = updateAvailable,
            forceUpdate = forceUpdate,
        )
}
