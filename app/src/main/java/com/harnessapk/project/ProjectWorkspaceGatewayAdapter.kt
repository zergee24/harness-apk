package com.harnessapk.project

import com.harnessapk.session.CreatedDeliverable
import com.harnessapk.session.MarkdownDeliverable
import com.harnessapk.session.MarkdownUpdateProposal
import com.harnessapk.session.ProjectWorkspaceGateway
import com.harnessapk.session.SessionSummary
import com.harnessapk.session.WorkspaceProject

class ProjectWorkspaceGatewayAdapter(
    private val repository: FileProjectRepository,
) : ProjectWorkspaceGateway {
    override suspend fun listProjects(): List<WorkspaceProject> =
        repository.listProjects().map { WorkspaceProject(id = it.id, name = it.name) }

    override suspend fun listDeliverables(projectId: String): List<MarkdownDeliverable> =
        repository.listDeliverables(projectId)
            .filter { it.artifactType.rendersAsMarkdown }
            .map {
                MarkdownDeliverable(id = it.id, title = it.title, path = it.relativePath)
            }

    override suspend fun readProjectContext(projectId: String): String =
        repository.readProjectContext(projectId)

    override suspend fun readDeliverable(projectId: String, deliverableId: String): String =
        repository.readDeliverable(projectId, deliverableId)

    override suspend fun writeDeliverable(projectId: String, deliverableId: String, markdown: String) =
        repository.writeDeliverable(projectId, deliverableId, markdown)

    override suspend fun createDeliverable(
        projectId: String,
        templateType: String,
        title: String,
        markdown: String,
    ): CreatedDeliverable {
        val deliverable = repository.createDeliverable(
            projectId = projectId,
            template = deliverableTemplateFromGatewayType(templateType),
            title = title,
            markdown = markdown,
        )
        return CreatedDeliverable(
            id = deliverable.id,
            title = deliverable.title,
            path = deliverable.relativePath,
        )
    }

    override suspend fun saveSessionSummary(projectId: String, sessionSummary: SessionSummary): CreatedDeliverable {
        val deliverable = repository.saveSessionSummary(
            projectId = projectId,
            summary = ProjectSessionSummary(
                id = sessionSummary.conversationId,
                title = sessionSummary.title,
                markdown = sessionSummary.summary,
            ),
        )
        return CreatedDeliverable(
            id = deliverable.id,
            title = deliverable.title,
            path = deliverable.relativePath,
        )
    }

    override suspend fun applyMarkdownUpdates(
        projectId: String,
        updates: List<MarkdownUpdateProposal>,
    ): List<CreatedDeliverable> =
        updates.map { update ->
            val deliverable = repository.writeMarkdownFile(
                projectId = projectId,
                relativePath = update.path,
                markdown = update.markdown,
            )
            CreatedDeliverable(
                id = deliverable.id,
                title = deliverable.title,
                path = deliverable.relativePath,
            )
        }
}

private fun deliverableTemplateFromGatewayType(templateType: String): DeliverableTemplate =
    DeliverableTemplate.entries.firstOrNull {
        it.name.equals(templateType, ignoreCase = true) ||
            it.label == templateType ||
            it.directoryName.equals(templateType, ignoreCase = true)
    } ?: DeliverableTemplate.RESEARCH
