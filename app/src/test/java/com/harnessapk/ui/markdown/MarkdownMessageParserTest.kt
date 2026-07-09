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
    fun preservesNestedBulletAndOrderedLists() {
        val blocks = parseMarkdownBlocks(
            """
            - 一级 A
              - 二级 A1
              - 二级 A2
                1. 三级有序 A2.1
            - 一级 B
            """.trimIndent(),
        )

        val list = blocks.single { it is MarkdownBlock.BulletList } as MarkdownBlock.BulletList
        assertEquals("一级 A", list.items[0].text.plainText())
        val nestedBullet = list.items[0].children.single { it is MarkdownBlock.BulletList } as MarkdownBlock.BulletList
        assertEquals(listOf("二级 A1", "二级 A2"), nestedBullet.items.map { it.text.plainText() })
        val nestedOrdered = nestedBullet.items[1].children.single { it is MarkdownBlock.OrderedList } as MarkdownBlock.OrderedList
        assertEquals("三级有序 A2.1", nestedOrdered.items.single().text.plainText())
        assertEquals("一级 B", list.items[1].text.plainText())
    }

    @Test
    fun parsesCompactSingleLineFencedCodeFromModelOutput() {
        val blocks = parseMarkdownBlocks("安装后执行：```bashgit --version```然后继续。")

        assertTrue(blocks.debugText(), blocks[0] is MarkdownBlock.Paragraph)
        val code = blocks.single { it is MarkdownBlock.Code } as MarkdownBlock.Code
        assertEquals("bash", code.info)
        assertEquals("git --version", code.literal)
        assertTrue(blocks.debugText(), blocks.last() is MarkdownBlock.Paragraph)
    }

    @Test
    fun markdownTextMetricsLeaveRoomForWrappedHeadingsAndCode() {
        assertTrue(markdownHeadingLineHeightSp(level = 1) > markdownHeadingFontSizeSp(level = 1))
        assertTrue(markdownHeadingLineHeightSp(level = 2) > markdownHeadingFontSizeSp(level = 2))
        assertTrue(markdownCodeLineHeightSp() > markdownCodeFontSizeSp())
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
    fun parsesTaskListItemsWithCheckedState() {
        val blocks = parseMarkdownBlocks(
            """
            - [x] 已完成
            - [ ] 待处理
            """.trimIndent(),
        )

        val list = blocks.single { it is MarkdownBlock.BulletList } as MarkdownBlock.BulletList
        assertEquals(true, list.items[0].taskChecked)
        assertEquals("已完成", list.items[0].text.plainText())
        assertEquals(false, list.items[1].taskChecked)
        assertEquals("待处理", list.items[1].text.plainText())
    }

    @Test
    fun parsesInlineAndBlockMath() {
        val blocks = parseMarkdownBlocks(
            """
            圆面积是 ${'$'}A=\pi r^2${'$'}。

            $$
            E = mc^2
            $$
            """.trimIndent(),
        )

        val paragraph = blocks.first() as MarkdownBlock.Paragraph
        assertTrue(paragraph.text.any { it is MarkdownInline.Math && it.literal == "A=\\pi r^2" })
        val math = blocks.single { it is MarkdownBlock.Math } as MarkdownBlock.Math
        assertEquals("E = mc^2", math.literal)
        assertEquals(true, math.display)
    }

    @Test
    fun parsesMermaidFenceAsMermaidBlock() {
        val blocks = parseMarkdownBlocks(
            """
            ```mermaid
            graph TD
              A --> B
            ```
            """.trimIndent(),
        )

        val mermaid = blocks.single { it is MarkdownBlock.Mermaid } as MarkdownBlock.Mermaid
        assertEquals("graph TD\n  A --> B", mermaid.literal)
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

    @Test
    fun incrementalParserKeepsUnclosedFenceInTailDuringStreaming() {
        val cache = IncrementalMarkdownBlockCache(
            maxStableChunkChars = 24,
            maxTailChars = 12,
            parse = { source -> listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(source)))) },
        )
        val source = markdownResource("streaming_unclosed_fence_steps.txt")

        val chunks = cache.chunksFor(source)

        assertEquals(listOf(true, false), chunks.map { it.stable })
        assertEquals("已经稳定的前言。\n\n", chunks.first().source)
        assertTrue(chunks.last().source.startsWith("```bash"))
        assertEquals(source.removePrefix(chunks.first().source), chunks.last().source)
    }

    @Test
    fun incrementalParserKeepsUnclosedDisplayMathInTailDuringStreaming() {
        val cache = IncrementalMarkdownBlockCache(
            maxStableChunkChars = 24,
            maxTailChars = 12,
            parse = { source -> listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(source)))) },
        )
        val source = """
            已经稳定的前言。

            $$
            E = mc^2
            + a_1
            + a_2
            + a_3
            + a_4
            + a_5
            + a_6
        """.trimIndent()

        val chunks = cache.chunksFor(source)

        assertEquals(listOf(true, false), chunks.map { it.stable })
        assertEquals("已经稳定的前言。\n\n", chunks.first().source)
        assertTrue(chunks.last().source.startsWith("$$"))
        assertEquals(source.removePrefix(chunks.first().source), chunks.last().source)
    }

    @Test
    fun incrementalParserKeepsStreamingTableInTailUntilBlankLineClosesIt() {
        val cache = IncrementalMarkdownBlockCache(
            maxStableChunkChars = 36,
            maxTailChars = 16,
            parse = { source -> listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(source)))) },
        )
        val source = """
            已经稳定的前言。

            | 命令 | 用途 |
            | --- | --- |
            | git status | 查看状态 |
            | git add . | 暂存 |
            | git commit | 提交 |
        """.trimIndent()

        val chunks = cache.chunksFor(source)

        assertEquals(listOf(true, false), chunks.map { it.stable })
        assertEquals("已经稳定的前言。\n\n", chunks.first().source)
        assertTrue(chunks.last().source.startsWith("| 命令 | 用途 |"))
        assertEquals(source.removePrefix(chunks.first().source), chunks.last().source)
    }

    @Test
    fun parsesMarkdownRegressionCorpus() {
        val samples = listOf(
            "nested_lists.md" to MarkdownBlock.BulletList::class,
            "code_fences.md" to MarkdownBlock.Code::class,
            "math_chemistry.md" to MarkdownBlock.Math::class,
            "mermaid_blocks.md" to MarkdownBlock.Mermaid::class,
            "tables_wide.md" to MarkdownBlock.Table::class,
            "long_git_usage.md" to MarkdownBlock.Code::class,
        )

        samples.forEach { (fileName, expectedBlockType) ->
            val blocks = parseMarkdownBlocks(markdownResource(fileName))
            assertTrue("$fileName parsed no blocks", blocks.isNotEmpty())
            assertTrue(
                "$fileName did not contain ${expectedBlockType.simpleName}: ${blocks.debugText()}",
                blocks.any { expectedBlockType.isInstance(it) },
            )
        }
    }

    private fun markdownResource(fileName: String): String =
        requireNotNull(javaClass.getResource("/markdown/$fileName")) { "Missing markdown sample $fileName" }
            .readText()

    private fun List<MarkdownBlock>.debugText(): String =
        joinToString("\n") { block ->
            when (block) {
                is MarkdownBlock.Heading -> "heading(${block.level}): ${block.text.plainText()}"
                is MarkdownBlock.Paragraph -> "paragraph: ${block.text.plainText()}"
                is MarkdownBlock.Table -> "table: ${block.headers.map { it.plainText() }} / ${block.rows.map { row -> row.map { it.plainText() } }}"
                is MarkdownBlock.Math -> "math: ${block.literal}"
                is MarkdownBlock.Mermaid -> "mermaid: ${block.literal}"
                is MarkdownBlock.Divider -> "divider"
                is MarkdownBlock.BulletList -> "bullet: ${block.items.map { "${it.taskChecked}:${it.text.plainText()}" }}"
                is MarkdownBlock.OrderedList -> "ordered: ${block.items.map { it.text.plainText() }}"
                is MarkdownBlock.Code -> "code: ${block.literal}"
                is MarkdownBlock.Quote -> "quote: ${block.blocks.debugText()}"
            }
        }
}
