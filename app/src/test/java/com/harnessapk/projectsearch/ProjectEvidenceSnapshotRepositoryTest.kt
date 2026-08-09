package com.harnessapk.projectsearch

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectEvidenceSnapshotRepositoryTest {
    @Test
    fun captureFreezesStableTokensAndBuildsSeparatedProjectContext() = runTest {
        var saved: ProjectEvidenceCapture? = null
        val ids = listOf("run-1", "evidence-1", "evidence-2").iterator()
        val repository = ProjectEvidenceSnapshotRepository(
            store = ProjectEvidenceStore { saved = it },
            timeProvider = { 10L },
            idFactory = ids::next,
        )

        val runtime = repository.capture(
            executionId = "execution-1",
            assistantMessageId = "assistant-1",
            projectId = "project-1",
            query = "上次决定了什么",
            result = ProjectRetrievalResult(
                ProjectRetrievalStatus.MATCH,
                listOf(document("a", "context.md"), document("b", "reports/m3.md")),
            ),
        )

        assertEquals(listOf("⟦P1⟧", "⟦P2⟧"), runtime!!.evidence.map { it.token })
        assertEquals("assistant-1", runtime.evidence.first().messageId)
        assertTrue("关系记忆不是项目事实" in runtime.systemContext)
        assertEquals("MATCH", saved!!.run.status)
    }

    @Test
    fun noMatchPersistsRunButInjectsNothing() = runTest {
        var saved: ProjectEvidenceCapture? = null
        val runtime = ProjectEvidenceSnapshotRepository(
            store = ProjectEvidenceStore { saved = it },
            timeProvider = { 10L },
            idFactory = { "run-no-match" },
        ).capture(
            "execution-1",
            "assistant-1",
            "project-1",
            "unrelated",
            ProjectRetrievalResult(ProjectRetrievalStatus.NO_MATCH, emptyList()),
        )

        assertNull(runtime)
        assertEquals("NO_MATCH", saved!!.run.status)
        assertTrue(saved!!.evidence.isEmpty())
    }

    @Test
    fun changedOrDeletedFilesAreDroppedAtSnapshotBoundaryAndTokensAreCompacted() = runTest {
        var saved: ProjectEvidenceCapture? = null
        val ids = listOf("run-1", "evidence-1").iterator()
        val repository = ProjectEvidenceSnapshotRepository(
            store = ProjectEvidenceStore { saved = it },
            timeProvider = { 10L },
            idFactory = ids::next,
            liveVerifier = ProjectEvidenceLiveVerifier { _, document -> document.documentKey == "current" },
        )

        val runtime = repository.capture(
            executionId = "execution-1",
            assistantMessageId = "assistant-1",
            projectId = "project-1",
            query = "当前决定",
            result = ProjectRetrievalResult(
                ProjectRetrievalStatus.MATCH,
                listOf(document("changed", "changed.md"), document("current", "current.md")),
            ),
        )

        assertEquals(listOf("current"), runtime!!.evidence.map { it.sourceKey })
        assertEquals(listOf("⟦P1⟧"), runtime.evidence.map { it.token })
        assertEquals("MATCH", saved!!.run.status)
    }

    @Test
    fun allStaleFilesPersistNoMatchAndInjectNothing() = runTest {
        var saved: ProjectEvidenceCapture? = null
        val repository = ProjectEvidenceSnapshotRepository(
            store = ProjectEvidenceStore { saved = it },
            timeProvider = { 10L },
            idFactory = { "id" },
            liveVerifier = ProjectEvidenceLiveVerifier { _, _ -> false },
        )

        val runtime = repository.capture(
            "execution-1",
            "assistant-1",
            "project-1",
            "当前决定",
            ProjectRetrievalResult(ProjectRetrievalStatus.MATCH, listOf(document("deleted", "gone.md"))),
        )

        assertNull(runtime)
        assertEquals("NO_MATCH", saved!!.run.status)
        assertTrue(saved!!.evidence.isEmpty())
    }

    private fun document(key: String, path: String) = ProjectSearchDocument(
        documentKey = key,
        projectId = "project-1",
        sourceType = ProjectSourceType.MARKDOWN,
        authority = ProjectSourceAuthority.REVIEWED_ARTIFACT,
        sourceKey = key,
        conversationId = null,
        messageId = null,
        relativePath = path,
        title = path,
        headingPath = "关键决策",
        ordinal = 0,
        text = "决定使用 Room",
        searchableText = "决定使用 Room",
        sourceSha256 = "a".repeat(64),
        gitBlobId = null,
        sourceUpdatedAt = 1L,
        indexedAt = 1L,
    )
}
