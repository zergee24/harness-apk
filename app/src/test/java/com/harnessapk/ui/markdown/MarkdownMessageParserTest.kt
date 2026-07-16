package com.harnessapk.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

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
    fun normalizesCommonModelListMarkersWithoutSpaces() {
        val blocks = parseMarkdownBlocks(
            """
            -第一项
              •子项
            -[x]已完成

            1、先做
            2、再做
            """.trimIndent(),
        )

        val bullet = blocks.filterIsInstance<MarkdownBlock.BulletList>().single()
        assertEquals("第一项", bullet.items[0].text.plainText())
        val nested = bullet.items[0].children.single() as MarkdownBlock.BulletList
        assertEquals("子项", nested.items.single().text.plainText())
        assertEquals(true, bullet.items[1].taskChecked)
        assertEquals("已完成", bullet.items[1].text.plainText())

        val ordered = blocks.filterIsInstance<MarkdownBlock.OrderedList>().single()
        assertEquals(listOf("先做", "再做"), ordered.items.map { it.text.plainText() })
    }

    @Test
    fun separatesNonOneOrderedListFromPrecedingModelParagraph() {
        val blocks = parseMarkdownBlocks(
            """
            下面继续说明：
            2、第二步
            3、第三步
            """.trimIndent(),
        )

        assertEquals("下面继续说明：", (blocks.first() as MarkdownBlock.Paragraph).text.plainText())
        val ordered = blocks.last() as MarkdownBlock.OrderedList
        assertEquals(2, ordered.startNumber)
        assertEquals(listOf("第二步", "第三步"), ordered.items.map { it.text.plainText() })
    }

    @Test
    fun preservesInlineEmphasisThatStartsAtTheBeginningOfLine() {
        val blocks = parseMarkdownBlocks("*强调*、**粗体** 与 `代码` 也应保留。")

        val paragraph = blocks.single() as MarkdownBlock.Paragraph
        assertTrue(paragraph.text.first() is MarkdownInline.Emphasis)
        assertTrue(paragraph.text.any { it is MarkdownInline.Strong })
        assertTrue(paragraph.text.any { it is MarkdownInline.Code })
    }

    @Test
    fun normalizesSupportedCompactListMarkersWithoutTouchingCodeBlocks() {
        val bulletSamples = listOf(
            Triple("-项目", "项目", null),
            Triple("•项目", "项目", null),
            Triple("-[ ]待办", "待办", false),
        )
        bulletSamples.forEach { (source, expectedText, checked) ->
            val list = parseMarkdownBlocks(source).single() as MarkdownBlock.BulletList
            assertEquals(expectedText, list.items.single().text.plainText())
            assertEquals(checked, list.items.single().taskChecked)
        }

        listOf("1.第一步", "1)第一步", "1、第一步", "1）第一步").forEach { source ->
            val list = parseMarkdownBlocks(source).single() as MarkdownBlock.OrderedList
            assertEquals("第一步", list.items.single().text.plainText())
        }

        val code = parseMarkdownBlocks(
            """
            ```text
            -第一项
            1、第二项
            ```
            """.trimIndent(),
        ).single() as MarkdownBlock.Code
        assertEquals("-第一项\n1、第二项", code.literal)
    }

    @Test
    fun incrementalParserKeepsNestedListTogetherWhileStreaming() {
        val cache = IncrementalMarkdownBlockCache(
            maxStableChunkChars = 64,
            maxTailChars = 16,
        )
        val source = """
            - ${"父项".repeat(24)}
              - 子项一
              - 子项二
            - 末项
        """.trimIndent()

        val chunks = cache.chunksFor(source)

        assertEquals(1, chunks.size)
        assertEquals(false, chunks.single().stable)
        val list = chunks.single().blocks.single() as MarkdownBlock.BulletList
        val nested = list.items[0].children.single() as MarkdownBlock.BulletList
        assertEquals(listOf("子项一", "子项二"), nested.items.map { it.text.plainText() })
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
    fun recoversTrailingAndUnclosedTextFencesFromModelOutput() {
        val blocks = parseMarkdownBlocks(
            """
            ```text左侧16:18:
            上：代码下：Codex / Terminal```

            如果 Codex 需要长时间跑任务，也可以临时变成竖屏。

            ```text左侧16:18:
            上半：Codex 下半：Terminal / 测试 / 日志
            然后代码临时放右下 24 寸。

            ---

            ## 人体工学上要注意最重要的一点：

            > **你身体应该正对主屏**
            """.trimIndent(),
        )

        assertTrue(blocks.none { it is MarkdownBlock.Code })
        assertTrue(blocks.any { it is MarkdownBlock.Divider })
        assertTrue(blocks.debugText(), "左侧16:18" in blocks.debugText())
        assertTrue(blocks.debugText(), "Codex / Terminal" in blocks.debugText())
        assertEquals(
            "人体工学上要注意最重要的一点：",
            blocks.filterIsInstance<MarkdownBlock.Heading>().single().text.plainText(),
        )
        val quote = blocks.filterIsInstance<MarkdownBlock.Quote>().single()
        val quoteParagraph = quote.blocks.single() as MarkdownBlock.Paragraph
        assertTrue(quoteParagraph.text.any { it is MarkdownInline.Strong })
        assertTrue(
            blocks.filterIsInstance<MarkdownBlock.Code>().none {
                "##" in it.literal || "> **" in it.literal
            },
        )
    }

    @Test
    fun doesNotRecoverMarkdownMarkersInsideRealCodeFence() {
        val source = """
            ```kotlin
            val divider = "---"
            # this is intentionally code-like text
            | column | value |

            fun keepGoing() = divider
            ```
        """.trimIndent()

        val code = parseMarkdownBlocks(source).single() as MarkdownBlock.Code

        assertTrue("---" in code.literal)
        assertTrue("# this is intentionally code-like text" in code.literal)
        assertTrue("| column | value |" in code.literal)
    }

    @Test
    fun unwrapsGluedChineseTextFenceAsMarkdown() {
        val blocks = parseMarkdownBlocks(
            """
            ```text一级标题
            这里是普通正文，包含 **重点**。
            ```

            后续正文
            """.trimIndent(),
        )

        assertTrue(blocks.none { it is MarkdownBlock.Code })
        assertTrue(blocks.debugText(), "一级标题" in blocks.debugText())
        assertTrue(blocks.debugText(), "这里是普通正文" in blocks.debugText())
        assertTrue(blocks.debugText(), "后续正文" in blocks.debugText())
    }

    @Test
    fun unwrapsSingleLineGluedChineseTextFence() {
        val blocks = parseMarkdownBlocks("```text左侧布局：主屏 + 日志屏```")

        assertTrue(blocks.none { it is MarkdownBlock.Code })
        assertEquals(
            "左侧布局：主屏 + 日志屏",
            (blocks.single() as MarkdownBlock.Paragraph).text.plainText(),
        )
    }

    @Test
    fun unwrapsInlineGluedChineseTextFenceInsideParagraph() {
        val blocks = parseMarkdownBlocks("前文 ```text一级内容``` 后文")

        assertTrue(blocks.none { it is MarkdownBlock.Code })
        assertEquals(
            "前文 一级内容 后文",
            (blocks.single() as MarkdownBlock.Paragraph).text.plainText(),
        )
    }

    @Test
    fun preservesValidTextAndTextLikeLanguageFences() {
        val samples = listOf("text", "plaintext", "text/plain", "kotlin")

        samples.forEach { info ->
            val code = parseMarkdownBlocks("```$info\n正文日志\n```").single()
            assertTrue("$info should remain code", code is MarkdownBlock.Code)
        }
    }

    @Test
    fun recoversTrailingTildeFence() {
        val blocks = parseMarkdownBlocks("~~~text\n日志内容~~~\n\n后续正文")

        assertEquals("日志内容", (blocks.first() as MarkdownBlock.Code).literal)
        assertEquals("后续正文", (blocks.last() as MarkdownBlock.Paragraph).text.plainText())
    }

    @Test
    fun openingFenceWithInfoInsideCodeIsNotAClosingLine() {
        val blocks = parseMarkdownBlocks("```text\n第一段\n```text第二段")

        val code = blocks.single() as MarkdownBlock.Code
        assertTrue("```text第二段" in code.literal)
    }

    @Test
    fun shorterTrailingFenceDoesNotCloseLongerOpeningFence() {
        val blocks = parseMarkdownBlocks("````text\n第一段```\n第二段\n````")

        val code = blocks.single() as MarkdownBlock.Code
        assertTrue("第一段```" in code.literal)
        assertTrue("第二段" in code.literal)
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
    fun recoversHeadingAfterUnclosedDisplayMathFromModelOutput() {
        val blocks = parseMarkdownBlocks(
            """
            \[
            28 \times \frac{16}{\sqrt{16^2+18^2}}
            ###43寸16:9 横屏宽约:
            约 **95.2 cm**
            """.trimIndent(),
        )

        val math = blocks.first() as MarkdownBlock.Math
        assertEquals("28 \\times \\frac{16}{\\sqrt{16^2+18^2}}", math.literal)
        val heading = blocks.filterIsInstance<MarkdownBlock.Heading>().single()
        assertEquals("43寸16:9 横屏宽约:", heading.text.plainText())
        val paragraph = blocks.last() as MarkdownBlock.Paragraph
        assertEquals("约 95.2 cm", paragraph.text.plainText())
    }

    @Test
    fun recoversListAfterUnclosedDollarDisplayMathFromModelOutput() {
        val blocks = parseMarkdownBlocks(
            """
            $$
            47.3 + 95.2 = 142.5
            1. 并排总宽度约 143 cm
            """.trimIndent(),
        )

        assertEquals(
            "47.3 + 95.2 = 142.5",
            (blocks.first() as MarkdownBlock.Math).literal,
        )
        val list = blocks.filterIsInstance<MarkdownBlock.OrderedList>().single()
        assertEquals("并排总宽度约 143 cm", list.items.single().text.plainText())
    }

    @Test
    fun mathFallbackTextMakesCommonLatexReadable() {
        assertEquals(
            "28 × (16)/(√(16²+18²))",
            mathFallbackText("28 \\times \\frac{16}{\\sqrt{16^2+18^2}}"),
        )
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
    fun incrementalParserKeepsFenceWithInvalidInfoCloserInTail() {
        val cache = IncrementalMarkdownBlockCache(
            maxStableChunkChars = 64,
            maxTailChars = 16,
        )
        val source = buildString {
            appendLine("```text")
            appendLine("短日志".repeat(3))
            appendLine("```text这不是合法关闭行")
            appendLine()
            appendLine("仍然属于未闭合代码块".repeat(24))
        }.trimEnd()

        val chunks = cache.chunksFor(source)

        assertEquals(1, chunks.size)
        assertEquals(false, chunks.single().stable)
        assertEquals(source, chunks.single().source)
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
            "chinese_headings_spacing.md" to MarkdownBlock.Heading::class,
            "malformed_fences_long.md" to MarkdownBlock.Code::class,
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

    @Test
    fun parsesLongMalformedFenceCorpusWithoutLosingStructures() {
        val source = markdownResource("malformed_fences_long.md")

        val blocks = parseMarkdownBlocks(source)

        assertTrue(source.length >= 4_000)
        val codes = blocks.filterIsInstance<MarkdownBlock.Code>()
        assertEquals(1, codes.size)
        assertEquals("标准日志代码块 TOKEN_REAL_CODE", codes.single().literal)
        assertTrue(blocks.any { it is MarkdownBlock.Divider })
        assertTrue(blocks.any { it is MarkdownBlock.Heading })
        assertTrue(blocks.any { it is MarkdownBlock.Quote })
        assertTrue(blocks.any { it is MarkdownBlock.BulletList })
        assertTrue(blocks.any { it is MarkdownBlock.Table })
        assertTrue(blocks.any { it is MarkdownBlock.Math })
        val rendered = blocks.debugText()
        assertTrue("TOKEN_TRANSITION" in rendered)
        assertTrue("TOKEN_NESTED" in rendered)
        repeat(80) { index -> assertTrue("Missing TOKEN_$index", "TOKEN_$index" in rendered) }
        assertTrue(codes.none { "TOKEN_79" in it.literal || "左侧16:18" in it.literal })
    }

    @Test
    fun fixedSeedMarkdownCombinationsKeepVisibleSentinels() {
        val random = Random(20260711)
        val structures = listOf(
            "##紧凑标题###子标题",
            "> **引用强调** 与 `inlineCode()`",
            "- [x] 已完成\n- [ ] 待处理\n  - 嵌套项",
            "| 列 A | 列 B |\n| --- | --- |\n| 中文 | English 🧪 |",
            "```kotlin\nval divider = \"---\"\nprintln(divider)\n```",
            "```text\n布局日志```",
            "```text\n未闭合文本块\n\n---",
            "$$\nE = mc^2\n$$",
            "普通长串 ${"X".repeat(512)}",
        )

        repeat(40) { index ->
            val sentinel = "TOKEN_GENERATED_$index"
            val chosen = structures.shuffled(random).take(3)
            val source = (chosen + "$sentinel 中文 English emoji ✅").joinToString("\n\n")

            val blocks = parseMarkdownBlocks(source)

            assertTrue("Combination $index parsed no blocks", blocks.isNotEmpty())
            assertTrue(
                "Combination $index lost $sentinel: ${blocks.debugText()}",
                sentinel in blocks.debugText(),
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
                is MarkdownBlock.BulletList -> "bullet: ${block.items.map { it.debugText() }}"
                is MarkdownBlock.OrderedList -> "ordered: ${block.items.map { it.debugText() }}"
                is MarkdownBlock.Code -> "code: ${block.literal}"
                is MarkdownBlock.Quote -> "quote: ${block.blocks.debugText()}"
            }
        }

    private fun MarkdownListItem.debugText(): String =
        listOfNotNull(
            "${taskChecked}:${text.plainText()}",
            children.debugText().takeIf { it.isNotBlank() },
        ).joinToString(" ")
}
