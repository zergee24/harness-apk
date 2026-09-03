package com.harnessapk.projectsearch

import com.harnessapk.search.LocalSearchTokenizer
import com.harnessapk.storage.LocalSearchDocumentEntity
import com.harnessapk.storage.LocalSearchDao
import com.harnessapk.storage.MessageDao
import com.harnessapk.storage.ProjectSearchDao

class RoomProjectRetrievalGateway(
    private val dao: ProjectSearchDao,
    private val localSearchDao: LocalSearchDao,
    private val messageDao: MessageDao,
    private val runEvidenceIndexer: RoomProjectRunEvidenceIndexer? = null,
    private val candidateLimit: Int = 200,
) {
    suspend fun retrieve(projectId: String?, query: String): ProjectRetrievalResult {
        val scopedProjectId = projectId?.trim().orEmpty()
        if (scopedProjectId.isEmpty()) {
            return ProjectRetrievalResult(ProjectRetrievalStatus.INVALID_PROJECT, emptyList())
        }
        val match = LocalSearchTokenizer.matchExpression(query)
        if (match.isBlank()) return ProjectRetrievalResult(ProjectRetrievalStatus.NO_MATCH, emptyList())
        runEvidenceIndexer?.refreshProject(scopedProjectId)
        refreshProjectMessages(scopedProjectId)
        val documents = dao.searchProjectFts(scopedProjectId, match, candidateLimit).map { row ->
            row.toProjectDocument(messageDao.findById(row.messageId.orEmpty())?.role)
        }
        return ProjectRetrievalRepository(
            candidateSource = ProjectSearchCandidateSource { requestedProjectId, _, _ ->
                if (requestedProjectId == scopedProjectId) documents else emptyList()
            },
            maxCandidates = candidateLimit,
        ).retrieve(scopedProjectId, query)
    }

    private suspend fun refreshProjectMessages(projectId: String) {
        localSearchDao.projectMessageDocumentsNeedingIndex(projectId).forEach { document ->
            val role = messageDao.findById(document.messageId.orEmpty())?.role
            val enriched = document.copy(
                sourceType = ProjectSourceType.PROJECT_MESSAGE.name,
                authority = if (role == "USER") {
                    ProjectSourceAuthority.USER_STATED.name
                } else {
                    ProjectSourceAuthority.ASSISTANT_PROPOSAL.name
                },
                sourceKey = document.sourceKey.ifBlank { "message:${document.messageId ?: document.id}" },
                searchableText = LocalSearchTokenizer.indexedText(document.title, document.body),
                sourceSha256 = document.body.sha256(),
                sourceUpdatedAt = document.updatedAt,
                indexedAt = System.currentTimeMillis(),
                dirty = false,
            )
            localSearchDao.replaceDocument(enriched, enriched.searchableText)
        }
    }
}

internal fun LocalSearchDocumentEntity.toProjectDocument(messageRole: String?): ProjectSearchDocument {
    val resolvedSourceType = runCatching { ProjectSourceType.valueOf(sourceType) }.getOrNull()
        ?: when (type) {
            "MESSAGE" -> ProjectSourceType.PROJECT_MESSAGE
            "RUN_EVIDENCE" -> ProjectSourceType.RUN_EVIDENCE
            "CONTEXT" -> ProjectSourceType.CONTEXT
            else -> ProjectSourceType.MARKDOWN
        }
    val resolvedAuthority = runCatching { ProjectSourceAuthority.valueOf(authority) }.getOrNull()
        ?: when {
            resolvedSourceType == ProjectSourceType.PROJECT_MESSAGE && messageRole == "USER" ->
                ProjectSourceAuthority.USER_STATED
            resolvedSourceType == ProjectSourceType.PROJECT_MESSAGE -> ProjectSourceAuthority.ASSISTANT_PROPOSAL
            resolvedSourceType == ProjectSourceType.RUN_EVIDENCE -> ProjectSourceAuthority.VERIFIED_RUN
            else -> ProjectSourceAuthority.REVIEWED_ARTIFACT
        }
    return ProjectSearchDocument(
        documentKey = id,
        projectId = requireNotNull(projectId),
        sourceType = resolvedSourceType,
        authority = resolvedAuthority,
        sourceKey = sourceKey.ifBlank { id },
        conversationId = conversationId,
        messageId = messageId,
        relativePath = relativePath,
        title = title,
        headingPath = headingPath,
        ordinal = ordinal,
        text = body,
        searchableText = searchableText.ifBlank { body },
        sourceSha256 = sourceSha256.ifBlank { body.sha256() },
        gitBlobId = gitBlobId,
        sourceUpdatedAt = sourceUpdatedAt.takeIf { it > 0 } ?: updatedAt,
        indexedAt = indexedAt.takeIf { it > 0 } ?: updatedAt,
        score = 0.0,
    )
}

private fun String.sha256(): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
