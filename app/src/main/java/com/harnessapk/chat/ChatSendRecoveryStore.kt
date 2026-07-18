package com.harnessapk.chat

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

data class ChatSendRequestState(
    val requestId: String,
    val submittedText: String,
    val draftImage: Uri?,
    val isFirstUserMessage: Boolean,
    val originalFailure: Throwable? = null,
    val cancellation: CancellationException? = null,
    val lookupFailure: Throwable? = null,
)

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

    fun updateUnknown(
        conversationId: String,
        expectedRequestId: String,
        originalFailure: Throwable,
        cancellation: CancellationException?,
        lookupFailure: Throwable,
    ): Boolean = updateIfRequest(conversationId, expectedRequestId) { current ->
        current.copy(
            originalFailure = originalFailure,
            cancellation = cancellation,
            lookupFailure = lookupFailure,
        )
    }

    fun handoffInFlight(conversationId: String, expectedRequestId: String): Boolean = synchronized(lock) {
        val current = states.value[conversationId] ?: return@synchronized false
        if (current.requestId != expectedRequestId || current.originalFailure != null) return@synchronized false
        states.value = states.value + (
            conversationId to current.copy(
                originalFailure = CancellationException("会话页面已离开，待确认请求是否已落地"),
            )
        )
        true
    }

    fun clearIfRequest(conversationId: String, expectedRequestId: String): Boolean = synchronized(lock) {
        val current = states.value[conversationId] ?: return@synchronized false
        if (current.requestId != expectedRequestId) return@synchronized false
        states.value = states.value - conversationId
        true
    }

    private fun updateIfRequest(
        conversationId: String,
        expectedRequestId: String,
        update: (ChatSendRequestState) -> ChatSendRequestState,
    ): Boolean = synchronized(lock) {
        val current = states.value[conversationId] ?: return@synchronized false
        if (current.requestId != expectedRequestId) return@synchronized false
        states.value = states.value + (conversationId to update(current))
        true
    }
}
