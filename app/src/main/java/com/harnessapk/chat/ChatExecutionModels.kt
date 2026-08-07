package com.harnessapk.chat

import com.harnessapk.session.SessionRequestContext
import com.harnessapk.websearch.WebSearchSettings
import com.harnessapk.wiki.WikiRef
import com.harnessapk.wiki.decodeWikiScopeSnapshot
import com.harnessapk.wiki.encodeWikiScopeSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

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

enum class ChatExecutionPhase {
    PREPARING_CONTEXT,
    SEARCHING_WEB,
    RETRIEVING_KNOWLEDGE,
    GENERATING,
    FINALIZING,
}

enum class ChatInterruptionReason {
    NETWORK,
    PROCESS_RESTART,
    SERVICE_TIMEOUT,
}

data class ChatExecutionRequestContext(
    val sessionContext: SessionRequestContext? = null,
    val webSearchEnabled: Boolean = false,
    val webSearchSettings: WebSearchSettings = WebSearchSettings(),
    val wikiScopeSnapshot: List<WikiRef>? = null,
    val contextSnapshot: ContextSnapshotV2? = null,
)

data class AttachmentSnapshot(
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class ContextSnapshotV2(
    val schemaVersion: Int = 2,
    val projectId: String?,
    val projectName: String?,
    val projectContextSha256: String?,
    val agentId: String?,
    val agentVersion: Int?,
    val wikiScope: List<WikiRef>,
    val providerId: String,
    val model: String,
    val reasoningEffort: String,
    val webSearchEnabled: Boolean,
    val attachments: List<AttachmentSnapshot>,
    val capturedAt: Long,
)

data class ContextSnapshotDraftV2(
    val projectId: String?,
    val projectName: String?,
    val projectContextSha256: String?,
    val agentId: String?,
    val agentVersion: Int?,
    val wikiScope: List<WikiRef>,
    val providerId: String,
    val model: String,
    val reasoningEffort: ReasoningEffort,
    val webSearchEnabled: Boolean,
    val capturedAt: Long,
) {
    fun finalize(attachments: List<AttachmentSnapshot>): ContextSnapshotV2 = ContextSnapshotV2(
        projectId = projectId,
        projectName = projectName,
        projectContextSha256 = projectContextSha256,
        agentId = agentId,
        agentVersion = agentVersion,
        wikiScope = wikiScope,
        providerId = providerId,
        model = model,
        reasoningEffort = reasoningEffort.name,
        webSearchEnabled = webSearchEnabled,
        attachments = attachments,
        capturedAt = capturedAt,
    )
}

internal fun projectContextSha256(projectId: String?, projectContext: String): String? =
    projectId?.let {
        MessageDigest.getInstance("SHA-256")
            .digest(projectContext.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

internal suspend fun captureLegacyWikiScopeSnapshot(
    context: ChatExecutionRequestContext,
    currentScope: suspend () -> List<WikiRef>,
): ChatExecutionRequestContext {
    if (context.wikiScopeSnapshot != null) return context
    val canonicalScope = decodeWikiScopeSnapshot(encodeWikiScopeSnapshot(currentScope()))
    return context.copy(wikiScopeSnapshot = canonicalScope)
}

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
    val phase: ChatExecutionPhase? = null,
    val automaticRetryCount: Int = 0,
    val interruptionReason: ChatInterruptionReason? = null,
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
    if (status == ChatExecutionStatus.RUNNING) ChatExecutionStatus.QUEUED else status

internal fun encodeExecutionRequestContext(context: ChatExecutionRequestContext): String = buildJsonObject {
    put("webSearchEnabled", JsonPrimitive(context.webSearchEnabled))
    put("webSearchMaxResults", JsonPrimitive(context.webSearchSettings.maxResults))
    context.wikiScopeSnapshot?.let { scope ->
        put("wikiScopeSnapshot", Json.parseToJsonElement(encodeWikiScopeSnapshot(scope)))
    }
    context.sessionContext?.let { session ->
        put("finalPrompt", JsonPrimitive(session.finalPrompt))
        put("projectName", JsonPrimitive(session.projectName.orEmpty()))
        put("deliverableTitle", JsonPrimitive(session.deliverableTitle.orEmpty()))
        put("projectContext", JsonPrimitive(session.projectContext))
        put("deliverableMarkdown", JsonPrimitive(session.deliverableMarkdown))
    }
    context.contextSnapshot?.let { snapshot ->
        put("schemaVersion", JsonPrimitive(snapshot.schemaVersion))
        put("webSearchEnabled", JsonPrimitive(snapshot.webSearchEnabled))
        put("wikiScopeSnapshot", Json.parseToJsonElement(encodeWikiScopeSnapshot(snapshot.wikiScope)))
        snapshot.projectId?.let { put("projectId", JsonPrimitive(it)) }
        snapshot.projectName?.let { put("snapshotProjectName", JsonPrimitive(it)) }
        snapshot.projectContextSha256?.let { put("projectContextSha256", JsonPrimitive(it)) }
        snapshot.agentId?.let { put("agentId", JsonPrimitive(it)) }
        snapshot.agentVersion?.let { put("agentVersion", JsonPrimitive(it)) }
        put("providerId", JsonPrimitive(snapshot.providerId))
        put("model", JsonPrimitive(snapshot.model))
        put("reasoningEffort", JsonPrimitive(snapshot.reasoningEffort))
        put("capturedAt", JsonPrimitive(snapshot.capturedAt))
        put("attachments", buildJsonArray {
            snapshot.attachments.forEach { attachment ->
                add(buildJsonObject {
                    put("mimeType", JsonPrimitive(attachment.mimeType))
                    put("sizeBytes", JsonPrimitive(attachment.sizeBytes))
                    put("sha256", JsonPrimitive(attachment.sha256))
                })
            }
        })
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
    val wikiScopeSnapshot = root["wikiScopeSnapshot"]?.let { encodedScope ->
        decodeWikiScopeSnapshot(encodedScope.toString())
    }
    val contextSnapshot = if (root.string("schemaVersion")?.toIntOrNull() == 2) {
        ContextSnapshotV2(
            projectId = root.string("projectId"),
            projectName = root.string("snapshotProjectName"),
            projectContextSha256 = root.string("projectContextSha256"),
            agentId = root.string("agentId"),
            agentVersion = root.string("agentVersion")?.toIntOrNull(),
            wikiScope = wikiScopeSnapshot.orEmpty(),
            providerId = root.string("providerId").orEmpty(),
            model = root.string("model").orEmpty(),
            reasoningEffort = root.string("reasoningEffort").orEmpty(),
            webSearchEnabled = root.string("webSearchEnabled")?.toBoolean() ?: false,
            attachments = root["attachments"]?.jsonArray.orEmpty().map { element ->
                val attachment = element.jsonObject
                AttachmentSnapshot(
                    mimeType = attachment.string("mimeType").orEmpty(),
                    sizeBytes = attachment.string("sizeBytes")?.toLongOrNull() ?: 0L,
                    sha256 = attachment.string("sha256").orEmpty(),
                )
            },
            capturedAt = root.string("capturedAt")?.toLongOrNull() ?: 0L,
        )
    } else {
        null
    }
    return ChatExecutionRequestContext(
        sessionContext = sessionContext,
        webSearchEnabled = root.string("webSearchEnabled")?.toBoolean() ?: false,
        webSearchSettings = WebSearchSettings(
            maxResults = root.string("webSearchMaxResults")?.toIntOrNull() ?: WebSearchSettings().maxResults,
        ),
        wikiScopeSnapshot = wikiScopeSnapshot,
        contextSnapshot = contextSnapshot,
    )
}

private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull
