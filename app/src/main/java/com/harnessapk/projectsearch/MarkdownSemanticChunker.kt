package com.harnessapk.projectsearch

class MarkdownSemanticChunker(
    private val targetMinCodePoints: Int = 300,
    private val targetMaxCodePoints: Int = 800,
    private val shortBlockCodePoints: Int = 80,
    private val maxCodePoints: Int = 1_200,
) {
    init {
        require(targetMinCodePoints in 1..targetMaxCodePoints)
        require(targetMaxCodePoints <= maxCodePoints)
        require(shortBlockCodePoints in 1..targetMinCodePoints)
    }

    fun chunk(relativePath: String, markdown: String): List<MarkdownSemanticChunk> {
        if (markdown.isBlank()) return emptyList()
        val semanticBlocks = parseBlocks(relativePath, markdown)
            .flatMap(::splitOversizedBlock)
        return mergeTowardTarget(semanticBlocks).mapIndexed { index, block ->
            MarkdownSemanticChunk(
                headingPath = block.headingPath,
                ordinal = index,
                text = block.text,
            )
        }
    }

    private fun parseBlocks(relativePath: String, markdown: String): List<Block> {
        val headings = mutableListOf<String>()
        val output = mutableListOf<Block>()
        val pending = mutableListOf<String>()
        val lines = markdown.lines()
        var index = 0

        fun headingPath(): String = headings.joinToString(" / ")

        fun flushPending() {
            val text = pending.joinToString("\n").trim()
            if (text.isNotBlank()) output += Block(headingPath(), text, fenced = false)
            pending.clear()
        }

        while (index < lines.size) {
            val line = lines[index]
            val heading = HEADING.matchEntire(line)
            when {
                heading != null -> {
                    flushPending()
                    val level = heading.groupValues[1].length
                    val title = heading.groupValues[2].trim()
                    while (headings.size >= level) headings.removeLast()
                    while (headings.size < level - 1) {
                        headings += relativePath.substringAfterLast('/')
                    }
                    headings += title
                    index++
                }

                FENCE_START.matches(line) -> {
                    flushPending()
                    val fenceLines = mutableListOf(line)
                    val marker = line.trimStart().takeWhile { it == '`' || it == '~' }
                    index++
                    while (index < lines.size) {
                        val candidate = lines[index]
                        fenceLines += candidate
                        index++
                        if (candidate.trim() == marker) break
                    }
                    output += Block(
                        headingPath = headingPath(),
                        text = fenceLines.joinToString("\n").trim(),
                        fenced = true,
                    )
                }

                line.isBlank() -> {
                    flushPending()
                    index++
                }

                else -> {
                    pending += line
                    index++
                }
            }
        }
        flushPending()
        return output
    }

    private fun splitOversizedBlock(block: Block): List<Block> {
        if (block.codePoints <= targetMaxCodePoints) return listOf(block)
        return if (block.fenced) splitFence(block) else splitText(block)
    }

    private fun splitFence(block: Block): List<Block> {
        val lines = block.text.lines()
        if (lines.size < 2) return splitText(block)
        val opening = lines.first()
        val marker = opening.trimStart().takeWhile { it == '`' || it == '~' }
        val hasClosingFence = lines.last().trim() == marker
        val closing = if (hasClosingFence) lines.last() else marker
        val body = lines.subList(1, lines.size - if (hasClosingFence) 1 else 0)
        val wrapperPoints = codePoints("$opening\n\n$closing")
        val bodyLimit = (targetMaxCodePoints - wrapperPoints).coerceAtLeast(1)
        val pieces = mutableListOf<String>()
        var pending = ""

        fun flush() {
            if (pending.isNotEmpty()) pieces += pending
            pending = ""
        }

        body.forEach { line ->
            val linePieces = splitByCodePoints(line, bodyLimit)
            linePieces.forEach { linePiece ->
                val combined = if (pending.isEmpty()) linePiece else "$pending\n$linePiece"
                if (codePoints(combined) <= bodyLimit) {
                    pending = combined
                } else {
                    flush()
                    pending = linePiece
                }
            }
        }
        flush()
        if (pieces.isEmpty()) pieces += ""
        return pieces.map { bodyPiece ->
            Block(
                headingPath = block.headingPath,
                text = "$opening\n$bodyPiece\n$closing",
                fenced = true,
            )
        }.onEach { require(it.codePoints <= maxCodePoints) }
    }

    private fun splitText(block: Block): List<Block> {
        val output = mutableListOf<Block>()
        var pending = ""

        fun flush() {
            if (pending.isNotBlank()) output += block.copy(text = pending.trim())
            pending = ""
        }

        block.text.lines().forEach { line ->
            splitByCodePoints(line, targetMaxCodePoints).forEach { linePiece ->
                val combined = if (pending.isEmpty()) linePiece else "$pending\n$linePiece"
                if (codePoints(combined) <= targetMaxCodePoints) {
                    pending = combined
                } else {
                    flush()
                    pending = linePiece
                }
            }
        }
        flush()
        return output
    }

    private fun mergeTowardTarget(blocks: List<Block>): List<Block> {
        val output = mutableListOf<Block>()
        var pending: Block? = null

        fun flush() {
            pending?.let(output::add)
            pending = null
        }

        blocks.forEach { next ->
            val current = pending
            if (current == null) {
                pending = next
                return@forEach
            }
            val combinedText = "${current.text}\n\n${next.text}"
            val combinedPoints = codePoints(combinedText)
            val sameSection = current.headingPath == next.headingPath
            val shouldMerge = !current.fenced && !next.fenced && sameSection && (
                combinedPoints <= targetMaxCodePoints && current.codePoints < targetMinCodePoints ||
                    combinedPoints <= maxCodePoints && next.codePoints < shortBlockCodePoints
                )
            if (shouldMerge) {
                pending = current.copy(text = combinedText)
            } else {
                flush()
                pending = next
            }
        }
        flush()
        return output
    }

    private fun splitByCodePoints(value: String, limit: Int): List<String> {
        if (codePoints(value) <= limit) return listOf(value)
        val output = mutableListOf<String>()
        var start = 0
        while (start < value.length) {
            val remaining = value.codePointCount(start, value.length)
            val count = minOf(limit, remaining)
            val end = value.offsetByCodePoints(start, count)
            output += value.substring(start, end)
            start = end
        }
        return output
    }

    private fun codePoints(value: String): Int = value.codePointCount(0, value.length)

    private data class Block(
        val headingPath: String,
        val text: String,
        val fenced: Boolean,
    ) {
        val codePoints: Int
            get() = text.codePointCount(0, text.length)
    }

    private companion object {
        val HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*$")
        val FENCE_START = Regex("^\\s*(`{3,}|~{3,})[^`~]*$")
    }
}
