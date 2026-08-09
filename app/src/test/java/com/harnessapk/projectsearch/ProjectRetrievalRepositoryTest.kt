package com.harnessapk.projectsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRetrievalRepositoryTest {
    @Test
    fun `missing project id fails closed without querying the candidate source`() {
        val source = RecordingCandidateSource(emptyList())
        val repository = ProjectRetrievalRepository(source)

        val missing = repository.retrieve(null, "上次决定了什么")
        val blank = repository.retrieve("  ", "当前进度")

        assertEquals(ProjectRetrievalStatus.INVALID_PROJECT, missing.status)
        assertEquals(ProjectRetrievalStatus.INVALID_PROJECT, blank.status)
        assertEquals(0, source.calls)
        assertTrue(missing.evidence.isEmpty())
    }

    @Test
    fun `foreign candidates are discarded before ranking and never leak`() {
        val source = RecordingCandidateSource(
            listOf(
                document("local", "project-a", "context.md", "决定使用 Room 23"),
                document("foreign", "project-b", "context.md", "决定使用 Room 24"),
            ),
        )
        val repository = ProjectRetrievalRepository(source)

        val result = repository.retrieve("project-a", "决定使用哪个 Room 版本")

        assertEquals(ProjectRetrievalStatus.MATCH, result.status)
        assertEquals(listOf("local"), result.evidence.map { it.documentKey })
        assertEquals(listOf("project-a"), source.projectIds)
    }

    @Test
    fun `ranking is deterministic and enforces authority diversity and budgets`() {
        val candidates = buildList {
            add(document("proposal", text = "助手提案决定改成云端搜索", authority = ProjectSourceAuthority.ASSISTANT_PROPOSAL))
            add(document("context-3", path = "context.md", text = "决定三：保留人工推送", score = 1.0))
            add(document("context-1", path = "context.md", text = "决定一：使用 Room 23", score = 2.0))
            add(document("context-2", path = "context.md", text = "决定二：只保留一个 FTS", score = 1.5))
            add(document("run", path = "runs/latest.md", text = "决定相关迁移测试通过", authority = ProjectSourceAuthority.VERIFIED_RUN))
            add(document("notes", path = "notes.md", text = "决定记录已经复核"))
            add(document("history", path = null, text = "用户决定不自动提交", authority = ProjectSourceAuthority.USER_STATED))
            add(document("extra", path = "extra.md", text = "决定之后再做语义向量"))
        }
        val repository = ProjectRetrievalRepository(RecordingCandidateSource(candidates))

        val first = repository.retrieve("project-a", "上次决定了什么")
        val second = repository.retrieve("project-a", "上次决定了什么")

        assertEquals(first.evidence.map { it.documentKey }, second.evidence.map { it.documentKey })
        assertTrue(first.evidence.none { it.documentKey == "proposal" })
        assertTrue(first.evidence.size <= 6)
        assertTrue(first.evidence.groupingBy { it.relativePath ?: it.sourceKey }.eachCount().values.all { it <= 2 })
    }

    @Test
    fun `total injected evidence is capped at 8000 code points`() {
        val candidates = List(6) { index ->
            document(
                key = "large-$index",
                path = "large-$index.md",
                text = "项目预算" + "😀".repeat(1_995),
            )
        }
        val result = ProjectRetrievalRepository(RecordingCandidateSource(candidates))
            .retrieve("project-a", "项目预算")

        assertEquals(ProjectRetrievalStatus.MATCH, result.status)
        assertTrue(result.totalCodePoints <= 8_000)
        assertTrue(result.evidence.size < candidates.size)
    }

    @Test
    fun `irrelevant candidates produce no match and inject nothing`() {
        val result = ProjectRetrievalRepository(
            RecordingCandidateSource(listOf(document("unrelated", text = "Android 打包配置"))),
        ).retrieve("project-a", "婚礼预算审批")

        assertEquals(ProjectRetrievalStatus.NO_MATCH, result.status)
        assertTrue(result.evidence.isEmpty())
    }

    private class RecordingCandidateSource(
        private val documents: List<ProjectSearchDocument>,
    ) : ProjectSearchCandidateSource {
        var calls: Int = 0
        val projectIds = mutableListOf<String>()

        override fun searchProject(projectId: String, query: String, limit: Int): List<ProjectSearchDocument> {
            calls++
            projectIds += projectId
            return documents.take(limit)
        }
    }

    private fun document(
        key: String,
        projectId: String = "project-a",
        path: String? = "$key.md",
        text: String,
        authority: ProjectSourceAuthority = ProjectSourceAuthority.REVIEWED_ARTIFACT,
        score: Double = 0.0,
    ) = ProjectSearchDocument(
        documentKey = key,
        projectId = projectId,
        sourceType = if (path == null) ProjectSourceType.PROJECT_MESSAGE else ProjectSourceType.MARKDOWN,
        authority = authority,
        sourceKey = key,
        conversationId = null,
        messageId = null,
        relativePath = path,
        title = path ?: key,
        headingPath = "",
        ordinal = 0,
        text = text,
        searchableText = "$path $text",
        sourceSha256 = key.padEnd(64, '0'),
        gitBlobId = null,
        sourceUpdatedAt = 1,
        indexedAt = 1,
        score = score,
    )
}
