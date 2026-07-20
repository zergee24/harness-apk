package com.harnessapk.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDestinationsTest {
    @Test
    fun settingsContainsAgentPackagesAsLowFrequencyManagement() {
        val destination = settingsDestinations().single { it.id == "agents" }

        assertEquals("智能体包", destination.title)
        assertEquals("安装、更新并查看人物身份与资料覆盖。", destination.description)
    }

    @Test
    fun settingsPageUsesTopLevelDestinationsInsteadOfOverflowMenuItems() {
        assertEquals(
            listOf("models", "search", "voice", "git", "skills", "agents", "updates"),
            settingsDestinations().map { it.id },
        )
        assertEquals(
            listOf("模型配置", "搜索能力", "语音能力", "Git / Gitee", "技能 / 插件", "智能体包", "检查更新"),
            settingsDestinations().map { it.title },
        )
    }

    @Test
    fun pluginDestinationExplainsFirstVersionScope() {
        val skills = settingsDestinations().first { it.id == "skills" }

        assertEquals("技能和插件默认关闭，可按需启用。", skills.description)
    }

    @Test
    fun searchDestinationExplainsWebSearchScope() {
        val search = settingsDestinations().first { it.id == "search" }

        assertEquals("配置会话可用的联网搜索。", search.description)
    }

    @Test
    fun voiceDestinationExplainsSpeechAndReadAloudScope() {
        val voice = settingsDestinations().first { it.id == "voice" }

        assertEquals("配置语音输入、麦克风权限和回复朗读。", voice.description)
    }

    @Test
    fun gitDestinationExplainsGiteeCredentialScope() {
        val git = settingsDestinations().first { it.id == "git" }

        assertEquals("配置 Gitee 认证和默认提交身份。", git.description)
    }

    @Test
    fun updateDestinationCarriesBadgeWhenUpdateIsAvailable() {
        val updates = settingsDestinations(showUpdateBadge = true).first { it.id == "updates" }

        assertEquals(true, updates.showBadge)
    }
}
