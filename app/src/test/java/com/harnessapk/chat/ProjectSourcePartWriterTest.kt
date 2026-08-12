package com.harnessapk.chat

import com.harnessapk.storage.ProjectEvidenceSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSourcePartWriterTest {
    @Test
    fun appendsOneStableProjectSourcesPartWithEvidenceIdentity() {
        val snapshot = StreamingMessageSnapshot(
            status = MessageStatus.SUCCEEDED,
            parts = listOf(UiMessagePartDraft(0, UiMessagePartType.TEXT, "已决定使用 Room ⟦P1⟧", stable = true)),
        )

        val prepared = appendProjectSourcesPart(snapshot, listOf(evidence("evidence-1", "⟦P1⟧")))

        assertEquals(1, prepared.parts.count { it.type == UiMessagePartType.PROJECT_SOURCES })
        val part = prepared.parts.last()
        assertTrue("context.md" in part.content)
        assertTrue("evidence-1" in part.metadata.getValue("evidenceIds"))
        assertTrue(part.stable)
    }

    @Test
    fun terminalSourcesContainOnlyEvidenceActuallyReferencedByTheAnswer() {
        val selected = referencedProjectEvidence(
            evidence = listOf(evidence("evidence-1", "⟦P1⟧"), evidence("evidence-2", "⟦P2⟧")),
            validTokens = setOf("⟦P2⟧"),
        )

        assertEquals(listOf("evidence-2"), selected.map { it.id })
    }

    private fun evidence(id: String, token: String) = ProjectEvidenceSnapshotEntity(
        id = id,
        executionId = "execution-1",
        messageId = "assistant-1",
        token = token,
        projectId = "project-1",
        sourceType = "CONTEXT",
        authority = "REVIEWED_ARTIFACT",
        sourceKey = "context.md",
        title = "context.md",
        locatorLabel = "关键决策",
        relativePath = "context.md",
        sourceMessageId = null,
        sourceSha256 = "a".repeat(64),
        gitBlobId = null,
        excerpt = "使用 Room",
        capturedAt = 1L,
    )
}
