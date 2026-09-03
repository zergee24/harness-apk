package com.harnessapk.chat

import com.harnessapk.storage.ProjectEvidenceSnapshotEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

class ProjectSourcePartWriter(
    private val chatRepository: ChatRepository,
    private val transactionRunner: ProjectTerminalTransactionRunner = ProjectTerminalTransactionRunner { block -> block() },
    private val auditWriter: suspend (executionId: String, status: String, unknownTokens: List<String>) -> Unit =
        { _, _, _ -> },
) {
    suspend fun persist(
        assistantMessageId: String,
        snapshot: StreamingMessageSnapshot,
        evidence: List<ProjectEvidenceSnapshotEntity>,
    ): StreamingMessageSnapshot {
        val prepared = appendProjectSourcesPart(snapshot, evidence)
        chatRepository.replaceMessagePartsFromSnapshot(assistantMessageId, prepared)
        return prepared
    }

    suspend fun persistTerminal(
        assistantMessageId: String,
        executionId: String,
        snapshot: StreamingMessageSnapshot,
        evidence: List<ProjectEvidenceSnapshotEntity>,
        validTokens: Set<String>,
        status: String,
        unknownTokens: List<String>,
    ): StreamingMessageSnapshot {
        val referencedEvidence = referencedProjectEvidence(evidence, validTokens)
        val prepared = appendProjectSourcesPart(snapshot, referencedEvidence)
        transactionRunner.run {
            chatRepository.replaceMessagePartsFromSnapshot(assistantMessageId, prepared)
            auditWriter(executionId, status, unknownTokens)
        }
        return prepared
    }
}

internal fun referencedProjectEvidence(
    evidence: List<ProjectEvidenceSnapshotEntity>,
    validTokens: Set<String>,
): List<ProjectEvidenceSnapshotEntity> = evidence.filter { it.token in validTokens }

fun interface ProjectTerminalTransactionRunner {
    suspend fun run(block: suspend () -> Unit)
}

internal fun appendProjectSourcesPart(
    snapshot: StreamingMessageSnapshot,
    evidence: List<ProjectEvidenceSnapshotEntity>,
): StreamingMessageSnapshot {
    if (evidence.isEmpty() || snapshot.legacyVisibleText().isBlank()) return snapshot
    val ordered = evidence.sortedBy { it.token }
    val content = ordered.joinToString("\n") { item ->
        val locator = listOfNotNull(item.relativePath, item.locatorLabel.takeIf(String::isNotBlank))
            .joinToString(" · ")
            .ifBlank { item.title }
        "依据 ${item.token.removePrefix("⟦P").removeSuffix("⟧")} · $locator"
    }
    val withoutExisting = snapshot.parts.filterNot { it.type == UiMessagePartType.PROJECT_SOURCES }
    return snapshot.copy(
        parts = withoutExisting + UiMessagePartDraft(
            index = withoutExisting.size,
            type = UiMessagePartType.PROJECT_SOURCES,
            content = content,
            metadata = mapOf(
                "evidenceIds" to JsonArray(ordered.map { JsonPrimitive(it.id) }).toString(),
                "tokens" to JsonArray(ordered.map { JsonPrimitive(it.token) }).toString(),
            ),
            stable = true,
        ),
    )
}
