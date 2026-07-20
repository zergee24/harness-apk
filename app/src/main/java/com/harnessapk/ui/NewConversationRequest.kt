package com.harnessapk.ui

import com.harnessapk.agent.Agent
import com.harnessapk.agent.InitialConversationIdentity
import com.harnessapk.chat.NewConversationUseCase
import com.harnessapk.ui.project.projectSessionTitle

internal data class NewConversationRequest(
    val title: String = "新会话",
    val projectId: String? = null,
    val identity: InitialConversationIdentity = InitialConversationIdentity.Suggested,
)

internal fun homeConversationRequest(): NewConversationRequest = NewConversationRequest()

internal fun projectConversationRequest(
    projectId: String,
    projectName: String,
): NewConversationRequest = NewConversationRequest(
    title = projectSessionTitle(projectName, null),
    projectId = projectId,
)

internal fun installedAgentConversationRequest(
    agent: Agent,
    sourceProjectId: String?,
): NewConversationRequest = NewConversationRequest(
    title = agent.name,
    projectId = sourceProjectId,
    identity = InitialConversationIdentity.Agent(agent.id),
)

internal suspend fun NewConversationUseCase.create(request: NewConversationRequest): String = create(
    title = request.title,
    projectId = request.projectId,
    identity = request.identity,
)
