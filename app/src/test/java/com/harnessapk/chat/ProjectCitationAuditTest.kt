package com.harnessapk.chat

import com.harnessapk.projectsearch.ProjectRuntimeContext
import com.harnessapk.storage.ProjectEvidenceSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectCitationAuditTest {
    @Test
    fun unknownTokensAreRemovedAndReturnedForPersistentAudit() {
        val snapshot = StreamingMessageSnapshot(
            status = MessageStatus.SUCCEEDED,
            parts = listOf(
                UiMessagePartDraft(0, UiMessagePartType.TEXT, "已确认 ⟦P1⟧，伪造 ⟦P9⟧。", stable = true),
            ),
        )
        val verification = verifyProjectSnapshot(
            snapshot,
            ProjectRuntimeContext("run-1", listOf(evidence("⟦P1⟧")), "context"),
        )

        val text = verification.snapshot.parts.single().content
        assertTrue("⟦P1⟧" in text)
        assertFalse("⟦P9⟧" in text)
        assertEquals(listOf("⟦P1⟧"), verification.validTokens)
        assertEquals(listOf("⟦P9⟧"), verification.unknownTokens)
    }

    private fun evidence(token: String) = ProjectEvidenceSnapshotEntity(
        id = "evidence-1",
        executionId = "execution-1",
        messageId = "assistant-1",
        token = token,
        projectId = "project-1",
        sourceType = "MARKDOWN",
        authority = "REVIEWED_ARTIFACT",
        sourceKey = "file:context.md",
        title = "context.md",
        locatorLabel = "关键决策",
        relativePath = "context.md",
        sourceMessageId = null,
        sourceSha256 = "a".repeat(64),
        gitBlobId = null,
        excerpt = "决定使用 Room",
        capturedAt = 1L,
    )
}
