package com.harnessapk.session

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Link
import org.commonmark.parser.Parser

object WikiMarkdownCitationFormatter {
    fun toPortableMarkdown(
        markdown: String,
        citations: WikiMarkdownCitationSet,
        includeQuotationDetail: Boolean = false,
    ): String {
        if (citations.citations.isEmpty()) return markdown
        val parsedInternalTargets = parsedInternalCitationTargets(markdown)
        val rawLinks = findMarkdownLinks(markdown)
        val firstUse = linkedSetOf<WikiMarkdownCitation>()
        val rewritten = StringBuilder(markdown.length + 256)
        var cursor = 0

        rawLinks.forEach { link ->
            if (!link.destination.startsWith(HARNESS_WIKI_CITATION_PREFIX)) return@forEach
            val citationId = link.destination.removePrefix(HARNESS_WIKI_CITATION_PREFIX)
            val citation = citations.citation(citationId)
            if (citation == null || link.destination !in parsedInternalTargets) {
                throw WikiMarkdownCitationException("助手输出包含无法核验的 Wiki 引用")
            }
            rewritten.append(markdown, cursor, link.start)
            rewritten.append("[^")
            rewritten.append(citations.footnoteLabel(citation))
            rewritten.append(']')
            firstUse += citation
            cursor = link.endExclusive
        }
        rewritten.append(markdown, cursor, markdown.length)
        val portable = rewritten.toString()
        if (portable.contains("harness-wiki://", ignoreCase = true)) {
            throw WikiMarkdownCitationException("助手输出包含无法转换的应用内部引用")
        }
        if (firstUse.isEmpty()) return portable
        val definitions = firstUse.joinToString(separator = "\n") { citation ->
            "[^${citations.footnoteLabel(citation)}]: ${formatWikiFootnoteDefinition(citation, includeQuotationDetail)}"
        }
        return portable.trimEnd() + "\n\n" + definitions
    }
}

internal fun formatWikiFootnoteDefinition(
    citation: WikiMarkdownCitation,
    includeQuotationDetail: Boolean = false,
): String {
    val sourceTitle = normalizeFootnoteMetadata(stripBookTitleMarks(citation.sourceTitle))
    val sectionPath = stripLeadingSourceTitle(
        path = normalizeFootnoteMetadata(citation.sectionPath),
        sourceTitle = sourceTitle,
    )
    val location = buildString {
        append('《')
        append(sourceTitle)
        append('》')
        if (sectionPath.isNotBlank()) {
            append("· ")
            append(sectionPath)
        }
        append('；')
        append(normalizeFootnoteMetadata(citation.wikiTitle))
        append(" v")
        append(citation.wikiVersion)
        append('；')
        append(normalizeFootnoteMetadata(citation.locatorLabel).trimEnd('。', '.'))
        append('。')
    }
    if (!includeQuotationDetail) return location
    val quotation = boundedCodePoints(normalizeFootnoteMetadata(citation.originalTextSnapshot), MAX_QUOTATION_CODE_POINTS)
    return if (quotation.isBlank()) location else "$location 引文：“$quotation”。"
}

private fun parsedInternalCitationTargets(markdown: String): Set<String> {
    val targets = linkedSetOf<String>()
    commonMarkParser.parse(markdown).accept(object : AbstractVisitor() {
        override fun visit(link: Link) {
            link.destination
                .takeIf { it.startsWith(HARNESS_WIKI_CITATION_PREFIX) }
                ?.let(targets::add)
            visitChildren(link)
        }
    })
    return targets
}

private data class MarkdownLinkRange(
    val start: Int,
    val endExclusive: Int,
    val destination: String,
)

