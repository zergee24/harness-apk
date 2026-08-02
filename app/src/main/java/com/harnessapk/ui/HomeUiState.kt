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

internal fun homeModeIcon(mode: MainMode): ImageVector = when (mode) {
    MainMode.LIFE -> Icons.AutoMirrored.Outlined.Chat
    MainMode.WORK -> Icons.Outlined.AccountTree
    MainMode.ME -> Icons.Outlined.Person
}

enum class HomePrimaryAction {
    CREATE_CONVERSATION,
    NONE,
}

internal fun homePrimaryAction(mode: MainMode): HomePrimaryAction = when (mode) {
    MainMode.LIFE -> HomePrimaryAction.CREATE_CONVERSATION
    MainMode.WORK -> HomePrimaryAction.NONE
    MainMode.ME -> HomePrimaryAction.NONE
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
