package com.harnessapk.agentmemory

import com.harnessapk.chat.modelForRequest
import com.harnessapk.network.ChatRequest
import com.harnessapk.network.OpenAiCompatibleClient
import com.harnessapk.network.OutgoingChatMessage
import com.harnessapk.provider.ProviderRepository
import com.harnessapk.provider.ProviderWithKey
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LlmAgentMemoryCandidateGenerator(
    private val providerResolver: AgentMemoryGenerationProviderResolver,
    private val completionGateway: AgentMemoryCompletionGateway,
) : AgentMemoryCandidateGenerator {
    override suspend fun generate(input: AgentMemoryExtractionInput): List<AgentMemoryCandidate> {
        val provider = try {
            providerResolver.resolve(input.generationTarget)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AgentMemoryGenerationException) {
            throw failure
        } catch (_: Exception) {
            throw AgentMemoryGenerationException(AgentMemoryExtractionFailureCategory.PROVIDER)
        }
        val request = ChatRequest(
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            model = provider.model,
            messages = listOf(
                OutgoingChatMessage(role = "system", text = AGENT_MEMORY_GENERATION_SYSTEM_PROMPT),
                OutgoingChatMessage(role = "user", text = input.toGenerationPrompt()),
            ),
            temperature = 0.0,
            reasoningEffort = null,
            nativeWebSearchMode = null,
            readTimeoutMillis = provider.readTimeoutMillis,
            customHeaders = provider.customHeaders,
            customBodyJson = "",
        )
        val output = StringBuilder()
        var outputBytes = 0
        try {
            completionGateway.stream(request).collect { delta ->
                outputBytes += delta.encodeToByteArray().size
                if (outputBytes > MAX_AGENT_MEMORY_GENERATION_BYTES) {
                    throw AgentMemoryGenerationException(AgentMemoryExtractionFailureCategory.OUTPUT_LIMIT)
                }
                output.append(delta)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AgentMemoryGenerationException) {
            throw failure
        } catch (_: IOException) {
            throw AgentMemoryGenerationException(AgentMemoryExtractionFailureCategory.NETWORK)
        } catch (_: Exception) {
            throw AgentMemoryGenerationException(AgentMemoryExtractionFailureCategory.NETWORK)
        }
        return parseAgentMemoryCandidates(output.toString())
    }
}

class RepositoryAgentMemoryGenerationProviderResolver(
    private val providerWithKey: suspend (String) -> ProviderWithKey,
    private val defaultProvider: suspend () -> ProviderWithKey,
) : AgentMemoryGenerationProviderResolver {
    constructor(repository: ProviderRepository) : this(
        providerWithKey = repository::providerWithKey,
        defaultProvider = repository::defaultProviderForText,
    )

    override suspend fun resolve(target: AgentMemoryGenerationTarget?): AgentMemoryResolvedProvider {
        val providerId = target?.providerId?.trim()?.takeIf(String::isNotEmpty)
        val model = target?.model?.trim()?.takeIf(String::isNotEmpty)
        if (providerId != null && model != null) {
            val preferred = try {
                providerWithKey(providerId)
                    .takeIf { it.profile.enabled }
                    ?.toMemoryProvider(model)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (preferred != null) return preferred
        }
        return try {
            val fallback = defaultProvider()
            val fallbackModel = fallback.profile.defaultModel.trim()
            if (!fallback.profile.enabled || fallbackModel.isEmpty()) {
                throw AgentMemoryGenerationException(AgentMemoryExtractionFailureCategory.PROVIDER)
            }
            fallback.toMemoryProvider(fallbackModel)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AgentMemoryGenerationException) {
            throw failure
        } catch (_: Exception) {
            throw AgentMemoryGenerationException(AgentMemoryExtractionFailureCategory.PROVIDER)
        }
    }
}

fun openAiAgentMemoryCompletionGateway(client: OpenAiCompatibleClient) =
    AgentMemoryCompletionGateway { request ->
        client.streamChat(request).map { it.text }
    }

private fun ProviderWithKey.toMemoryProvider(selectedModel: String): AgentMemoryResolvedProvider {
    val requestModel = modelForRequest(selectedModel)
    if (
        profile.baseUrl.isBlank() ||
        apiKey.isBlank() ||
        requestModel.isBlank()
    ) {
        throw AgentMemoryGenerationException(AgentMemoryExtractionFailureCategory.PROVIDER)
    }
    val readTimeout = profile.modelConfigs
        .firstOrNull { it.id.equals(selectedModel, ignoreCase = true) }
        ?.readTimeoutMillis
    return AgentMemoryResolvedProvider(
        baseUrl = profile.baseUrl,
        apiKey = apiKey,
        model = requestModel,
        customHeaders = profile.customHeaders,
        readTimeoutMillis = readTimeout,
    )
}

private fun AgentMemoryExtractionInput.toGenerationPrompt(): String = buildJsonObject {
    put("conversationSummary", conversationSummary)
    put(
        "recentMessages",
        buildJsonArray {
            recentMessages.forEach { message ->
                add(
                    buildJsonObject {
                        put("id", message.id)
                        put("role", message.role.name)
                        put("content", message.content)
                        put("order", message.order)
                    },
                )
            }
        },
    )
}.toString()

internal const val AGENT_MEMORY_GENERATION_SYSTEM_PROMPT = """
只提取用户与当前人物以后如何相处仍有价值的离散事实。
允许：称呼偏好、稳定偏好、共同经历、关系变化。
禁止：项目目标、文件、任务、决定、业务事实、当前临时话题、人物历史资料、模型推理。
每项必须引用一条用户消息，并给出该消息中的连续原文 sourceQuote。
只输出 JSON 数组；字段为 kind、dedupeKey、content、sourceMessageId、sourceQuote、confidence。
kind 只能是 USER_PREFERENCE、ADDRESS_PREFERENCE、SHARED_HISTORY、RELATIONSHIP_EVENT。
没有合格内容时输出 []。
"""
