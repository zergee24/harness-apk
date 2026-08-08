package com.harnessapk.voice

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class M4aVoiceRecorderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun initializationRemovesRecordingLeftByADeadProcess() {
        val staleAudio = File(temporaryFolder.root, "stale.m4a").apply {
            writeBytes("partial".encodeToByteArray())
        }

        M4aVoiceRecorder(temporaryFolder.root) { FakeRecorderEngine(writeAudioOnStop = false) }

        assertFalse(staleAudio.exists())
    }

    @Test
    fun stoppedRecordingReturnsPrivateTemporaryAudio() {
        val engine = FakeRecorderEngine(writeAudioOnStop = true)
        val recorder = M4aVoiceRecorder(temporaryFolder.root) { engine }

        recorder.start()
        val audio = recorder.stop()

        assertTrue(engine.started)
        assertTrue(engine.released)
        assertTrue(audio.isFile)
        assertTrue(audio.parentFile?.canonicalPath?.startsWith(temporaryFolder.root.canonicalPath) == true)
    }

    @Test
    fun cancelledRecordingDeletesTemporaryAudio() {
        val engine = FakeRecorderEngine(writeAudioOnStop = false)
        val recorder = M4aVoiceRecorder(temporaryFolder.root) { engine }

        recorder.start()
        val audio = recorder.currentFileForTest()
        audio.writeBytes("partial".encodeToByteArray())
        recorder.cancel()

        assertTrue(engine.released)
        assertFalse(audio.exists())
    }
}

private class FakeRecorderEngine(
    private val writeAudioOnStop: Boolean,
) : VoiceRecorderEngine {
    private lateinit var target: File
    var started = false
    var released = false

    override fun prepare(target: File) {
        this.target = target
    }

    override fun start() {
        started = true
    }

    override fun stop() {
        if (writeAudioOnStop) target.writeBytes("audio".encodeToByteArray())
    }

    override fun release() {
        released = true
    }
}
