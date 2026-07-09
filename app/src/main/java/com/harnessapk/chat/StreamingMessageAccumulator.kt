package com.harnessapk.chat

sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
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

fun StreamingMessageSnapshot.legacyVisibleText(): String =
    parts
        .filter { it.type == UiMessagePartType.TEXT }
        .joinToString(separator = "\n\n") { it.content }

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

    fun onEvent(event: StreamEvent, nowMillis: Long): StreamingFlush? {
        if (terminal) return null
        return when (event) {
            is StreamEvent.TextDelta -> appendContent(
                type = UiMessagePartType.TEXT,
                content = event.text,
                metadata = emptyMap(),
                nowMillis = nowMillis,
            )
            is StreamEvent.ReasoningDelta -> appendContent(
                type = UiMessagePartType.REASONING,
                content = event.text,
                metadata = emptyMap(),
                nowMillis = nowMillis,
            )
            is StreamEvent.ToolCallDelta -> appendContent(
                type = UiMessagePartType.TOOL_CALL,
                content = event.argumentsDelta,
                metadata = mapOf(
                    "id" to event.id,
                    "name" to event.name.orEmpty(),
                ),
                nowMillis = nowMillis,
                forceStructureFlush = true,
            )
            is StreamEvent.ToolResult -> appendContent(
                type = UiMessagePartType.TOOL_RESULT,
                content = event.content,
                metadata = mapOf("toolCallId" to event.toolCallId),
                nowMillis = nowMillis,
                forceStructureFlush = true,
            )
            is StreamEvent.SearchResult -> appendContent(
                type = UiMessagePartType.SEARCH_RESULT,
                content = event.snippet,
                metadata = mapOf("title" to event.title, "url" to event.url),
                nowMillis = nowMillis,
                forceStructureFlush = true,
            )
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
        val reason = flushReason(
            active = active,
            structureChanged = structureChanged || forceStructureFlush,
            nowMillis = nowMillis,
        ) ?: return null
        return flush(reason, nowMillis)
    }

    private fun ensureActivePart(type: UiMessagePartType, metadata: Map<String, String>): Boolean {
        val active = parts.lastOrNull()
        if (active != null && active.type == type && active.canMerge(metadata)) return false
        active?.stable = true
        parts += MutablePart(
            index = parts.size,
            type = type,
            metadata = metadata.filterValues { it.isNotBlank() },
            nextSizeFlushAt = maxBufferedChars.coerceAtLeast(1),
        )
        return active != null
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
    }
}
