package com.harnessapk.chat

import com.harnessapk.wiki.hideWikiCitationTokensForDisplay
import com.harnessapk.wiki.removeVisibleWikiCitationTokens
sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ImageDelta(
        val source: String,
        val mimeType: String? = null,
        val altText: String? = null,
    ) : StreamEvent
    data class ReasoningDelta(val text: String) : StreamEvent
    data class ToolCallDelta(
        val id: String,
        val name: String?,
        val argumentsDelta: String,
    ) : StreamEvent
    data class ToolResult(val toolCallId: String, val content: String) : StreamEvent
    data class SearchResult(val title: String, val url: String, val snippet: String) : StreamEvent
    data class Usage(val inputTokens: Int?, val outputTokens: Int?, val totalTokens: Int?) : StreamEvent
    data class Finished(val reason: String?) : StreamEvent
    data class RawProviderEvent(val provider: String, val payload: String) : StreamEvent
}

enum class UiMessagePartType {
    TEXT,
    REASONING,
    IMAGE,
    DOCUMENT,
    TOOL_CALL,
    TOOL_RESULT,
    SEARCH_RESULT,
    FILE_CHANGE,
    ERROR_DETAIL,
    SYSTEM_EVENT,
    AGENT_SOURCES,
    WIKI_SOURCES,
}

data class UiMessagePartDraft(
    val index: Int,
    val type: UiMessagePartType,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val stable: Boolean,
)

data class StreamingMessageSnapshot(
    val status: MessageStatus,
    val parts: List<UiMessagePartDraft>,
    val finishReason: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
)

fun StreamingMessageSnapshot.hideWikiCitationTokensForDisplay(): StreamingMessageSnapshot =
    hideWikiCitationTokensForDisplay(this)

fun StreamingMessageSnapshot.removeWikiCitationTokensForTerminal(): StreamingMessageSnapshot =
    removeVisibleWikiCitationTokens(this)

fun StreamingMessageSnapshot.legacyVisibleText(): String =
    parts
        .filter { it.type == UiMessagePartType.TEXT }
        .joinToString(separator = "") { it.content }

fun StreamingMessageSnapshot.legacyVisibleDelta(previousVisibleText: String): String {
    val next = legacyVisibleText()
    return if (next.startsWith(previousVisibleText)) {
        next.removePrefix(previousVisibleText)
    } else {
        next
    }
}

data class StreamingFlush(
    val reason: StreamingFlushReason,
    val snapshot: StreamingMessageSnapshot,
)

enum class StreamingFlushReason {
    FIRST_DELTA,
    INTERVAL,
    SIZE,
    STRUCTURE,
    FINISHED,
    CANCELLED,
    USAGE,
}

