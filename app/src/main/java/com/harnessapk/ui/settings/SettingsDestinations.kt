package com.harnessapk.ui.settings

data class SettingsDestination(
    val id: String,
    val title: String,
    val description: String,
    val showBadge: Boolean = false,
)

// 配置页分组：设备与同步（远程/更新）→ 对话能力（模型/搜索/语音/配置包）
// → 项目与资料（Git/技能/智能体/Wiki）。顺序即页内渲染顺序。
enum class SettingsGroup(val title: String) {
    Device("设备与同步"),
    Conversation("对话"),
    Project("项目与资料"),
}

fun settingsDestinations(
    showUpdateBadge: Boolean = false,
    simpleMode: Boolean = false,
): List<Pair<SettingsGroup, List<SettingsDestination>>> = listOf(
    SettingsGroup.Device to listOf(
        SettingsDestination(
            id = "remote",
            title = "Codex 远程节点",
            description = "扫描 Mac Bridge 配对二维码并管理私有中继。",
        ),
        SettingsDestination(
            id = "updates",
            title = "检查更新",
            description = "查看新版本并安装 APK 更新。",
            showBadge = showUpdateBadge,
        ),
    ),
    SettingsGroup.Conversation to listOf(
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
            id = "config",
            title = "配置包",
            description = "导出模型与语音配置给家人设备，或从配置包导入。",
        ),
    ),
    SettingsGroup.Project to listOf(
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
            id = "agents",
            title = "智能体包",
            description = "安装、更新并查看人物身份与资料覆盖。",
        ),
        SettingsDestination(
            id = "wikis",
            title = "Wiki 知识库",
            description = "导入、管理并浏览离线原文资料。",
        ),
    ),
).map { (group, items) ->
    group to if (simpleMode) items.filter { it.id == "updates" || it.id == "config" } else items
}.filter { (_, items) -> items.isNotEmpty() }
