package com.harnessapk.project.anchor

import com.harnessapk.storage.CodeAnchorEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeAnchorBridgeTest {

    private val entity = CodeAnchorEntity(
        id = "anchor-1",
        sessionId = "session-1",
        type = "PROJECT_FILE",
        relativePath = "docs/spec.md",
        startLine = null,
        endLine = null,
        contentHash = "a".repeat(64),
        manualLabel = "关键段落",
        createdAt = 1_000L,
    )

    @Test
    fun bridgesEntityToValidAnchor() {
        val anchor = entity.toEvidenceAnchor(projectId = "p1")

        assertEquals("anchor-1", anchor.id)
        assertEquals(AnchorType.PROJECT_FILE, anchor.type)
        assertEquals("p1", anchor.projectId)
        assertEquals("docs/spec.md", anchor.relativePath)
        assertEquals("a".repeat(64), anchor.contentHash)
        assertEquals("关键段落", anchor.excerpt)
        assertEquals(1_000L, anchor.createdAt)
    }

    @Test
    fun bridgesFileRangeWithLines() {
        val anchor = CodeAnchorEntity(
            id = "anchor-2",
            sessionId = "session-1",
            type = "FILE_RANGE",
            relativePath = "src/App.kt",
            startLine = 3,
            endLine = 9,
            contentHash = "b".repeat(64),
            manualLabel = null,
            createdAt = 1_000L,
        ).toEvidenceAnchor(projectId = "p1")

        assertEquals(AnchorType.FILE_RANGE, anchor.type)
        assertEquals(3, anchor.startLine)
        assertEquals(9, anchor.endLine)
    }

    @Test
    fun rejectsAnchorWithUnknownProject() {
        val error = runCatching {
            entity.toEvidenceAnchor(projectId = "")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
