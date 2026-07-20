package com.harnessapk.agentmemory

import com.harnessapk.network.ChatRequest
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.provider.ProviderProfile
import com.harnessapk.provider.ProviderWithKey
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryCandidateGeneratorTest {
    @Test
    fun strictParserAcceptsOnlyExactCandidateObjects() {
        val parsed = parseAgentMemoryCandidates(
            """
            [
              {
                "kind":"USER_PREFERENCE",
                "dedupeKey":"language",
                "content":"默认使用中文回答",
                "sourceMessageId":"message-1",
                "sourceQuote":"以后默认用中文回答",
                "confidence":0.95
              },
              {
                "kind":"UNKNOWN",
                "dedupeKey":"ignored",
                "content":"忽略",
                "sourceMessageId":"message-1",
                "sourceQuote":"忽略",
                "confidence":0.5
              },
              {
                "kind":"USER_PREFERENCE",
                "dedupeKey":"extra",
                "content":"忽略",
                "sourceMessageId":"message-1",
                "sourceQuote":"忽略",
                "confidence":0.5,
                "extra":true
              },
              {
                "kind":"USER_PREFERENCE",
                "dedupeKey":"wrong-type",
                "content":"忽略",
                "sourceMessageId":"message-1",
                "sourceQuote":"忽略",
                "confidence":"0.5"
              }
            ]
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                AgentMemoryCandidate(
                    kind = AgentMemoryKind.USER_PREFERENCE,
                    dedupeKey = "language",
                    content = "默认使用中文回答",
                    sourceMessageId = "message-1",
                    sourceQuote = "以后默认用中文回答",
                    confidence = 0.95,
                ),
            ),
            parsed,
        )
    }

    @Test
    fun strictParserRejectsNonArrayFenceTrailingRootsAndDeepStructures() {
        val invalid = listOf(
            """{"kind":"USER_PREFERENCE"}""",
            "```json\n[]\n```",
            "[] trailing",
            "[[[[]]]]",
            "[",
            "",
        )

        invalid.forEach { raw ->
            val failure = runCatching { parseAgentMemoryCandidates(raw) }.exceptionOrNull()
            assertEquals(AgentMemoryExtractionFailureCategory.INVALID_RESPONSE, failure.generationCategory())
        }
    }

    @Test
    fun strictParserRejectsOversizedArraysAndDropsNonFiniteOrOutOfRangeConfidence() {
        val oversized = buildString {
            append('[')
            repeat(MAX_AGENT_MEMORY_GENERATED_CANDIDATES + 1) { index ->
                if (index > 0) append(',')
                append(validCandidateJson("key-$index", "message-$index"))
            }
            append(']')
        }
        val oversizedFailure = runCatching {
            parseAgentMemoryCandidates(oversized)
        }.exceptionOrNull()

        val malformedNumbers = parseAgentMemoryCandidates(
            """
            [
              ${validCandidateJson("nan", "m1").replace("0.8", "1e999")},
              ${validCandidateJson("negative", "m2").replace("0.8", "-0.1")},
              ${validCandidateJson("overflow", "m3").replace("0.8", "1.1")},
              ${validCandidateJson("bool", "m4").replace("0.8", "true")}
            ]
            """.trimIndent(),
        )

        assertEquals(AgentMemoryExtractionFailureCategory.OUTPUT_LIMIT, oversizedFailure.generationCategory())
        assertTrue(malformedNumbers.isEmpty())
    }

    @Test
    fun generatorUsesSelectedTargetAndSendsOnlyBoundedTextContract() = runTest {
        var resolvedTarget: AgentMemoryGenerationTarget? = null
        var request: ChatRequest? = null
        val resolver = AgentMemoryGenerationProviderResolver { target ->
            resolvedTarget = target
            AgentMemoryResolvedProvider(
                baseUrl = "https://api.example.com/v1",
                apiKey = "secret-key",
                model = "model-selected",
                customHeaders = mapOf("X-Tenant" to "tenant"),
                readTimeoutMillis = 30_000L,
            )
        }
        val gateway = AgentMemoryCompletionGateway { next ->
            request = next
            flowOf(
                "[",
                validCandidateJson("language", "user-1"),
                "]",
            )
        }
        val generator = LlmAgentMemoryCandidateGenerator(resolver, gateway)
        val input = extractionInput(
            generationTarget = AgentMemoryGenerationTarget("provider-selected", "model-selected"),
            projectFacts = listOf("PROJECT_SECRET_DO_NOT_SEND"),
        )

        val result = generator.generate(input)

        assertEquals("provider-selected", resolvedTarget?.providerId)
        assertEquals("model-selected", resolvedTarget?.model)
        assertEquals(1, result.size)
        val sent = checkNotNull(request)
        assertEquals("model-selected", sent.model)
        assertEquals(0.0, sent.temperature, 0.0)
        assertNull(sent.reasoningEffort)
        assertNull(sent.nativeWebSearchMode)
        assertEquals("", sent.customBodyJson)
        assertEquals(mapOf("X-Tenant" to "tenant"), sent.customHeaders)
        assertEquals(2, sent.messages.size)
        assertEquals(listOf("system", "user"), sent.messages.map { it.role })
        assertTrue(sent.messages.first().text.contains("只提取用户与当前人物"))
        assertTrue(sent.messages.all { it.imageDataUrls.isEmpty() })
        assertFalse(sent.messages.joinToString("\n") { it.text }.contains("PROJECT_SECRET_DO_NOT_SEND"))
        assertFalse(sent.messages.joinToString("\n") { it.text }.contains("secret-key"))
    }

    @Test
    fun generatorFailsClosedOnOutputLimitNetworkAndCancellation() = runTest {
        val resolver = AgentMemoryGenerationProviderResolver {
            AgentMemoryResolvedProvider(
                baseUrl = "https://api.example.com/v1",
                apiKey = "key",
                model = "model",
            )
        }
        suspend fun failureFrom(stream: Flow<String>): Throwable? = runCatching {
            LlmAgentMemoryCandidateGenerator(
                resolver,
                AgentMemoryCompletionGateway { stream },
            ).generate(extractionInput())
        }.exceptionOrNull()

        val limitFailure = failureFrom(flowOf("x".repeat(MAX_AGENT_MEMORY_GENERATION_BYTES + 1)))
        val networkFailure = failureFrom(flow { throw IOException("raw provider body") })
        val cancellation = failureFrom(flow { throw CancellationException("cancel") })

        assertEquals(AgentMemoryExtractionFailureCategory.OUTPUT_LIMIT, limitFailure.generationCategory())
        assertEquals(AgentMemoryExtractionFailureCategory.NETWORK, networkFailure.generationCategory())
        assertTrue(cancellation is CancellationException)
        assertFalse(networkFailure?.message.orEmpty().contains("raw provider body"))
    }

    @Test
    fun repositoryResolverUsesPreferredAssistantTargetAndFallsBackSafely() = runTest {
        val preferred = provider("preferred", "preferred-default")
        val fallback = provider("fallback", "fallback-model")
        var preferredRequests = 0
        var fallbackRequests = 0
        val resolver = RepositoryAgentMemoryGenerationProviderResolver(
            providerWithKey = {
                preferredRequests += 1
                preferred
            },
            defaultProvider = {
                fallbackRequests += 1
                fallback
            },
        )

        val selected = resolver.resolve(AgentMemoryGenerationTarget("preferred", "assistant-model"))
        val defaulted = RepositoryAgentMemoryGenerationProviderResolver(
            providerWithKey = { throw IOException("missing key") },
            defaultProvider = { fallback },
        ).resolve(AgentMemoryGenerationTarget("missing", "assistant-model"))
        val blankModel = resolver.resolve(AgentMemoryGenerationTarget("preferred", " "))
        val invalidPreferred = RepositoryAgentMemoryGenerationProviderResolver(
            providerWithKey = {
                preferred.copy(profile = preferred.profile.copy(baseUrl = " "))
            },
            defaultProvider = { fallback },
        ).resolve(AgentMemoryGenerationTarget("preferred", "assistant-model"))

        assertEquals("assistant-model", selected.model)
        assertEquals("https://preferred.example.com/v1", selected.baseUrl)
        assertEquals("fallback-model", defaulted.model)
        assertEquals("fallback-model", blankModel.model)
        assertEquals("fallback-model", invalidPreferred.model)
        assertEquals(1, preferredRequests)
        assertEquals(1, fallbackRequests)
    }

    @Test
    fun repositoryResolverReturnsTypedProviderFailureAndPropagatesCancellation() = runTest {
        val unavailable = RepositoryAgentMemoryGenerationProviderResolver(
            providerWithKey = { throw IOException("preferred raw") },
            defaultProvider = { throw IOException("default raw") },
        )
        val providerFailure = runCatching {
            unavailable.resolve(AgentMemoryGenerationTarget("missing", "model"))
        }.exceptionOrNull()
        val cancellation = runCatching {
            RepositoryAgentMemoryGenerationProviderResolver(
                providerWithKey = { throw CancellationException("cancel") },
                defaultProvider = { provider("fallback", "fallback-model") },
            ).resolve(AgentMemoryGenerationTarget("preferred", "model"))
        }.exceptionOrNull()

        assertEquals(AgentMemoryExtractionFailureCategory.PROVIDER, providerFailure.generationCategory())
        assertTrue(cancellation is CancellationException)
        assertFalse(providerFailure?.message.orEmpty().contains("raw"))
    }

    private fun extractionInput(
        generationTarget: AgentMemoryGenerationTarget? = AgentMemoryGenerationTarget("provider", "model"),
        projectFacts: List<String> = emptyList(),
    ) = AgentMemoryExtractionInput(
        agentId = "agent-1",
        conversationId = "conversation-1",
        projectId = "project-1",
        conversationSummary = "用户希望长期交流",
        recentMessages = listOf(
            AgentMemoryMessageSnapshot(
                id = "user-1",
                conversationId = "conversation-1",
                role = com.harnessapk.chat.MessageRole.USER,
                status = com.harnessapk.chat.MessageStatus.SUCCEEDED,
                content = "以后默认用中文回答",
                order = 1L,
            ),
        ),
        projectFacts = projectFacts,
        generationTarget = generationTarget,
    )

    private fun validCandidateJson(key: String, messageId: String): String =
        """
        {
          "kind":"USER_PREFERENCE",
          "dedupeKey":"$key",
          "content":"默认使用中文回答",
          "sourceMessageId":"$messageId",
          "sourceQuote":"以后默认用中文回答",
          "confidence":0.8
        }
        """.trimIndent()

    private fun provider(id: String, defaultModel: String) = ProviderWithKey(
        profile = ProviderProfile(
            id = id,
            name = id,
            baseUrl = "https://$id.example.com/v1",
            defaultModel = defaultModel,
            defaultVisionModel = null,
            supportsVision = false,
            nativeWebSearchMode = NativeWebSearchMode.DISABLED,
            enabled = true,
            hasApiKey = true,
        ),
        apiKey = "$id-key",
    )

    private fun Throwable?.generationCategory(): AgentMemoryExtractionFailureCategory? =
        (this as? AgentMemoryGenerationException)?.category
}
