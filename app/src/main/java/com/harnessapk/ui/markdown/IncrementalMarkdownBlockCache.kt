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
        val safeBoundary = latestSafeBoundary(source, source.length.coerceAtMost(maxStableChunkChars))
        if (safeBoundary != null) return safeBoundary
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
        val paragraphBreak = window.lastIndexOf("\n\n").takeIf { it >= MIN_SAFE_BOUNDARY_CHARS }?.let { it + 2 }
        val headingBreak = window.lastHeadingBoundary()?.takeIf { it >= MIN_SAFE_BOUNDARY_CHARS }
        val listBreak = window.lastListBoundary()?.takeIf { it >= MIN_SAFE_BOUNDARY_CHARS }
        return listOfNotNull(paragraphBreak, headingBreak, listBreak).maxOrNull()
    }

    private fun String.lastHeadingBoundary(): Int? =
        headingBoundary.findAll(this).lastOrNull()?.range?.first?.takeIf { it > 0 }

    private fun String.lastListBoundary(): Int? =
        listBoundary.findAll(this).lastOrNull()?.range?.first?.takeIf { it > 0 }

    private companion object {
        const val DEFAULT_MAX_STABLE_CHUNK_CHARS = 2_400
        const val DEFAULT_MAX_TAIL_CHARS = 800
        const val MIN_SAFE_BOUNDARY_CHARS = 8
        const val TAIL_CHUNK_ID = -1
        val headingBoundary = Regex("\n(?=#{1,6}\\s)")
        val listBoundary = Regex("\n(?=(?:[-*+]\\s|\\d+[.)]\\s))")
    }
}
