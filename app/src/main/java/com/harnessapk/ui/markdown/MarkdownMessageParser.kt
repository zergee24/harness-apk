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
    data class BulletList(val items: List<MarkdownListItem>) : MarkdownBlock
    data class OrderedList(val startNumber: Int, val items: List<MarkdownListItem>) : MarkdownBlock
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class Code(val literal: String, val info: String?) : MarkdownBlock
    data class Math(val literal: String, val display: Boolean) : MarkdownBlock
    data class Mermaid(val literal: String) : MarkdownBlock
    data class Table(val headers: List<List<MarkdownInline>>, val rows: List<List<List<MarkdownInline>>>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

@Immutable
data class MarkdownListItem(
    val text: List<MarkdownInline>,
    val children: List<MarkdownBlock> = emptyList(),
    val taskChecked: Boolean? = null,
)

@Immutable
sealed interface MarkdownInline {
    data class Text(val literal: String) : MarkdownInline
    data class Strong(val children: List<MarkdownInline>) : MarkdownInline
    data class Emphasis(val children: List<MarkdownInline>) : MarkdownInline
    data class Code(val literal: String) : MarkdownInline
    data class Math(val literal: String) : MarkdownInline
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
            is MarkdownInline.Math -> append(inline.literal)
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
private val compactFence = Regex("```([^`]*)```")
private val inlineDollarMath = Regex("""(?<!\\)\$(?!\s)(.+?)(?<!\\)\$""")
private val inlineParenMath = Regex("""\\\((.+?)\\\)""")
private val compactFenceLanguages = listOf(
    "bash",
    "shell",
    "sh",
    "zsh",
    "kotlin",
    "java",
    "python",
    "javascript",
    "typescript",
    "json",
    "text",
    "xml",
    "html",
    "css",
    "sql",
)

private fun normalizeModelMarkdown(markdown: String): String {
    var inFence = false
    var inDisplayMath = false
    return buildList {
        markdown.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trimStart()
            if (inDisplayMath && looksLikeMarkdownBlockStart(trimmed)) {
                add("```")
                add("")
                inDisplayMath = false
            }
            when {
                !inFence && trimmed.trim() == "$$" -> {
                    add(if (inDisplayMath) "```" else "```math")
                    inDisplayMath = !inDisplayMath
                }
                !inFence && trimmed.trim() == "\\[" -> {
                    add("```math")
                    inDisplayMath = true
                }
                !inFence && trimmed.trim() == "\\]" -> {
                    add("```")
                    inDisplayMath = false
                }
                inDisplayMath -> add(rawLine)
                (trimmed.startsWith("```") || trimmed.startsWith("~~~")) && !hasSingleLineFence(rawLine) -> {
                    inFence = !inFence
                    add(rawLine)
                }
                inFence -> add(rawLine)
                else -> {
                    normalizeCompactCodeFences(rawLine).lineSequence().forEach { compactLine ->
                        normalizeHeadingLine(compactLine).lineSequence().forEach { line ->
                            if (thematicBreakLine.matches(line) && lastOrNull()?.isNotBlank() == true) {
                                add("")
                            }
                            add(line)
                        }
                    }
                }
            }
        }
    }.joinToString("\n")
}

private fun looksLikeMarkdownBlockStart(trimmed: String): Boolean =
    trimmed.startsWith("#") ||
        trimmed.startsWith("- ") ||
        trimmed.startsWith("* ") ||
        trimmed.matches(Regex("^\\d+[.)]\\s+.*"))

private fun normalizeHeadingLine(line: String): String =
    inlineHeadingMarker.replace(line) { "\n${it.value} " }
        .lineSequence()
        .joinToString("\n") { segment ->
            headingWithoutSpaceAtStart.replace(segment) {
                "${it.groupValues[1]}${it.groupValues[2]} "
            }
        }

private fun hasSingleLineFence(line: String): Boolean {
    val first = line.indexOf("```")
    return first >= 0 && line.indexOf("```", startIndex = first + 3) >= 0
}

private fun normalizeCompactCodeFences(line: String): String {
    if (!hasSingleLineFence(line)) return line
    val output = StringBuilder()
    var cursor = 0
    compactFence.findAll(line).forEach { match ->
        val prefix = line.substring(cursor, match.range.first)
        if (prefix.isNotEmpty()) output.append(prefix.trimEnd()).append('\n')
        val (language, literal) = splitCompactFencePayload(match.groupValues[1])
        output.append("```")
        if (language.isNotBlank()) output.append(language)
        output.append('\n')
        output.append(literal)
        output.append('\n').append("```")
        cursor = match.range.last + 1
        val suffixStartsText = line.getOrNull(cursor)?.isWhitespace() == false
        if (suffixStartsText) output.append('\n')
    }
    if (cursor < line.length) {
        output.append(line.substring(cursor).trimStart())
    }
    return output.toString().trimEnd()
}

private fun splitCompactFencePayload(payload: String): Pair<String, String> {
    val trimmed = payload.trim()
    val language = compactFenceLanguages.firstOrNull { language ->
        trimmed == language ||
            trimmed.startsWith("$language ") ||
            trimmed.startsWith("$language\n") ||
            (language in compactShellLanguages && trimmed.startsWith(language) && trimmed.length > language.length)
    }.orEmpty()
    if (language.isBlank()) return "" to trimmed
    return language to trimmed.removePrefix(language).trimStart()
}

private val compactShellLanguages = setOf("bash", "shell", "sh", "zsh")

private fun Node.toBlocks(): List<MarkdownBlock> = when (this) {
    is Heading -> listOf(MarkdownBlock.Heading(level, inlineChildren()))
    is Paragraph -> listOf(MarkdownBlock.Paragraph(inlineChildren()))
    is BulletList -> listOf(MarkdownBlock.BulletList(listItems()))
    is OrderedList -> listOf(MarkdownBlock.OrderedList(markerStartNumber ?: 1, listItems()))
    is BlockQuote -> listOf(MarkdownBlock.Quote(children().flatMap { it.toBlocks() }.toList()))
    is FencedCodeBlock -> listOf(toCodeLikeBlock())
    is IndentedCodeBlock -> listOf(MarkdownBlock.Code(literal.trimEnd(), null))
    is ThematicBreak -> listOf(MarkdownBlock.Divider)
    is TableBlock -> listOf(toMarkdownTable())
    else -> children().flatMap { it.toBlocks() }.toList()
}

private fun FencedCodeBlock.toCodeLikeBlock(): MarkdownBlock {
    val normalizedInfo = info.orEmpty().trim().lowercase()
    val literal = literal.trimEnd()
    return when {
        normalizedInfo == "math" || normalizedInfo == "tex" || normalizedInfo == "latex" ->
            MarkdownBlock.Math(literal = literal.trim(), display = true)
        normalizedInfo == "mermaid" ->
            MarkdownBlock.Mermaid(literal = literal)
        else -> MarkdownBlock.Code(literal, info)
    }
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

private fun Node.listItems(): List<MarkdownListItem> =
    children()
        .filterIsInstance<ListItem>()
        .map { it.toListItem() }
        .toList()

private fun ListItem.toListItem(): MarkdownListItem {
    val text = mutableListOf<MarkdownInline>()
    val children = mutableListOf<MarkdownBlock>()
    children().forEach { child ->
        child.toBlocks().forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> text.addAll(block.text)
                is MarkdownBlock.Paragraph -> text.addAll(block.text)
                is MarkdownBlock.Code,
                is MarkdownBlock.Math,
                is MarkdownBlock.Mermaid,
                is MarkdownBlock.Quote,
                is MarkdownBlock.BulletList,
                is MarkdownBlock.OrderedList,
                is MarkdownBlock.Table,
                MarkdownBlock.Divider -> children.add(block)
            }
        }
    }
    val task = text.extractTaskState()
    return MarkdownListItem(text = task.text, children = children, taskChecked = task.checked)
}

