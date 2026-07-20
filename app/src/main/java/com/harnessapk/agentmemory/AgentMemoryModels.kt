package com.harnessapk.agentmemory

internal const val MAX_AGENT_MEMORY_ID_CHARS = 256
internal const val MAX_AGENT_MEMORY_DEDUPE_KEY_CHARS = 256
internal const val MAX_AGENT_MEMORY_CONTENT_CHARS = 2_000
internal const val MAX_AGENT_MEMORY_SOURCE_QUOTE_CHARS = 2_000
internal const val MAX_AGENT_MEMORY_CANDIDATES_PER_MERGE = 64

enum class AgentMemoryKind {
    USER_PREFERENCE,
    ADDRESS_PREFERENCE,
    SHARED_HISTORY,
    RELATIONSHIP_EVENT,
}

data class AgentMemory(
    val id: String,
    val agentId: String,
    val kind: AgentMemoryKind,
    val content: String,
    val sourceConversationId: String,
    val sourceMessageId: String,
    val confidence: Double,
    val userEdited: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class AgentMemoryCandidate(
    val kind: AgentMemoryKind,
    val dedupeKey: String,
    val content: String,
    val sourceMessageId: String,
    val sourceQuote: String,
    val confidence: Double,
)

data class AgentMemoryMergeResult(
    val insertedCount: Int,
    val updatedCount: Int,
    val protectedCount: Int,
    val duplicateCount: Int,
) {
    val savedCount: Int
        get() = insertedCount + updatedCount
}

class AgentMemoryValidationException(message: String) : IllegalArgumentException(message)

class AgentMemoryDataException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
