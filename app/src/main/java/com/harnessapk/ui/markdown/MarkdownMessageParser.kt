package com.harnessapk.ui.markdown

import androidx.compose.runtime.Immutable
import org.commonmark.Extension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

@Immutable
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: List<MarkdownInline>) : MarkdownBlock
    data class Paragraph(val text: List<MarkdownInline>) : MarkdownBlock
    data class BulletList(val items: List<List<MarkdownInline>>) : MarkdownBlock
    data class OrderedList(val startNumber: Int, val items: List<List<MarkdownInline>>) : MarkdownBlock
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class Code(val literal: String, val info: String?) : MarkdownBlock
    data class Table(val headers: List<List<MarkdownInline>>, val rows: List<List<List<MarkdownInline>>>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

@Immutable
sealed interface MarkdownInline {
    data class Text(val literal: String) : MarkdownInline
    data class Strong(val children: List<MarkdownInline>) : MarkdownInline
    data class Emphasis(val children: List<MarkdownInline>) : MarkdownInline
    data class Code(val literal: String) : MarkdownInline
    data class Link(val destination: String, val children: List<MarkdownInline>) : MarkdownInline
    data object LineBreak : MarkdownInline
}

fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val document = markdownParser.parse(normalizeModelMarkdown(markdown))
    return document.children().flatMap { it.toBlocks() }.toList()
}

fun List<MarkdownInline>.plainText(): String = buildString {
    fun appendInline(inline: MarkdownInline) {
        when (inline) {
            is MarkdownInline.Text -> append(inline.literal)
            is MarkdownInline.Strong -> inline.children.forEach(::appendInline)
            is MarkdownInline.Emphasis -> inline.children.forEach(::appendInline)
            is MarkdownInline.Code -> append(inline.literal)
            is MarkdownInline.Link -> inline.children.forEach(::appendInline)
            MarkdownInline.LineBreak -> append('\n')
        }
    }
    this@plainText.forEach(::appendInline)
}

private val markdownParser = Parser.builder()
    .extensions(listOf<Extension>(TablesExtension.create()))
    .build()

private val headingWithoutSpaceAtStart = Regex("^(\\s{0,3})(#{1,6})(?!#)(?=\\S)")
private val inlineHeadingMarker = Regex("(?<=[^#\\s])(#{1,6})(?!#)(?=[\\p{L}\\p{N}])")
private val thematicBreakLine = Regex("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$")

private fun normalizeModelMarkdown(markdown: String): String {
    var inFence = false
    return buildList {
        markdown.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trimStart()
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    inFence = !inFence
                    add(rawLine)
                }
                inFence -> add(rawLine)
                else -> {
                    normalizeHeadingLine(rawLine).lineSequence().forEach { line ->
                        if (thematicBreakLine.matches(line) && lastOrNull()?.isNotBlank() == true) {
                            add("")
                        }
                        add(line)
                    }
                }
            }
        }
    }.joinToString("\n")
}

private fun normalizeHeadingLine(line: String): String =
    inlineHeadingMarker.replace(line) { "\n${it.value} " }
        .lineSequence()
        .joinToString("\n") { segment ->
            headingWithoutSpaceAtStart.replace(segment) {
                "${it.groupValues[1]}${it.groupValues[2]} "
            }
        }

private fun Node.toBlocks(): List<MarkdownBlock> = when (this) {
    is Heading -> listOf(MarkdownBlock.Heading(level, inlineChildren()))
    is Paragraph -> listOf(MarkdownBlock.Paragraph(inlineChildren()))
    is BulletList -> listOf(MarkdownBlock.BulletList(listItemInlineChildren()))
    is OrderedList -> listOf(MarkdownBlock.OrderedList(markerStartNumber ?: 1, listItemInlineChildren()))
    is BlockQuote -> listOf(MarkdownBlock.Quote(children().flatMap { it.toBlocks() }.toList()))
    is FencedCodeBlock -> listOf(MarkdownBlock.Code(literal.trimEnd(), info))
    is IndentedCodeBlock -> listOf(MarkdownBlock.Code(literal.trimEnd(), null))
    is ThematicBreak -> listOf(MarkdownBlock.Divider)
    is TableBlock -> listOf(toMarkdownTable())
    else -> children().flatMap { it.toBlocks() }.toList()
}

