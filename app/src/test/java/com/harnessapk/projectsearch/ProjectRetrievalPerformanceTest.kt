package com.harnessapk.projectsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRetrievalPerformanceTest {
    @Test
    fun `p95 uses the measured samples instead of a hard coded value`() {
        val samples = (1L..10L).map { it * 10_000_000L }

        val p95Millis = ProjectRetrievalStatistics.p95Millis(samples)

        assertEquals(100.0, p95Millis, 0.0001)
    }

    @Test
    fun `ten thousand chunk retrieval reports real p95 under the JVM gate`() {
        val documents = List(10_000) { index ->
            document(
                key = "perf-$index",
                title = if (index == 9_876) "极光目标文档" else "普通索引分片 $index",
                text = if (index == 9_876) {
                    "极光目标文档记录唯一的检索性能门禁答案。"
                } else {
                    "普通索引分片 $index 保存可重复的项目文本。"
                },
            )
        }
        val repository = ProjectRetrievalRepository(
            ProjectSearchCandidateSource { projectId, _, limit ->
                documents.asSequence().filter { it.projectId == projectId }.take(limit).toList()
            },
        )
        repeat(3) {
            repository.retrieve("performance-project", "极光目标文档")
        }

        var blackhole = 0
        val samples = List(10) {
            val startedAt = System.nanoTime()
            val result = repository.retrieve("performance-project", "极光目标文档")
            val elapsed = System.nanoTime() - startedAt
            assertEquals("perf-9876", result.evidence.first().documentKey)
            blackhole = blackhole xor result.evidence.hashCode()
            elapsed
        }
        val p95Millis = ProjectRetrievalStatistics.p95Millis(samples)
        println(
            "M3_RETRIEVAL_PERF chunks=${documents.size} samples=${samples.size} " +
                "p95Ms=${"%.3f".format(p95Millis)} blackhole=$blackhole",
        )

        assertTrue("10k chunk p95 was ${"%.3f".format(p95Millis)} ms", p95Millis < 250.0)
    }

    private fun document(key: String, title: String, text: String) = ProjectSearchDocument(
        documentKey = key,
        projectId = "performance-project",
        sourceType = ProjectSourceType.MARKDOWN,
        authority = ProjectSourceAuthority.REVIEWED_ARTIFACT,
        sourceKey = key,
        conversationId = null,
        messageId = null,
        relativePath = "docs/$key.md",
        title = title,
        headingPath = title,
        ordinal = 0,
        text = text,
        searchableText = "$title $text",
        sourceSha256 = key.padEnd(64, '0'),
        gitBlobId = null,
        sourceUpdatedAt = 1,
        indexedAt = 1,
    )
}
