package com.harnessapk.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

enum class BackgroundPermissionGuide { HONOR_HUAWEI, OTHER }

// 荣耀/华为系 ROM 退后台速冻最狠，应用信息页之外还有独立的「应用启动管理」入口；
// 其余厂商跳系统应用详情页（电池/权限都能到）。
internal fun backgroundPermissionGuide(manufacturer: String = Build.MANUFACTURER): BackgroundPermissionGuide =
    if (
        manufacturer.contains("HONOR", ignoreCase = true) ||
        manufacturer.contains("HUAWEI", ignoreCase = true)
    ) {
        BackgroundPermissionGuide.HONOR_HUAWEI
    } else {
        BackgroundPermissionGuide.OTHER
    }

// 依次尝试厂商手机管家的启动管理页，任一可用即返回；全部不可用则落到本应用详情页。
internal fun backgroundPermissionIntents(packageName: String, guide: BackgroundPermissionGuide): List<Intent> {
    val vendorComponents = if (guide == BackgroundPermissionGuide.HONOR_HUAWEI) {
        listOf(
            ComponentName(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        )
    } else {
        emptyList()
    }
    return vendorComponents.map { Intent().setComponent(it) } +
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName"))
}

fun openBackgroundPermissionSettings(context: Context) {
    val candidates = backgroundPermissionIntents(
        packageName = context.packageName,
        guide = backgroundPermissionGuide(),
    )
    candidates.firstOrNull { candidate ->
        runCatching {
            context.startActivity(candidate)
            true
        }.getOrDefault(false)
    }
}
