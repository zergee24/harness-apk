package com.harnessapk.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max

internal interface PcmRecorderEngine {
    fun start()
    fun read(buffer: ByteArray): Int
    fun stop()
    fun release()
}

internal class PcmVoiceRecorder(
    private val engineFactory: () -> PcmRecorderEngine,
) {
    constructor() : this(engineFactory = { AndroidPcmRecorderEngine() })

    @Volatile
    var active: Boolean = false
        private set

    private var engine: PcmRecorderEngine? = null
    private var readerThread: Thread? = null

    @Synchronized
    fun start(
        onAudioChunk: (ByteArray) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        check(!active) { "实时语音录音已开始" }
        val nextEngine = engineFactory()
        runCatching { nextEngine.start() }
            .onFailure {
                nextEngine.release()
                throw it
            }
        engine = nextEngine
        active = true
        readerThread = Thread({
            val buffer = ByteArray(PCM_CHUNK_BYTES)
            try {
                while (active) {
                    val count = nextEngine.read(buffer)
                    when {
                        count > 0 -> onAudioChunk(buffer.copyOf(count))
                        count < 0 -> throw IllegalStateException("实时录音读取失败（$count）")
                        else -> Thread.yield()
                    }
                }
            } catch (error: Throwable) {
                if (active) onFailure(error)
            }
        }, "aliyun-pcm-recorder").apply { start() }
    }

    @Synchronized
    fun stop() {
        val currentEngine = engine ?: return
        active = false
        runCatching { currentEngine.stop() }
        readerThread?.join(1_000)
        currentEngine.release()
        engine = null
        readerThread = null
    }

    fun cancel() = stop()

    private companion object {
        const val PCM_CHUNK_BYTES = 3_200
    }
}

@SuppressLint("MissingPermission")
private class AndroidPcmRecorderEngine : PcmRecorderEngine {
    private val minimumBufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    private val recorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        max(minimumBufferSize, 6_400),
    )

    init {
        check(minimumBufferSize > 0 && recorder.state == AudioRecord.STATE_INITIALIZED) {
            "无法初始化实时语音录音"
        }
    }

    override fun start() = recorder.startRecording()

    override fun read(buffer: ByteArray): Int = recorder.read(buffer, 0, buffer.size)

    override fun stop() {
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
    }

    override fun release() = recorder.release()

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
