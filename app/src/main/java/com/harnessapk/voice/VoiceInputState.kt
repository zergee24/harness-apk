package com.harnessapk.voice

enum class VoiceInputPhase {
    IDLE,
    REQUESTING_PERMISSION,
    LISTENING,
    FINALIZING,
    REVIEW,
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
    val reviewText: String = "",
    val generation: Long = 0L,
) {
    val active: Boolean
        get() = phase == VoiceInputPhase.REQUESTING_PERMISSION ||
            phase == VoiceInputPhase.LISTENING ||
            phase == VoiceInputPhase.FINALIZING
}

sealed interface VoiceInputEvent {
    data class StartRequested(
        val currentDraft: String,
        val generation: Long = 0L,
    ) : VoiceInputEvent

    data object PermissionGranted : VoiceInputEvent

    data class PartialResult(
        val transcript: String,
        val generation: Long = 0L,
    ) : VoiceInputEvent

    data object StopRequested : VoiceInputEvent

    data class FinalResult(
        val transcript: String,
        val generation: Long = 0L,
    ) : VoiceInputEvent

    data object CancelRequested : VoiceInputEvent

    data class Failed(
        val message: String,
        val preservePartial: Boolean,
        val generation: Long = 0L,
    ) : VoiceInputEvent

    data object ConfirmRequested : VoiceInputEvent

    data class ReviewEdited(val text: String) : VoiceInputEvent

    data object Consumed : VoiceInputEvent
}

internal data class GenerationBoundVoiceInputEvent(
    val generation: Long,
    val event: VoiceInputEvent,
) : VoiceInputEvent

fun reduceVoiceInputState(
    state: VoiceInputState,
    event: VoiceInputEvent,
): VoiceInputState = when (event) {
    is GenerationBoundVoiceInputEvent -> if (event.generation == state.generation) {
        val boundEvent = when (val inner = event.event) {
            is VoiceInputEvent.StartRequested -> inner.copy(generation = event.generation)
            is VoiceInputEvent.PartialResult -> inner.copy(generation = event.generation)
            is VoiceInputEvent.FinalResult -> inner.copy(generation = event.generation)
            is VoiceInputEvent.Failed -> inner.copy(generation = event.generation)
            else -> inner
        }
        reduceVoiceInputState(state, boundEvent)
    } else {
        state
    }

    is VoiceInputEvent.StartRequested -> VoiceInputState(
        phase = VoiceInputPhase.REQUESTING_PERMISSION,
        baseDraft = event.currentDraft,
        displayText = event.currentDraft,
        generation = event.generation,
    )

    VoiceInputEvent.PermissionGranted -> if (state.phase == VoiceInputPhase.REQUESTING_PERMISSION) {
        state.copy(
            phase = VoiceInputPhase.LISTENING,
            partialTranscript = "",
            reviewText = "",
            committedText = null,
            errorMessage = null,
            incomplete = false,
        )
    } else {
        state
    }

    is VoiceInputEvent.PartialResult -> if (
        state.phase == VoiceInputPhase.LISTENING &&
        event.generation == state.generation
    ) {
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
        if (
            (state.phase == VoiceInputPhase.LISTENING || state.phase == VoiceInputPhase.FINALIZING) &&
            event.generation == state.generation
        ) {
            val transcript = event.transcript.ifBlank { state.partialTranscript }.trim()
            state.copy(
                phase = VoiceInputPhase.REVIEW,
                partialTranscript = "",
                displayText = state.baseDraft,
                reviewText = transcript,
                committedText = null,
                errorMessage = null,
                incomplete = false,
            )
        } else {
            state
        }
    }

    VoiceInputEvent.CancelRequested -> if (state.active || state.phase == VoiceInputPhase.REVIEW) {
        state.copy(
            phase = VoiceInputPhase.CANCELLED,
            partialTranscript = "",
            reviewText = "",
            displayText = state.baseDraft,
            committedText = null,
            errorMessage = null,
            incomplete = false,
        )
    } else {
        state
    }

    is VoiceInputEvent.Failed -> {
        if (state.active && event.generation == state.generation) {
            val partial = state.partialTranscript.takeIf { event.preservePartial && it.isNotBlank() }
            val retained = partial?.let { mergeTranscriptIntoInput(state.baseDraft, it) } ?: state.baseDraft
            state.copy(
                phase = VoiceInputPhase.ERROR,
                displayText = retained,
                reviewText = "",
                committedText = partial?.let { retained },
                errorMessage = event.message,
                incomplete = partial != null,
            )
        } else {
            state
        }
    }

    VoiceInputEvent.ConfirmRequested -> if (state.phase == VoiceInputPhase.REVIEW) {
        val committed = mergeTranscriptIntoInput(state.baseDraft, state.reviewText)
        state.copy(
            phase = VoiceInputPhase.IDLE,
            baseDraft = committed,
            partialTranscript = "",
            displayText = committed,
            reviewText = "",
            committedText = committed,
            errorMessage = null,
            incomplete = false,
        )
    } else {
        state
    }

    is VoiceInputEvent.ReviewEdited -> if (state.phase == VoiceInputPhase.REVIEW) {
        state.copy(
            reviewText = event.text,
            partialTranscript = event.text,
        )
    } else {
        state
    }

    VoiceInputEvent.Consumed -> VoiceInputState(displayText = state.displayText)
}
