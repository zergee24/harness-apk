package com.harnessapk.wiki

import com.harnessapk.chat.StreamingMessageSnapshot
import com.harnessapk.chat.UiMessagePartDraft
import com.harnessapk.chat.UiMessagePartType
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.UUID

data class WikiCitationAnswerRange(
    val start: Int,
    val endExclusive: Int,
)

data class WikiCitationDraft(
    val id: String,
    val displayOrdinal: Int,
    val evidence: WikiEvidence,
    val answerRanges: List<WikiCitationAnswerRange>,
    val verificationState: WikiCitationVerificationState,
)

data class WikiCitationVerificationResult(
    val snapshot: StreamingMessageSnapshot,
    val citations: List<WikiCitationDraft>,
    val hasInvalidVisibleTokens: Boolean,
)

class WikiCitationVerifier {
    fun verify(
        messageId: String,
        snapshot: StreamingMessageSnapshot,
        context: WikiRuntimeContext,
    ): WikiCitationVerificationResult {
        val layout = TextPartLayout.from(snapshot) ?: return WikiCitationVerificationResult(snapshot, emptyList(), false)
        val evidenceByToken = context.retrieval?.evidence.orEmpty().associateBy(WikiEvidence::token)
        val citations = linkedMapOf<WikiCitationSourceKey, MutableCitation>()
        val replacements = mutableListOf<TextReplacement>()
        var invalid = false

        visibleWikiTokenOccurrences(layout.text).forEach { occurrence ->
            val evidence = evidenceByToken[occurrence.token]
            if (evidence == null) {
                invalid = true
                replacements += TextReplacement(occurrence.start, occurrence.endExclusive, "")
                return@forEach
            }
            val key = WikiCitationSourceKey(evidence.ref, evidence.chunkId)
            val citation = citations.getOrPut(key) {
                MutableCitation(
                    id = stableCitationId(messageId, evidence),
                    displayOrdinal = citations.size + 1,
                    evidence = evidence,
                )
            }
            citation.answerRanges += WikiCitationAnswerRange(occurrence.start, occurrence.endExclusive)
            quoteImmediatelyBefore(layout.text, occurrence.start)?.let { quote ->
                if (!quoteMatchesSource(quote.text, evidence.originalText)) {
                    citation.verificationState = WikiCitationVerificationState.QUOTE_MISMATCH
                    replacements += TextReplacement(quote.openIndex, quote.openIndex + 1, "")
                    replacements += TextReplacement(quote.closeIndex, quote.closeIndex + 1, "")
                }
            }
            replacements += TextReplacement(
                occurrence.start,
                occurrence.endExclusive,
                "[${superscript(citation.displayOrdinal)}](harness-wiki://citation/${citation.id})",
            )
        }
        trailingVisibleWikiTokenRange(layout.text)?.let { range ->
            invalid = true
            replacements += TextReplacement(range.first, range.last + 1, "")
        }
        val rewritten = layout.apply(replacements)
        return WikiCitationVerificationResult(
            snapshot = rewritten,
            citations = citations.values.map { it.toDraft() },
            hasInvalidVisibleTokens = invalid,
        )
    }
}

fun hideWikiCitationTokensForDisplay(snapshot: StreamingMessageSnapshot): StreamingMessageSnapshot {
    val layout = TextPartLayout.from(snapshot) ?: return snapshot
    val replacements = buildList {
        visibleWikiTokenOccurrences(layout.text).forEach { occurrence ->
            add(TextReplacement(occurrence.start, occurrence.endExclusive, ""))
        }
        trailingVisibleWikiTokenRange(layout.text)?.let { range ->
            add(TextReplacement(range.first, range.last + 1, ""))
        }
    }
    return layout.apply(replacements)
}

fun removeVisibleWikiCitationTokens(snapshot: StreamingMessageSnapshot): StreamingMessageSnapshot =
    hideWikiCitationTokensForDisplay(snapshot)

private data class WikiTokenOccurrence(
    val token: String,
    val start: Int,
    val endExclusive: Int,
)

private data class WikiCitationSourceKey(
    val ref: WikiRef,
    val chunkId: String,
)

