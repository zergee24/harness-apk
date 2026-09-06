package com.harnessapk.chat

import android.content.Context
import android.net.Uri
import com.harnessapk.wiki.encodeWikiScopeSnapshot
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The immutable information needed to explain and recover one send attempt.
 *
 * The request context is deliberately kept as the canonical encoded context. This
 * lets additions such as typed document presentation metadata survive recovery
 * without making this journal another copy of the execution protocol. The
 * encoder does not include provider credentials or web-search keys.
 */
data class ChatSendIntent(
    val conversationId: String,
    val requestId: String,
    val originalText: String,
    val originalAttachments: List<ChatSendAttachmentSnapshot> = emptyList(),
    val providerId: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val requestContextJson: String = "{}",
    val contextSnapshotDraftJson: String? = null,
    val isFirstUserMessage: Boolean = false,
    val createdAtMillis: Long = 0L,
) {
    companion object
}

/**
 * A small, protocol-independent attachment snapshot. Documents are represented
 * here even though the legacy enqueue request carries only image URIs; their
 * typed presentation data is also retained in [ChatSendIntent.requestContextJson].
 */
data class ChatSendAttachmentSnapshot(
    val id: String,
    val kind: String,
    val uri: String,
    val mimeType: String,
    val displayName: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val extractedText: String? = null,
    val truncated: Boolean = false,
    val originalCharCount: Int? = null,
)

/** Durable journal boundary for pending sends. Implementations must replace atomically. */
interface ChatSendRecoveryPersistence {
    fun load(): List<ChatSendRecoveryRecord>

    fun save(records: List<ChatSendRecoveryRecord>)
}

/** A durable representation that excludes Throwable instances and transient coroutine state. */
data class ChatSendRecoveryRecord(
    val intent: ChatSendIntent,
    val submittedText: String = intent.originalText,
    val phase: ChatSendRequestPhase,
    val currentDraftText: String,
    val currentDraftAttachments: List<ChatSendAttachmentSnapshot> = emptyList(),
    val originalFailureMessage: String? = null,
    val cancellationMessage: String? = null,
    val lookupFailureMessage: String? = null,
    val automaticRecheckAttempts: Int = 0,
    val consumed: Boolean = false,
    val updatedAtMillis: Long = 0L,
) {
    fun toState(): ChatSendRequestState {
        val restoredCurrentSnapshots = currentDraftAttachments.ifEmpty { intent.originalAttachments }
        return ChatSendRequestState(
            requestId = intent.requestId,
            submittedText = submittedText,
            submittedAttachments = intent.originalAttachments
                .filter { it.kind == ChatSendAttachmentKind.IMAGE }
                .map { PendingImageAttachment(Uri.parse(it.uri), it.mimeType) },
            isFirstUserMessage = intent.isFirstUserMessage,
            currentDraftText = currentDraftText,
            currentDraftAttachments = restoredCurrentSnapshots
                .filter { it.kind == ChatSendAttachmentKind.IMAGE }
                .map { PendingImageAttachment(Uri.parse(it.uri), it.mimeType) },
            phase = phase,
            originalFailure = originalFailureMessage?.let(::IllegalStateException),
            cancellation = cancellationMessage?.let(::CancellationException),
            lookupFailure = lookupFailureMessage?.let(::IllegalStateException),
            intent = intent,
            currentDraftAttachmentSnapshots = restoredCurrentSnapshots,
            automaticRecheckAttempts = automaticRecheckAttempts,
            conversationId = intent.conversationId,
        )
    }

    companion object {
        fun fromState(
            state: ChatSendRequestState,
            consumed: Boolean = false,
            updatedAtMillis: Long = System.currentTimeMillis(),
        ): ChatSendRecoveryRecord {
            val intent = state.intent ?: ChatSendIntent.legacy(state)
            val explicitCurrentSnapshots = state.currentDraftAttachmentSnapshots
            val currentDocuments = if (explicitCurrentSnapshots.isNotEmpty()) {
                explicitCurrentSnapshots.filter { it.kind != ChatSendAttachmentKind.IMAGE }
            } else {
                intent.originalAttachments.filter { it.kind != ChatSendAttachmentKind.IMAGE }
            }
            val currentImages = explicitCurrentSnapshots
                .filter { it.kind == ChatSendAttachmentKind.IMAGE }
                .ifEmpty {
                    state.currentDraftAttachments.mapIndexed { index, attachment ->
                        ChatSendAttachmentSnapshot(
                            id = "image-$index-${attachment.uri}",
                            kind = ChatSendAttachmentKind.IMAGE,
                            uri = attachment.uri.toString(),
                            mimeType = attachment.mimeType,
                        )
                    }
                }
                .ifEmpty { intent.originalAttachments.filter { it.kind == ChatSendAttachmentKind.IMAGE } }
            val currentSnapshots = currentDocuments + currentImages
            return ChatSendRecoveryRecord(
                intent = intent,
                submittedText = state.submittedText,
                phase = state.phase,
                currentDraftText = state.currentDraftText,
                currentDraftAttachments = currentSnapshots,
                originalFailureMessage = state.originalFailure?.message,
                cancellationMessage = state.cancellation?.message,
                lookupFailureMessage = state.lookupFailure?.message,
                automaticRecheckAttempts = state.automaticRecheckAttempts,
                consumed = consumed,
                updatedAtMillis = updatedAtMillis,
            )
        }
    }
}

