package com.harnessapk.voice

import com.harnessapk.ui.voice.shouldCancelVoiceOnStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputStateTest {
    @Test
    fun backgroundCancellationOnlyAppliesToActiveVoicePhases() {
        val review = VoiceInputState(
            phase = VoiceInputPhase.REVIEW,
            baseDraft = "原草稿",
            reviewText = "待确认语音",
            generation = 7L,
        )
        assertFalse(
            shouldCancelVoiceOnStop(
                state = review,
                pendingPermissionGeneration = null,
                pendingFallbackGeneration = null,
                activeGeneration = review.generation,
                embeddedRecognizerActive = false,
                activeProviderType = VoiceProviderType.ANDROID_SYSTEM,
            ),
        )

        val listening = review.copy(
            phase = VoiceInputPhase.LISTENING,
            reviewText = "",
        )
        assertTrue(
            shouldCancelVoiceOnStop(
                state = listening,
                pendingPermissionGeneration = null,
                pendingFallbackGeneration = null,
                activeGeneration = listening.generation,
                embeddedRecognizerActive = true,
                activeProviderType = VoiceProviderType.ANDROID_SYSTEM,
            ),
        )

        val finalizing = listening.copy(phase = VoiceInputPhase.FINALIZING)
        assertTrue(
            shouldCancelVoiceOnStop(
                state = finalizing,
                pendingPermissionGeneration = null,
                pendingFallbackGeneration = null,
                activeGeneration = finalizing.generation,
                embeddedRecognizerActive = false,
                activeProviderType = VoiceProviderType.SILICON_FLOW,
            ),
        )
    }

    @Test
    fun backgroundCancellationWaitsForPermissionOrExternalRecognizerActivity() {
        val listening = VoiceInputState(
            phase = VoiceInputPhase.LISTENING,
            generation = 8L,
        )

        assertFalse(
            shouldCancelVoiceOnStop(
                state = listening,
                pendingPermissionGeneration = listening.generation,
                pendingFallbackGeneration = null,
                activeGeneration = listening.generation,
                embeddedRecognizerActive = false,
                activeProviderType = VoiceProviderType.ANDROID_SYSTEM,
            ),
        )
        assertFalse(
            shouldCancelVoiceOnStop(
                state = listening,
                pendingPermissionGeneration = null,
                pendingFallbackGeneration = listening.generation,
                activeGeneration = listening.generation,
                embeddedRecognizerActive = false,
                activeProviderType = VoiceProviderType.ANDROID_SYSTEM,
            ),
        )
    }

    @Test
    fun finalResultEntersReviewAndConfirmCommitsExactlyOnce() {
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
        assertEquals(VoiceInputPhase.REVIEW, state.phase)
        assertEquals("已有草稿", state.displayText)
        assertEquals("最终语音", state.reviewText)
        assertNull(state.committedText)

        val duplicateResult = reduceVoiceInputState(
            state,
            VoiceInputEvent.FinalResult("迟到重复结果"),
        )
        assertEquals(state, duplicateResult)

        state = reduceVoiceInputState(state, VoiceInputEvent.ConfirmRequested)
        assertEquals(VoiceInputPhase.IDLE, state.phase)
        assertEquals("已有草稿\n最终语音", state.displayText)
        assertEquals(state.displayText, state.committedText)
        assertFalse(state.incomplete)

        val confirmedAgain = reduceVoiceInputState(state, VoiceInputEvent.ConfirmRequested)
        assertEquals(state, confirmedAgain)
    }

    @Test
    fun reviewCanBeEditedBeforeConfirming() {
        var state = reduceVoiceInputState(
            reduceVoiceInputState(
                reduceVoiceInputState(VoiceInputState(), VoiceInputEvent.StartRequested("开头")),
                VoiceInputEvent.PermissionGranted,
            ),
            VoiceInputEvent.FinalResult("识别结果"),
        )

        state = reduceVoiceInputState(state, VoiceInputEvent.ReviewEdited("用户改过的结果"))

        assertEquals(VoiceInputPhase.REVIEW, state.phase)
        assertEquals("开头", state.displayText)
        assertEquals("用户改过的结果", state.reviewText)

        state = reduceVoiceInputState(state, VoiceInputEvent.ConfirmRequested)

        assertEquals("开头\n用户改过的结果", state.displayText)
        assertEquals(state.displayText, state.committedText)
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
    fun cancellationFromFinalizingAndReviewRestoresTheSameBaseline() {
        var finalizing = reduceVoiceInputState(
            reduceVoiceInputState(
                reduceVoiceInputState(VoiceInputState(), VoiceInputEvent.StartRequested("原草稿")),
                VoiceInputEvent.PermissionGranted,
            ),
            VoiceInputEvent.StopRequested,
        )
        finalizing = reduceVoiceInputState(finalizing, VoiceInputEvent.CancelRequested)

        assertEquals(VoiceInputPhase.CANCELLED, finalizing.phase)
        assertEquals("原草稿", finalizing.displayText)
        assertEquals("", finalizing.reviewText)

        val lateFinal = reduceVoiceInputState(
            finalizing,
            GenerationBoundVoiceInputEvent(
                finalizing.generation,
                VoiceInputEvent.FinalResult("迟到结果", generation = finalizing.generation),
            ),
        )
        assertEquals(finalizing, lateFinal)

        var review = reduceVoiceInputState(
            reduceVoiceInputState(
                reduceVoiceInputState(VoiceInputState(), VoiceInputEvent.StartRequested("原草稿")),
                VoiceInputEvent.PermissionGranted,
            ),
            VoiceInputEvent.FinalResult("不要使用"),
        )
        review = reduceVoiceInputState(review, VoiceInputEvent.CancelRequested)

        assertEquals(VoiceInputPhase.CANCELLED, review.phase)
        assertEquals("原草稿", review.displayText)
        assertEquals("", review.reviewText)
        assertNull(review.committedText)
    }

    @Test
    fun confirmedDraftBecomesBaselineForTheNextSession() {
        var state = reduceVoiceInputState(
            reduceVoiceInputState(
                reduceVoiceInputState(VoiceInputState(), VoiceInputEvent.StartRequested("第一段")),
                VoiceInputEvent.PermissionGranted,
            ),
            VoiceInputEvent.FinalResult("第二段"),
        )
        state = reduceVoiceInputState(state, VoiceInputEvent.ConfirmRequested)

        state = reduceVoiceInputState(
            state,
            VoiceInputEvent.StartRequested(state.displayText, generation = state.generation + 1),
        )
        state = reduceVoiceInputState(state, VoiceInputEvent.PermissionGranted)
        state = reduceVoiceInputState(
            state,
            VoiceInputEvent.FinalResult("第三段", generation = state.generation),
        )
        state = reduceVoiceInputState(state, VoiceInputEvent.ConfirmRequested)

        assertEquals("第一段\n第二段\n第三段", state.displayText)
    }

    @Test
    fun lateResultFromAnOlderGenerationIsIgnored() {
        var state = reduceVoiceInputState(
            VoiceInputState(),
            VoiceInputEvent.StartRequested("新基线", generation = 12L),
        )
        state = reduceVoiceInputState(state, VoiceInputEvent.PermissionGranted)
        state = reduceVoiceInputState(
            state,
            VoiceInputEvent.StartRequested("更新基线", generation = 13L),
        )

        val stalePermission = reduceVoiceInputState(
            state,
            GenerationBoundVoiceInputEvent(12L, VoiceInputEvent.PermissionGranted),
        )
        assertEquals(state, stalePermission)

        val unchanged = reduceVoiceInputState(
            state,
            VoiceInputEvent.FinalResult("旧 session 结果", generation = 12L),
        )

        assertEquals(state, unchanged)
        assertEquals(13L, unchanged.generation)
        assertEquals(VoiceInputPhase.REQUESTING_PERMISSION, unchanged.phase)
        assertEquals("更新基线", unchanged.displayText)
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
