package com.harnessapk.voice

enum class VoiceInputPhase {
    IDLE,
    REQUESTING_PERMISSION,
    LISTENING,
    FINALIZING,
    ERROR,
    CANCELLED,
}

data class VoiceInputState(
    val phase: VoiceInputPhase = VoiceInputPhase.IDLE,
    val baseDraft: String = "",
    val partialTranscript: String = "",
    val displayText: String = "",
    val committedText: String? = null,
    val errorMessage: String? = null,
    val incomplete: Boolean = false,
) {
    val active: Boolean
        get() = phase == VoiceInputPhase.REQUESTING_PERMISSION ||
            phase == VoiceInputPhase.LISTENING ||
            phase == VoiceInputPhase.FINALIZING
}

sealed interface VoiceInputEvent {
    data class StartRequested(val currentDraft: String) : VoiceInputEvent
    data object PermissionGranted : VoiceInputEvent
    data class PartialResult(val transcript: String) : VoiceInputEvent
    data object StopRequested : VoiceInputEvent
    data class FinalResult(val transcript: String) : VoiceInputEvent
    data object CancelRequested : VoiceInputEvent
    data class Failed(
        val message: String,
        val preservePartial: Boolean,
    ) : VoiceInputEvent
    data object Consumed : VoiceInputEvent
}

fun reduceVoiceInputState(
    state: VoiceInputState,
    event: VoiceInputEvent,
): VoiceInputState = when (event) {
    is VoiceInputEvent.StartRequested -> VoiceInputState(
        phase = VoiceInputPhase.REQUESTING_PERMISSION,
        baseDraft = event.currentDraft,
        displayText = event.currentDraft,
    )
    VoiceInputEvent.PermissionGranted -> state.copy(
        phase = VoiceInputPhase.LISTENING,
        committedText = null,
        errorMessage = null,
        incomplete = false,
    )
    is VoiceInputEvent.PartialResult -> if (state.phase == VoiceInputPhase.LISTENING) {
        state.copy(
            partialTranscript = event.transcript,
            displayText = mergeTranscriptIntoInput(state.baseDraft, event.transcript),
        )
    } else {
        state
    }
    VoiceInputEvent.StopRequested -> if (state.phase == VoiceInputPhase.LISTENING) {
        state.copy(phase = VoiceInputPhase.FINALIZING)
    } else {
        state
    }
    is VoiceInputEvent.FinalResult -> {
        val transcript = event.transcript.ifBlank { state.partialTranscript }
        val committed = mergeTranscriptIntoInput(state.baseDraft, transcript)
        state.copy(
            phase = VoiceInputPhase.IDLE,
            displayText = committed,
            committedText = committed,
            errorMessage = null,
            incomplete = false,
        )
    }
    VoiceInputEvent.CancelRequested -> state.copy(
        phase = VoiceInputPhase.CANCELLED,
        partialTranscript = "",
        displayText = state.baseDraft,
        committedText = null,
        errorMessage = null,
        incomplete = false,
    )
    is VoiceInputEvent.Failed -> {
        val partial = state.partialTranscript.takeIf { event.preservePartial && it.isNotBlank() }
        val retained = partial?.let { mergeTranscriptIntoInput(state.baseDraft, it) } ?: state.baseDraft
        state.copy(
            phase = VoiceInputPhase.ERROR,
            displayText = retained,
            committedText = partial?.let { retained },
            errorMessage = event.message,
            incomplete = partial != null,
        )
    }
    VoiceInputEvent.Consumed -> VoiceInputState(displayText = state.displayText)
}
