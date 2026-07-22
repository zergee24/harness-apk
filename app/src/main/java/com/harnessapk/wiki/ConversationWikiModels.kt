package com.harnessapk.wiki

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class WikiRetrievalRunStatus {
    NO_QUERY,
    NO_HIT,
    HIT,
    FAILED,
}

enum class WikiCitationVerificationState {
    VERIFIED,
    PARTIAL,
    QUOTE_MISMATCH,
    PACKAGE_UNAVAILABLE,
}

data class ConversationWikiMount(
    val conversationId: String,
    val ref: WikiRef,
    val enabled: Boolean,
    val mountedAt: Long,
    val updatedAt: Long,
)

/** Desired persisted scope for one Wiki identity within a conversation. */
data class ConversationWikiMountSelection(
    val ref: WikiRef,
    val enabled: Boolean,
)

data class WikiRetrievalRun(
    val messageId: String,
    val allowedScope: List<WikiRef>,
    val explicitOverrideJson: String? = null,
    val routerVersion: String,
    val retrieverVersion: String,
    val status: WikiRetrievalRunStatus,
    val candidateCount: Int,
    val evidenceCount: Int,
    val elapsedMillis: Long,
    val errorCode: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

data class MessageWikiUsage(
    val messageId: String,
    val ref: WikiRef,
    val scoutRank: Int?,
    val deepHitCount: Int,
    val selectedEvidenceCount: Int,
    val enteredContext: Boolean,
)

data class MessageWikiCitation(
    val id: String,
    val messageId: String,
    val displayOrdinal: Int,
    val ref: WikiRef,
    val wikiTitle: String,
    val documentId: String,
    val sectionId: String,
    val chunkId: String,
    val sourceTitle: String,
    val sectionPath: String,
    val locatorLabel: String,
    val originalTextSnapshot: String,
    val originalTextSha256: String,
    val answerRangesJson: String,
    val verificationState: WikiCitationVerificationState,
    val createdAt: Long,
)

class ConversationWikiException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

fun encodeWikiScopeSnapshot(scope: List<WikiRef>): String = JsonArray(
    canonicalWikiScope(scope).map { ref ->
        JsonObject(
            linkedMapOf(
                "wikiId" to JsonPrimitive(ref.wikiId),
                "version" to JsonPrimitive(ref.version),
            ),
        )
    },
).toString()

fun decodeWikiScopeSnapshot(raw: String): List<WikiRef> {
    val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrElse { error ->
        throw ConversationWikiException("Wiki 范围快照格式无效", error)
    }
    val parsed = array.mapIndexed { index, element ->
        val objectValue = runCatching { element.jsonObject }.getOrElse { error ->
            throw ConversationWikiException("Wiki 范围快照第 ${index + 1} 项无效", error)
        }
        val wikiId = objectValue["wikiId"]?.jsonPrimitive?.contentOrNull
            ?: throw ConversationWikiException("Wiki 范围快照缺少标识")
        val version = objectValue["version"]?.jsonPrimitive?.intOrNull
            ?: throw ConversationWikiException("Wiki 范围快照缺少版本")
        WikiRef(wikiId, version)
    }
    val canonical = canonicalWikiScope(parsed)
    if (canonical.size != parsed.size || canonical != parsed) {
        throw ConversationWikiException("Wiki 范围快照必须按唯一稳定顺序保存")
    }
    return canonical
}

internal fun canonicalWikiScope(scope: List<WikiRef>): List<WikiRef> {
    val canonical = scope.sortedWith(compareBy<WikiRef> { it.wikiId }.thenBy { it.version })
    canonical.forEach(::validateWikiRef)
    if (canonical.zipWithNext().any { (left, right) -> left == right }) {
        throw ConversationWikiException("Wiki 范围不能包含重复的精确版本")
    }
    return canonical
}

internal fun validateWikiRef(ref: WikiRef) {
    if (ref.version <= 0 || !WIKI_ID_PATTERN.matches(ref.wikiId)) {
        throw ConversationWikiException("Wiki 标识或版本无效")
    }
}

internal fun requireIdentifier(value: String, label: String) {
    if (value.isBlank() || value.length > MAX_IDENTIFIER_LENGTH) {
        throw ConversationWikiException("$label 无效")
    }
}

private const val MAX_IDENTIFIER_LENGTH = 256
private val WIKI_ID_PATTERN = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
