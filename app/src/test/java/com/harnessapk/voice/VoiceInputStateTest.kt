package com.harnessapk.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputStateTest {
    @Test
    fun partialResultPreviewsWithoutCommittingAndFinalResultCommits() {
        var state = VoiceInputState()
        state = reduceVoiceInputState(state, VoiceInputEvent.StartRequested("已有草稿"))
        assertEquals(VoiceInputPhase.REQUESTING_PERMISSION, state.phase)

        state = reduceVoiceInputState(state, VoiceInputEvent.PermissionGranted)
        assertEquals(VoiceInputPhase.LISTENING, state.phase)

        state = reduceVoiceInputState(state, VoiceInputEvent.PartialResult("语音片段"))
        assertEquals("已有草稿\n语音片段", state.displayText)
        assertNull(state.committedText)

        state = reduceVoiceInputState(state, VoiceInputEvent.StopRequested)
        assertEquals(VoiceInputPhase.FINALIZING, state.phase)

        state = reduceVoiceInputState(state, VoiceInputEvent.FinalResult("最终语音"))
        assertEquals(VoiceInputPhase.IDLE, state.phase)
        assertEquals("已有草稿\n最终语音", state.displayText)
        assertEquals(state.displayText, state.committedText)
        assertFalse(state.incomplete)
    }

    @Test
    fun cancellationRestoresDraftBeforeRecognition() {
        val listening = reduceVoiceInputState(
            reduceVoiceInputState(
                reduceVoiceInputState(VoiceInputState(), VoiceInputEvent.StartRequested("不要丢")),
                VoiceInputEvent.PermissionGranted,
            ),
            VoiceInputEvent.PartialResult("临时内容"),
        )

        val cancelled = reduceVoiceInputState(listening, VoiceInputEvent.CancelRequested)

        assertEquals(VoiceInputPhase.CANCELLED, cancelled.phase)
        assertEquals("不要丢", cancelled.displayText)
        assertNull(cancelled.committedText)
    }

    @Test
    fun recognitionFailureKeepsLastPartialAsEditableIncompleteText() {
        val listening = reduceVoiceInputState(
            reduceVoiceInputState(
                reduceVoiceInputState(VoiceInputState(), VoiceInputEvent.StartRequested("开头")),
                VoiceInputEvent.PermissionGranted,
            ),
            VoiceInputEvent.PartialResult("还能保留"),
        )

        val failed = reduceVoiceInputState(
            listening,
            VoiceInputEvent.Failed("语音识别超时", preservePartial = true),
        )

        assertEquals(VoiceInputPhase.ERROR, failed.phase)
        assertEquals("开头\n还能保留", failed.displayText)
        assertEquals(failed.displayText, failed.committedText)
        assertEquals("语音识别超时", failed.errorMessage)
        assertTrue(failed.incomplete)
    }

    @Test
    fun permissionFailurePreservesOriginalDraftOnly() {
        val requesting = reduceVoiceInputState(
            VoiceInputState(),
            VoiceInputEvent.StartRequested("原草稿"),
        )

        val denied = reduceVoiceInputState(
            requesting,
            VoiceInputEvent.Failed("未获得麦克风权限", preservePartial = false),
        )

        assertEquals(VoiceInputPhase.ERROR, denied.phase)
        assertEquals("原草稿", denied.displayText)
        assertNull(denied.committedText)
    }

    @Test
    fun speechBackendPrefersRecognizerThenSystemActivity() {
        assertEquals(
            SpeechRecognitionBackend.SPEECH_RECOGNIZER,
            chooseSpeechRecognitionBackend(recognizerAvailable = true, intentAvailable = true),
        )
        assertEquals(
            SpeechRecognitionBackend.RECOGNIZER_INTENT,
            chooseSpeechRecognitionBackend(recognizerAvailable = false, intentAvailable = true),
        )
        assertEquals(
            SpeechRecognitionBackend.UNAVAILABLE,
            chooseSpeechRecognitionBackend(recognizerAvailable = false, intentAvailable = false),
        )
    }
}