private fun MarkdownBlock.toPlainText(): String = when (this) {
    is MarkdownBlock.Heading -> text.plainText()
    is MarkdownBlock.Paragraph -> text.plainText()
    is MarkdownBlock.BulletList -> items.joinToString(" ") { it.toPlainText() }
    is MarkdownBlock.OrderedList -> items.joinToString(" ") { it.toPlainText() }
    is MarkdownBlock.Quote -> blocks.joinToString(" ") { it.toPlainText() }
    is MarkdownBlock.Code -> literal
    is MarkdownBlock.Math -> literal
    is MarkdownBlock.Mermaid -> literal
    is MarkdownBlock.Table -> (headers + rows.flatten()).joinToString(" ") { it.plainText() }
    MarkdownBlock.Divider -> ""
}

private fun MarkdownListItem.toPlainText(): String =
    (listOf(text.plainText()) + children.map { it.toPlainText() })
        .filter { it.isNotBlank() }
        .joinToString(" ")

private fun Node.inlineChildren(): List<MarkdownInline> {
    val collector = InlineCollector()
    accept(collector)
    return collector.children.withInlineMath()
}

private data class TaskText(
    val checked: Boolean?,
    val text: List<MarkdownInline>,
)

private fun List<MarkdownInline>.extractTaskState(): TaskText {
    val firstText = firstOrNull() as? MarkdownInline.Text ?: return TaskText(null, this)
    val literal = firstText.literal
    val checked = when {
        literal.startsWith("[x] ", ignoreCase = true) -> true
        literal.startsWith("[ ] ") -> false
        else -> null
    } ?: return TaskText(null, this)
    val stripped = firstText.copy(literal = literal.drop(4))
    return TaskText(checked, listOf(stripped).filter { it.literal.isNotEmpty() } + drop(1))
}

private fun List<MarkdownInline>.withInlineMath(): List<MarkdownInline> =
    flatMap { inline ->
        when (inline) {
            is MarkdownInline.Text -> inline.literal.splitInlineMath()
            else -> listOf(inline)
        }
    }

private fun String.splitInlineMath(): List<MarkdownInline> {
    val matches = (inlineDollarMath.findAll(this) + inlineParenMath.findAll(this))
        .sortedBy { it.range.first }
        .toList()
    if (matches.isEmpty()) return listOf(MarkdownInline.Text(this))
    val output = mutableListOf<MarkdownInline>()
    var cursor = 0
    matches.forEach { match ->
        if (match.range.first < cursor) return@forEach
        if (match.range.first > cursor) {
            output += MarkdownInline.Text(substring(cursor, match.range.first))
        }
        output += MarkdownInline.Math(match.groupValues[1])
        cursor = match.range.last + 1
    }
    if (cursor < length) output += MarkdownInline.Text(substring(cursor))
    return output.filterNot { it is MarkdownInline.Text && it.literal.isEmpty() }
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