object ChatSendAttachmentKind {
    const val IMAGE = "IMAGE"
    const val DOCUMENT = "DOCUMENT"
}

fun ChatSendIntent.Companion.from(
    state: ChatSendRequestState,
    request: EnqueueChatRequest,
    createdAtMillis: Long = System.currentTimeMillis(),
): ChatSendIntent {
    val imageSnapshots = state.currentDraftAttachmentSnapshots
        .filter { it.kind == ChatSendAttachmentKind.IMAGE }
        .ifEmpty {
            state.submittedAttachments.mapIndexed { index, attachment ->
                ChatSendAttachmentSnapshot(
                    id = "image-$index-${attachment.uri}",
                    kind = ChatSendAttachmentKind.IMAGE,
                    uri = attachment.uri.toString(),
                    mimeType = attachment.mimeType,
                )
            }
        }
    val contextDocumentSnapshots = request.requestContext.documents.map { document ->
        ChatSendAttachmentSnapshot(
            id = document.id,
            kind = ChatSendAttachmentKind.DOCUMENT,
            uri = document.uri,
            mimeType = document.mimeType,
            displayName = document.fileName,
            sizeBytes = document.sizeBytes,
            sha256 = document.sha256,
            extractedText = document.extractedText,
            truncated = document.truncated,
        )
    }
    val stateDocumentSnapshots = state.currentDraftAttachmentSnapshots
        .filter { it.kind == ChatSendAttachmentKind.DOCUMENT }
    val documentSnapshots = (stateDocumentSnapshots + contextDocumentSnapshots)
        .distinctBy { it.kind to it.id }
    return ChatSendIntent(
        conversationId = request.conversationId,
        requestId = request.requestId,
        originalText = request.requestContext.userInputText ?: state.submittedText,
        originalAttachments = imageSnapshots + documentSnapshots,
        providerId = request.providerId,
        model = request.model,
        reasoningEffort = request.reasoningEffort.name,
        requestContextJson = encodeExecutionRequestContext(request.requestContext),
        contextSnapshotDraftJson = request.contextSnapshotDraft?.let(::encodeContextSnapshotDraft),
        isFirstUserMessage = state.isFirstUserMessage,
        createdAtMillis = createdAtMillis,
    )
}

internal fun ChatSendIntent.Companion.legacy(state: ChatSendRequestState): ChatSendIntent = ChatSendIntent(
    conversationId = "",
    requestId = state.requestId,
    originalText = state.submittedText,
    originalAttachments = state.currentDraftAttachmentSnapshots
        .let { explicit ->
            val images = explicit.filter { it.kind == ChatSendAttachmentKind.IMAGE }
                .ifEmpty {
                    state.submittedAttachments.mapIndexed { index, attachment ->
                        ChatSendAttachmentSnapshot(
                            id = "image-$index-${attachment.uri}",
                            kind = ChatSendAttachmentKind.IMAGE,
                            uri = attachment.uri.toString(),
                            mimeType = attachment.mimeType,
                        )
                    }
                }
            explicit.filter { it.kind != ChatSendAttachmentKind.IMAGE } + images
        },
    isFirstUserMessage = state.isFirstUserMessage,
)

