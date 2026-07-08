package com.harnessapk.markdownpdf

import com.harnessapk.ui.markdown.MarkdownBlock
import com.harnessapk.ui.markdown.MarkdownInline
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
    is MarkdownBlock.BulletList -> items.map {
        MarkdownPdfLine("• ${it.plainText()}", MarkdownPdfTextStyle.LIST)
    }
    is MarkdownBlock.OrderedList -> items.mapIndexed { index, item ->
        MarkdownPdfLine("${startNumber + index}. ${item.plainText()}", MarkdownPdfTextStyle.LIST)
    }
    is MarkdownBlock.Quote -> blocks.flatMap { block ->
        block.toPdfLines().map { line ->
            line.copy(text = "│ ${line.text}", style = MarkdownPdfTextStyle.QUOTE)
        }
    }
    is MarkdownBlock.Code -> literal.toTextLines(MarkdownPdfTextStyle.CODE)
    is MarkdownBlock.Table -> buildList {
        if (headers.isNotEmpty()) add(MarkdownPdfLine(headers.toTableLine(), MarkdownPdfTextStyle.TABLE))
        rows.forEach { row -> add(MarkdownPdfLine(row.toTableLine(), MarkdownPdfTextStyle.TABLE)) }
    }
    MarkdownBlock.Divider -> listOf(MarkdownPdfLine("", MarkdownPdfTextStyle.DIVIDER))
}

private fun String.toTextLines(style: MarkdownPdfTextStyle): List<MarkdownPdfLine> =
    lineSequence()
        .map { MarkdownPdfLine(it.ifBlank { " " }, style) }
        .toList()
        .ifEmpty { listOf(MarkdownPdfLine(" ", style)) }

private fun List<List<MarkdownInline>>.toTableLine(): String =
    joinToString(" | ") { it.plainText() }
