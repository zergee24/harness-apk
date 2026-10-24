package com.harnessapk.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDestinationsTest {
    @Test
    fun simpleModeKeepsOnlyUpdateAndConfigWithoutEmptyGroups() {
        val groups = settingsDestinations(simpleMode = true)
        assertEquals(listOf(SettingsGroup.Device, SettingsGroup.Conversation), groups.map { it.first })
        assertEquals(listOf("updates", "config"), groups.flatMap { it.second }.map { it.id })
    }

    @Test
    fun settingsKeepAllDestinationsInThreeGroups() {
        val groups = settingsDestinations()
        assertEquals(listOf(SettingsGroup.Device, SettingsGroup.Conversation, SettingsGroup.Project), groups.map { it.first })
        val ids = groups.flatMap { it.second }.map { it.id }
        assertEquals(10, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun settingsContainsAgentPackagesAsLowFrequencyManagement() {
        val destination = settingsDestinations().flatMap { it.second }.single { it.id == "agents" }

        assertEquals("智能体包", destination.title)
        assertEquals("安装、更新并查看人物身份与资料覆盖。", destination.description)
    }

    @Test
    fun settingsContainsWikiLibraryAsLowFrequencyManagement() {
        val destination = settingsDestinations().flatMap { it.second }.single { it.id == "wikis" }

        assertEquals("Wiki 知识库", destination.title)
        assertEquals("导入、管理并浏览离线原文资料。", destination.description)
    }

    @Test
    fun settingsPageUsesTopLevelDestinationsInsteadOfOverflowMenuItems() {
        assertEquals(
            listOf("remote", "updates", "models", "search", "voice", "config", "git", "skills", "agents", "wikis"),
            settingsDestinations().flatMap { it.second }.map { it.id },
        )
        assertEquals(
            listOf("Codex 远程节点", "检查更新", "模型配置", "搜索能力", "语音能力", "配置包", "Git / Gitee", "技能 / 插件", "智能体包", "Wiki 知识库"),
            settingsDestinations().flatMap { it.second }.map { it.title },
        )
    }

    @Test
    fun pluginDestinationExplainsFirstVersionScope() {
        val skills = settingsDestinations().flatMap { it.second }.first { it.id == "skills" }

        assertEquals("技能和插件默认关闭，可按需启用。", skills.description)
    }

    @Test
    fun searchDestinationExplainsWebSearchScope() {
        val search = settingsDestinations().flatMap { it.second }.first { it.id == "search" }

        assertEquals("配置会话可用的联网搜索。", search.description)
    }

    @Test
    fun voiceDestinationExplainsSpeechAndReadAloudScope() {
        val voice = settingsDestinations().flatMap { it.second }.first { it.id == "voice" }

        assertEquals("配置语音输入、麦克风权限和回复朗读。", voice.description)
    }

    @Test
    fun gitDestinationExplainsGiteeCredentialScope() {
        val git = settingsDestinations().flatMap { it.second }.first { it.id == "git" }

        assertEquals("配置 Gitee 认证和默认提交身份。", git.description)
    }

    @Test
    fun updateDestinationCarriesBadgeWhenUpdateIsAvailable() {
        val updates = settingsDestinations(showUpdateBadge = true).flatMap { it.second }.first { it.id == "updates" }

        assertEquals(true, updates.showBadge)
    }
}
