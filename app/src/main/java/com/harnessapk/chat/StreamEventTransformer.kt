package com.harnessapk.chat

interface StreamEventTransformer {
    fun transform(event: StreamEvent): List<StreamEvent>
}

class StreamEventTransformerPipeline(
    private val transformers: List<StreamEventTransformer>,
) {
    fun transform(event: StreamEvent): List<StreamEvent> =
        transformers.fold(listOf(event)) { events, transformer ->
            events.flatMap(transformer::transform)
        }
}

class ThinkTagStreamTransformer : StreamEventTransformer {
    private val buffer = StringBuilder()
    private var mode = Mode.TEXT

    override fun transform(event: StreamEvent): List<StreamEvent> {
        if (event !is StreamEvent.TextDelta) return flushPendingForTerminal(event)
        buffer.append(event.text)
        return drainBuffer()
    }

    private fun flushPendingForTerminal(event: StreamEvent): List<StreamEvent> {
        if (event !is StreamEvent.Finished || buffer.isEmpty()) return listOf(event)
        val pending = buffer.toString()
        buffer.clear()
        return when (mode) {
            Mode.TEXT -> listOf(StreamEvent.TextDelta(pending), event)
            Mode.THINK -> listOf(StreamEvent.ReasoningDelta(pending), event)
        }
    }

    private fun drainBuffer(): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        while (buffer.isNotEmpty()) {
            val previousLength = buffer.length
            val previousMode = mode
            when (mode) {
                Mode.TEXT -> drainText(events)
                Mode.THINK -> drainThink(events)
            }
            if (previousLength == buffer.length && previousMode == mode) break
        }
        return events
    }

    private fun drainText(events: MutableList<StreamEvent>) {
        val start = buffer.indexOf(OPEN_TAG)
        if (start >= 0) {
            buffer.takePrefix(start).takeIf { it.isNotEmpty() }?.let {
                events += StreamEvent.TextDelta(it)
            }
            buffer.delete(0, start + OPEN_TAG.length)
            mode = Mode.THINK
            return
        }
        val safeLength = buffer.safePrefixLengthKeepingPossibleToken(OPEN_TAG)
        if (safeLength > 0) {
            events += StreamEvent.TextDelta(buffer.takePrefix(safeLength))
            buffer.delete(0, safeLength)
        }
    }

    private fun drainThink(events: MutableList<StreamEvent>) {
        val end = buffer.indexOf(CLOSE_TAG)
        if (end >= 0) {
            buffer.takePrefix(end).takeIf { it.isNotEmpty() }?.let {
                events += StreamEvent.ReasoningDelta(it)
            }
            buffer.delete(0, end + CLOSE_TAG.length)
            mode = Mode.TEXT
            return
        }
        val safeLength = buffer.safePrefixLengthKeepingPossibleToken(CLOSE_TAG)
        if (safeLength > 0) {
            events += StreamEvent.ReasoningDelta(buffer.takePrefix(safeLength))
            buffer.delete(0, safeLength)
        }
    }

    private enum class Mode {
        TEXT,
        THINK,
    }

    private companion object {
        const val OPEN_TAG = "<think>"
        const val CLOSE_TAG = "</think>"
    }
}

private fun StringBuilder.indexOf(token: String): Int = toString().indexOf(token)

private fun StringBuilder.takePrefix(length: Int): String = substring(0, length)

private fun StringBuilder.safePrefixLengthKeepingPossibleToken(token: String): Int {
    val text = toString()
    val keepLength = token.indices
        .asSequence()
        .drop(1)
        .filter { prefixLength -> text.endsWith(token.take(prefixLength)) }
        .maxOrNull()
        ?: 0
    return length - keepLength
}
