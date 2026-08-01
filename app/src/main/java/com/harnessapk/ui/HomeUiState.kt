package com.harnessapk.ui

import com.harnessapk.updater.UpdateCheckResult

enum class MainMode(val label: String) {
    LIFE("生活"),
    WORK("工作"),
}

enum class HomePrimaryAction {
    CREATE_CONVERSATION,
    NONE,
}

internal fun homePrimaryAction(mode: MainMode): HomePrimaryAction = when (mode) {
    MainMode.LIFE -> HomePrimaryAction.CREATE_CONVERSATION
    MainMode.WORK -> HomePrimaryAction.NONE
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
