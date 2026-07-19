package com.harnessapk.ui.chat

import com.harnessapk.chat.ChatMessage

internal sealed interface PersistedMessagesState {
    data object Loading : PersistedMessagesState

    data class Loaded(
        val messages: List<ChatMessage>,
    ) : PersistedMessagesState
}

internal fun PersistedMessagesState.messagesOrEmpty(): List<ChatMessage> = when (this) {
    PersistedMessagesState.Loading -> emptyList()
    is PersistedMessagesState.Loaded -> messages
}

internal fun PersistedMessagesState.isLoadedEmpty(): Boolean =
    this is PersistedMessagesState.Loaded && messages.isEmpty()
