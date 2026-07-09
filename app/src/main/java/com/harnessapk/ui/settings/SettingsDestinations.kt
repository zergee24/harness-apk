package com.harnessapk.ui.settings

data class SettingsDestination(
    val id: String,
    val title: String,
    val description: String,
    val showBadge: Boolean = false,
)

fun settingsDestinations(
    showUpdateBadge: Boolean = false,
): List<SettingsDestination> = listOf(
    SettingsDestination(
        id = "models",
        title = "模型配置",
        description = "维护供应商、模型列表和默认模型。",
    ),
    SettingsDestination(
        id = "search",
        title = "搜索能力",
        description = "配置会话可用的联网搜索。",
    ),
    SettingsDestination(
        id = "voice",
        title = "语音能力",
        description = "配置语音输入、麦克风权限和回复朗读。",
    ),
    SettingsDestination(
        id = "git",
        title = "Git / Gitee",
        description = "配置 Gitee 认证和默认提交身份。",
    ),
    SettingsDestination(
        id = "skills",
        title = "技能 / 插件",
        description = "技能和插件默认关闭，可按需启用。",
    ),
    SettingsDestination(
        id = "updates",
        title = "检查更新",
        description = "查看新版本并安装 APK 更新。",
        showBadge = showUpdateBadge,
    ),
)