class StreamingMessageAccumulator(
    private val flushIntervalMillis: Long = DEFAULT_FLUSH_INTERVAL_MILLIS,
    private val maxBufferedChars: Int = DEFAULT_MAX_BUFFERED_CHARS,
    private val maxTailChars: Int = DEFAULT_MAX_TAIL_CHARS,
) {
    private val parts = mutableListOf<MutablePart>()
    private var status = MessageStatus.PENDING
    private var finishReason: String? = null
    private var inputTokens: Int? = null
    private var outputTokens: Int? = null
    private var totalTokens: Int? = null
    private var lastFlushMillis: Long? = null
    private var hasFlushedDelta = false
    private var terminal = false
    private val markdownBuffer = StringBuilder()

    fun onEvent(event: StreamEvent, nowMillis: Long): StreamingFlush? {
        if (terminal) return null
        return when (event) {
            is StreamEvent.TextDelta -> appendMarkdownText(event.text, nowMillis)
            is StreamEvent.ImageDelta -> {
                appendPendingMarkdownText(nowMillis)
                appendImage(event, nowMillis)
            }
            is StreamEvent.ReasoningDelta -> {
                appendPendingMarkdownText(nowMillis)
                appendContent(
                    type = UiMessagePartType.REASONING,
                    content = event.text,
                    metadata = emptyMap(),
                    nowMillis = nowMillis,
                )
            }
            is StreamEvent.ToolCallDelta -> {
                appendPendingMarkdownText(nowMillis)
                appendContent(
                    type = UiMessagePartType.TOOL_CALL,
                    content = event.argumentsDelta,
                    metadata = mapOf(
                        "id" to event.id,
                        "name" to event.name.orEmpty(),
                    ),
                    nowMillis = nowMillis,
                    forceStructureFlush = true,
                )
            }
            is StreamEvent.ToolResult -> {
                appendPendingMarkdownText(nowMillis)
                appendContent(
                    type = UiMessagePartType.TOOL_RESULT,
                    content = event.content,
                    metadata = mapOf("toolCallId" to event.toolCallId),
                    nowMillis = nowMillis,
                    forceStructureFlush = true,
                )
            }
            is StreamEvent.SearchResult -> {
                appendPendingMarkdownText(nowMillis)
                appendContent(
                    type = UiMessagePartType.SEARCH_RESULT,
                    content = event.snippet,
                    metadata = mapOf("title" to event.title, "url" to event.url),
                    nowMillis = nowMillis,
                    forceStructureFlush = true,
                )
            }
            is StreamEvent.Usage -> {
                inputTokens = event.inputTokens
                outputTokens = event.outputTokens
                totalTokens = event.totalTokens
                flush(StreamingFlushReason.USAGE, nowMillis)
            }
            is StreamEvent.Finished -> finish(event.reason, nowMillis)
            is StreamEvent.RawProviderEvent -> null
        }
    }

    fun cancel(nowMillis: Long): StreamingFlush {
        if (!terminal) {
            appendPendingMarkdownText(nowMillis)
            status = MessageStatus.CANCELLED
            terminal = true
            markAllPartsStable()
        }
        return flush(StreamingFlushReason.CANCELLED, nowMillis)
    }

    fun snapshot(): StreamingMessageSnapshot = StreamingMessageSnapshot(
        status = status,
        parts = parts.map { it.toDraft() },
        finishReason = finishReason,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
    )

    private fun appendContent(
        type: UiMessagePartType,
        content: String,
        metadata: Map<String, String>,
        nowMillis: Long,
        forceStructureFlush: Boolean = false,
    ): StreamingFlush? {
        if (content.isEmpty()) return null
        status = MessageStatus.STREAMING
        val structureChanged = ensureActivePart(type, metadata)
        val active = parts.last()
        active.content.append(content)
        val activePartSplit = splitActivePartIfNeeded()
        val reason = flushReason(
            active = parts.last(),
            structureChanged = structureChanged || forceStructureFlush || activePartSplit,
            nowMillis = nowMillis,
        ) ?: return null
        return flush(reason, nowMillis)
    }

    private fun appendMarkdownText(content: String, nowMillis: Long): StreamingFlush? {
        if (content.isEmpty()) return null
        markdownBuffer.append(content)
        return drainMarkdownBuffer(terminal = false, nowMillis = nowMillis)
    }

    private fun appendPendingMarkdownText(nowMillis: Long): StreamingFlush? {
        if (markdownBuffer.isEmpty()) return null
        return drainMarkdownBuffer(terminal = true, nowMillis = nowMillis)
    }

    private fun drainMarkdownBuffer(terminal: Boolean, nowMillis: Long): StreamingFlush? {
        var latestFlush: StreamingFlush? = null
        markdownBuffer.drainMarkdownImages(
            terminal = terminal,
            onText = { text ->
                appendContent(
                    type = UiMessagePartType.TEXT,
                    content = text,
                    metadata = emptyMap(),
                    nowMillis = nowMillis,
                )?.let { latestFlush = it }
            },
            onImage = { source, altText ->
                appendImage(
                    StreamEvent.ImageDelta(
                        source = source,
                        mimeType = source.dataImageMimeType(),
                        altText = altText,
                    ),
                    nowMillis = nowMillis,
                )?.let { latestFlush = it }
            },
        )
        return latestFlush
    }

    private fun appendImage(event: StreamEvent.ImageDelta, nowMillis: Long): StreamingFlush? {
        val source = event.source.trim()
        if (source.isEmpty()) return null
        status = MessageStatus.STREAMING
        ensureActivePart(
            type = UiMessagePartType.IMAGE,
            metadata = buildMap {
                event.mimeType?.takeIf { it.isNotBlank() }?.let { put("mimeType", it) }
                event.altText?.takeIf { it.isNotBlank() }?.let { put("altText", it) }
            },
        )
        val active = parts.last()
        active.content.append(source)
        active.stable = true
        val reason = flushReason(
            active = active,
            structureChanged = true,
            nowMillis = nowMillis,
        ) ?: return null
        return flush(reason, nowMillis)
    }

    private fun ensureActivePart(type: UiMessagePartType, metadata: Map<String, String>): Boolean {
        val active = parts.lastOrNull()
        if (active != null && !active.stable && active.type == type && active.canMerge(metadata)) return false
        active?.stable = true
        parts += MutablePart(
            index = parts.size,
            type = type,
            metadata = metadata.filterValues { it.isNotBlank() },
            nextSizeFlushAt = maxBufferedChars.coerceAtLeast(1),
        )
        return active != null
    }

    private fun splitActivePartIfNeeded(): Boolean {
        var changed = false
        while (true) {
            val active = parts.lastOrNull() ?: return changed
            if (!active.type.canSplitTail() || active.content.length <= maxTailChars.coerceAtLeast(1)) return changed
            val text = active.content.toString()
            val splitAt = splitPoint(text, maxTailChars.coerceAtLeast(1))
            if (splitAt <= 0 || splitAt >= text.length) return changed
            val stablePrefix = text.take(splitAt)
            val tail = text.drop(splitAt)
            active.content.clear()
            active.content.append(stablePrefix)
            active.stable = true
            parts += MutablePart(
                index = parts.size,
                type = active.type,
                metadata = active.metadata,
                nextSizeFlushAt = maxBufferedChars.coerceAtLeast(1),
            ).also { it.content.append(tail) }
            changed = true
        }
    }

    private fun flushReason(
        active: MutablePart,
        structureChanged: Boolean,
        nowMillis: Long,
    ): StreamingFlushReason? {
        if (!hasFlushedDelta) return StreamingFlushReason.FIRST_DELTA
        if (structureChanged) return StreamingFlushReason.STRUCTURE
        if (active.content.length >= active.nextSizeFlushAt) return StreamingFlushReason.SIZE
        val previousFlush = lastFlushMillis ?: return null
        if (nowMillis - previousFlush >= flushIntervalMillis) return StreamingFlushReason.INTERVAL
        return null
    }

    private fun finish(reason: String?, nowMillis: Long): StreamingFlush {
        appendPendingMarkdownText(nowMillis)
        status = MessageStatus.SUCCEEDED
        finishReason = reason
        terminal = true
        markAllPartsStable()
        return flush(StreamingFlushReason.FINISHED, nowMillis)
    }

    private fun flush(reason: StreamingFlushReason, nowMillis: Long): StreamingFlush {
        hasFlushedDelta = true
        lastFlushMillis = nowMillis
        parts.lastOrNull()?.let { active ->
            while (active.content.length >= active.nextSizeFlushAt) {
                active.nextSizeFlushAt += maxBufferedChars.coerceAtLeast(1)
            }
        }
        return StreamingFlush(reason = reason, snapshot = snapshot())
    }

    private fun markAllPartsStable() {
        parts.forEach { it.stable = true }
    }

    private class MutablePart(
        val index: Int,
        val type: UiMessagePartType,
        val metadata: Map<String, String>,
        var nextSizeFlushAt: Int,
    ) {
        val content = StringBuilder()
        var stable: Boolean = false

        fun canMerge(nextMetadata: Map<String, String>): Boolean =
            metadata == nextMetadata.filterValues { it.isNotBlank() }

        fun toDraft(): UiMessagePartDraft = UiMessagePartDraft(
            index = index,
            type = type,
            content = content.toString(),
            metadata = metadata,
            stable = stable,
        )
    }

    private companion object {
        const val DEFAULT_FLUSH_INTERVAL_MILLIS = 300L
        const val DEFAULT_MAX_BUFFERED_CHARS = 320
        const val DEFAULT_MAX_TAIL_CHARS = 1_200
    }
}

