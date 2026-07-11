package com.harnessapk.ui.markdown

internal data class MarkdownFence(
    val marker: Char,
    val length: Int,
    val info: String,
) {
    val token: String = marker.toString().repeat(length)
    val isTextLike: Boolean = info.startsWith("text", ignoreCase = true)

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

private const val MAX_FENCE_INDENT = 3
private const val MIN_FENCE_LENGTH = 3
private val recoveredTextDivider = Regex("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$")
