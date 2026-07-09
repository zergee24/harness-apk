package com.harnessapk.markdownpdf

import com.harnessapk.ui.markdown.MarkdownBlock
import com.harnessapk.ui.markdown.MarkdownInline
import com.harnessapk.ui.markdown.MarkdownListItem
import com.harnessapk.ui.markdown.parseMarkdownBlocks
import com.harnessapk.ui.markdown.plainText

data class MarkdownPdfDocument(
    val lines: List<MarkdownPdfLine>,
)

data class MarkdownPdfLine(
    val text: String,
    val style: MarkdownPdfTextStyle,
)

enum class MarkdownPdfTextStyle {
    HEADING_1,
    HEADING_2,
    HEADING_3,
    BODY,
    LIST,
    QUOTE,
    CODE,
    TABLE,
    DIVIDER,
}

fun buildMarkdownPdfDocument(markdown: String): MarkdownPdfDocument =
    MarkdownPdfDocument(
        lines = parseMarkdownBlocks(markdown.ifBlank { " " })
            .flatMap { it.toPdfLines() }
            .ifEmpty { listOf(MarkdownPdfLine(" ", MarkdownPdfTextStyle.BODY)) },
    )

private fun MarkdownBlock.toPdfLines(): List<MarkdownPdfLine> = when (this) {
    is MarkdownBlock.Heading -> listOf(
        MarkdownPdfLine(
            text = text.plainText(),
            style = when (level) {
                1 -> MarkdownPdfTextStyle.HEADING_1
                2 -> MarkdownPdfTextStyle.HEADING_2
                else -> MarkdownPdfTextStyle.HEADING_3
            },
        ),
    )
    is MarkdownBlock.Paragraph -> text.plainText().toTextLines(MarkdownPdfTextStyle.BODY)
    is MarkdownBlock.BulletList -> items.flatMap {
        it.toPdfLines(prefix = "•")
    }
    is MarkdownBlock.OrderedList -> items.flatMapIndexed { index, item ->
        item.toPdfLines(prefix = "${startNumber + index}.")
    }
    is MarkdownBlock.Quote -> blocks.flatMap { block ->
        block.toPdfLines().map { line ->
            line.copy(text = "│ ${line.text}", style = MarkdownPdfTextStyle.QUOTE)
        }
    }
    is MarkdownBlock.Code -> literal.toTextLines(MarkdownPdfTextStyle.CODE)
    is MarkdownBlock.Math -> literal.toTextLines(MarkdownPdfTextStyle.CODE)
    is MarkdownBlock.Mermaid -> literal.toTextLines(MarkdownPdfTextStyle.CODE)
    is MarkdownBlock.Table -> buildList {
        if (headers.isNotEmpty()) add(MarkdownPdfLine(headers.toTableLine(), MarkdownPdfTextStyle.TABLE))
        rows.forEach { row -> add(MarkdownPdfLine(row.toTableLine(), MarkdownPdfTextStyle.TABLE)) }
    }
    MarkdownBlock.Divider -> listOf(MarkdownPdfLine("", MarkdownPdfTextStyle.DIVIDER))
}

private fun MarkdownListItem.toPdfLines(prefix: String): List<MarkdownPdfLine> =
    listOf(MarkdownPdfLine("${taskPrefix(prefix)} ${text.plainText()}", MarkdownPdfTextStyle.LIST)) +
        children.flatMap { child ->
            child.toPdfLines().map { line -> line.copy(text = "  ${line.text}") }
        }

private fun MarkdownListItem.taskPrefix(fallback: String): String =
    taskChecked?.let { if (it) "[x]" else "[ ]" } ?: fallback

private fun String.toTextLines(style: MarkdownPdfTextStyle): List<MarkdownPdfLine> =
    lineSequence()
        .map { MarkdownPdfLine(it.ifBlank { " " }, style) }
        .toList()
        .ifEmpty { listOf(MarkdownPdfLine(" ", style)) }

private fun List<List<MarkdownInline>>.toTableLine(): String =
    joinToString(" | ") { it.plainText() }
