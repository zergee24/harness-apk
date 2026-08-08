package com.harnessapk.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.UUID

internal interface VoiceRecorderEngine {
    fun prepare(target: File)
    fun start()
    fun stop()
    fun release()
}

internal class M4aVoiceRecorder(
    private val recordingDirectory: File,
    private val engineFactory: () -> VoiceRecorderEngine,
) {
    constructor(context: Context) : this(
        recordingDirectory = File(context.cacheDir, "voice-input"),
        engineFactory = { AndroidVoiceRecorderEngine(context.applicationContext) },
    )

    init {
        recordingDirectory.listFiles()?.forEach { it.delete() }
    }

    private var engine: VoiceRecorderEngine? = null
    private var recordingFile: File? = null

    fun start() {
        check(engine == null) { "语音录音已开始" }
        recordingDirectory.mkdirs()
        val target = File(recordingDirectory, "${UUID.randomUUID()}.m4a")
        val nextEngine = engineFactory()
        runCatching {
            nextEngine.prepare(target)
            nextEngine.start()
        }.onFailure {
            nextEngine.release()
            target.delete()
        }.getOrThrow()
        recordingFile = target
        engine = nextEngine
    }

    fun stop(): File {
        val activeEngine = engine ?: throw IllegalStateException("语音录音尚未开始")
        val target = recordingFile ?: throw IllegalStateException("语音录音文件不存在")
        engine = null
        recordingFile = null
        runCatching { activeEngine.stop() }
            .onFailure { target.delete() }
            .also { activeEngine.release() }
            .getOrThrow()
        if (!target.isFile || target.length() == 0L) {
            target.delete()
            throw IllegalStateException("录音时间太短，请重试")
        }
        return target
    }

    fun cancel() {
        val activeEngine = engine
        val target = recordingFile
        engine = null
        recordingFile = null
        activeEngine?.release()
        target?.delete()
    }

    internal fun currentFileForTest(): File = checkNotNull(recordingFile)
}

private class AndroidVoiceRecorderEngine(context: Context) : VoiceRecorderEngine {
    @Suppress("DEPRECATION")
    private val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        MediaRecorder()
    }

    override fun prepare(target: File) {
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioSamplingRate(16_000)
        recorder.setAudioEncodingBitRate(64_000)
        recorder.setOutputFile(target.absolutePath)
        recorder.prepare()
    }

    override fun start() = recorder.start()

    override fun stop() = recorder.stop()

    override fun release() = recorder.release()
}
