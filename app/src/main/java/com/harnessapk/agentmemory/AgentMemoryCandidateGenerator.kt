package com.harnessapk.agentmemory

import com.harnessapk.network.ChatRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val MAX_AGENT_MEMORY_GENERATED_CANDIDATES = 32
internal const val MAX_AGENT_MEMORY_GENERATION_BYTES = 64 * 1024
private const val MAX_AGENT_MEMORY_JSON_DEPTH = 2

fun interface AgentMemoryCandidateGenerator {
    suspend fun generate(input: AgentMemoryExtractionInput): List<AgentMemoryCandidate>
}

fun interface AgentMemoryGenerationProviderResolver {
    suspend fun resolve(target: AgentMemoryGenerationTarget?): AgentMemoryResolvedProvider
}

data class AgentMemoryResolvedProvider(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val customHeaders: Map<String, String> = emptyMap(),
    val readTimeoutMillis: Long? = null,
)

fun interface AgentMemoryCompletionGateway {
    fun stream(request: ChatRequest): Flow<String>
}

class AgentMemoryGenerationException(
    val category: AgentMemoryExtractionFailureCategory,
) : Exception("关系记忆候选生成失败：${category.name}")

internal fun parseAgentMemoryCandidates(raw: String): List<AgentMemoryCandidate> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.encodeToByteArray().size > MAX_AGENT_MEMORY_GENERATION_BYTES) {
        throw generationFailure(
            if (trimmed.isEmpty()) {
                AgentMemoryExtractionFailureCategory.INVALID_RESPONSE
            } else {
                AgentMemoryExtractionFailureCategory.OUTPUT_LIMIT
            },
        )
    }
    validateJsonEnvelope(trimmed)
    val root = try {
        STRICT_AGENT_MEMORY_JSON.parseToJsonElement(trimmed)
    } catch (_: SerializationException) {
        throw generationFailure(AgentMemoryExtractionFailureCategory.INVALID_RESPONSE)
    } catch (_: IllegalArgumentException) {
        throw generationFailure(AgentMemoryExtractionFailureCategory.INVALID_RESPONSE)
    }
    val array = root as? JsonArray
        ?: throw generationFailure(AgentMemoryExtractionFailureCategory.INVALID_RESPONSE)
    if (array.size > MAX_AGENT_MEMORY_GENERATED_CANDIDATES) {
        throw generationFailure(AgentMemoryExtractionFailureCategory.OUTPUT_LIMIT)
    }
    return array.mapNotNull(::parseCandidate)
}

private fun parseCandidate(element: kotlinx.serialization.json.JsonElement): AgentMemoryCandidate? {
    val objectValue = element as? JsonObject ?: return null
    if (objectValue.keys != CANDIDATE_FIELDS) return null
    val kindValue = objectValue.strictString("kind", 64) ?: return null
    val kind = runCatching { AgentMemoryKind.valueOf(kindValue) }.getOrNull() ?: return null
    val dedupeKey = objectValue.strictString("dedupeKey", MAX_AGENT_MEMORY_DEDUPE_KEY_CHARS) ?: return null
    val content = objectValue.strictString("content", MAX_AGENT_MEMORY_CONTENT_CHARS) ?: return null
    val sourceMessageId = objectValue.strictString("sourceMessageId", MAX_AGENT_MEMORY_ID_CHARS) ?: return null
    val sourceQuote = objectValue.strictString(
        "sourceQuote",
        MAX_AGENT_MEMORY_SOURCE_QUOTE_CHARS,
    ) ?: return null
    val confidencePrimitive = objectValue["confidence"] as? JsonPrimitive ?: return null
    if (confidencePrimitive.isString) return null
    val confidence = confidencePrimitive.content.toDoubleOrNull() ?: return null
    if (!confidence.isFinite() || confidence !in 0.0..1.0) return null
    return AgentMemoryCandidate(
        kind = kind,
        dedupeKey = dedupeKey,
        content = content,
        sourceMessageId = sourceMessageId,
        sourceQuote = sourceQuote,
        confidence = confidence,
    )
}

private fun JsonObject.strictString(key: String, maxChars: Int): String? {
    val primitive = get(key) as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    val raw = primitive.content
    if (raw.length > maxChars) return null
    return raw.trim().takeIf(String::isNotEmpty)
}

private fun validateJsonEnvelope(raw: String) {
    if (!raw.startsWith('[') || !raw.endsWith(']')) {
        throw generationFailure(AgentMemoryExtractionFailureCategory.INVALID_RESPONSE)
    }
    val stack = ArrayDeque<Char>()
    var inString = false
    var escaped = false
    raw.forEach { character ->
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
            return@forEach
        }
        when (character) {
            '"' -> inString = true
            '[', '{' -> {
                stack.addLast(character)
                if (stack.size > MAX_AGENT_MEMORY_JSON_DEPTH) {
                    throw generationFailure(AgentMemoryExtractionFailureCategory.INVALID_RESPONSE)
                }
            }
            ']' -> if (stack.removeLastOrNull() != '[') {
                throw generationFailure(AgentMemoryExtractionFailureCategory.INVALID_RESPONSE)
            }
            '}' -> if (stack.removeLastOrNull() != '{') {
                throw generationFailure(AgentMemoryExtractionFailureCategory.INVALID_RESPONSE)
            }
        }
    }
    if (inString || escaped || stack.isNotEmpty()) {
        throw generationFailure(AgentMemoryExtractionFailureCategory.INVALID_RESPONSE)
    }
}

private fun generationFailure(category: AgentMemoryExtractionFailureCategory) =
    AgentMemoryGenerationException(category)

private val STRICT_AGENT_MEMORY_JSON = Json {
    isLenient = false
    allowSpecialFloatingPointValues = false
}
private val CANDIDATE_FIELDS = setOf(
    "kind",
    "dedupeKey",
    "content",
    "sourceMessageId",
    "sourceQuote",
    "confidence",
)
