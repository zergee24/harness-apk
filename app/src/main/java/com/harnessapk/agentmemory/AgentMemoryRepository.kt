package com.harnessapk.agentmemory

import com.harnessapk.common.SystemTimeProvider
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AgentMemoryDao
import com.harnessapk.storage.AgentMemoryEntity
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun interface AgentMemoryTransactionRunner {
    suspend fun run(block: suspend () -> Unit)
}

class AgentMemoryRepository(
    private val dao: AgentMemoryDao,
    private val transactionRunner: AgentMemoryTransactionRunner =
        AgentMemoryTransactionRunner { block -> block() },
    private val timeProvider: TimeProvider = SystemTimeProvider,
) {
    fun observe(agentId: String): Flow<List<AgentMemory>> {
        val normalizedAgentId = requiredField("agentId", agentId, MAX_AGENT_MEMORY_ID_CHARS)
        return dao.observeForAgent(normalizedAgentId).map { rows -> rows.map(AgentMemoryEntity::toDomain) }
    }

    suspend fun list(agentId: String): List<AgentMemory> {
        val normalizedAgentId = requiredField("agentId", agentId, MAX_AGENT_MEMORY_ID_CHARS)
        return dao.listForAgent(normalizedAgentId).map(AgentMemoryEntity::toDomain)
    }

    suspend fun merge(
        agentId: String,
        conversationId: String,
        candidates: List<AgentMemoryCandidate>,
    ): AgentMemoryMergeResult {
        val normalizedAgentId = requiredField("agentId", agentId, MAX_AGENT_MEMORY_ID_CHARS)
        val normalizedConversationId = requiredField(
            "sourceConversationId",
            conversationId,
            MAX_AGENT_MEMORY_ID_CHARS,
        )
        if (candidates.size > MAX_AGENT_MEMORY_CANDIDATES_PER_MERGE) {
            throw AgentMemoryValidationException(
                "关系记忆候选数量超过 $MAX_AGENT_MEMORY_CANDIDATES_PER_MERGE",
            )
        }
        val normalized = candidates.map(AgentMemoryCandidate::normalized)
        val winners = linkedMapOf<Pair<AgentMemoryKind, String>, NormalizedCandidate>()
        normalized.forEach { candidate ->
            val key = candidate.kind to candidate.dedupeKey
            val existing = winners[key]
            if (existing == null || candidate.confidence > existing.confidence) {
                winners[key] = candidate
            }
        }
        if (winners.isEmpty()) {
            return AgentMemoryMergeResult(0, 0, 0, normalized.size)
        }

        val now = timeProvider.nowMillis()
        var insertedCount = 0
        var updatedCount = 0
        var protectedCount = 0
        transactionRunner.run {
            winners.values.forEach { candidate ->
                val id = agentMemoryId(normalizedAgentId, candidate.kind, candidate.dedupeKey)
                val inserted = dao.insert(
                    AgentMemoryEntity(
                        id = id,
                        agentId = normalizedAgentId,
                        kind = candidate.kind.name,
                        content = candidate.content,
                        sourceConversationId = normalizedConversationId,
                        sourceMessageId = candidate.sourceMessageId,
                        confidence = candidate.confidence,
                        userEdited = false,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                if (inserted != -1L) {
                    insertedCount += 1
                } else {
                    val existing = dao.findById(id)
                        ?: throw AgentMemoryDataException("关系记忆在合并事务中消失：$id")
                    if (
                        existing.agentId != normalizedAgentId ||
                        existing.kind != candidate.kind.name
                    ) {
                        throw AgentMemoryDataException("关系记忆稳定 ID 对应的作用域不一致：$id")
                    }
                    if (existing.userEdited) {
                        protectedCount += 1
                    } else if (
                        dao.updateAutomatically(
                            id = id,
                            agentId = normalizedAgentId,
                            kind = candidate.kind.name,
                            content = candidate.content,
                            sourceConversationId = normalizedConversationId,
                            sourceMessageId = candidate.sourceMessageId,
                            confidence = candidate.confidence,
                            updatedAt = now,
                        ) > 0
                    ) {
                        updatedCount += 1
                    } else {
                        val current = dao.findById(id)
                            ?: throw AgentMemoryDataException("关系记忆在合并事务中消失：$id")
                        if (!current.userEdited) {
                            throw AgentMemoryDataException("关系记忆自动更新未生效：$id")
                        }
                        protectedCount += 1
                    }
                }
            }
        }
        return AgentMemoryMergeResult(
            insertedCount = insertedCount,
            updatedCount = updatedCount,
            protectedCount = protectedCount,
            duplicateCount = normalized.size - winners.size,
        )
    }

    suspend fun edit(id: String, content: String): Boolean {
        val normalizedId = requiredField("id", id, MAX_AGENT_MEMORY_ID_CHARS)
        val normalizedContent = requiredField("content", content, MAX_AGENT_MEMORY_CONTENT_CHARS)
        var updated = 0
        transactionRunner.run {
            updated = dao.markUserEdited(normalizedId, normalizedContent, timeProvider.nowMillis())
        }
        return updated > 0
    }

    suspend fun delete(id: String): Boolean {
        val normalizedId = requiredField("id", id, MAX_AGENT_MEMORY_ID_CHARS)
        var deleted = 0
        transactionRunner.run { deleted = dao.delete(normalizedId) }
        return deleted > 0
    }

    suspend fun clear(agentId: String): Int {
        val normalizedAgentId = requiredField("agentId", agentId, MAX_AGENT_MEMORY_ID_CHARS)
        var deleted = 0
        transactionRunner.run { deleted = dao.clear(normalizedAgentId) }
        return deleted
    }
}

internal fun agentMemoryId(
    agentId: String,
    kind: AgentMemoryKind,
    dedupeKey: String,
): String {
    val normalizedAgentId = requiredField("agentId", agentId, MAX_AGENT_MEMORY_ID_CHARS)
    val normalizedDedupeKey = normalizedDedupeKey(dedupeKey)
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$normalizedAgentId|${kind.name}|$normalizedDedupeKey".encodeToByteArray())
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }
}

private data class NormalizedCandidate(
    val kind: AgentMemoryKind,
    val dedupeKey: String,
    val content: String,
    val sourceMessageId: String,
    val confidence: Double,
)

private fun AgentMemoryCandidate.normalized(): NormalizedCandidate {
    if (!confidence.isFinite() || confidence !in 0.0..1.0) {
        throw AgentMemoryValidationException("confidence 必须是 [0.0, 1.0] 内的有限数")
    }
    requiredField("sourceQuote", sourceQuote, MAX_AGENT_MEMORY_SOURCE_QUOTE_CHARS)
    return NormalizedCandidate(
        kind = kind,
        dedupeKey = normalizedDedupeKey(dedupeKey),
        content = requiredField("content", content, MAX_AGENT_MEMORY_CONTENT_CHARS),
        sourceMessageId = requiredField("sourceMessageId", sourceMessageId, MAX_AGENT_MEMORY_ID_CHARS),
        confidence = confidence,
    )
}

private fun normalizedDedupeKey(value: String): String =
    requiredField("dedupeKey", value, MAX_AGENT_MEMORY_DEDUPE_KEY_CHARS)
        .lowercase(Locale.ROOT)

private fun requiredField(name: String, value: String, maxChars: Int): String {
    val normalized = value.trim()
    if (normalized.isEmpty()) throw AgentMemoryValidationException("$name 不能为空")
    if (normalized.length > maxChars) {
        throw AgentMemoryValidationException("$name 超过 $maxChars 字符")
    }
    return normalized
}

private fun AgentMemoryEntity.toDomain(): AgentMemory {
    val parsedKind = runCatching { AgentMemoryKind.valueOf(kind) }.getOrElse { error ->
        throw AgentMemoryDataException("未知关系记忆类型：$kind", error)
    }
    if (!confidence.isFinite() || confidence !in 0.0..1.0) {
        throw AgentMemoryDataException("关系记忆置信度无效：$id")
    }
    return AgentMemory(
        id = id,
        agentId = agentId,
        kind = parsedKind,
        content = content,
        sourceConversationId = sourceConversationId,
        sourceMessageId = sourceMessageId,
        confidence = confidence,
        userEdited = userEdited,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private const val HEX = "0123456789abcdef"
