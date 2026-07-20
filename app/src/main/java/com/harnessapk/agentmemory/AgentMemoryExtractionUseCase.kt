package com.harnessapk.agentmemory

import com.harnessapk.chat.ChatMessage
import com.harnessapk.chat.ChatRepository
import com.harnessapk.chat.Conversation
import com.harnessapk.chat.ConversationMemory
import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal const val MAX_AGENT_MEMORY_EXTRACTION_RECENT_MESSAGES = 20
internal const val MAX_AGENT_MEMORY_EXTRACTION_MESSAGE_CHARS = 6_000
internal const val MAX_AGENT_MEMORY_EXTRACTION_TOTAL_MESSAGE_CHARS = 48_000
private const val MAX_AGENT_MEMORY_EXTRACTION_SUMMARY_CHARS = 8_000
private const val MAX_AGENT_MEMORY_EXTRACTION_PROJECT_FACTS = 128
private const val MAX_AGENT_MEMORY_EXTRACTION_PROJECT_FACT_CHARS = 4_000
internal const val MAX_AGENT_MEMORY_PROJECT_CONTEXT_CHARS = 64_000

interface AgentMemoryExtractionSource {
    suspend fun conversation(conversationId: String): Conversation?

    suspend fun recentSuccessfulMessages(
        conversationId: String,
        limit: Int,
    ): List<ChatMessage>

    suspend fun lastSuccessfulAssistant(conversationId: String): ChatMessage?

    suspend fun memory(conversationId: String): ConversationMemory?
}

fun interface AgentMemoryProjectFactSource {
    suspend fun facts(projectId: String): List<String>
}

fun interface AgentMemoryCandidatePolicy {
    fun evaluate(
        input: AgentMemoryExtractionInput,
        candidates: List<AgentMemoryCandidate>,
    ): AgentMemoryPolicyResult
}

fun interface AgentMemoryAcceptedBatchMerger {
    suspend fun merge(batch: AgentMemoryPolicy.AcceptedBatch): AgentMemoryMergeResult
}

