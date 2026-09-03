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
import java.security.MessageDigest

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
    ): MarkdownBatchApplyResult {
        currentCoroutineContext().ensureActive()
        val normalized = updates.map { proposal ->
            proposal to try {
                val validated = proposal.copy(path = repository.validateMarkdownFilePath(projectId, proposal.path))
                Result.success(validated)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }
        val duplicatePaths = normalized.mapNotNull { (_, result) -> result.getOrNull()?.path }
            .groupingBy { it.lowercase() }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        return MarkdownBatchApplyResult(
            results = normalized.map { (originalProposal, validation) ->
                validation.fold(
                    onSuccess = { validatedProposal ->
                        try {
                            currentCoroutineContext().ensureActive()
                            if (validatedProposal.path.lowercase() in duplicatePaths) {
                                throw ProjectWorkspaceException("同一批次包含重复 Markdown 路径：${validatedProposal.path}")
                            }
                            // Keep the revision check adjacent to the write. This also prevents an
                            // earlier proposal in this batch from invalidating a later baseline.
                            validateProposalBaseline(projectId, validatedProposal)
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

    private fun validateProposalBaseline(projectId: String, proposal: MarkdownUpdateProposal) {
        val file = repository.resolveDeliverableFile(projectId, proposal.path)
        if (proposal.expectedAbsent && file.exists()) {
            throw ProjectWorkspaceException("文件已存在，草稿的新建基线已变化：${proposal.path}")
        }
        proposal.baselineSha256?.let { expected ->
            if (!file.isFile) {
                throw ProjectWorkspaceException("文件基线已变化或文件已删除：${proposal.path}")
            }
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
            if (!actual.equals(expected, ignoreCase = true)) {
                throw ProjectWorkspaceException("文件基线已变化，请重新审核：${proposal.path}")
            }
        }
    }

}

private fun deliverableTemplateFromGatewayType(templateType: String): DeliverableTemplate =
    DeliverableTemplate.entries.firstOrNull {
        it.name.equals(templateType, ignoreCase = true) ||
            it.label == templateType ||
            it.directoryName.equals(templateType, ignoreCase = true)
    } ?: DeliverableTemplate.RESEARCH