private class MutableCitation(
    val id: String,
    val displayOrdinal: Int,
    val evidence: WikiEvidence,
    val answerRanges: MutableList<WikiCitationAnswerRange> = mutableListOf(),
    var verificationState: WikiCitationVerificationState = WikiCitationVerificationState.VERIFIED,
) {
    fun toDraft(): WikiCitationDraft = WikiCitationDraft(
        id = id,
        displayOrdinal = displayOrdinal,
        evidence = evidence,
        answerRanges = answerRanges.toList(),
        verificationState = verificationState,
    )
}

private data class TextReplacement(
    val start: Int,
    val endExclusive: Int,
    val replacement: String,
)

private data class TextPartSegment(
    val partPosition: Int,
    val start: Int,
    val endExclusive: Int,
)

private class TextPartLayout private constructor(
    val snapshot: StreamingMessageSnapshot,
    val text: String,
    private val segments: List<TextPartSegment>,
) {
    fun apply(replacements: List<TextReplacement>): StreamingMessageSnapshot {
        if (replacements.isEmpty()) return snapshot
        val normalized = replacements
            .filter { it.start >= 0 && it.endExclusive in (it.start + 1)..text.length }
            .sortedWith(compareBy<TextReplacement> { it.start }.thenBy { it.endExclusive })
        if (normalized.isEmpty()) return snapshot
        val builders = snapshot.parts.map { StringBuilder() }
        var cursor = 0
        normalized.forEach { replacement ->
            if (replacement.start < cursor) return@forEach
            appendOriginal(builders, cursor, replacement.start)
            appendReplacement(builders, replacement.start, replacement.replacement)
            cursor = replacement.endExclusive
        }
        appendOriginal(builders, cursor, text.length)
        return snapshot.copy(
            parts = snapshot.parts.mapIndexed { index, part ->
                if (part.type == UiMessagePartType.TEXT) part.copy(content = builders[index].toString()) else part
            },
        )
    }

    private fun appendOriginal(builders: List<StringBuilder>, start: Int, endExclusive: Int) {
        if (start >= endExclusive) return
        segments.forEach { segment ->
            val from = maxOf(start, segment.start)
            val until = minOf(endExclusive, segment.endExclusive)
            if (from < until) {
                builders[segment.partPosition].append(text, from, until)
            }
        }
    }

    private fun appendReplacement(builders: List<StringBuilder>, position: Int, replacement: String) {
        val target = segments.firstOrNull { position in it.start until it.endExclusive } ?: return
        builders[target.partPosition].append(replacement)
    }

    companion object {
        fun from(snapshot: StreamingMessageSnapshot): TextPartLayout? {
            val segments = mutableListOf<TextPartSegment>()
            var offset = 0
            snapshot.parts.forEachIndexed { index, part ->
                if (part.type != UiMessagePartType.TEXT) return@forEachIndexed
                val end = offset + part.content.length
                segments += TextPartSegment(index, offset, end)
                offset = end
            }
            if (segments.isEmpty()) return null
            val text = snapshot.parts.filter { it.type == UiMessagePartType.TEXT }.joinToString("") { it.content }
            return TextPartLayout(snapshot, text, segments)
        }
    }
}

private data class QuoteSpan(
    val openIndex: Int,
    val closeIndex: Int,
    val text: String,
)

private fun quoteImmediatelyBefore(text: String, tokenStart: Int): QuoteSpan? {
    var closeIndex = tokenStart - 1
    while (closeIndex >= 0 && text[closeIndex].isWhitespace()) closeIndex -= 1
    val close = text.getOrNull(closeIndex) ?: return null
    val open = when (close) {
        '”' -> '“'
        '"' -> '"'
        '』' -> '『'
        '」' -> '「'
        else -> return null
    }
    val sentenceStart = (text.lastIndexOfAny(SENTENCE_BOUNDARIES, startIndex = closeIndex - 1) + 1).coerceAtLeast(0)
    val openIndex = text.lastIndexOf(open, startIndex = closeIndex - 1)
    if (openIndex < sentenceStart || openIndex >= closeIndex - 1) return null
    return QuoteSpan(openIndex, closeIndex, text.substring(openIndex + 1, closeIndex))
}

private fun quoteMatchesSource(quote: String, source: String): Boolean {
    val normalizedQuote = normalizeQuoteText(quote)
    return normalizedQuote.isNotBlank() && normalizeQuoteText(source).contains(normalizedQuote)
}

private fun normalizeQuoteText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .replace(Regex("\\s+"), "")

