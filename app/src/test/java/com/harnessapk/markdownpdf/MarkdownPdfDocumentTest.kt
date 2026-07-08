package com.harnessapk.markdownpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPdfDocumentTest {
    @Test
    fun buildMarkdownPdfDocumentUsesRenderedMarkdownText() {
        val document = buildMarkdownPdfDocument(
            """
            # 标题
            一段 **粗体** 和 `代码`

            - 第一项
            - 第二项

            | 字段 | 值 |
            | --- | --- |
            | 状态 | 已完成 |
            """.trimIndent(),
        )

        val lines = document.lines.map { it.text }

        assertTrue(lines.contains("标题"))
        assertTrue(lines.contains("一段 粗体 和 代码"))
        assertTrue(lines.contains("• 第一项"))
        assertTrue(lines.contains("字段 | 值"))
        assertTrue(lines.contains("状态 | 已完成"))
        assertFalse(lines.any { it.contains("# 标题") })
        assertFalse(lines.any { it.contains("| --- |") })
        assertEquals(MarkdownPdfTextStyle.HEADING_1, document.lines.first().style)
    }
}
