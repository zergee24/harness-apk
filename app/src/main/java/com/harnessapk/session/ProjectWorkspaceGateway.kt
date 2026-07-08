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
    ): List<CreatedDeliverable>
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
    ): List<CreatedDeliverable> = updates.map {
        CreatedDeliverable(id = it.path, title = it.title, path = it.path)
    }
}
