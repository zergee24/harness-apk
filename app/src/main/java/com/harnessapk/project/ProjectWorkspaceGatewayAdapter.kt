package com.harnessapk.project

import com.harnessapk.common.toUserMessage
import com.harnessapk.session.CreatedDeliverable
import com.harnessapk.session.MarkdownBatchApplyResult
import com.harnessapk.session.MarkdownDeliverable
import com.harnessapk.session.MarkdownFileApplyResult
import com.harnessapk.session.MarkdownFileApplyStatus
import com.harnessapk.session.MarkdownUpdateProposal
import com.harnessapk.session.ProjectWorkspaceGateway
import com.harnessapk.session.SessionSummary
import com.harnessapk.session.WorkspaceProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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

    override suspend fun applyMarkdownUpdatesWithResults(
        projectId: String,
        updates: List<MarkdownUpdateProposal>,
    ): MarkdownBatchApplyResult {
        currentCoroutineContext().ensureActive()
        val validations = updates.map { proposal ->
            proposal to try {
                Result.success(proposal.copy(path = repository.validateMarkdownFilePath(projectId, proposal.path)))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }
        return MarkdownBatchApplyResult(
            results = validations.map { (originalProposal, validation) ->
                validation.fold(
                    onSuccess = { validatedProposal ->
                        try {
                            currentCoroutineContext().ensureActive()
                            val deliverable = repository.writeMarkdownFile(
                                projectId = projectId,
                                relativePath = validatedProposal.path,
                                markdown = validatedProposal.markdown,
                            )
                            MarkdownFileApplyResult(
                                proposal = originalProposal,
                                status = MarkdownFileApplyStatus.SUCCEEDED,
                                writtenDeliverable = CreatedDeliverable(
                                    id = deliverable.id,
                                    title = deliverable.title,
                                    path = deliverable.relativePath,
                                ),
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            MarkdownFileApplyResult(
                                proposal = originalProposal,
                                status = MarkdownFileApplyStatus.FAILED,
                                errorMessage = error.toUserMessage(),
                            )
                        }
                    },
                    onFailure = { error ->
                        MarkdownFileApplyResult(
                            proposal = originalProposal,
                            status = MarkdownFileApplyStatus.FAILED,
                            errorMessage = error.toUserMessage(),
                        )
                    },
                )
            },
        )
    }

    override suspend fun applyMarkdownUpdates(
        projectId: String,
        updates: List<MarkdownUpdateProposal>,
    ): List<CreatedDeliverable> {
        val result = applyMarkdownUpdatesWithResults(projectId, updates)
        check(result.failed.isEmpty()) {
            result.failed.joinToString("；") { failed ->
                "${failed.proposal.path}：${failed.errorMessage.orEmpty().ifBlank { "文件写入失败" }}"
            }
        }
        return result.succeeded.mapNotNull { it.writtenDeliverable }
    }
}

private fun deliverableTemplateFromGatewayType(templateType: String): DeliverableTemplate =
    DeliverableTemplate.entries.firstOrNull {
        it.name.equals(templateType, ignoreCase = true) ||
            it.label == templateType ||
            it.directoryName.equals(templateType, ignoreCase = true)
    } ?: DeliverableTemplate.RESEARCH
