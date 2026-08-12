package com.harnessapk.projectsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSearchContractTest {
    @Test
    fun `markdown chunker preserves semantic blocks and heading paths`() {
        val markdown = """
            # CRM

            ## 关键决策

            - 使用 Room 23
            - 不自动提交

            | 项目 | 状态 |
            | --- | --- |
            | M3 | 进行中 |

            ```kotlin
            val schema = 23
            println(schema)
            ```
        """.trimIndent()

        val chunks = MarkdownSemanticChunker().chunk("context.md", markdown)

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.any { it.headingPath == "CRM / 关键决策" && "不自动提交" in it.text })
        assertTrue(chunks.any { "| M3 | 进行中 |" in it.text })
        assertTrue(chunks.any { "```kotlin" in it.text && "println(schema)" in it.text })
        assertTrue(chunks.all { it.text.codePointCount(0, it.text.length) <= 1_200 })
    }

    @Test
    fun `selector enforces authority file diversity and entropy budget`() {
        val candidates = listOf(
            document("a1", "context.md", ProjectSourceAuthority.ASSISTANT_PROPOSAL, "曾建议改用向量库"),
            document("a2", "context.md", ProjectSourceAuthority.REVIEWED_ARTIFACT, "决定继续使用本地 FTS"),
            document("a3", "context.md", ProjectSourceAuthority.USER_STATED, "用户确认不自动提交"),
            document("a4", "notes.md", ProjectSourceAuthority.VERIFIED_RUN, "Room 迁移测试通过"),
            document("a5", "notes.md", ProjectSourceAuthority.VERIFIED_RUN, "Go 测试通过"),
            document("a6", "notes.md", ProjectSourceAuthority.VERIFIED_RUN, "构建通过"),
            document("a7", "more.md", ProjectSourceAuthority.REVIEWED_ARTIFACT, "后续人工 Push"),
        )

        val selected = ProjectEvidenceSelector().select(
            query = "上次决定了什么",
            candidates = candidates,
        )

        assertFalse(selected.any { it.sourceKey == "a1" })
        assertTrue(selected.size <= 6)
        assertTrue(selected.groupingBy { it.relativePath }.eachCount().values.all { it <= 2 })
        assertTrue(selected.sumOf(ProjectSearchDocument::injectionCodePoints) <= 8_000)
    }

    @Test
    fun `citation verifier removes unknown project tokens`() {
        val result = ProjectCitationVerifier.verify(
            text = "已决定使用 FTS ⟦P1⟧，另有未知结论 ⟦P9⟧。",
            allowedTokens = setOf("⟦P1⟧"),
        )

        assertEquals(listOf("⟦P1⟧"), result.validTokens)
        assertEquals(listOf("⟦P9⟧"), result.unknownTokens)
        assertTrue("⟦P1⟧" in result.text)
        assertFalse("⟦P9⟧" in result.text)
    }

    private fun document(
        key: String,
        path: String,
        authority: ProjectSourceAuthority,
        text: String,
    ) = ProjectSearchDocument(
        documentKey = key,
        projectId = "project-a",
        sourceType = ProjectSourceType.MARKDOWN,
        authority = authority,
        sourceKey = key,
        conversationId = null,
        messageId = null,
        relativePath = path,
        title = path,
        headingPath = "",
        ordinal = 0,
        text = text,
        searchableText = text,
        sourceSha256 = key.padEnd(64, '0'),
        gitBlobId = null,
        sourceUpdatedAt = 1,
        indexedAt = 1,
        score = 1.0,
    )
}
