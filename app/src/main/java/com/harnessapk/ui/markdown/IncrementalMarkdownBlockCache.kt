package com.harnessapk.ui.markdown

import androidx.compose.runtime.Immutable

@Immutable
internal data class ParsedMarkdownChunk(
    val id: Int,
    val source: String,
    val blocks: List<MarkdownBlock>,
    val stable: Boolean,
)

internal class IncrementalMarkdownBlockCache(
    private val maxStableChunkChars: Int = DEFAULT_MAX_STABLE_CHUNK_CHARS,
    private val maxTailChars: Int = DEFAULT_MAX_TAIL_CHARS,
    private val parse: (String) -> List<MarkdownBlock> = ::parseMarkdownBlocks,
) {
    private val stableChunks = mutableListOf<ParsedMarkdownChunk>()
    private var stableLength = 0
    private var nextChunkId = 1

    fun chunksFor(markdown: String): List<ParsedMarkdownChunk> {
        val source = markdown.ifBlank { " " }
        if (!source.hasStablePrefix()) {
            stableChunks.clear()
            stableLength = 0
            nextChunkId = 1
        }

        var tail = source.drop(stableLength)
        while (tail.length > maxTailChars) {
            val chunkLength = stableChunkLength(tail) ?: break
            val chunkSource = tail.take(chunkLength)
            stableChunks += ParsedMarkdownChunk(
                id = nextChunkId++,
                source = chunkSource,
                blocks = parse(chunkSource),
                stable = true,
            )
            stableLength += chunkSource.length
            tail = tail.drop(chunkLength)
        }

        return if (tail.isEmpty()) {
            stableChunks.toList()
        } else {
            stableChunks + ParsedMarkdownChunk(
                id = TAIL_CHUNK_ID,
                source = tail,
                blocks = parse(tail),
                stable = false,
            )
        }
    }

    private fun stableChunkLength(source: String): Int? {
        val maxLength = source.length.coerceAtMost(maxStableChunkChars)
        val safeBoundary = latestSafeBoundary(source, maxLength)
        if (safeBoundary != null) return safeBoundary
        if (source.take(maxLength).hasOpenStreamingStructure()) return null
        return if (source.length > maxStableChunkChars + maxTailChars) maxStableChunkChars else null
    }

    private fun String.hasStablePrefix(): Boolean {
        if (length < stableLength) return false
        var offset = 0
        return stableChunks.all { chunk ->
            val matches = regionMatches(
                thisOffset = offset,
                other = chunk.source,
                otherOffset = 0,
                length = chunk.source.length,
                ignoreCase = false,
            )
            offset += chunk.source.length
            matches
        }
    }

    private fun latestSafeBoundary(source: String, maxLength: Int): Int? {
        val window = source.take(maxLength)
        val paragraphBreaks = window.boundaryIndexes("\n\n").map { it + 2 }
        val headingBreaks = headingBoundary.findAll(window).map { it.range.first }
        val listBreaks = listBoundary.findAll(window).map { it.range.first }
        return (paragraphBreaks + headingBreaks + listBreaks)
            .filter { it >= MIN_SAFE_BOUNDARY_CHARS }
            .filterNot { source.take(it).hasOpenStreamingStructure() }
            .maxOrNull()
    }

    private companion object {
        const val DEFAULT_MAX_STABLE_CHUNK_CHARS = 2_400
        const val DEFAULT_MAX_TAIL_CHARS = 800
        const val MIN_SAFE_BOUNDARY_CHARS = 8
        const val TAIL_CHUNK_ID = -1
        val headingBoundary = Regex("\n(?=#{1,6}\\s)")
        val listBoundary = Regex("\n(?=(?:[-*+]\\s|\\d+[.)]\\s))")
    }
}

private fun String.boundaryIndexes(token: String): Sequence<Int> = sequence {
    var start = indexOf(token)
    while (start >= 0) {
        yield(start)
        start = indexOf(token, startIndex = start + token.length)
    }
}

private fun String.hasUnclosedFence(): Boolean {
    var activeFence: MarkdownFence? = null
    var previousFenceLineBlank = false
    lineSequence().forEach { line ->
        val active = activeFence
        when {
            active == null -> {
                activeFence = parseMarkdownFenceOpening(line)
                previousFenceLineBlank = false
            }
            active.isClosingLine(line) || active.trailingCloseContent(line) != null -> {
                activeFence = null
                previousFenceLineBlank = false
            }
            active.shouldRecoverBefore(line, previousFenceLineBlank) -> {
                activeFence = parseMarkdownFenceOpening(line)
                previousFenceLineBlank = false
            }
            else -> previousFenceLineBlank = line.isBlank()
        }
    }
    return activeFence != null
}

private fun String.hasOpenStreamingStructure(): Boolean =
    hasUnclosedFence() || hasUnclosedDisplayMath() || hasUnclosedTable()

private fun String.hasUnclosedDisplayMath(): Boolean {
    var dollarMathOpen = false
    var bracketMathOpen = false
    lineSequence().forEach { line ->
        when (line.trim()) {
            "$$" -> dollarMathOpen = !dollarMathOpen
            "\\[" -> bracketMathOpen = true
            "\\]" -> bracketMathOpen = false
        }
    }
    return dollarMathOpen || bracketMathOpen
}

private fun String.hasUnclosedTable(): Boolean {
    val lines = lines()
    val delimiterIndex = lines.indexOfFirst { it.isGfmTableDelimiterLine() }
    if (delimiterIndex <= 0) return false
    if (!lines[delimiterIndex - 1].isLikelyTableRow()) return false
    return lines
        .drop(delimiterIndex + 1)
        .none { it.isBlank() }
}

private fun String.isLikelyTableRow(): Boolean {
    val trimmed = trim()
    return trimmed.count { it == '|' } >= 2
}

private fun String.isGfmTableDelimiterLine(): Boolean {
    val cells = trim().trim('|').split('|')
    if (cells.size < 2) return false
    return cells.all { cell ->
        val normalized = cell.trim()
        normalized.length >= 3 &&
            normalized.all { it == '-' || it == ':' } &&
            normalized.any { it == '-' }
    }
}
