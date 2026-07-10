package com.harnessapk.session

data class WorkspaceProject(
    val id: String,
    val name: String,
)

data class MarkdownDeliverable(
    val id: String,
    val title: String,
    val path: String,
)

data class CreatedDeliverable(
    val id: String,
    val title: String,
    val path: String,
)

enum class MarkdownFileApplyStatus { SUCCEEDED, FAILED }

data class MarkdownFileApplyResult(
    val proposal: MarkdownUpdateProposal,
    val status: MarkdownFileApplyStatus,
    val writtenDeliverable: CreatedDeliverable? = null,
    val errorMessage: String? = null,
)

data class MarkdownBatchApplyResult(
    val results: List<MarkdownFileApplyResult>,
) {
    val succeeded: List<MarkdownFileApplyResult>
        get() = results.filter { it.status == MarkdownFileApplyStatus.SUCCEEDED }
    val failed: List<MarkdownFileApplyResult>
        get() = results.filter { it.status == MarkdownFileApplyStatus.FAILED }
    val isFullyApplied: Boolean
        get() = results.isNotEmpty() && failed.isEmpty()
    val isPartiallyApplied: Boolean
        get() = succeeded.isNotEmpty() && failed.isNotEmpty()
}

data class SessionSummary(
    val conversationId: String,
    val title: String,
    val summary: String,
)

interface ProjectWorkspaceGateway {
    suspend fun listProjects(): List<WorkspaceProject>
    suspend fun listDeliverables(projectId: String): List<MarkdownDeliverable>
    suspend fun readProjectContext(projectId: String): String
    suspend fun readDeliverable(projectId: String, deliverableId: String): String
    suspend fun writeDeliverable(projectId: String, deliverableId: String, markdown: String)
    suspend fun createDeliverable(
        projectId: String,
        templateType: String,
        title: String,
        markdown: String,
    ): CreatedDeliverable
    suspend fun saveSessionSummary(projectId: String, sessionSummary: SessionSummary): CreatedDeliverable
    suspend fun applyMarkdownUpdates(
        projectId: String,
        updates: List<MarkdownUpdateProposal>,
    ): MarkdownBatchApplyResult
}

class EmptyProjectWorkspaceGateway : ProjectWorkspaceGateway {
    override suspend fun listProjects(): List<WorkspaceProject> = emptyList()
    override suspend fun listDeliverables(projectId: String): List<MarkdownDeliverable> = emptyList()
    override suspend fun readProjectContext(projectId: String): String = ""
    override suspend fun readDeliverable(projectId: String, deliverableId: String): String = ""
    override suspend fun writeDeliverable(projectId: String, deliverableId: String, markdown: String) = Unit
    override suspend fun createDeliverable(
        projectId: String,
        templateType: String,
        title: String,
        markdown: String,
    ): CreatedDeliverable = CreatedDeliverable(id = "", title = title, path = "")
    override suspend fun saveSessionSummary(projectId: String, sessionSummary: SessionSummary): CreatedDeliverable =
        CreatedDeliverable(id = "", title = sessionSummary.title, path = "")
    override suspend fun applyMarkdownUpdates(
        projectId: String,
        updates: List<MarkdownUpdateProposal>,
    ): MarkdownBatchApplyResult = MarkdownBatchApplyResult(
        results = updates.map { proposal ->
            MarkdownFileApplyResult(
                proposal = proposal,
                status = MarkdownFileApplyStatus.SUCCEEDED,
                writtenDeliverable = CreatedDeliverable(
                    id = proposal.path,
                    title = proposal.title,
                    path = proposal.path,
                ),
            )
        },
    )
}
