package com.harnessapk.ui.conversation

import com.harnessapk.chat.Conversation

internal fun lifeConversations(conversations: List<Conversation>): List<Conversation> =
    conversations.filter { it.projectId == null }