class AgentMemoryExtractionUseCase(
    private val source: AgentMemoryExtractionSource,
    private val projectFactSource: AgentMemoryProjectFactSource,
    private val generator: AgentMemoryCandidateGenerator,
    private val policy: AgentMemoryCandidatePolicy,
    private val merger: AgentMemoryAcceptedBatchMerger,
) {
    suspend fun extract(conversationId: String): AgentMemoryExtractionResult {
        val initialConversation = try {
            source.conversation(conversationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failed(AgentMemoryExtractionFailureCategory.SNAPSHOT)
        } ?: return skipped(AgentMemoryExtractionSkipReason.CONVERSATION_MISSING)

        if (initialConversation.isArchived) return skipped(AgentMemoryExtractionSkipReason.ARCHIVED)
        val agentId = initialConversation.agentId?.trim()?.takeIf(String::isNotEmpty)
            ?: return skipped(AgentMemoryExtractionSkipReason.NO_AGENT)
        val agentVersion = initialConversation.agentVersion?.takeIf { it > 0 }
            ?: return skipped(AgentMemoryExtractionSkipReason.NO_AGENT)

        val assistant = try {
            source.lastSuccessfulAssistant(conversationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failed(AgentMemoryExtractionFailureCategory.SNAPSHOT)
        } ?: return skipped(AgentMemoryExtractionSkipReason.NO_COMPLETED_ASSISTANT)
        if (
            assistant.conversationId != conversationId ||
            assistant.role != MessageRole.ASSISTANT ||
            assistant.status != MessageStatus.SUCCEEDED
        ) {
            return skipped(AgentMemoryExtractionSkipReason.NO_COMPLETED_ASSISTANT)
        }

        val messages = try {
            source.recentSuccessfulMessages(
                conversationId,
                MAX_AGENT_MEMORY_EXTRACTION_RECENT_MESSAGES,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failed(AgentMemoryExtractionFailureCategory.SNAPSHOT)
        }
        val recentMessages = boundedMessages(conversationId, messages)
        if (recentMessages.none { it.role == MessageRole.USER }) {
            return skipped(AgentMemoryExtractionSkipReason.NO_USER_MESSAGES)
        }

        val summary = try {
            source.memory(conversationId)?.summary.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failed(AgentMemoryExtractionFailureCategory.SNAPSHOT)
        }.takeLast(MAX_AGENT_MEMORY_EXTRACTION_SUMMARY_CHARS)

        val projectFacts = if (initialConversation.projectId == null) {
            emptyList()
        } else {
            try {
                boundedProjectFacts(projectFactSource.facts(initialConversation.projectId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return failed(AgentMemoryExtractionFailureCategory.PROJECT_CONTEXT)
            }
        }
        val input = AgentMemoryExtractionInput(
            agentId = agentId,
            conversationId = conversationId,
            projectId = initialConversation.projectId,
            conversationSummary = summary,
            recentMessages = recentMessages,
            projectFacts = projectFacts,
            generationTarget = AgentMemoryGenerationTarget(
                providerId = assistant.providerId,
                model = assistant.model,
            ),
        )
        val candidates = try {
            generator.generate(input)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AgentMemoryGenerationException) {
            return failed(failure.category)
        } catch (_: IOException) {
            return failed(AgentMemoryExtractionFailureCategory.NETWORK)
        } catch (_: Exception) {
            return failed(AgentMemoryExtractionFailureCategory.UNKNOWN)
        }
        if (candidates.isEmpty()) return AgentMemoryExtractionResult.Succeeded(0, 0)

        val policyResult = try {
            policy.evaluate(input, candidates)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failed(AgentMemoryExtractionFailureCategory.POLICY)
        }
        if (policyResult.status != AgentMemoryPolicyStatus.COMPLETED) {
            return failed(AgentMemoryExtractionFailureCategory.POLICY)
        }
        val batch = policyResult.acceptedBatch
            ?: return failed(AgentMemoryExtractionFailureCategory.POLICY)
        if (
            batch.agentId != agentId ||
            batch.conversationId != conversationId ||
            policyResult.accepted.isEmpty()
        ) {
            return if (policyResult.accepted.isEmpty()) {
                AgentMemoryExtractionResult.Succeeded(0, candidates.size)
            } else {
                failed(AgentMemoryExtractionFailureCategory.POLICY)
            }
        }

        val currentConversation = try {
            source.conversation(conversationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failed(AgentMemoryExtractionFailureCategory.SNAPSHOT)
        }
        if (
            currentConversation == null ||
            currentConversation.isArchived ||
            currentConversation.agentId?.trim() != agentId ||
            currentConversation.agentVersion != agentVersion ||
            currentConversation.projectId != initialConversation.projectId
        ) {
            return skipped(AgentMemoryExtractionSkipReason.IDENTITY_CHANGED)
        }
        val mergeResult = try {
            merger.merge(batch)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failed(AgentMemoryExtractionFailureCategory.STORAGE)
        }
        if (mergeResult.savedCount !in 0..batch.candidates.size) {
            return failed(AgentMemoryExtractionFailureCategory.STORAGE)
        }
        return AgentMemoryExtractionResult.Succeeded(
            savedCount = mergeResult.savedCount,
            ignoredCount = candidates.size - mergeResult.savedCount,
        )
    }
}

class RepositoryAgentMemoryExtractionSource(
    private val chatRepository: ChatRepository,
) : AgentMemoryExtractionSource {
    override suspend fun conversation(conversationId: String): Conversation? =
        chatRepository.conversation(conversationId)

    override suspend fun recentSuccessfulMessages(
        conversationId: String,
        limit: Int,
    ): List<ChatMessage> = chatRepository.recentSuccessfulTextMessages(conversationId, limit)

    override suspend fun lastSuccessfulAssistant(conversationId: String): ChatMessage? =
        chatRepository.lastSuccessfulAssistant(conversationId)

    override suspend fun memory(conversationId: String): ConversationMemory? =
        chatRepository.memoryForConversation(conversationId)
}

class MarkdownAgentMemoryProjectFactSource(
    private val readProjectContext: suspend (String) -> String,
) : AgentMemoryProjectFactSource {
    override suspend fun facts(projectId: String): List<String> =
        readProjectContext(projectId)
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
}

private fun boundedMessages(
    conversationId: String,
    messages: List<ChatMessage>,
): List<AgentMemoryMessageSnapshot> {
    val selected = messages
        .asSequence()
        .filter {
            it.conversationId == conversationId &&
                it.status == MessageStatus.SUCCEEDED &&
                it.role in setOf(MessageRole.USER, MessageRole.ASSISTANT) &&
                it.content.isNotBlank()
        }
        .sortedWith(compareBy<ChatMessage> { it.createdAt }.thenBy { it.id })
        .toList()
        .takeLast(MAX_AGENT_MEMORY_EXTRACTION_RECENT_MESSAGES)
    var remainingBudget = MAX_AGENT_MEMORY_EXTRACTION_TOTAL_MESSAGE_CHARS
    val boundedNewestFirst = selected.asReversed().mapIndexed { index, message ->
        val messagesAfterThis = selected.size - index - 1
        val available = (remainingBudget - messagesAfterThis).coerceAtLeast(1)
        val allowed = minOf(MAX_AGENT_MEMORY_EXTRACTION_MESSAGE_CHARS, available)
        val content = message.content.takeLast(allowed)
        remainingBudget -= content.length
        message to content
    }
    return boundedNewestFirst
        .asReversed()
        .mapIndexed { index, (message, content) ->
            AgentMemoryMessageSnapshot(
                id = message.id,
                conversationId = conversationId,
                role = message.role,
                status = message.status,
                content = content,
                order = index.toLong(),
            )
        }
}

private fun boundedProjectFacts(facts: List<String>): List<String> {
    if (facts.size > MAX_AGENT_MEMORY_EXTRACTION_PROJECT_FACTS) {
        throw IllegalArgumentException("项目事实数量超限")
    }
    val normalized = facts.map(String::trim).filter(String::isNotEmpty)
    if (
        normalized.any { it.length > MAX_AGENT_MEMORY_EXTRACTION_PROJECT_FACT_CHARS } ||
        normalized.sumOf(String::length) > MAX_AGENT_MEMORY_PROJECT_CONTEXT_CHARS
    ) {
        throw IllegalArgumentException("项目事实文本超限")
    }
    return normalized
}

private fun skipped(reason: AgentMemoryExtractionSkipReason) =
    AgentMemoryExtractionResult.Skipped(reason)

private fun failed(category: AgentMemoryExtractionFailureCategory) =
    AgentMemoryExtractionResult.Failed(category)
