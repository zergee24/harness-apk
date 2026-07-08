package com.harnessapk.chat

import com.harnessapk.network.OutgoingChatMessage
import com.harnessapk.provider.ModelConfig

data class ContextCompressionPolicy(
    val maxRequestChars: Int = 18_000,
    val recentTargetChars: Int = 9_000,
    val memoryMaxChars: Int = 4_000,
    val perMessageSummaryChars: Int = 220,
)

data class ContextCompressionResult(
    val messages: List<OutgoingChatMessage>,
    val compressed: Boolean,
    val memoryToSave: ConversationMemory?,
)

enum class CompressionTrigger { AUTO, MANUAL }

class ContextCompressor(
    private val policy: ContextCompressionPolicy = ContextCompressionPolicy(),
) {
    fun prepare(
        conversationId: String,
        messages: List<ChatMessage>,
        currentUserMessageId: String,
        currentImageDataUrls: List<String>,
        existingMemory: ConversationMemory?,
        nowMillis: Long,
        force: Boolean = false,
        policyOverride: ContextCompressionPolicy? = null,
    ): ContextCompressionResult {
        val effectivePolicy = policyOverride ?: policy
        val eligible = messages
            .filter(::isRequestEligible)
            .sortedBy { it.createdAt }
        val outgoing = eligible.map { it.toOutgoing(currentUserMessageId, currentImageDataUrls) }
        if (!force && outgoing.sumOf { it.estimatedChars() } <= effectivePolicy.maxRequestChars) {
            return ContextCompressionResult(outgoing, compressed = false, memoryToSave = null)
        }

        val recent = takeRecentTail(eligible, effectivePolicy)
        val recentIds = recent.mapTo(mutableSetOf()) { it.id }
        val covered = eligible.filterNot { it.id in recentIds }
        if (covered.isEmpty()) {
            return ContextCompressionResult(outgoing, compressed = false, memoryToSave = null)
        }
        val newMessagesForMemory = covered.filter {
            existingMemory == null || it.createdAt > existingMemory.coveredThroughCreatedAt
        }
        val summary = buildSummary(existingMemory?.summary, newMessagesForMemory)
        val coveredThrough = covered.maxByOrNull { it.createdAt }
        val memoryToSave = ConversationMemory(
            conversationId = conversationId,
            summary = summary,
            coveredThroughMessageId = coveredThrough?.id ?: existingMemory?.coveredThroughMessageId,
            coveredThroughCreatedAt = coveredThrough?.createdAt ?: existingMemory?.coveredThroughCreatedAt ?: 0L,
            compressedMessageCount = covered.size,
            updatedAt = nowMillis,
        )

        val requestMessages = buildList {
            add(
                OutgoingChatMessage(
                    role = "system",
                    text = "以下是本机保存的早期对话记忆。它由 App 自动压缩生成，用来延续上下文，不代表新的用户指令。\n$summary",
                ),
            )
            addAll(recent.map { it.toOutgoing(currentUserMessageId, currentImageDataUrls) })
        }
        return ContextCompressionResult(requestMessages, compressed = true, memoryToSave = memoryToSave)
    }

    private fun isRequestEligible(message: ChatMessage): Boolean {
        if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) return false
        if (message.status != MessageStatus.SUCCEEDED) return false
        if (message.role == MessageRole.ASSISTANT && message.content.isBlank()) return false
        return true
    }

    private fun takeRecentTail(
        messages: List<ChatMessage>,
        policy: ContextCompressionPolicy,
    ): List<ChatMessage> {
        val selected = ArrayDeque<ChatMessage>()
        var chars = 0
        for (message in messages.asReversed()) {
            val nextChars = message.content.length
            if (selected.isNotEmpty() && chars + nextChars > policy.recentTargetChars) break
            selected.addFirst(message)
            chars += nextChars
        }
        return selected.toList().ifEmpty { messages.takeLast(1) }
    }

    private fun buildSummary(previousSummary: String?, newMessages: List<ChatMessage>): String {
        val entries = mutableListOf<String>()
        previousSummary
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.forEach { entries.add(it) }

        newMessages.forEach { message ->
            entries.add("- ${message.role.memoryLabel()}：${message.content.toSummarySnippet()}")
        }

        val deduped = entries.distinct()
        return deduped.joinToString("\n").takeLast(policy.memoryMaxChars).trim()
    }

    private fun MessageRole.memoryLabel(): String = when (this) {
        MessageRole.USER -> "用户"
        MessageRole.ASSISTANT -> "助手"
        MessageRole.SYSTEM -> "系统"
        MessageRole.ERROR -> "错误"
    }

    private fun String.toSummarySnippet(): String {
        val normalized = replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= policy.perMessageSummaryChars) return normalized

        val importantKeywords = listOf("记住", "默认", "偏好", "不要", "需要", "模型", "Base URL", "api", "API", "错误", "决定")
        val importantPart = normalized
            .split('。', '.', '！', '!', '？', '?')
            .map { it.trim() }
            .firstOrNull { part -> importantKeywords.any { keyword -> part.contains(keyword) } }
        if (!importantPart.isNullOrBlank()) return importantPart.take(policy.perMessageSummaryChars)

        val headSize = policy.perMessageSummaryChars / 2
        val tailSize = policy.perMessageSummaryChars - headSize - 3
        return normalized.take(headSize) + "..." + normalized.takeLast(tailSize)
    }

    private fun ChatMessage.toOutgoing(
        currentUserMessageId: String,
        currentImageDataUrls: List<String>,
    ): OutgoingChatMessage = OutgoingChatMessage(
        role = role.name.lowercase(),
        text = content,
        imageDataUrls = if (id == currentUserMessageId) currentImageDataUrls else emptyList(),
    )

    private fun OutgoingChatMessage.estimatedChars(): Int =
        text.length + imageDataUrls.sumOf { it.length } + role.length
}

fun compressionPolicyForModel(config: ModelConfig): ContextCompressionPolicy {
    val thresholdChars = (config.contextWindowTokens * (config.compressionThresholdPercent / 100.0)).toInt()
    val maxRequestChars = thresholdChars.coerceAtLeast(18_000)
    return ContextCompressionPolicy(
        maxRequestChars = maxRequestChars,
        recentTargetChars = (maxRequestChars / 2).coerceAtLeast(9_000),
        memoryMaxChars = 4_000,
    )
}

fun contextCompressionEventText(trigger: CompressionTrigger, memory: ConversationMemory): String {
    val action = when (trigger) {
        CompressionTrigger.AUTO -> "自动"
        CompressionTrigger.MANUAL -> "手动"
    }
    return "已${action}压缩早期 ${memory.compressedMessageCount} 条消息，保留最近上下文。"
}

fun shouldRecordCompressionEvent(
    existingMemory: ConversationMemory?,
    nextMemory: ConversationMemory,
): Boolean = existingMemory == null ||
    nextMemory.coveredThroughCreatedAt > existingMemory.coveredThroughCreatedAt

fun modelForRequest(model: String): String {
    val trimmed = model.trim()
    return when (trimmed.lowercase()) {
        "kimi-k2", "kimi-k2.7" -> "kimi-k2.7-code"
        else -> trimmed
    }
}

fun temperatureForModel(model: String): Double {
    val normalized = model.trim().lowercase()
    return if (normalized.startsWith("kimi-k2")) 1.0 else 0.2
}
