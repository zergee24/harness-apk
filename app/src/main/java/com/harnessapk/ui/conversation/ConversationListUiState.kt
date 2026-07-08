package com.harnessapk.ui.conversation

import com.harnessapk.chat.Conversation
import com.harnessapk.session.WorkspaceProject

data class ConversationProjectGroupedState(
    val regularConversations: List<Conversation>,
    val projectGroups: List<ConversationProjectGroup>,
    val allProjectSessionsCollapsed: Boolean,
    val canGroupByProject: Boolean,
) {
    val visibleProjectGroups: List<ConversationProjectGroup>
        get() = if (allProjectSessionsCollapsed) emptyList() else projectGroups
}

data class ConversationProjectGroup(
    val projectId: String,
    val projectName: String,
    val conversations: List<Conversation>,
    val isCollapsed: Boolean,
) {
    val visibleConversations: List<Conversation>
        get() = if (isCollapsed) emptyList() else conversations
}

sealed interface ConversationGroupedDisplaySection {
    data class ProjectGroupsToggle(val collapsed: Boolean, val count: Int) : ConversationGroupedDisplaySection
    data class ProjectGroup(val group: ConversationProjectGroup) : ConversationGroupedDisplaySection
    data class Regular(val conversations: List<Conversation>) : ConversationGroupedDisplaySection
}

fun buildProjectGroupedConversationState(
    conversations: List<Conversation>,
    projects: List<WorkspaceProject>,
    allProjectSessionsCollapsed: Boolean,
    collapsedProjectIds: Set<String>,
): ConversationProjectGroupedState {
    val projectsById = projects.associateBy { it.id }
    val sortedConversations = conversations.sortedByDescending { it.updatedAt }
    val regularConversations = sortedConversations.filter { it.projectId.isNullOrBlank() }
    val projectGroups = sortedConversations
        .filter { !it.projectId.isNullOrBlank() }
        .groupBy { it.projectId.orEmpty() }
        .map { (projectId, groupConversations) ->
            ConversationProjectGroup(
                projectId = projectId,
                projectName = projectsById[projectId]?.name ?: "未知项目",
                conversations = groupConversations.sortedByDescending { it.updatedAt },
                isCollapsed = projectId in collapsedProjectIds,
            )
        }
        .sortedByDescending { group -> group.conversations.maxOf { it.updatedAt } }

    return ConversationProjectGroupedState(
        regularConversations = regularConversations,
        projectGroups = projectGroups,
        allProjectSessionsCollapsed = allProjectSessionsCollapsed,
        canGroupByProject = projects.isNotEmpty(),
    )
}

fun groupedConversationDisplaySections(
    state: ConversationProjectGroupedState,
): List<ConversationGroupedDisplaySection> = buildList {
    if (state.projectGroups.isNotEmpty()) {
        add(
            ConversationGroupedDisplaySection.ProjectGroupsToggle(
                collapsed = state.allProjectSessionsCollapsed,
                count = state.projectGroups.sumOf { it.conversations.size },
            ),
        )
    }
    if (!state.allProjectSessionsCollapsed) {
        state.visibleProjectGroups.forEach { group ->
            add(ConversationGroupedDisplaySection.ProjectGroup(group))
        }
    }
    if (state.regularConversations.isNotEmpty()) {
        add(ConversationGroupedDisplaySection.Regular(state.regularConversations))
    }
}