private fun findMarkdownLinks(markdown: String): List<MarkdownLinkRange> {
    val opaque = markdownOpaqueRanges(markdown)
    val links = mutableListOf<MarkdownLinkRange>()
    var index = 0
    while (index < markdown.length) {
        if (opaque.any { index in it }) {
            index += 1
            continue
        }
        if (markdown[index] != '[' || markdown.getOrNull(index - 1) == '\\') {
            index += 1
            continue
        }
        val labelEnd = matchingBracket(markdown, index, '[', ']') ?: run {
            index += 1
            continue
        }
        val openParenthesis = labelEnd + 1
        if (markdown.getOrNull(openParenthesis) != '(' || opaque.any { openParenthesis in it }) {
            index = labelEnd + 1
            continue
        }
        val closeParenthesis = matchingBracket(markdown, openParenthesis, '(', ')') ?: run {
            index = openParenthesis + 1
            continue
        }
        val destination = markdown.substring(openParenthesis + 1, closeParenthesis).trim()
            .removeSurrounding("<", ">")
        links += MarkdownLinkRange(index, closeParenthesis + 1, destination)
        index = closeParenthesis + 1
    }
    return links
}

private fun matchingBracket(text: String, start: Int, open: Char, close: Char): Int? {
    var depth = 0
    var index = start
    while (index < text.length) {
        if (text[index] == '\\') {
            index += 2
            continue
        }
        when (text[index]) {
            open -> depth += 1
            close -> {
                depth -= 1
                if (depth == 0) return index
            }
        }
        index += 1
    }
    return null
}

private fun markdownOpaqueRanges(text: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var activeFence: FenceMarker? = null
    var fenceStart = -1
    var lineStart = 0
    while (lineStart <= text.length) {
        val newline = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, newline)
        val marker = fenceMarker(line)
        if (activeFence == null && marker != null) {
            activeFence = marker
            fenceStart = lineStart
        } else if (activeFence != null && marker != null && marker.marker == activeFence.marker && marker.length >= activeFence.length) {
            ranges += fenceStart..newline
            activeFence = null
            fenceStart = -1
        }
        if (newline == text.length) break
        lineStart = newline + 1
    }
    if (activeFence != null) ranges += fenceStart..text.lastIndex.coerceAtLeast(fenceStart)

    var index = 0
    while (index < text.length) {
        if (ranges.any { index in it } || text[index] != '`') {
            index += 1
            continue
        }
        val ticks = text.runLength(index, '`')
        val close = text.indexOf("`".repeat(ticks), index + ticks)
        if (close < 0 || ranges.any { close in it }) {
            index += ticks
        } else {
            ranges += index..(close + ticks - 1)
            index = close + ticks
        }
    }
    return ranges
}

private data class FenceMarker(val marker: Char, val length: Int)

private fun fenceMarker(line: String): FenceMarker? {
    val trimmed = line.trimStart()
    val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    val length = trimmed.runLength(0, marker)
    return FenceMarker(marker, length).takeIf { it.length >= 3 }
}

private fun String.runLength(start: Int, char: Char): Int {
    var index = start
    while (getOrNull(index) == char) index += 1
    return index - start
}

private fun stripBookTitleMarks(value: String): String = value.trim()
    .removePrefix("《")
    .removeSuffix("》")
    .trim()

private fun stripLeadingSourceTitle(path: String, sourceTitle: String): String {
    if (!path.startsWith(sourceTitle)) return path
    return path.removePrefix(sourceTitle).trimStart(' ', '　', '/', '／', '·', '：', ':', '-', '—')
}

private fun normalizeFootnoteMetadata(value: String): String = value
    .lineSequence()
    .joinToString(separator = " ") { line -> line.trim() }
    .replace("[^", "[ ^")
    .replace('[', '［')
    .replace(']', '］')
    .trim()

private fun boundedCodePoints(value: String, limit: Int): String {
    if (value.codePointCount(0, value.length) <= limit) return value
    val end = value.offsetByCodePoints(0, limit)
    return value.substring(0, end) + "…"
}

private const val HARNESS_WIKI_CITATION_PREFIX = "harness-wiki://citation/"
private const val MAX_QUOTATION_CODE_POINTS = 160
private val commonMarkParser = Parser.builder().build()
