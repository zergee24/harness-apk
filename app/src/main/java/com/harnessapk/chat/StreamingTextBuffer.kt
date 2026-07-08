package com.harnessapk.chat

class StreamingTextBuffer(
    private val flushIntervalMillis: Long = DEFAULT_FLUSH_INTERVAL_MILLIS,
    private val maxBufferedChars: Int = DEFAULT_MAX_BUFFERED_CHARS,
) {
    private val pending = StringBuilder()
    private var lastFlushMillis: Long? = null

    fun append(delta: String, nowMillis: Long): String? {
        if (delta.isEmpty()) return null
        pending.append(delta)
        val previousFlush = lastFlushMillis
        val shouldFlushBySize = pending.length >= maxBufferedChars
        val shouldFlushByTime = previousFlush != null && nowMillis - previousFlush >= flushIntervalMillis
        val shouldFlushFirstChunk = previousFlush == null && nowMillis >= flushIntervalMillis
        return if (shouldFlushBySize || shouldFlushByTime || shouldFlushFirstChunk) {
            flush(nowMillis)
        } else {
            null
        }
    }

    fun drain(): String? = flush(nowMillis = null)

    private fun flush(nowMillis: Long?): String? {
        if (pending.isEmpty()) return null
        val text = pending.toString()
        pending.clear()
        lastFlushMillis = nowMillis ?: lastFlushMillis
        return text
    }

    private companion object {
        const val DEFAULT_FLUSH_INTERVAL_MILLIS = 250L
        const val DEFAULT_MAX_BUFFERED_CHARS = 160
    }
}
