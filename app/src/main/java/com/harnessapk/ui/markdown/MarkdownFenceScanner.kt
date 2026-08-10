package com.harnessapk.ui.markdown

internal data class MarkdownFence(
    val marker: Char,
    val length: Int,
    val info: String,
) {
    val token: String = marker.toString().repeat(length)
    val isTextLike: Boolean = info
        .takeWhile { !it.isWhitespace() }
        .equals("text", ignoreCase = true)
    val gluedTextContent: String?
        get() {
            if (!info.startsWith("text", ignoreCase = true)) return null
            val suffix = info.drop(TEXT_LANGUAGE_LENGTH)
            return suffix.takeIf { it.startsWithHanCharacter() }
        }

    fun isClosingLine(line: String): Boolean {
        val indent = line.takeWhile { it == ' ' }.length
        if (indent > MAX_FENCE_INDENT) return false
        val trimmed = line.drop(indent)
        val runLength = trimmed.takeWhile { it == marker }.length
        return runLength >= length && trimmed.drop(runLength).isBlank()
    }

    fun trailingCloseContent(line: String): String? {
        val withoutTrailingSpace = line.trimEnd()
        val runLength = withoutTrailingSpace.takeLastWhile { it == marker }.length
        if (runLength < length) return null
        return withoutTrailingSpace
            .dropLast(runLength)
            .takeIf { it.isNotBlank() }
    }

    fun shouldRecoverBefore(line: String, previousLineBlank: Boolean): Boolean {
        if (!isTextLike || !previousLineBlank) return false
        val trimmed = line.trimStart()
        return recoveredTextDivider.matches(trimmed) ||
            trimmed.startsWith("#") ||
            trimmed.startsWith("> ")
    }
}

internal fun parseMarkdownFenceOpening(line: String): MarkdownFence? {
    val indent = line.takeWhile { it == ' ' }.length
    if (indent > MAX_FENCE_INDENT) return null
    val trimmed = line.drop(indent)
    val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    val length = trimmed.takeWhile { it == marker }.length
    if (length < MIN_FENCE_LENGTH) return null
    val info = trimmed.drop(length)
    if (marker == '`' && '`' in info) return null
    return MarkdownFence(marker = marker, length = length, info = info.trim())
}

internal fun unwrapSingleLineGluedTextFence(line: String): String? {
    var unwrapped = line
    var changed = false
    inlineGluedTextFencePatterns.forEach { pattern ->
        unwrapped = pattern.replace(unwrapped) { match ->
            val content = match.groupValues[1]
            if (content.startsWithHanCharacter()) {
                changed = true
                content
            } else {
                match.value
            }
        }
    }
    return unwrapped.takeIf { changed }
}

private fun String.startsWithHanCharacter(): Boolean =
    firstOrNull()?.let { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } == true

internal fun String.hasUnclosedFence(): Boolean {
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

internal fun String.hasUnclosedTable(): Boolean {
    val lines = lines()
    val delimiterIndex = lines.indexOfFirst { it.isGfmTableDelimiterLine() }
    if (delimiterIndex <= 0) return false
    if (!lines[delimiterIndex - 1].isLikelyTableRow()) return false
    return lines
        .drop(delimiterIndex + 1)
        .none { it.isBlank() }
}

internal fun String.isLikelyTableRow(): Boolean {
    val trimmed = trim()
    return trimmed.count { it == '|' } >= 2
}

internal fun String.isGfmTableDelimiterLine(): Boolean {
    val cells = trim().trim('|').split('|')
    if (cells.size < 2) return false
    return cells.all { cell ->
        val normalized = cell.trim()
        normalized.length >= 3 &&
            normalized.all { it == '-' || it == ':' } &&
            normalized.any { it == '-' }
    }
}

internal fun String.hasUnclosedDisplayMath(): Boolean {
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

internal fun String.hasOpenStreamingStructure(): Boolean =
    hasUnclosedFence() || hasUnclosedDisplayMath() || hasUnclosedTable()

private const val MAX_FENCE_INDENT = 3
private const val MIN_FENCE_LENGTH = 3
private const val TEXT_LANGUAGE_LENGTH = 4
private val recoveredTextDivider = Regex("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$")
private val inlineGluedTextFencePatterns = listOf(
    Regex("`{3,}text(.*?)`{3,}", RegexOption.IGNORE_CASE),
    Regex("~{3,}text(.*?)~{3,}", RegexOption.IGNORE_CASE),
)
