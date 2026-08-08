package com.harnessapk.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmVoiceRecorderTest {
    @Test
    fun streamsPcmChunksAndReleasesRecorderWhenStopped() {
        val engine = FakePcmRecorderEngine()
        val recorder = PcmVoiceRecorder { engine }
        val received = mutableListOf<ByteArray>()
        val chunkArrived = CountDownLatch(1)

        recorder.start(
            onAudioChunk = {
                received += it
                chunkArrived.countDown()
            },
            onFailure = { throw AssertionError(it) },
        )

        assertTrue(chunkArrived.await(2, TimeUnit.SECONDS))
        recorder.stop()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), received.single())
        assertTrue(engine.started)
        assertTrue(engine.stopped)
        assertTrue(engine.released)
        assertFalse(recorder.active)
    }

    @Test
    fun negativeAudioReadStopsStreamingAndReportsFailure() {
        val engine = FailingPcmRecorderEngine()
        val recorder = PcmVoiceRecorder { engine }
        val failed = CountDownLatch(1)

        recorder.start(
            onAudioChunk = { throw AssertionError("不应产生音频") },
            onFailure = { failed.countDown() },
        )

        assertTrue(failed.await(2, TimeUnit.SECONDS))
        recorder.stop()
        assertTrue(engine.released)
    }
}

private class FakePcmRecorderEngine : PcmRecorderEngine {
    var started = false
    var stopped = false
    var released = false
    private var delivered = false

    override fun start() {
        started = true
    }

    override fun read(buffer: ByteArray): Int {
        if (!delivered) {
            byteArrayOf(1, 2, 3, 4).copyInto(buffer)
            delivered = true
            return 4
        }
        Thread.sleep(10)
        return 0
    }

    override fun stop() {
        stopped = true
    }

    override fun release() {
        released = true
    }
}

private class FailingPcmRecorderEngine : PcmRecorderEngine {
    var released = false

    override fun start() = Unit
    override fun read(buffer: ByteArray): Int = -3
    override fun stop() = Unit
    override fun release() {
        released = true
    }
}
