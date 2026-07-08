package com.harnessapk.ui

import com.harnessapk.updater.UpdateCheckResult
import com.harnessapk.updater.UpdateManifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateTest {
    @Test
    fun updateBadgeShowsWhenUpdateIsAvailable() {
        assertTrue(shouldShowUpdateBadge(updateResult(updateAvailable = true, forceUpdate = false)))
    }

    @Test
    fun updateBadgeShowsWhenForceUpdateIsRequired() {
        assertTrue(shouldShowUpdateBadge(updateResult(updateAvailable = false, forceUpdate = true)))
    }

    @Test
    fun updateBadgeIsHiddenWithoutUpdateResult() {
        assertFalse(shouldShowUpdateBadge(null))
    }

    @Test
    fun updateBadgeIsHiddenWhenCurrentVersionIsLatest() {
        assertFalse(shouldShowUpdateBadge(updateResult(updateAvailable = false, forceUpdate = false)))
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
