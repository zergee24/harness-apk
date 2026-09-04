package com.harnessapk.spike.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpikeCanvasLogicTest {

    private val now = 100_000L

    @Test
    fun stylusAlwaysDraws() {
        assertEquals(
            SpikeInputVerdict.DRAW,
            SpikeInputPolicy().decide(SpikeInputPolicy.TOOL_STYLUS, now, now, fingerMode = false, eraserActive = false),
        )
        // 手写笔刚离开也不影响笔自身
        assertEquals(
            SpikeInputVerdict.DRAW,
            SpikeInputPolicy().decide(SpikeInputPolicy.TOOL_STYLUS, now - 20_000, now, fingerMode = false, eraserActive = false),
        )
    }

    @Test
    fun eraserToolAndEraserButtonEnterErase() {
        assertEquals(
            SpikeInputVerdict.ERASE,
            SpikeInputPolicy().decide(SpikeInputPolicy.TOOL_ERASER, now, now, fingerMode = false, eraserActive = false),
        )
        assertEquals(
            SpikeInputVerdict.ERASE,
            SpikeInputPolicy().decide(SpikeInputPolicy.TOOL_STYLUS, now, now, fingerMode = false, eraserActive = true),
        )
    }

    @Test
    fun fingerIsPalmRejectedWhileStylusRecentlySeen() {
        assertEquals(
            SpikeInputVerdict.REJECT_PALM,
            SpikeInputPolicy().decide(SpikeInputPolicy.TOOL_FINGER, now - 1_000, now, fingerMode = false, eraserActive = false),
        )
    }

    @Test
    fun fingerDrawsWithoutStylusOrInFingerMode() {
        assertEquals(
            SpikeInputVerdict.DRAW,
            SpikeInputPolicy().decide(SpikeInputPolicy.TOOL_FINGER, null, now, fingerMode = false, eraserActive = false),
        )
        assertEquals(
            SpikeInputVerdict.DRAW,
            SpikeInputPolicy().decide(SpikeInputPolicy.TOOL_FINGER, now - 20_000, now, fingerMode = false, eraserActive = false),
        )
        assertEquals(
            SpikeInputVerdict.DRAW,
            SpikeInputPolicy().decide(SpikeInputPolicy.TOOL_FINGER, now - 1_000, now, fingerMode = true, eraserActive = false),
        )
    }

    @Test
    fun statsTrackLatencyPercentilesAndCounters() {
        val stats = SpikeStats()
        repeat(100) { index -> stats.addLatency((index + 1).toLong()) }
        stats.addStroke()
        stats.addPoint(0.2f, tilt = false, tool = SpikeInputPolicy.TOOL_STYLUS)
        stats.addPoint(0.9f, tilt = true, tool = SpikeInputPolicy.TOOL_STYLUS)
        stats.addPalmRejected()

        assertEquals(50L, stats.percentile(0.5))
        assertEquals(95L, stats.percentile(0.95))
        assertEquals(1, stats.strokes)
        assertEquals(2, stats.points)
        assertEquals(1, stats.palmRejected)
        assertTrue(stats.tiltSeen)
        assertEquals("0.20 ~ 0.90", stats.pressureRangeText())
    }

    @Test
    fun statsEmptyLatencyReturnsSentinel() {
        assertEquals(-1L, SpikeStats().percentile(0.5))
    }
}