private fun TableBlock.toMarkdownTable(): MarkdownBlock.Table {
    val headers = children()
        .filterIsInstance<TableHead>()
        .flatMap { it.children().filterIsInstance<TableRow>() }
        .firstOrNull()
        ?.inlineCells()
        .orEmpty()
    val bodyRows = children()
        .filterIsInstance<TableBody>()
        .flatMap { it.children().filterIsInstance<TableRow>() }
        .map { it.inlineCells() }
        .toList()
    return MarkdownBlock.Table(headers = headers, rows = bodyRows)
}

private fun TableRow.inlineCells(): List<List<MarkdownInline>> =
    children()
        .filterIsInstance<TableCell>()
        .map { it.inlineChildren() }
        .toList()

private fun Node.listItemInlineChildren(): List<List<MarkdownInline>> =
    children()
        .filterIsInstance<ListItem>()
        .map { item ->
            val blocks = item.children().flatMap { it.toBlocks() }
            blocks.flatMap { block ->
                when (block) {
                    is MarkdownBlock.Heading -> block.text
                    is MarkdownBlock.Paragraph -> block.text
                    is MarkdownBlock.Code -> listOf(MarkdownInline.Code(block.literal))
                    is MarkdownBlock.Quote -> listOf(MarkdownInline.Text(block.blocks.joinToString(" ") { it.toPlainText() }))
                    MarkdownBlock.Divider -> emptyList()
                    is MarkdownBlock.BulletList -> listOf(MarkdownInline.Text(block.items.joinToString(" ") { it.plainText() }))
                    is MarkdownBlock.OrderedList -> listOf(MarkdownInline.Text(block.items.joinToString(" ") { it.plainText() }))
                    is MarkdownBlock.Table -> listOf(MarkdownInline.Text(block.toPlainText()))
                }
            }.toList()
        }.toList()

private fun MarkdownBlock.toPlainText(): String = when (this) {
    is MarkdownBlock.Heading -> text.plainText()
    is MarkdownBlock.Paragraph -> text.plainText()
    is MarkdownBlock.BulletList -> items.joinToString(" ") { it.plainText() }
    is MarkdownBlock.OrderedList -> items.joinToString(" ") { it.plainText() }
    is MarkdownBlock.Quote -> blocks.joinToString(" ") { it.toPlainText() }
    is MarkdownBlock.Code -> literal
    is MarkdownBlock.Table -> (headers + rows.flatten()).joinToString(" ") { it.plainText() }
    MarkdownBlock.Divider -> ""
}

private fun Node.inlineChildren(): List<MarkdownInline> {
    val collector = InlineCollector()
    accept(collector)
    return collector.children
}

private fun Node.children(): Sequence<Node> = sequence {
    var child = firstChild
    while (child != null) {
        yield(child)
        child = child.next
    }
}

private class InlineCollector : AbstractVisitor() {
    val children = mutableListOf<MarkdownInline>()

    override fun visit(text: Text) {
        children.add(MarkdownInline.Text(text.literal))
    }

    override fun visit(code: Code) {
        children.add(MarkdownInline.Code(code.literal))
    }

    override fun visit(softLineBreak: SoftLineBreak) {
        children.add(MarkdownInline.Text(" "))
    }

    override fun visit(hardLineBreak: HardLineBreak) {
        children.add(MarkdownInline.LineBreak)
    }

    override fun visit(emphasis: Emphasis) {
        children.add(MarkdownInline.Emphasis(emphasis.collectInlineChildren()))
    }

    override fun visit(strongEmphasis: StrongEmphasis) {
        children.add(MarkdownInline.Strong(strongEmphasis.collectInlineChildren()))
    }

    override fun visit(link: Link) {
        children.add(MarkdownInline.Link(link.destination, link.collectInlineChildren()))
    }

    private fun Node.collectInlineChildren(): List<MarkdownInline> {
        val nested = InlineCollector()
        children().forEach { it.accept(nested) }
        return nested.children
    }
}
