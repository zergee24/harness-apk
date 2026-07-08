package com.harnessapk.ui.updater

import com.harnessapk.updater.UpdateCheckResult
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
    fun installerLaunchesDirectlyWhenPermissionExists() {
        assertEquals(InstallLaunchTarget.INSTALLER, installLaunchTarget(canRequestPackageInstalls = true))
    }

    @Test
    fun installerOpensPermissionSettingsWhenPermissionIsMissing() {
        assertEquals(InstallLaunchTarget.UNKNOWN_SOURCES_SETTINGS, installLaunchTarget(canRequestPackageInstalls = false))
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