private fun StringBuilder.drainMarkdownImages(
    terminal: Boolean,
    onText: (String) -> Unit,
    onImage: (source: String, altText: String) -> Unit,
) {
    while (isNotEmpty()) {
        val imageStart = indexOf("![")
        if (imageStart < 0) {
            val textLength = if (!terminal && endsWith("!")) length - 1 else length
            if (textLength > 0) onText(substring(0, textLength))
            delete(0, textLength)
            return
        }
        if (imageStart > 0) {
            onText(substring(0, imageStart))
            delete(0, imageStart)
        }
        val altEnd = indexOf("](", startIndex = 2)
        if (altEnd < 0) {
            if (terminal) {
                onText(toString())
                clear()
            }
            return
        }
        val sourceEnd = indexOf(")", startIndex = altEnd + 2)
        if (sourceEnd < 0) {
            if (terminal) {
                onText(toString())
                clear()
            }
            return
        }
        val source = substring(altEnd + 2, sourceEnd).trim()
        if (source.isSupportedMarkdownImageSource()) {
            onImage(source, substring(2, altEnd))
        } else {
            onText(substring(0, sourceEnd + 1))
        }
        delete(0, sourceEnd + 1)
    }
}

private fun String.isSupportedMarkdownImageSource(): Boolean =
    startsWith("https://", ignoreCase = true) || startsWith("data:image/", ignoreCase = true)

private fun String.dataImageMimeType(): String? =
    takeIf { startsWith("data:image/", ignoreCase = true) }
        ?.substringAfter("data:")
        ?.substringBefore(';')
        ?.takeIf { it.startsWith("image/", ignoreCase = true) }

private fun UiMessagePartType.canSplitTail(): Boolean =
    this == UiMessagePartType.TEXT || this == UiMessagePartType.REASONING

private fun splitPoint(text: String, maxPrefixChars: Int): Int {
    val maxSplit = maxPrefixChars.coerceIn(1, text.length - 1)
    text.lastIndexOf("\n\n", startIndex = (maxSplit - 1).coerceAtLeast(0)).takeIf { it >= 0 }?.let {
        return it + 2
    }
    text.lastIndexOf('\n', startIndex = (maxSplit - 1).coerceAtLeast(0)).takeIf { it >= 0 }?.let {
        return it + 1
    }
    val punctuationSplit = listOf('。', '！', '？', '.', '!', '?')
        .map { punctuation -> text.lastIndexOf(punctuation, startIndex = (maxSplit - 1).coerceAtLeast(0)) }
        .filter { it >= 0 }
        .maxOrNull()
    if (punctuationSplit != null) return punctuationSplit + 1
    return maxSplit
}
