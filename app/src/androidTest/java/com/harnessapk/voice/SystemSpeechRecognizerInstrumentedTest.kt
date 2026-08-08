package com.harnessapk.voice

import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemSpeechRecognizerInstrumentedTest {
    @Test
    fun recognitionIntentRequestsPartialResultsAndSelectedLanguage() {
        val intent = systemSpeechRecognitionIntent("zh-CN")

        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals("zh-CN", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        assertEquals(
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL),
        )
    }

    @Test
    fun timeoutAndNoMatchKeepPartialButPermissionErrorDoesNot() {
        assertTrue(shouldPreservePartialForSpeechError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertTrue(shouldPreservePartialForSpeechError(SpeechRecognizer.ERROR_NO_MATCH))
        assertFalse(shouldPreservePartialForSpeechError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(
            "未获得麦克风权限",
            speechRecognitionErrorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS),
        )
    }

    @Test
    fun serviceFailureBeforeTranscriptionFallsBackToRecognizerIntent() {
        assertTrue(
            shouldFallbackToRecognizerIntent(
                error = SpeechRecognizer.ERROR_NETWORK,
                recognitionActive = true,
                hasPartialResult = false,
                intentAvailable = true,
            ),
        )
        assertTrue(
            shouldFallbackToRecognizerIntent(
                error = SpeechRecognizer.ERROR_SERVER,
                recognitionActive = true,
                hasPartialResult = false,
                intentAvailable = true,
            ),
        )
        assertFalse(
            shouldFallbackToRecognizerIntent(
                error = SpeechRecognizer.ERROR_SERVER,
                recognitionActive = true,
                hasPartialResult = true,
                intentAvailable = true,
            ),
        )
        assertFalse(
            shouldFallbackToRecognizerIntent(
                error = SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                recognitionActive = true,
                hasPartialResult = false,
                intentAvailable = true,
            ),
        )
        assertFalse(
            shouldFallbackToRecognizerIntent(
                error = SpeechRecognizer.ERROR_NETWORK,
                recognitionActive = true,
                hasPartialResult = false,
                intentAvailable = false,
            ),
        )
        assertFalse(
            shouldFallbackToRecognizerIntent(
                error = SpeechRecognizer.ERROR_NETWORK,
                recognitionActive = false,
                hasPartialResult = false,
                intentAvailable = true,
            ),
        )
    }

    @Test
    fun errorCallbackAfterCancellationIsIgnored() {
        val events = mutableListOf<VoiceInputEvent>()
        val recognizer = SystemSpeechRecognizer(
            context = ApplicationProvider.getApplicationContext(),
            onEvent = events::add,
        )

        recognizer.cancel()
        recognizer.onError(SpeechRecognizer.ERROR_CLIENT)

        assertTrue(events.isEmpty())
    }
}
