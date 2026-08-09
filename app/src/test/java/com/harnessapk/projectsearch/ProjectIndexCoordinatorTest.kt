package com.harnessapk.projectsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectIndexCoordinatorTest {
    @Test
    fun `file and project content budgets fall back to metadata only indexing`() {
        val sink = RecordingSink()
        val dirty = RecordingDirtyStore()
        val coordinator = ProjectIndexCoordinator(
            sink = sink,
            dirtyStore = dirty,
            maxFileBytes = 32,
            maxProjectBodyBytes = 50,
        )
        val sources = listOf(
            source("a", "docs/a.md", "甲".repeat(10)),
            source("b", "docs/b.md", "b".repeat(40)),
            source("c", "docs/c.md", "c".repeat(25)),
        )

        val result = coordinator.indexProject("project-a", sources)

        assertEquals(listOf("a"), result.indexedSourceKeys)
        assertEquals(listOf("b", "c"), result.metadataOnlySourceKeys)
        assertTrue(result.failedSourceKeys.isEmpty())
        assertEquals(30, result.indexedBodyBytes)
        assertTrue(sink.values.getValue("a").bodyIndexed)
        assertFalse(sink.values.getValue("b").bodyIndexed)
        assertEquals("docs/b.md\nB", sink.values.getValue("b").chunks.single().text)
        assertTrue(dirty.current.isEmpty())
    }

    @Test
    fun `failed replacement remains dirty and does not stop other sources`() {
        val sink = RecordingSink(failFor = setOf("broken"))
        val dirty = RecordingDirtyStore()
        val coordinator = ProjectIndexCoordinator(sink = sink, dirtyStore = dirty)

        val result = coordinator.indexProject(
            projectId = "project-a",
            sources = listOf(
                source("broken", "broken.md", "无法写入索引"),
                source("healthy", "healthy.md", "正常写入索引"),
            ),
        )

        assertEquals(listOf("broken"), result.failedSourceKeys)
        assertEquals(setOf("broken"), dirty.current)
        assertTrue("healthy" in sink.values)
        assertFalse("healthy" in dirty.current)
    }

    @Test
    fun `project mismatch rejects the whole batch without touching index state`() {
        val sink = RecordingSink()
        val dirty = RecordingDirtyStore()
        val coordinator = ProjectIndexCoordinator(sink = sink, dirtyStore = dirty)

        val result = coordinator.indexProject(
            projectId = "project-a",
            sources = listOf(source("foreign", "context.md", "不能泄漏", projectId = "project-b")),
        )

        assertTrue(result.rejected)
        assertTrue(sink.values.isEmpty())
        assertTrue(dirty.current.isEmpty())
    }

    @Test
    fun `messages and run evidence are indexed without markdown file budgets`() {
        val sink = RecordingSink()
        val coordinator = ProjectIndexCoordinator(
            sink = sink,
            dirtyStore = RecordingDirtyStore(),
            maxFileBytes = 8,
            maxProjectBodyBytes = 8,
        )

        val result = coordinator.indexProject(
            projectId = "project-a",
            sources = listOf(
                source("message", null, "用户确认使用 Room 23", ProjectSourceType.PROJECT_MESSAGE),
                source("run", null, "migration tests passed", ProjectSourceType.RUN_EVIDENCE),
            ),
        )

        assertEquals(listOf("message", "run"), result.indexedSourceKeys)
        assertTrue(result.metadataOnlySourceKeys.isEmpty())
    }

    private fun source(
        key: String,
        relativePath: String?,
        body: String,
        type: ProjectSourceType = ProjectSourceType.MARKDOWN,
        projectId: String = "project-a",
    ) = ProjectIndexSource(
        projectId = projectId,
        sourceType = type,
        authority = ProjectSourceAuthority.REVIEWED_ARTIFACT,
        sourceKey = key,
        relativePath = relativePath,
        title = key.uppercase(),
        body = body,
        sourceSha256 = key.padEnd(64, '0'),
        sourceUpdatedAt = 1,
    )

    private class RecordingSink(
        private val failFor: Set<String> = emptySet(),
    ) : ProjectIndexSink {
        val values = linkedMapOf<String, ProjectIndexedSource>()

        override fun replace(source: ProjectIndexedSource) {
            if (source.source.sourceKey in failFor) error("simulated index failure")
            values[source.source.sourceKey] = source
        }
    }

    private class RecordingDirtyStore : ProjectIndexDirtyStore {
        val current = linkedSetOf<String>()

        override fun markDirty(projectId: String, sourceKey: String) {
            current += sourceKey
        }

        override fun clearDirty(projectId: String, sourceKey: String) {
            current -= sourceKey
        }
    }
}
