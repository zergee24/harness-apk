package com.harnessapk.search

import com.harnessapk.common.AppDispatchers
import com.harnessapk.project.Project
import com.harnessapk.storage.LocalSearchDao
import com.harnessapk.storage.LocalSearchDocumentEntity
import kotlinx.coroutines.withContext

class LocalSearchRepository(
    private val dao: LocalSearchDao,
    private val dispatchers: AppDispatchers,
) {
    suspend fun rebuildTokens(projects: List<Project> = emptyList()) = withContext(dispatchers.io) {
        dao.listDocuments().forEach { document ->
            val searchText = if (
                document.sourceType in PROJECT_DERIVED_SOURCE_TYPES && document.searchableText.isNotBlank()
            ) {
                document.searchableText
            } else {
                LocalSearchTokenizer.indexedText(document.title, document.body)
            }
            dao.replaceFts(document.id, searchText)
        }
        if (projects.isNotEmpty()) replaceProjects(projects)
    }

    suspend fun replaceProjects(projects: List<Project>) = withContext(dispatchers.io) {
        dao.deleteProjectNameFts()
        dao.deleteProjectDocuments()
        projects.forEach { project ->
            val document = LocalSearchDocumentEntity(
                id = "project:${project.id}",
                type = LocalSearchDocumentType.PROJECT_NAME.name,
                title = project.name,
                body = project.name,
                conversationId = null,
                messageId = null,
                projectId = project.id,
                updatedAt = project.updatedAt,
            )
            dao.replaceDocument(document, LocalSearchTokenizer.indexedText(document.title, document.body))
        }
    }

    suspend fun upsertProject(project: Project) = withContext(dispatchers.io) {
        val document = LocalSearchDocumentEntity(
            id = "project:${project.id}",
            type = LocalSearchDocumentType.PROJECT_NAME.name,
            title = project.name,
            body = project.name,
            conversationId = null,
            messageId = null,
            projectId = project.id,
            updatedAt = project.updatedAt,
        )
        dao.replaceDocument(document, LocalSearchTokenizer.indexedText(document.title, document.body))
    }

    suspend fun deleteProject(projectId: String) = withContext(dispatchers.io) {
        val documentId = "project:$projectId"
        dao.deleteFts(documentId)
        dao.deleteDocument(documentId)
    }

    suspend fun search(query: String, limit: Int = 30): List<LocalSearchResult> = withContext(dispatchers.io) {
        val normalized = query.trim()
        if (normalized.isBlank()) return@withContext emptyList()
        require(limit in 1..100) { "搜索数量必须在 1 到 100 之间" }
        val match = LocalSearchTokenizer.matchExpression(normalized)
        val fts = if (match.isBlank()) emptyList() else dao.searchFts(match, limit)
        val contains = dao.searchContains(normalized, limit)
        (fts + contains)
            .distinctBy(LocalSearchDocumentEntity::id)
            .sortedWith(compareByDescending<LocalSearchDocumentEntity> { it.updatedAt }.thenBy { it.id })
            .take(limit)
            .map { it.toResult(normalized) }
    }

    private companion object {
        val PROJECT_DERIVED_SOURCE_TYPES = setOf("CONTEXT", "MARKDOWN", "RUN_EVIDENCE")
    }
}

private fun LocalSearchDocumentEntity.toResult(query: String): LocalSearchResult = LocalSearchResult(
    id = id,
    type = LocalSearchDocumentType.valueOf(type),
    title = title,
    snippet = searchSnippet(body, query),
    conversationId = conversationId,
    messageId = messageId,
    projectId = projectId,
    updatedAt = updatedAt,
)

internal fun searchSnippet(body: String, query: String, radius: Int = 48): String {
    val compact = body.replace(Regex("\\s+"), " ").trim()
    if (compact.length <= radius * 2) return compact
    val index = compact.indexOf(query, ignoreCase = true).coerceAtLeast(0)
    val start = (index - radius).coerceAtLeast(0)
    val end = (index + query.length + radius).coerceAtMost(compact.length)
    return (if (start > 0) "…" else "") + compact.substring(start, end) + if (end < compact.length) "…" else ""
}
