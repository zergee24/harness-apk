package com.harnessapk.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMessageParserTest {
    @Test
    fun parsesCommonMarkdownBlocks() {
        val blocks = parseMarkdownBlocks(
            """
            ## 标题
            一段 **粗体** 和 `代码`

            - 第一项
            - 第二项

            ```kotlin
            val x = 1
            ```
            """.trimIndent(),
        )

        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertEquals("标题", (blocks[0] as MarkdownBlock.Heading).text.plainText())
        assertTrue(blocks.any { it is MarkdownBlock.BulletList })
        assertTrue(blocks.any { it is MarkdownBlock.Code })
    }

    @Test
    fun parsesLinksAndQuotes() {
        val blocks = parseMarkdownBlocks(
            """
            > 引用内容

            查看 [链接](https://example.com)
            """.trimIndent(),
        )

        assertTrue(blocks[0] is MarkdownBlock.Quote)
        val paragraph = blocks.last() as MarkdownBlock.Paragraph
        assertTrue(paragraph.text.any { it is MarkdownInline.Link && it.destination == "https://example.com" })
    }

    @Test
    fun parsesHeadingMarkersWithoutSpacesFromModelOutput() {
        val blocks = parseMarkdownBlocks("##一、主流天赋加点###1. Q技能（共生体/帽子） 主输出流")

        assertTrue(blocks.debugText(), blocks[0] is MarkdownBlock.Heading)
        assertEquals(2, (blocks[0] as MarkdownBlock.Heading).level)
        assertEquals("一、主流天赋加点", (blocks[0] as MarkdownBlock.Heading).text.plainText())
        assertTrue(blocks.debugText(), blocks[1] is MarkdownBlock.Heading)
        assertEquals(3, (blocks[1] as MarkdownBlock.Heading).level)
        assertEquals("1. Q技能（共生体/帽子） 主输出流", (blocks[1] as MarkdownBlock.Heading).text.plainText())
    }

    @Test
    fun parsesGfmTablesWithInlineFormatting() {
        val blocks = parseMarkdownBlocks(
            """
            | 等级 | 天赋 | 说明 |
            | --- | --- | --- |
            | 1 | **Pressurized Glands（压力腺体）** | Q任务：用尖刺击中英雄叠层 |
            | 4 | `Adrenal Overload` | 队友攻速提升 |
            """.trimIndent(),
        )

        val table = blocks.single { it is MarkdownBlock.Table } as MarkdownBlock.Table
        assertEquals(listOf("等级", "天赋", "说明"), table.headers.map { it.plainText() })
        assertEquals(2, table.rows.size)
        assertTrue(table.rows[0][1].any { it is MarkdownInline.Strong })
        assertTrue(table.rows[1][1].any { it is MarkdownInline.Code })
    }

    @Test
    fun parsesComplexKimiMarkdownWithoutLeakingRawMarkers() {
        val blocks = parseMarkdownBlocks(
            """
            前通用环境为准。
            ---
            ##一、主流天赋加点###1. Q技能（共生体/帽子） 主输出流
            |等级|天赋|说明|
            |---|---|---|
            |1|Pressurized Glands（压力腺体）|Q任务：用尖刺击中英雄叠层|

            打法：全程附体队友打输出。
            """.trimIndent(),
        )

        assertTrue(blocks.any { it is MarkdownBlock.Divider })
        assertTrue(blocks.debugText(), blocks.any { it is MarkdownBlock.Table })
        val renderedText = blocks.joinToString("\n") { block ->
            when (block) {
                is MarkdownBlock.Heading -> block.text.plainText()
                is MarkdownBlock.Paragraph -> block.text.plainText()
                is MarkdownBlock.Table -> (block.headers + block.rows.flatten()).joinToString(" ") { it.plainText() }
                else -> ""
            }
        }
        assertTrue(!renderedText.contains("##"))
        assertTrue(!renderedText.contains("|---|"))
    }

    @Test
    fun incrementalParserReusesStableChunksWhenMarkdownAppends() {
        val parsedSources = mutableListOf<String>()
        val cache = IncrementalMarkdownBlockCache(
            maxStableChunkChars = 32,
            maxTailChars = 8,
            parse = { source ->
                parsedSources += source
                listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(source))))
            },
        )
        val stablePrefix = "第一段内容，已经完整。\n\n"

        cache.chunksFor(stablePrefix + "第二段")
        cache.chunksFor(stablePrefix + "第二段继续追加")

        assertEquals(1, parsedSources.count { it == stablePrefix })
        assertEquals(3, parsedSources.size)
    }

    @Test
    fun incrementalParserHardSplitsVeryLargeUnbrokenTail() {
        val cache = IncrementalMarkdownBlockCache(
            maxStableChunkChars = 10,
            maxTailChars = 4,
            parse = { source -> listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(source)))) },
        )

        val chunks = cache.chunksFor("a".repeat(25))

        assertEquals(listOf(true, true, false), chunks.map { it.stable })
        assertEquals(listOf(10, 10, 5), chunks.map { it.source.length })
    }

    private fun List<MarkdownBlock>.debugText(): String =
        joinToString("\n") { block ->
            when (block) {
                is MarkdownBlock.Heading -> "heading(${block.level}): ${block.text.plainText()}"
                is MarkdownBlock.Paragraph -> "paragraph: ${block.text.plainText()}"
                is MarkdownBlock.Table -> "table: ${block.headers.map { it.plainText() }} / ${block.rows.map { row -> row.map { it.plainText() } }}"
                is MarkdownBlock.Divider -> "divider"
                is MarkdownBlock.BulletList -> "bullet: ${block.items.map { it.plainText() }}"
                is MarkdownBlock.OrderedList -> "ordered: ${block.items.map { it.plainText() }}"
                is MarkdownBlock.Code -> "code: ${block.literal}"
                is MarkdownBlock.Quote -> "quote: ${block.blocks.debugText()}"
            }
        }
}
