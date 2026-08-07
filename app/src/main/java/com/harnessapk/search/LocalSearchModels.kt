package com.harnessapk.search

enum class LocalSearchDocumentType {
    CONVERSATION,
    MESSAGE,
    MESSAGE_SOURCE,
    PROJECT_NAME,
}

data class LocalSearchResult(
    val id: String,
    val type: LocalSearchDocumentType,
    val title: String,
    val snippet: String,
    val conversationId: String?,
    val messageId: String?,
    val projectId: String?,
    val updatedAt: Long,
)

sealed interface LocalSearchTarget {
    data class ConversationMessage(val conversationId: String, val messageId: String) : LocalSearchTarget
    data class Conversation(val conversationId: String) : LocalSearchTarget
    data class Project(val projectId: String) : LocalSearchTarget
}

fun LocalSearchResult.target(): LocalSearchTarget? = when {
    conversationId != null && messageId != null -> LocalSearchTarget.ConversationMessage(conversationId, messageId)
    conversationId != null -> LocalSearchTarget.Conversation(conversationId)
    projectId != null -> LocalSearchTarget.Project(projectId)
    else -> null
}
