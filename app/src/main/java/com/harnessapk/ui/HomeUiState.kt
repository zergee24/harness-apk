package com.harnessapk.ui

import com.harnessapk.updater.UpdateCheckResult

enum class MainMode(val label: String) {
    SESSION("会话"),
    PROJECT("项目"),
}

enum class HomePrimaryAction {
    CREATE_CONVERSATION,
    NONE,
}

internal fun homePrimaryAction(mode: MainMode): HomePrimaryAction = when (mode) {
    MainMode.SESSION -> HomePrimaryAction.CREATE_CONVERSATION
    MainMode.PROJECT -> HomePrimaryAction.NONE
}

internal fun shouldShowUpdateBadge(result: UpdateCheckResult?): Boolean =
    result?.updateAvailable == true || result?.forceUpdate == true

internal fun topLevelTitle(
    mode: MainMode,
    currentProjectName: String?,
): String {
    val projectName = currentProjectName?.trim().orEmpty()
    return when {
        mode != MainMode.PROJECT -> mode.label
        projectName.isBlank() -> mode.label
        else -> "${mode.label} · $projectName"
    }
}