private fun visibleWikiTokenOccurrences(text: String): List<WikiTokenOccurrence> {
    val opaqueRanges = markdownCodeRanges(text)
    return WIKI_TOKEN.findAll(text)
        .filter { match -> opaqueRanges.none { range -> match.range.first >= range.first && match.range.last <= range.last } }
        .map { match ->
            WikiTokenOccurrence(
                token = match.value,
                start = match.range.first,
                endExclusive = match.range.last + 1,
            )
        }
        .toList()
}

private fun trailingVisibleWikiTokenRange(text: String): IntRange? {
    val match = TRAILING_WIKI_TOKEN.findAll(text).lastOrNull() ?: return null
    if (match.range.last != text.lastIndex) return null
    val opaqueRanges = markdownCodeRanges(text)
    return match.range.takeUnless { range -> opaqueRanges.any { it.first <= range.first && it.last >= range.last } }
}

private fun markdownCodeRanges(text: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var activeFence: MarkdownFenceRange? = null
    var lineStart = 0
    while (lineStart <= text.length) {
        val newline = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val lineEnd = if (newline < text.length) newline + 1 else newline
        val line = text.substring(lineStart, newline)
        val marker = fenceMarker(line)
        if (activeFence != null) {
            if (marker != null && marker.marker == activeFence.marker && marker.length >= activeFence.length) {
                ranges += activeFence.start..(lineEnd - 1).coerceAtLeast(activeFence.start)
                activeFence = null
            }
        } else if (marker != null) {
            activeFence = MarkdownFenceRange(marker.marker, marker.length, lineStart)
        }
        if (newline == text.length) break
        lineStart = lineEnd
    }
    activeFence?.let { fence -> ranges += fence.start..text.lastIndex.coerceAtLeast(fence.start) }

    var cursor = 0
    while (cursor < text.length) {
        if (ranges.any { cursor in it }) {
            cursor += 1
            continue
        }
        if (text[cursor] != '`') {
            cursor += 1
            continue
        }
        val length = text.runLength(cursor, '`')
        val close = findInlineCodeClose(text, cursor + length, length, ranges)
        if (close == null) {
            cursor += length
        } else {
            ranges += cursor..(close + length - 1)
            cursor = close + length
        }
    }
    return ranges.sortedBy(IntRange::first)
}

private fun fenceMarker(line: String): FenceMarker? {
    val trimmed = line.trimStart()
    val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    val length = trimmed.runLength(0, marker)
    return FenceMarker(marker, length).takeIf { it.length >= 3 }
}

private fun String.runLength(start: Int, character: Char): Int {
    var end = start
    while (end < length && this[end] == character) end += 1
    return end - start
}

private fun findInlineCodeClose(
    text: String,
    start: Int,
    length: Int,
    opaqueRanges: List<IntRange>,
): Int? {
    var cursor = start
    while (cursor < text.length) {
        if (opaqueRanges.any { cursor in it }) {
            cursor += 1
            continue
        }
        if (text[cursor] == '`' && text.runLength(cursor, '`') == length) return cursor
        cursor += 1
    }
    return null
}

private fun stableCitationId(messageId: String, evidence: WikiEvidence): String = UUID.nameUUIDFromBytes(
    "$messageId|${evidence.ref.wikiId}|${evidence.ref.version}|${evidence.chunkId}".toByteArray(StandardCharsets.UTF_8),
).toString()

private fun superscript(number: Int): String = number.toString().map { digit ->
    SUPERSCRIPT_DIGITS[digit] ?: digit
}.joinToString("")

private data class FenceMarker(
    val marker: Char,
    val length: Int,
)

private data class MarkdownFenceRange(
    val marker: Char,
    val length: Int,
    val start: Int,
)

private val WIKI_TOKEN = Regex("⟦W[1-9][0-9]*⟧")
private val TRAILING_WIKI_TOKEN = Regex("⟦W[0-9]*$")
private val SENTENCE_BOUNDARIES = charArrayOf('。', '！', '？', '!', '?', '；', ';', '\n')
private val SUPERSCRIPT_DIGITS = mapOf(
    '0' to '⁰',
    '1' to '¹',
    '2' to '²',
    '3' to '³',
    '4' to '⁴',
    '5' to '⁵',
    '6' to '⁶',
    '7' to '⁷',
    '8' to '⁸',
    '9' to '⁹',
)
