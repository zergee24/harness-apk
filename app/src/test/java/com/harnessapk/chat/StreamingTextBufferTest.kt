package com.harnessapk.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingTextBufferTest {
    @Test
    fun buffersSmallFrequentDeltasUntilFlushInterval() {
        val buffer = StreamingTextBuffer(flushIntervalMillis = 250L, maxBufferedChars = 100)

        assertNull(buffer.append("一", nowMillis = 0L))
        assertNull(buffer.append("二", nowMillis = 100L))
        assertEquals("一二三", buffer.append("三", nowMillis = 250L))
        assertNull(buffer.drain())
    }

    @Test
    fun flushesImmediatelyWhenBufferGetsLarge() {
        val buffer = StreamingTextBuffer(flushIntervalMillis = 250L, maxBufferedChars = 4)

        assertNull(buffer.append("ab", nowMillis = 0L))
        assertEquals("abcd", buffer.append("cd", nowMillis = 10L))
    }

    @Test
    fun drainFlushesRemainingTextAtStreamEnd() {
        val buffer = StreamingTextBuffer(flushIntervalMillis = 250L, maxBufferedChars = 100)

        assertNull(buffer.append("最后", nowMillis = 0L))

        assertEquals("最后", buffer.drain())
        assertNull(buffer.drain())
    }
}
