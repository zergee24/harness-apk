package com.harnessapk.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.ImageVector
import com.harnessapk.updater.UpdateCheckResult

enum class MainMode(val label: String) {
    LIFE("生活"),
    WORK("工作"),
    ME("我的"),
}

internal fun normalizeThemeSource(mode: MainMode): MainMode = when (mode) {
    MainMode.LIFE -> MainMode.LIFE
    MainMode.WORK -> MainMode.WORK
    MainMode.ME -> MainMode.LIFE
}

internal fun resolveThemeMode(
    mainMode: MainMode,
    themeSourceMode: MainMode,
): MainMode = if (mainMode == MainMode.ME) {
    normalizeThemeSource(themeSourceMode)
} else {
    mainMode
}

internal fun nextThemeSource(
    currentSource: MainMode,
    selectedMode: MainMode,
): MainMode = when (selectedMode) {
    MainMode.LIFE -> MainMode.LIFE
    MainMode.WORK -> MainMode.WORK
    MainMode.ME -> normalizeThemeSource(currentSource)
}

internal fun homeModeIcon(mode: MainMode): ImageVector = when (mode) {
    MainMode.LIFE -> Icons.AutoMirrored.Outlined.Chat
    MainMode.WORK -> Icons.Outlined.AccountTree
    MainMode.ME -> Icons.Outlined.Person
}

internal fun shouldShowUpdateBadge(result: UpdateCheckResult?): Boolean =
    result?.updateAvailable == true || result?.forceUpdate == true

internal fun topLevelTitle(
    mode: MainMode,
    currentProjectName: String?,
): String {
    val projectName = currentProjectName?.trim().orEmpty()
    return when {
        mode != MainMode.WORK -> mode.label
        projectName.isBlank() -> mode.label
        else -> "${mode.label} · $projectName"
    }
}
