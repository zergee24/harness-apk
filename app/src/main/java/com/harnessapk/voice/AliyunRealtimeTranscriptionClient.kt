package com.harnessapk.voice

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

interface AliyunRealtimeTranscriptionListener {
    fun onReady()
    fun onPartialResult(transcript: String)
    fun onFinalResult(transcript: String)
    fun onFailure(error: Throwable)
}

interface AliyunRealtimeTranscriptionSession {
    fun sendAudio(audio: ByteArray): Boolean
    fun finish()
    fun cancel()
}

class AliyunRealtimeTranscriptionClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    fun start(
        request: AliyunRealtimeRequest,
        listener: AliyunRealtimeTranscriptionListener,
    ): AliyunRealtimeTranscriptionSession {
        require(request.apiKey.isNotBlank()) { "阿里云百炼 API Key 不能为空" }
        require(request.model.isNotBlank()) { "阿里云实时语音模型不能为空" }
        require(
            request.webSocketUrl.startsWith("wss://") || request.webSocketUrl.startsWith("ws://localhost"),
        ) { "阿里云实时语音地址必须使用 WSS" }

        val taskId = UUID.randomUUID().toString()
        val assembler = AliyunTranscriptAssembler()
        val cancelled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val webSocket = httpClient.newWebSocket(
            Request.Builder()
                .url(request.webSocketUrl)
                .header("Authorization", "Bearer ${request.apiKey}")
                .header("User-Agent", "harness-apk-android")
                .build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(aliyunRunTaskMessage(request, taskId))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val event = parseAliyunRealtimeEvent(text, json)) {
                        AliyunRealtimeEvent.TaskStarted -> listener.onReady()
                        is AliyunRealtimeEvent.Transcript -> {
                            val transcript = assembler.accept(event.text, event.sentenceEnd)
                            if (transcript.isNotBlank()) listener.onPartialResult(transcript)
                        }
                        AliyunRealtimeEvent.TaskFinished -> if (finished.compareAndSet(false, true)) {
                            listener.onFinalResult(assembler.finalText)
                            webSocket.close(NORMAL_CLOSURE_CODE, null)
                        }
                        is AliyunRealtimeEvent.Failed -> failOnce(
                            listener = listener,
                            finished = finished,
                            error = AliyunRealtimeException(event.message, event.code),
                        )
                        AliyunRealtimeEvent.Heartbeat,
                        AliyunRealtimeEvent.Unknown,
                        -> Unit
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!cancelled.get()) {
                        failOnce(
                            listener = listener,
                            finished = finished,
                            error = AliyunRealtimeException(
                                message = t.message ?: "阿里云实时语音连接失败",
                                code = response?.code?.toString().orEmpty(),
                                statusCode = response?.code,
                            ),
                        )
                    }
                }
            },
        )

        return object : AliyunRealtimeTranscriptionSession {
            override fun sendAudio(audio: ByteArray): Boolean =
                audio.isNotEmpty() && !finished.get() && webSocket.send(audio.toByteString())

            override fun finish() {
                if (!finished.get()) webSocket.send(aliyunFinishTaskMessage(taskId))
            }

            override fun cancel() {
                cancelled.set(true)
                finished.set(true)
                webSocket.cancel()
            }
        }
    }

    private fun failOnce(
        listener: AliyunRealtimeTranscriptionListener,
        finished: AtomicBoolean,
        error: Throwable,
    ) {
        if (finished.compareAndSet(false, true)) listener.onFailure(error)
    }

    private companion object {
        const val NORMAL_CLOSURE_CODE = 1000
    }
}

class AliyunRealtimeException(
    message: String,
    val code: String = "",
    val statusCode: Int? = null,
) : Exception(message)

fun aliyunRealtimeTranscriptionError(error: Throwable): String {
    val failure = error as? AliyunRealtimeException
    return when {
        failure?.statusCode == 401 || failure?.statusCode == 403 ||
            failure?.code.orEmpty().contains("AUTH", ignoreCase = true) ->
            "阿里云百炼 API Key 无效，请重新配置"
        failure?.statusCode == 429 || failure?.code.orEmpty().contains("THROTT", ignoreCase = true) ->
            "阿里云实时语音请求过于频繁，请稍后重试"
        else -> error.message ?: "阿里云实时语音识别失败，请重试"
    }
}
