package com.harnessapk.ui.chat

import com.harnessapk.chat.ChatMessage
import com.harnessapk.chat.ContextCompressionPolicy
import com.harnessapk.chat.ConversationMemory
import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import com.harnessapk.provider.ModelConfig
import com.harnessapk.provider.defaultModelConfig
import kotlin.math.roundToInt

internal data class ContextWindowStatus(
    val usedTokens: Int,
    val maxTokens: Int,
    val compressionThresholdPercent: Int,
    val compressedMessageCount: Int,
) {
    val usedChars: Int = usedTokens
    val maxChars: Int = maxTokens
}

internal fun contextWindowStatus(
    messages: List<ChatMessage>,
    memory: ConversationMemory?,
    policy: ContextCompressionPolicy = ContextCompressionPolicy(),
    modelConfig: ModelConfig = defaultModelConfig("", ""),
): ContextWindowStatus {
    val coveredThrough = memory?.coveredThroughCreatedAt ?: 0L
    val liveTokens = messages
        .asSequence()
        .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        .filter { it.status == MessageStatus.SUCCEEDED }
        .filter { memory == null || it.createdAt > coveredThrough }
        .sumOf { it.content.estimatedContextTokens() }
    return ContextWindowStatus(
        usedTokens = liveTokens + memory?.summary.orEmpty().estimatedContextTokens(),
        maxTokens = if (modelConfig.id.isBlank()) policy.maxRequestChars else modelConfig.contextWindowTokens,
        compressionThresholdPercent = modelConfig.compressionThresholdPercent,
        compressedMessageCount = memory?.compressedMessageCount ?: 0,
    )
}

internal fun contextWindowStatusText(status: ContextWindowStatus): String {
    val compressedText = if (status.compressedMessageCount > 0) {
        " · 已压缩 ${status.compressedMessageCount} 条"
    } else {
        ""
    }
    return "上下文 ${status.usedTokens.toKiloText()} / ${status.maxTokens.toKiloText()}$compressedText"
}

internal fun contextWindowStatusCompactText(status: ContextWindowStatus): String =
    "${contextWindowUsagePercent(status)}%"

internal fun contextWindowUsagePercent(status: ContextWindowStatus): Int =
    (contextWindowUsageProgress(status) * 100).roundToInt()

internal fun contextWindowUsageProgress(status: ContextWindowStatus): Float {
    if (status.maxTokens <= 0) return 0f
    return (status.usedTokens.toFloat() / status.maxTokens.toFloat()).coerceIn(0f, 1f)
}

internal fun contextWindowCanManualCompress(status: ContextWindowStatus): Boolean {
    if (status.maxTokens <= 0) return false
    val thresholdTokens = status.maxTokens * (status.compressionThresholdPercent.coerceIn(1, 100) / 100.0)
    return status.usedTokens >= thresholdTokens
}

private fun Int.toKiloText(): String =
    if (this >= 1_000_000) {
        "%.1fM".format(this / 1_000_000.0)
    } else {
        "%.1fk".format(this / 1000.0)
    }

internal fun String.estimatedContextTokens(): Int = length
