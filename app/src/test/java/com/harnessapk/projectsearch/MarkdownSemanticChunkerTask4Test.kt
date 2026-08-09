package com.harnessapk.projectsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSemanticChunkerTask4Test {
    @Test
    fun `combines adjacent semantic blocks toward the 300 to 800 code point target`() {
        val paragraphs = List(6) { index ->
            "段落${index + 1}：" + "项目检索需要保留完整语义。".repeat(10)
        }
        val markdown = "# 检索设计\n\n" + paragraphs.joinToString("\n\n")

        val chunks = MarkdownSemanticChunker().chunk("docs/search.md", markdown)

        assertTrue("expected semantic blocks to be combined", chunks.size < paragraphs.size)
        assertTrue(
            "all non-final chunks should stay in the target band: ${chunks.map { it.codePoints }}",
            chunks.dropLast(1).all { it.codePoints in 300..800 },
        )
        assertTrue(chunks.all { it.codePoints <= 1_200 })
        assertTrue(chunks.all { it.headingPath == "检索设计" })
    }

    @Test
    fun `merges a short block with its adjacent block without mechanical overlap`() {
        val markdown = """
            # 状态

            短说明。

            ${"这是完整状态段落。".repeat(20)}
        """.trimIndent()

        val chunks = MarkdownSemanticChunker().chunk("context.md", markdown)

        assertEquals(1, chunks.size)
        assertEquals(1, chunks.single().text.split("短说明。").size - 1)
        assertTrue("完整状态段落" in chunks.single().text)
    }

    @Test
    fun `oversized fenced code is split by line and every piece repeats the fence`() {
        val codeLines = List(36) { index ->
            "val item$index = \"${"x".repeat(48)}\""
        }
        val markdown = buildString {
            appendLine("# 实现")
            appendLine()
            appendLine("```kotlin")
            codeLines.forEach(::appendLine)
            appendLine("```")
        }

        val chunks = MarkdownSemanticChunker().chunk("src/Index.kt.md", markdown)

        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.text.startsWith("```kotlin\n") })
        assertTrue(chunks.all { it.text.endsWith("\n```") })
        assertTrue(chunks.all { it.codePoints <= 1_200 })
        codeLines.forEach { line ->
            assertEquals("code line must occur exactly once", 1, chunks.sumOf { it.text.split(line).size - 1 })
        }
    }

    @Test
    fun `list table and fenced code remain complete semantic structures`() {
        val markdown = """
            # 项目

            - 第一项
            - 第二项

            | 字段 | 值 |
            | --- | --- |
            | 状态 | 进行中 |

            ```text
            keep this fence
            ```
        """.trimIndent()

        val chunks = MarkdownSemanticChunker().chunk("context.md", markdown)
        val joined = chunks.joinToString("\n") { it.text }

        assertTrue("- 第一项\n- 第二项" in joined)
        assertTrue("| 字段 | 值 |\n| --- | --- |\n| 状态 | 进行中 |" in joined)
        assertTrue("```text\nkeep this fence\n```" in joined)
    }

    private val MarkdownSemanticChunk.codePoints: Int
        get() = text.codePointCount(0, text.length)
}
