package com.harnessapk.agentmemory

import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus

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

data class AgentMemoryExtractionInput(
    val agentId: String,
    val conversationId: String,
    val projectId: String?,
    val conversationSummary: String,
    val recentMessages: List<AgentMemoryMessageSnapshot>,
    val projectFacts: List<String>,
    val generationTarget: AgentMemoryGenerationTarget? = null,
)

data class AgentMemoryGenerationTarget(
    val providerId: String?,
    val model: String?,
)

data class AgentMemoryMessageSnapshot(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val status: MessageStatus,
    val content: String,
    val order: Long,
)

enum class AgentMemoryPolicyStatus {
    COMPLETED,
    INVALID_INPUT,
    RESOURCE_LIMIT_EXCEEDED,
}

enum class AgentMemoryExtractionFailureCategory {
    SNAPSHOT,
    PROJECT_CONTEXT,
    PROVIDER,
    NETWORK,
    OUTPUT_LIMIT,
    INVALID_RESPONSE,
    POLICY,
    STORAGE,
    UNKNOWN,
}

enum class AgentMemoryExtractionSkipReason {
    CONVERSATION_MISSING,
    ARCHIVED,
    NO_AGENT,
    NO_COMPLETED_ASSISTANT,
    NO_USER_MESSAGES,
    IDENTITY_CHANGED,
}

sealed interface AgentMemoryExtractionResult {
    data class Succeeded(
        val savedCount: Int,
        val ignoredCount: Int,
    ) : AgentMemoryExtractionResult

    data class Skipped(
        val reason: AgentMemoryExtractionSkipReason,
    ) : AgentMemoryExtractionResult

    data class Failed(
        val category: AgentMemoryExtractionFailureCategory,
    ) : AgentMemoryExtractionResult
}

class AgentMemoryPolicyResult internal constructor(
    val status: AgentMemoryPolicyStatus,
    val accepted: List<AgentMemoryCandidate>,
    val rejectedCount: Int,
    val reason: String,
    internal val acceptedBatch: AgentMemoryPolicy.AcceptedBatch?,
)

class AgentMemoryValidationException(message: String) : IllegalArgumentException(message)

class AgentMemoryDataException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
