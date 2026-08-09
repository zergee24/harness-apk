package com.harnessapk.projectsearch

import com.harnessapk.storage.ProjectEvidenceSnapshotEntity
import com.harnessapk.storage.ProjectRetrievalRunEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

data class ProjectRuntimeContext(
    val retrievalRunId: String,
    val evidence: List<ProjectEvidenceSnapshotEntity>,
    val systemContext: String,
)

data class ProjectEvidenceCapture(
    val run: ProjectRetrievalRunEntity,
    val evidence: List<ProjectEvidenceSnapshotEntity>,
)

fun interface ProjectEvidenceStore {
    suspend fun save(capture: ProjectEvidenceCapture)
}

fun interface ProjectEvidenceLiveVerifier {
    suspend fun isCurrent(projectId: String, document: ProjectSearchDocument): Boolean
}

class ProjectEvidenceSnapshotRepository(
    private val store: ProjectEvidenceStore,
    private val timeProvider: () -> Long,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val liveVerifier: ProjectEvidenceLiveVerifier = ProjectEvidenceLiveVerifier { _, _ -> true },
) {
    suspend fun capture(
        executionId: String,
        assistantMessageId: String,
        projectId: String,
        query: String,
        result: ProjectRetrievalResult,
    ): ProjectRuntimeContext? {
        val runId = idFactory()
        val now = timeProvider()
        val currentDocuments = result.evidence.filter { document ->
            liveVerifier.isCurrent(projectId, document)
        }
        val evidence = currentDocuments.mapIndexed { index, document ->
            ProjectEvidenceSnapshotEntity(
                id = idFactory(),
                executionId = executionId,
                messageId = assistantMessageId,
                token = "⟦P${index + 1}⟧",
                projectId = projectId,
                sourceType = document.sourceType.name,
                authority = document.authority.name,
                sourceKey = document.sourceKey,
                title = document.title,
                locatorLabel = document.headingPath.ifBlank {
                    document.relativePath ?: document.messageId ?: document.sourceKey
                },
                relativePath = document.relativePath,
                sourceMessageId = document.messageId,
                sourceSha256 = document.sourceSha256,
                gitBlobId = document.gitBlobId,
                excerpt = document.text.takeCodePoints(1_200),
                capturedAt = now,
            )
        }
        val run = ProjectRetrievalRunEntity(
            id = runId,
            executionId = executionId,
            projectId = projectId,
            query = query,
            selectedEvidenceIdsJson = JsonArray(evidence.map { JsonPrimitive(it.id) }).toString(),
            status = when {
                result.status == ProjectRetrievalStatus.MATCH && evidence.isEmpty() -> ProjectRetrievalStatus.NO_MATCH.name
                else -> result.status.name
            },
            createdAt = now,
        )
        store.save(ProjectEvidenceCapture(run, evidence))
        if (evidence.isEmpty()) return null
        return ProjectRuntimeContext(
            retrievalRunId = runId,
            evidence = evidence,
            systemContext = ProjectContextAssembler.assemble(evidence),
        )
    }
}

object ProjectContextAssembler {
    fun assemble(evidence: List<ProjectEvidenceSnapshotEntity>): String = buildString {
        appendLine("以下是当前项目中本轮冻结的可审计证据。只可按原文支持的范围使用；关系记忆不是项目事实。")
        evidence.sortedBy(ProjectEvidenceSnapshotEntity::token).forEach { item ->
            appendLine()
            appendLine("${item.token} [${item.authority}] ${item.title} · ${item.locatorLabel}")
            appendLine(item.excerpt)
        }
        appendLine()
        append("使用项目事实时保留对应 ⟦P#⟧ 标记；不得编造未列出的标记。")
    }.trim()
}

private fun String.takeCodePoints(limit: Int): String {
    if (codePointCount(0, length) <= limit) return this
    return substring(0, offsetByCodePoints(0, limit))
}
