package com.harnessapk.project.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectEvidenceAnchorTest {

    private val hash = "a".repeat(64)

    @Test
    fun acceptsMinimalAnchorsForEachType() {
        val store = InMemoryProjectEvidenceAnchorStore()

        store.create(
            type = AnchorType.PROJECT_FILE,
            projectId = "p1",
            contentHash = hash,
            relativePath = "docs/spec.md",
        )
        store.create(
            type = AnchorType.FILE_RANGE,
            projectId = "p1",
            contentHash = hash,
            relativePath = "docs/spec.md",
            startLine = 3,
            endLine = 9,
        )
        store.create(
            type = AnchorType.COMMIT,
            projectId = "p1",
            contentHash = hash,
            relativePath = "docs/spec.md",
            gitRef = "4c49cb5",
        )
        store.create(
            type = AnchorType.RUN_COMPLETION,
            projectId = "p1",
            contentHash = hash,
            runSnapshotId = "run-1",
        )

        assertEquals(4, store.listByProject("p1").size)
    }

    @Test
    fun rejectsMalformedContentHash() {
        val store = InMemoryProjectEvidenceAnchorStore()
        val error = runCatching {
            store.create(
                type = AnchorType.PROJECT_FILE,
                projectId = "p1",
                contentHash = "short-hash",
                relativePath = "docs/spec.md",
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("SHA-256"))
    }

    @Test
    fun rejectsCredentialUrlsAndMacUserDirectoryPrefixes() {
        val store = InMemoryProjectEvidenceAnchorStore()

        listOf(
            "https://user:token@gitee.com/repo.git",
            "http://example.com/file",
            "/Users/tony/work/spec.md",
        ).forEach { path ->
            val error = runCatching {
                store.create(
                    type = AnchorType.PROJECT_FILE,
                    projectId = "p1",
                    contentHash = hash,
                    relativePath = path,
                )
            }.exceptionOrNull()
            assertTrue("应拒绝：$path", error is IllegalArgumentException)
            assertTrue(error!!.message!!.contains("禁止保存"))
        }
    }

    @Test
    fun rejectsOversizedExcerpt() {
        val store = InMemoryProjectEvidenceAnchorStore()
        val error = runCatching {
            store.create(
                type = AnchorType.PROJECT_FILE,
                projectId = "p1",
                contentHash = hash,
                relativePath = "docs/spec.md",
                excerpt = "长".repeat(ProjectEvidenceAnchorValidator.EXCERPT_MAX_LENGTH + 1),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("excerpt"))
    }

    @Test
    fun enforcesTypeRequiredFieldsAndLineRangeScope() {
        val store = InMemoryProjectEvidenceAnchorStore()

        assertTrue(
            runCatching {
                store.create(type = AnchorType.DIFF, projectId = "p1", contentHash = hash)
            }.exceptionOrNull()!!.message!!.contains("项目相对路径"),
        )
        assertTrue(
            runCatching {
                store.create(
                    type = AnchorType.DIFF,
                    projectId = "p1",
                    contentHash = hash,
                    relativePath = "docs/spec.md",
                )
            }.exceptionOrNull()!!.message!!.contains("gitRef"),
        )
        assertTrue(
            runCatching {
                store.create(
                    type = AnchorType.RUN_EVENT,
                    projectId = "p1",
                    contentHash = hash,
                )
            }.exceptionOrNull()!!.message!!.contains("runSnapshotId"),
        )
        assertTrue(
            runCatching {
                store.create(
                    type = AnchorType.FILE_RANGE,
                    projectId = "p1",
                    contentHash = hash,
                    relativePath = "docs/spec.md",
                    startLine = 9,
                    endLine = 3,
                )
            }.exceptionOrNull()!!.message!!.contains("startLine"),
        )
        assertTrue(
            runCatching {
                store.create(
                    type = AnchorType.PROJECT_FILE,
                    projectId = "p1",
                    contentHash = hash,
                    relativePath = "docs/spec.md",
                    startLine = 1,
                    endLine = 2,
                )
            }.exceptionOrNull()!!.message!!.contains("仅 FILE_RANGE"),
        )
    }

    @Test
    fun storeKeepsAnchorsImmutableAndListsByProjectNewestFirst() {
        val store = InMemoryProjectEvidenceAnchorStore()
        val first = store.create(
            type = AnchorType.PROJECT_FILE,
            projectId = "p1",
            contentHash = hash,
            relativePath = "docs/a.md",
        )
        val second = store.create(
            type = AnchorType.COMMIT,
            projectId = "p1",
            contentHash = hash,
            relativePath = "docs/a.md",
            gitRef = "abc1234",
        )
        store.create(
            type = AnchorType.PROJECT_FILE,
            projectId = "p2",
            contentHash = hash,
            relativePath = "docs/b.md",
        )

        assertEquals(2, store.listByProject("p1").size)
        assertEquals(listOf(second.id, first.id), store.listByProject("p1").map { it.id })
        assertEquals(first, store.get(first.id))

        val fetched = store.get(first.id)!!
        assertEquals("docs/a.md", fetched.relativePath)
        assertEquals(AnchorType.PROJECT_FILE, fetched.type)
    }
}
