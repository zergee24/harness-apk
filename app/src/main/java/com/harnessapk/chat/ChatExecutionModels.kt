package com.harnessapk.chat

import com.harnessapk.session.SessionRequestContext
import com.harnessapk.websearch.WebSearchSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ChatExecutionType {
    NORMAL,
    STEER_CURRENT,
}

enum class ChatExecutionStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    STEERED,
}

data class ChatExecutionRequestContext(
    val sessionContext: SessionRequestContext? = null,
    val webSearchEnabled: Boolean = false,
    val webSearchSettings: WebSearchSettings = WebSearchSettings(),
)

data class ChatExecutionEntry(
    val id: String,
    val conversationId: String,
    val userMessageId: String,
    val assistantMessageId: String?,
    val targetAssistantMessageId: String?,
    val sequence: Long,
    val type: ChatExecutionType,
    val status: ChatExecutionStatus,
    val providerId: String?,
    val model: String?,
    val reasoningEffort: ReasoningEffort,
    val requestContext: ChatExecutionRequestContext,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

internal fun nextExecutionSequence(entries: List<ChatExecutionEntry>): Long =
    (entries.maxOfOrNull(ChatExecutionEntry::sequence) ?: 0L) + 1L

internal fun executionRequestHistory(
    messages: List<ChatMessage>,
    entries: List<ChatExecutionEntry>,
    currentEntryId: String,
): List<ChatMessage> {
    val queuedUserMessageIds = entries
        .filter { it.status == ChatExecutionStatus.QUEUED && it.id != currentEntryId }
        .mapTo(mutableSetOf(), ChatExecutionEntry::userMessageId)
    return messages.filterNot { message ->
        message.role == MessageRole.USER && message.id in queuedUserMessageIds
    }
}

internal fun executionHistoryWithCurrent(
    history: List<ChatMessage>,
    currentUserMessage: ChatMessage,
): List<ChatMessage> =
    history.filterNot { it.id == currentUserMessage.id } + currentUserMessage

internal fun recoveredExecutionStatus(status: ChatExecutionStatus): ChatExecutionStatus =
    if (status == ChatExecutionStatus.RUNNING) ChatExecutionStatus.INTERRUPTED else status

internal fun encodeExecutionRequestContext(context: ChatExecutionRequestContext): String = buildJsonObject {
    put("webSearchEnabled", JsonPrimitive(context.webSearchEnabled))
    put("webSearchMaxResults", JsonPrimitive(context.webSearchSettings.maxResults))
    context.sessionContext?.let { session ->
        put("finalPrompt", JsonPrimitive(session.finalPrompt))
        put("projectName", JsonPrimitive(session.projectName.orEmpty()))
        put("deliverableTitle", JsonPrimitive(session.deliverableTitle.orEmpty()))
        put("projectContext", JsonPrimitive(session.projectContext))
        put("deliverableMarkdown", JsonPrimitive(session.deliverableMarkdown))
    }
}.toString()

internal fun decodeExecutionRequestContext(raw: String): ChatExecutionRequestContext {
    val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return ChatExecutionRequestContext()
    val finalPrompt = root.string("finalPrompt")
    val sessionContext = finalPrompt?.let {
        SessionRequestContext(
            finalPrompt = it,
            projectName = root.string("projectName").orEmpty().ifBlank { null },
            deliverableTitle = root.string("deliverableTitle").orEmpty().ifBlank { null },
            projectContext = root.string("projectContext").orEmpty(),
            deliverableMarkdown = root.string("deliverableMarkdown").orEmpty(),
        )
    }
    return ChatExecutionRequestContext(
        sessionContext = sessionContext,
        webSearchEnabled = root.string("webSearchEnabled")?.toBoolean() ?: false,
        webSearchSettings = WebSearchSettings(
            maxResults = root.string("webSearchMaxResults")?.toIntOrNull() ?: WebSearchSettings().maxResults,
        ),
    )
}

private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull
