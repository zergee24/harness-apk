package com.harnessapk.ui.conversation

import com.harnessapk.chat.Conversation
import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentStatus
import com.harnessapk.session.WorkspaceProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversationListUiStateTest {
    @Test
    fun agentConversationUsesUnifiedSimulationDisclosure() {
        val conversation = conversation("agent", "人物会话", 1L, null).copy(agentId = "a1")
        val label = conversationIdentityLabel(conversation, mapOf("a1" to agent("a1", "李德胜")))

        assertEquals("李德胜 · 基于资料模拟", label)
    }

    @Test
    fun missingAgentNameUsesInstalledPersonFallbackAndAssistantHasNoDisclosure() {
        assertEquals(
            "已安装人物 · 基于资料模拟",
            conversationIdentityLabel(conversation("agent", "人物会话", 1L, null).copy(agentId = "missing"), emptyMap()),
        )
        assertEquals(null, conversationIdentityLabel(conversation("assistant", "普通会话", 1L, null), emptyMap()))
    }
    @Test
    fun conversationTabsDoNotDuplicateTitleOrUseDisabledSelectedState() {
        val source = File("src/main/java/com/harnessapk/ui/conversation/ConversationListScreen.kt").readText()

        assertFalse(source.contains("text = if (groupedByProject) \"按项目分组\" else \"最近会话\""))
        assertFalse(source.contains("enabled = groupedByProject"))
        assertFalse(source.contains("enabled = !groupedByProject"))
        assertTrue(source.contains("selected = !groupedByProject"))
        assertTrue(source.contains("selected = groupedByProject"))
    }

    @Test
    fun groupedStateSupportsCollapsingAllProjectSessions() {
        val state = buildProjectGroupedConversationState(
            conversations = listOf(
                conversation("plain", "普通对话", 30L, null),
                conversation("p1-new", "新方案", 50L, "project-1"),
                conversation("p1-old", "旧方案", 20L, "project-1"),
                conversation("p2", "验收", 40L, "project-2"),
            ),
            projects = listOf(project("project-1", "移动端 Harness"), project("project-2", "CRM")),
            allProjectSessionsCollapsed = true,
            collapsedProjectIds = emptySet(),
        )

        assertEquals(listOf("plain"), state.regularConversations.map { it.id })
        assertTrue(state.allProjectSessionsCollapsed)
        assertEquals(emptyList<String>(), state.visibleProjectGroups.map { it.projectId })
    }

    @Test
    fun groupedModeIsAvailableOnlyWhenProjectsExist() {
        val withoutProjects = buildProjectGroupedConversationState(
            conversations = listOf(conversation("plain", "普通对话", 30L, null)),
            projects = emptyList(),
            allProjectSessionsCollapsed = false,
            collapsedProjectIds = emptySet(),
        )
        val withProjects = buildProjectGroupedConversationState(
            conversations = listOf(conversation("plain", "普通对话", 30L, null)),
            projects = listOf(project("project-1", "移动端 Harness")),
            allProjectSessionsCollapsed = false,
            collapsedProjectIds = emptySet(),
        )

        assertFalse(withoutProjects.canGroupByProject)
        assertTrue(withProjects.canGroupByProject)
    }

    @Test
    fun groupedStateSupportsCollapsingSingleProjectSessions() {
        val state = buildProjectGroupedConversationState(
            conversations = listOf(
                conversation("p1-new", "新方案", 50L, "project-1"),
                conversation("p1-old", "旧方案", 20L, "project-1"),
                conversation("p2", "验收", 40L, "project-2"),
            ),
            projects = listOf(project("project-1", "移动端 Harness"), project("project-2", "CRM")),
            allProjectSessionsCollapsed = false,
            collapsedProjectIds = setOf("project-1"),
        )

        assertEquals("移动端 Harness", state.projectGroups.first().projectName)
        assertTrue(state.projectGroups.first().isCollapsed)
        assertEquals(emptyList<String>(), state.projectGroups.first().visibleConversations.map { it.id })
        assertEquals(listOf("p2"), state.projectGroups.last().visibleConversations.map { it.id })
    }

    @Test
    fun groupedDisplaySectionsPutProjectSessionsBeforeRegularSessions() {
        val state = buildProjectGroupedConversationState(
            conversations = listOf(
                conversation("plain-new", "普通新对话", 100L, null),
                conversation("p1", "项目方案", 50L, "project-1"),
                conversation("plain-old", "普通旧对话", 10L, null),
            ),
            projects = listOf(project("project-1", "移动端 Harness")),
            allProjectSessionsCollapsed = false,
            collapsedProjectIds = emptySet(),
        )

        val sections = groupedConversationDisplaySections(state)

        assertEquals(listOf("toggle:false", "project:project-1", "regular"), sections.map(::sectionKey))
        assertEquals(listOf("plain-new", "plain-old"), (sections.last() as ConversationGroupedDisplaySection.Regular).conversations.map { it.id })
    }

    @Test
    fun groupedDisplaySectionsKeepCollapseAllControlInListWhenCollapsed() {
        val state = buildProjectGroupedConversationState(
            conversations = listOf(
                conversation("plain", "普通对话", 100L, null),
                conversation("p1", "项目方案", 50L, "project-1"),
            ),
            projects = listOf(project("project-1", "移动端 Harness")),
            allProjectSessionsCollapsed = true,
            collapsedProjectIds = emptySet(),
        )

        assertEquals(listOf("toggle:true", "regular"), groupedConversationDisplaySections(state).map(::sectionKey))
    }

    private fun sectionKey(section: ConversationGroupedDisplaySection): String = when (section) {
        is ConversationGroupedDisplaySection.ProjectGroup -> "project:${section.group.projectId}"
        is ConversationGroupedDisplaySection.ProjectGroupsToggle -> "toggle:${section.collapsed}"
        is ConversationGroupedDisplaySection.Regular -> "regular"
    }

    private fun conversation(
        id: String,
        title: String,
        updatedAt: Long,
        projectId: String?,
    ): Conversation = Conversation(
        id = id,
        title = title,
        updatedAt = updatedAt,
        projectId = projectId,
        promptOriginal = "",
        promptOptimized = "",
        promptFinal = "",
    )

    private fun project(id: String, name: String): WorkspaceProject = WorkspaceProject(
        id = id,
        name = name,
    )

    private fun agent(id: String, name: String): Agent = Agent(
        id = id,
        name = name,
        summary = "",
        activeVersion = 1,
        publisherFingerprint = "fingerprint",
        status = AgentStatus.READY,
        requiredCorpusCount = 0,
        installedCorpusCount = 0,
    )
}