private fun encodeContextSnapshotDraft(snapshot: ContextSnapshotDraftV2): String = buildJsonObject {
    snapshot.projectId?.let { put("projectId", it) }
    snapshot.projectName?.let { put("projectName", it) }
    snapshot.projectContextSha256?.let { put("projectContextSha256", it) }
    snapshot.agentId?.let { put("agentId", it) }
    snapshot.agentVersion?.let { put("agentVersion", it) }
    put("wikiScope", Json.parseToJsonElement(encodeWikiScopeSnapshot(snapshot.wikiScope)))
    put("providerId", snapshot.providerId)
    put("model", snapshot.model)
    put("reasoningEffort", snapshot.reasoningEffort.name)
    put("webSearchEnabled", snapshot.webSearchEnabled)
    put("capturedAt", snapshot.capturedAt)
}.toString()

/** File-backed production persistence. The temporary file is fsynced before atomic replace. */
class FileChatSendRecoveryPersistence private constructor(
    private val file: File,
) : ChatSendRecoveryPersistence {
    constructor(context: Context, fileName: String = DEFAULT_FILE_NAME) : this(
        File(context.applicationContext.filesDir, fileName),
    )

    internal constructor(file: File, allowTestConstruction: Boolean = true) : this(file)

    private val lock = Any()

    override fun load(): List<ChatSendRecoveryRecord> = synchronized(lock) {
        if (!file.exists()) return@synchronized emptyList()
        val raw = file.readText(Charsets.UTF_8)
        decodeRecords(raw)
    }

    override fun save(records: List<ChatSendRecoveryRecord>) = synchronized(lock) {
        val parent = requireNotNull(file.parentFile) { "发送恢复文件缺少父目录" }
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IllegalStateException("无法创建发送恢复目录")
        }
        val temporary = File(parent, "${file.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encodeRecords(records).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: FileAlreadyExistsException) {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        Unit
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "chat-send-recovery.json"

        fun encodeRecords(records: List<ChatSendRecoveryRecord>): String = buildJsonObject {
            put("schemaVersion", 1)
            put("records", buildJsonArray { records.forEach { add(it.toJson()) } })
        }.toString()

        fun decodeRecords(raw: String): List<ChatSendRecoveryRecord> =
            runCatching {
                val root = Json.parseToJsonElement(raw).jsonObject
                val records = root["records"]?.jsonArray
                    ?: throw IllegalArgumentException("恢复记录缺少 records")
                records.map { element -> element.jsonObject.toRecord() }
            }.getOrElse { throw IllegalStateException("发送恢复记录损坏", it) }

        fun ChatSendRecoveryRecord.toJson(): JsonObject = buildJsonObject {
            put("intent", intent.toJson())
            put("submittedText", submittedText)
            put("phase", phase.name)
            put("currentDraftText", currentDraftText)
            put("currentDraftAttachments", currentDraftAttachments.toJson())
            originalFailureMessage?.let { put("originalFailureMessage", it) }
            cancellationMessage?.let { put("cancellationMessage", it) }
            lookupFailureMessage?.let { put("lookupFailureMessage", it) }
            put("automaticRecheckAttempts", automaticRecheckAttempts)
            put("consumed", consumed)
            put("updatedAtMillis", updatedAtMillis)
        }

        fun ChatSendIntent.toJson(): JsonObject = buildJsonObject {
            put("conversationId", conversationId)
            put("requestId", requestId)
            put("originalText", originalText)
            put("originalAttachments", originalAttachments.toJson())
            providerId?.let { put("providerId", it) }
            model?.let { put("model", it) }
            reasoningEffort?.let { put("reasoningEffort", it) }
            put("requestContext", parseJsonOrString(requestContextJson))
            contextSnapshotDraftJson?.let { put("contextSnapshotDraft", parseJsonOrString(it)) }
            put("isFirstUserMessage", isFirstUserMessage)
            put("createdAtMillis", createdAtMillis)
        }

        fun List<ChatSendAttachmentSnapshot>.toJson(): JsonArray = buildJsonArray {
            forEach { attachment ->
                add(buildJsonObject {
                    put("id", attachment.id)
                    put("kind", attachment.kind)
                    put("uri", attachment.uri)
                    put("mimeType", attachment.mimeType)
                    attachment.displayName?.let { put("displayName", it) }
                    attachment.sizeBytes?.let { put("sizeBytes", it) }
                    attachment.sha256?.let { put("sha256", it) }
                    attachment.extractedText?.let { put("extractedText", it) }
                    put("truncated", attachment.truncated)
                    attachment.originalCharCount?.let { put("originalCharCount", it) }
                })
            }
        }

        fun JsonObject.toRecord(): ChatSendRecoveryRecord {
            val intent = requireNotNull(this["intent"]?.jsonObject?.toIntent())
            val phase = runCatching {
                ChatSendRequestPhase.valueOf(requiredString("phase"))
            }.getOrElse { throw IllegalArgumentException("发送恢复阶段无效") }
            return ChatSendRecoveryRecord(
                intent = intent,
                submittedText = string("submittedText") ?: intent.originalText,
                phase = phase,
                currentDraftText = string("currentDraftText").orEmpty(),
                currentDraftAttachments = this["currentDraftAttachments"]?.jsonArray
                    .orEmpty().mapNotNull { it.jsonObject.toAttachmentOrNull() },
                originalFailureMessage = string("originalFailureMessage"),
                cancellationMessage = string("cancellationMessage"),
                lookupFailureMessage = string("lookupFailureMessage"),
                automaticRecheckAttempts = string("automaticRecheckAttempts")?.toIntOrNull() ?: 0,
                consumed = this["consumed"]?.jsonPrimitive?.booleanOrNull ?: false,
                updatedAtMillis = string("updatedAtMillis")?.toLongOrNull() ?: 0L,
            )
        }

        fun JsonObject.toIntent(): ChatSendIntent? {
            val requestId = string("requestId")?.takeIf(String::isNotBlank) ?: return null
            val conversationId = string("conversationId") ?: ""
            val requestContext = this["requestContext"]?.asRawJsonString() ?: "{}"
            return ChatSendIntent(
                conversationId = conversationId,
                requestId = requestId,
                originalText = string("originalText").orEmpty(),
                originalAttachments = this["originalAttachments"]?.jsonArray
                    .orEmpty().mapNotNull { it.jsonObject.toAttachmentOrNull() },
                providerId = string("providerId"),
                model = string("model"),
                reasoningEffort = string("reasoningEffort"),
                requestContextJson = requestContext,
                contextSnapshotDraftJson = this["contextSnapshotDraft"]?.asRawJsonString(),
                isFirstUserMessage = this["isFirstUserMessage"]?.jsonPrimitive?.booleanOrNull ?: false,
                createdAtMillis = string("createdAtMillis")?.toLongOrNull() ?: 0L,
            )
        }

        fun JsonObject.toAttachmentOrNull(): ChatSendAttachmentSnapshot? {
            val id = string("id")?.takeIf(String::isNotBlank) ?: return null
            return ChatSendAttachmentSnapshot(
                id = id,
                kind = string("kind").orEmpty().ifBlank { ChatSendAttachmentKind.IMAGE },
                uri = string("uri").orEmpty(),
                mimeType = string("mimeType").orEmpty(),
                displayName = string("displayName"),
                sizeBytes = string("sizeBytes")?.toLongOrNull(),
                sha256 = string("sha256"),
                extractedText = string("extractedText"),
                truncated = this["truncated"]?.jsonPrimitive?.booleanOrNull ?: false,
                originalCharCount = string("originalCharCount")?.toIntOrNull(),
            )
        }

        fun JsonObject.requiredString(key: String): String =
            string(key)?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("缺少 $key")

        fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

        fun JsonElement.asRawJsonString(): String =
            if (this is JsonPrimitive && isString) content else toString()

        fun parseJsonOrString(raw: String): JsonElement =
            runCatching { Json.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
    }
}
