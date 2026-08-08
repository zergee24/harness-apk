package com.harnessapk.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

enum class SpeechRecognitionBackend {
    SPEECH_RECOGNIZER,
    RECOGNIZER_INTENT,
    UNAVAILABLE,
}

fun chooseSpeechRecognitionBackend(
    recognizerAvailable: Boolean,
    intentAvailable: Boolean,
): SpeechRecognitionBackend = when {
    recognizerAvailable -> SpeechRecognitionBackend.SPEECH_RECOGNIZER
    intentAvailable -> SpeechRecognitionBackend.RECOGNIZER_INTENT
    else -> SpeechRecognitionBackend.UNAVAILABLE
}

fun systemSpeechRecognitionIntent(language: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    language.takeIf { it.isNotBlank() && it != "system" }?.let {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, it)
    }
}

fun shouldPreservePartialForSpeechError(error: Int): Boolean = error in setOf(
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    SpeechRecognizer.ERROR_SERVER,
)

fun shouldFallbackToRecognizerIntent(
    error: Int,
    recognitionActive: Boolean,
    hasPartialResult: Boolean,
    intentAvailable: Boolean,
): Boolean = recognitionActive && intentAvailable && !hasPartialResult && error in setOf(
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    SpeechRecognizer.ERROR_SERVER,
)

fun speechRecognitionErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "未获得麦克风权限"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音识别超时，可继续说"
    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音，可继续说"
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    SpeechRecognizer.ERROR_SERVER,
    -> "系统语音识别暂时不可用"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "系统语音识别正忙，请稍后重试"
    else -> "系统语音识别失败，请重试"
}

class SystemSpeechRecognizer(
    context: Context,
    private val onEvent: (VoiceInputEvent) -> Unit,
    private val onFallbackRequired: (language: String) -> Unit = {},
) : RecognitionListener {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var activeLanguage = "system"
    private var recognitionActive = false
    private var hasPartialResult = false

    fun backend(): SpeechRecognitionBackend = chooseSpeechRecognitionBackend(
        recognizerAvailable = SpeechRecognizer.isRecognitionAvailable(appContext),
        intentAvailable = systemSpeechRecognitionIntent("system").resolveActivity(appContext.packageManager) != null,
    )

    fun start(language: String): SpeechRecognitionBackend {
        val backend = backend()
        if (backend == SpeechRecognitionBackend.SPEECH_RECOGNIZER) {
            activeLanguage = language
            recognitionActive = true
            hasPartialResult = false
            val engine = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(this)
                recognizer = it
            }
            engine.startListening(systemSpeechRecognitionIntent(language))
        }
        return backend
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun cancel() {
        recognitionActive = false
        recognizer?.cancel()
    }

    fun destroy() {
        recognitionActive = false
        recognizer?.destroy()
        recognizer = null
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults.firstRecognitionResult()?.let {
            hasPartialResult = true
            onEvent(VoiceInputEvent.PartialResult(it))
        }
    }

    override fun onResults(results: Bundle?) {
        recognitionActive = false
        onEvent(VoiceInputEvent.FinalResult(results.firstRecognitionResult().orEmpty()))
    }

    override fun onError(error: Int) {
        if (!recognitionActive) return
        if (
            shouldFallbackToRecognizerIntent(
                error = error,
                recognitionActive = recognitionActive,
                hasPartialResult = hasPartialResult,
                intentAvailable = systemSpeechRecognitionIntent(activeLanguage)
                    .resolveActivity(appContext.packageManager) != null,
            )
        ) {
            recognitionActive = false
            onFallbackRequired(activeLanguage)
            return
        }
        recognitionActive = false
        onEvent(
            VoiceInputEvent.Failed(
                message = speechRecognitionErrorMessage(error),
                preservePartial = shouldPreservePartialForSpeechError(error),
            ),
        )
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}

private fun Bundle?.firstRecognitionResult(): String? =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.trim()
        ?.takeIf(String::isNotBlank)
