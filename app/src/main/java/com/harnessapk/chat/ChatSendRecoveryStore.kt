package com.harnessapk.chat

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

data class ChatSendRequestState(
    val requestId: String,
    val submittedText: String,
    val submittedImage: Uri?,
    val submittedMimeType: String,
    val isFirstUserMessage: Boolean,
    val currentDraftText: String = submittedText,
    val currentDraftImage: Uri? = submittedImage,
    val currentDraftMimeType: String = submittedMimeType,
    val phase: ChatSendRequestPhase = ChatSendRequestPhase.IN_FLIGHT,
    val originalFailure: Throwable? = null,
    val cancellation: CancellationException? = null,
    val lookupFailure: Throwable? = null,
)

enum class ChatSendRequestPhase {
    IN_FLIGHT,
    UNKNOWN,
    LANDED,
    NOT_LANDED,
}

class ChatSendRecoveryStore {
    private val lock = Any()
    private val states = MutableStateFlow<Map<String, ChatSendRequestState>>(emptyMap())

    fun current(conversationId: String): ChatSendRequestState? = synchronized(lock) {
        states.value[conversationId]
    }

    fun observe(conversationId: String): Flow<ChatSendRequestState?> =
        states.map { it[conversationId] }

    fun start(conversationId: String, request: ChatSendRequestState): Boolean = synchronized(lock) {
        if (states.value.containsKey(conversationId)) return@synchronized false
        states.value = states.value + (conversationId to request)
        true
    }

    fun markUnknown(
        conversationId: String,
        expectedRequestId: String,
        originalFailure: Throwable,
        cancellation: CancellationException?,
        lookupFailure: Throwable,
    ): Boolean = transitionIfRequest(
        conversationId = conversationId,
        expectedRequestId = expectedRequestId,
        allowedPhases = setOf(ChatSendRequestPhase.IN_FLIGHT),
    ) { current ->
        current.copy(
            phase = ChatSendRequestPhase.UNKNOWN,
            originalFailure = originalFailure,
            cancellation = cancellation,
            lookupFailure = lookupFailure,
        )
    }

    fun markLanded(
        conversationId: String,
        expectedRequestId: String,
        originalFailure: Throwable? = null,
        cancellation: CancellationException? = null,
    ): Boolean = transitionIfRequest(
        conversationId = conversationId,
        expectedRequestId = expectedRequestId,
        allowedPhases = ChatSendRequestPhase.entries.toSet(),
    ) { current ->
        current.copy(
            phase = ChatSendRequestPhase.LANDED,
            originalFailure = originalFailure,
            cancellation = cancellation,
        )
    }

    fun markNotLanded(
        conversationId: String,
        expectedRequestId: String,
        originalFailure: Throwable,
        cancellation: CancellationException?,
    ): Boolean = transitionIfRequest(
        conversationId = conversationId,
        expectedRequestId = expectedRequestId,
        allowedPhases = setOf(ChatSendRequestPhase.IN_FLIGHT, ChatSendRequestPhase.UNKNOWN),
    ) { current ->
        current.copy(
            phase = ChatSendRequestPhase.NOT_LANDED,
            originalFailure = originalFailure,
            cancellation = cancellation,
        )
    }

    fun updateCurrentDraft(
        conversationId: String,
        expectedRequestId: String,
        text: String,
        image: Uri?,
        mimeType: String,
    ): Boolean = transitionIfRequest(
        conversationId = conversationId,
        expectedRequestId = expectedRequestId,
        allowedPhases = ChatSendRequestPhase.entries.toSet(),
    ) { current ->
        current.copy(
            currentDraftText = text,
            currentDraftImage = image,
            currentDraftMimeType = mimeType,
        )
    }

    fun consumeTerminal(conversationId: String, expectedRequestId: String): ChatSendRequestState? = synchronized(lock) {
        val current = states.value[conversationId] ?: return@synchronized null
        if (
            current.requestId != expectedRequestId ||
            current.phase !in setOf(ChatSendRequestPhase.LANDED, ChatSendRequestPhase.NOT_LANDED)
        ) return@synchronized null
        states.value = states.value - conversationId
        current
    }

    private fun transitionIfRequest(
        conversationId: String,
        expectedRequestId: String,
        allowedPhases: Set<ChatSendRequestPhase>,
        update: (ChatSendRequestState) -> ChatSendRequestState,
    ): Boolean = synchronized(lock) {
        val current = states.value[conversationId] ?: return@synchronized false
        if (current.requestId != expectedRequestId || current.phase !in allowedPhases) return@synchronized false
        states.value = states.value + (conversationId to update(current))
        true
    }
}

fun identityLockedForPendingSend(request: ChatSendRequestState?): Boolean =
    request?.isFirstUserMessage == true
