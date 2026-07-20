package com.harnessapk.agentmemory

import com.harnessapk.chat.ChatMessage
import com.harnessapk.chat.Conversation
import com.harnessapk.chat.ConversationMemory
import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryExtractionUseCaseTest {
    @Test
    fun onlyPolicyAcceptedCandidatesReachRepository() = runTest {
        val source = source()
        var generatedInput: AgentMemoryExtractionInput? = null
        var mergedBatch: AgentMemoryPolicy.AcceptedBatch? = null
        val accepted = candidate(
            messageId = "user-1",
            content = "默认使用中文回答",
            quote = "以后默认用中文回答",
        )
        val rejected = accepted.copy(
            dedupeKey = "forged",
            content = "项目预算五十万元",
        )
        val useCase = useCase(
            source = source,
            generator = AgentMemoryCandidateGenerator { input ->
                generatedInput = input
                listOf(accepted, rejected)
            },
            merger = AgentMemoryAcceptedBatchMerger { batch ->
                mergedBatch = batch
                AgentMemoryMergeResult(1, 0, 0, 0)
            },
        )

        val result = useCase.extract("conversation-1")

        assertEquals(AgentMemoryExtractionResult.Succeeded(1, 1), result)
        assertEquals(listOf(accepted), mergedBatch?.candidates)
        assertEquals("provider-last", generatedInput?.generationTarget?.providerId)
        assertEquals("model-last", generatedInput?.generationTarget?.model)
    }

    @Test
    fun missingIdentityNoAssistantAndEmptyCandidatesNeverWrite() = runTest {
        var mergeCalls = 0
        val merger = AgentMemoryAcceptedBatchMerger {
            mergeCalls += 1
            AgentMemoryMergeResult(0, 0, 0, 0)
        }
        val noIdentity = source().apply {
            conversation = conversation?.copy(agentId = null, agentVersion = null)
        }
        val noAssistant = source().apply {
            assistant = null
        }
        val empty = source()

        val noIdentityResult = useCase(noIdentity, merger = merger).extract("conversation-1")
        val noAssistantResult = useCase(noAssistant, merger = merger).extract("conversation-1")
        val emptyResult = useCase(
            empty,
            generator = AgentMemoryCandidateGenerator { emptyList() },
            merger = merger,
        ).extract("conversation-1")

        assertEquals(
            AgentMemoryExtractionResult.Skipped(AgentMemoryExtractionSkipReason.NO_AGENT),
            noIdentityResult,
        )
        assertEquals(
            AgentMemoryExtractionResult.Skipped(AgentMemoryExtractionSkipReason.NO_COMPLETED_ASSISTANT),
            noAssistantResult,
        )
        assertEquals(AgentMemoryExtractionResult.Succeeded(0, 0), emptyResult)
        assertEquals(0, mergeCalls)
    }

    @Test
    fun nonSuccessfulAssistantSnapshotCannotSelectGenerationTarget() = runTest {
        val source = source().apply {
            assistant = assistant?.copy(status = MessageStatus.FAILED)
        }

        val result = useCase(source).extract("conversation-1")

        assertEquals(
            AgentMemoryExtractionResult.Skipped(AgentMemoryExtractionSkipReason.NO_COMPLETED_ASSISTANT),
            result,
        )
    }

    @Test
    fun snapshotKeepsLatestTwentyInStableOrderAndWithinCharacterBudget() = runTest {
        val source = source()
        source.messages = (0 until 25).map { index ->
            userMessage(
                id = "user-${index.toString().padStart(2, '0')}",
                content = "$index:" + "x".repeat(MAX_AGENT_MEMORY_EXTRACTION_MESSAGE_CHARS + 20),
                createdAt = index.toLong(),
            )
        }.reversed()
        var generatedInput: AgentMemoryExtractionInput? = null
        val useCase = useCase(
            source,
            generator = AgentMemoryCandidateGenerator {
                generatedInput = it
                emptyList()
            },
        )

        useCase.extract("conversation-1")

        val recent = checkNotNull(generatedInput).recentMessages
        assertEquals(MAX_AGENT_MEMORY_EXTRACTION_RECENT_MESSAGES, recent.size)
        assertEquals("user-05", recent.first().id)
        assertEquals("user-24", recent.last().id)
        assertEquals(recent.map { it.order }.sorted(), recent.map { it.order })
        assertTrue(recent.all { it.content.length <= MAX_AGENT_MEMORY_EXTRACTION_MESSAGE_CHARS })
        assertTrue(recent.sumOf { it.content.length } <= MAX_AGENT_MEMORY_EXTRACTION_TOTAL_MESSAGE_CHARS)
    }

    @Test
    fun projectFactsReachPolicyButNotGeneratorOwnedMetadata() = runTest {
        val source = source()
        var policyInput: AgentMemoryExtractionInput? = null
        val useCase = useCase(
            source = source,
            projectFacts = AgentMemoryProjectFactSource { listOf("项目预算五十万元") },
            policy = AgentMemoryCandidatePolicy { input, candidates ->
                policyInput = input
                AgentMemoryPolicy().evaluate(input, candidates)
            },
            generator = AgentMemoryCandidateGenerator {
                listOf(candidate("user-1", "默认使用中文回答", "以后默认用中文回答"))
            },
        )

        useCase.extract("conversation-1")

        assertEquals(listOf("项目预算五十万元"), policyInput?.projectFacts)
    }

    @Test
    fun identityOrArchiveChangeBeforeMergeSkipsWrite() = runTest {
        val cases = listOf<(Conversation) -> Conversation>(
            { it.copy(agentId = "agent-2") },
            { it.copy(agentVersion = 8) },
            { it.copy(projectId = "project-2") },
            { it.copy(isArchived = true) },
        )
        cases.forEach { mutation ->
            val source = source()
            var mergeCalls = 0
            val useCase = useCase(
                source = source,
                generator = AgentMemoryCandidateGenerator {
                    source.conversation = mutation(checkNotNull(source.conversation))
                    listOf(candidate("user-1", "默认使用中文回答", "以后默认用中文回答"))
                },
                merger = AgentMemoryAcceptedBatchMerger {
                    mergeCalls += 1
                    AgentMemoryMergeResult(1, 0, 0, 0)
                },
            )

            val result = useCase.extract("conversation-1")

            assertEquals(
                AgentMemoryExtractionResult.Skipped(AgentMemoryExtractionSkipReason.IDENTITY_CHANGED),
                result,
            )
            assertEquals(0, mergeCalls)
        }
    }

    @Test
    fun typedFailuresDoNotExposeContentAndCancellationPropagates() = runTest {
        val source = source()
        val network = useCase(
            source,
            generator = AgentMemoryCandidateGenerator {
                throw IOException("provider raw response secret")
            },
        ).extract("conversation-1")
        val storage = useCase(
            source,
            merger = AgentMemoryAcceptedBatchMerger { throw IOException("database raw secret") },
        ).extract("conversation-1")
        val cancelled = runCatching {
            useCase(
                source,
                generator = AgentMemoryCandidateGenerator { throw CancellationException("cancel") },
            ).extract("conversation-1")
        }.exceptionOrNull()

        assertEquals(
            AgentMemoryExtractionResult.Failed(AgentMemoryExtractionFailureCategory.NETWORK),
            network,
        )
        assertEquals(
            AgentMemoryExtractionResult.Failed(AgentMemoryExtractionFailureCategory.STORAGE),
            storage,
        )
        assertTrue(cancelled is CancellationException)
        assertFalse(network.toString().contains("raw"))
        assertFalse(storage.toString().contains("secret"))
    }

    @Test
    fun transientGenerationFailureDoesNotPoisonNextExtraction() = runTest {
        var generationAttempts = 0
        var mergeCalls = 0
        val useCase = useCase(
            source = source(),
            generator = AgentMemoryCandidateGenerator {
                generationAttempts += 1
                if (generationAttempts == 1) {
                    throw IOException("provider raw response secret")
                }
                listOf(candidate("user-1", "默认使用中文回答", "以后默认用中文回答"))
            },
            merger = AgentMemoryAcceptedBatchMerger { batch ->
                mergeCalls += 1
                assertEquals(1, batch.candidates.size)
                AgentMemoryMergeResult(1, 0, 0, 0)
            },
        )

        val failed = useCase.extract("conversation-1")
        val retried = useCase.extract("conversation-1")

        assertEquals(
            AgentMemoryExtractionResult.Failed(AgentMemoryExtractionFailureCategory.NETWORK),
            failed,
        )
        assertFalse(failed.toString().contains("secret"))
        assertEquals(AgentMemoryExtractionResult.Succeeded(1, 0), retried)
        assertEquals(2, generationAttempts)
        assertEquals(1, mergeCalls)
    }

    @Test
    fun mergerCannotReportMoreWritesThanPolicyAccepted() = runTest {
        val accepted = candidate(
            messageId = "user-1",
            content = "默认使用中文回答",
            quote = "以后默认用中文回答",
        )
        val rejected = accepted.copy(
            dedupeKey = "project-budget",
            content = "项目预算五十万元",
        )
        val result = useCase(
            source = source(),
            generator = AgentMemoryCandidateGenerator { listOf(accepted, rejected) },
            merger = AgentMemoryAcceptedBatchMerger {
                AgentMemoryMergeResult(
                    insertedCount = 2,
                    updatedCount = 0,
                    protectedCount = 0,
                    duplicateCount = 0,
                )
            },
        ).extract("conversation-1")

        assertEquals(
            AgentMemoryExtractionResult.Failed(AgentMemoryExtractionFailureCategory.STORAGE),
            result,
        )
    }

    @Test
    fun snapshotAndProjectFailuresAreSeparated() = runTest {
        val snapshotSource = source().apply {
            conversationFailure = IOException("message body secret")
        }
        val snapshotResult = useCase(snapshotSource).extract("conversation-1")
        val projectResult = useCase(
            source(),
            projectFacts = AgentMemoryProjectFactSource { throw IOException("project secret") },
        ).extract("conversation-1")

        assertEquals(
            AgentMemoryExtractionResult.Failed(AgentMemoryExtractionFailureCategory.SNAPSHOT),
            snapshotResult,
        )
        assertEquals(
            AgentMemoryExtractionResult.Failed(AgentMemoryExtractionFailureCategory.PROJECT_CONTEXT),
            projectResult,
        )
    }

    private fun useCase(
        source: FakeExtractionSource,
        projectFacts: AgentMemoryProjectFactSource = AgentMemoryProjectFactSource { emptyList() },
        generator: AgentMemoryCandidateGenerator = AgentMemoryCandidateGenerator {
            listOf(candidate("user-1", "默认使用中文回答", "以后默认用中文回答"))
        },
        policy: AgentMemoryCandidatePolicy = AgentMemoryCandidatePolicy { input, candidates ->
            AgentMemoryPolicy().evaluate(input, candidates)
        },
        merger: AgentMemoryAcceptedBatchMerger = AgentMemoryAcceptedBatchMerger {
            AgentMemoryMergeResult(1, 0, 0, 0)
        },
    ) = AgentMemoryExtractionUseCase(source, projectFacts, generator, policy, merger)

    private fun source() = FakeExtractionSource(
        conversation = Conversation(
            id = "conversation-1",
            title = "测试",
            updatedAt = 10L,
            projectId = "project-1",
            promptOriginal = "",
            promptOptimized = "",
            promptFinal = "",
            agentId = "agent-1",
            agentVersion = 7,
            isArchived = false,
        ),
        messages = listOf(
            userMessage("user-1", "以后默认用中文回答", 1L),
            assistantMessage("assistant-old", "provider-old", "model-old", 2L),
            assistantMessage("assistant-last", "provider-last", "model-last", 3L),
        ),
        assistant = assistantMessage("assistant-last", "provider-last", "model-last", 3L),
        memory = ConversationMemory(
            conversationId = "conversation-1",
            summary = "摘要",
            coveredThroughMessageId = "user-1",
            coveredThroughCreatedAt = 1L,
            compressedMessageCount = 1,
            updatedAt = 2L,
        ),
    )

    private fun candidate(messageId: String, content: String, quote: String) = AgentMemoryCandidate(
        kind = AgentMemoryKind.USER_PREFERENCE,
        dedupeKey = "language",
        content = content,
        sourceMessageId = messageId,
        sourceQuote = quote,
        confidence = 0.9,
    )

    private fun userMessage(
        id: String,
        content: String,
        createdAt: Long,
    ) = ChatMessage(
        id = id,
        conversationId = "conversation-1",
        role = MessageRole.USER,
        content = content,
        status = MessageStatus.SUCCEEDED,
        providerId = null,
        model = null,
        errorMessage = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun assistantMessage(
        id: String,
        providerId: String,
        model: String,
        createdAt: Long,
    ) = ChatMessage(
        id = id,
        conversationId = "conversation-1",
        role = MessageRole.ASSISTANT,
        content = "回复",
        status = MessageStatus.SUCCEEDED,
        providerId = providerId,
        model = model,
        errorMessage = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}

private class FakeExtractionSource(
    var conversation: Conversation?,
    var messages: List<ChatMessage>,
    var assistant: ChatMessage?,
    var memory: ConversationMemory?,
) : AgentMemoryExtractionSource {
    var conversationFailure: Throwable? = null

    override suspend fun conversation(conversationId: String): Conversation? {
        conversationFailure?.let { throw it }
        return conversation
    }

    override suspend fun recentSuccessfulMessages(
        conversationId: String,
        limit: Int,
    ): List<ChatMessage> = messages

    override suspend fun lastSuccessfulAssistant(conversationId: String): ChatMessage? = assistant

    override suspend fun memory(conversationId: String): ConversationMemory? = memory
}
