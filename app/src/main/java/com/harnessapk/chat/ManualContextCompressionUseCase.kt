package com.harnessapk.chat

import com.harnessapk.common.TimeProvider
import com.harnessapk.provider.ModelConfig

data class ManualContextCompressionResult(
    val compressed: Boolean,
    val message: String,
)

class ManualContextCompressionUseCase(
    private val chatRepository: ChatRepository,
    private val timeProvider: TimeProvider,
    private val contextCompressor: ContextCompressor = ContextCompressor(),
) {
    suspend fun compress(
        conversationId: String,
        modelConfig: ModelConfig? = null,
    ): ManualContextCompressionResult {
        val existingMemory = chatRepository.memoryForConversation(conversationId)
        val result = contextCompressor.prepare(
            conversationId = conversationId,
            messages = chatRepository.listMessages(conversationId),
            currentUserMessageId = "",
            currentImageDataUrls = emptyList(),
            existingMemory = existingMemory,
            nowMillis = timeProvider.nowMillis(),
            force = true,
            policyOverride = modelConfig?.let(::compressionPolicyForModel),
        )
        val memory = result.memoryToSave
        if (memory == null || !shouldRecordCompressionEvent(existingMemory, memory)) {
            return ManualContextCompressionResult(
                compressed = false,
                message = "当前上下文暂不需要压缩",
            )
        }

        chatRepository.upsertMemory(memory)
        val eventText = contextCompressionEventText(CompressionTrigger.MANUAL, memory)
        chatRepository.insertSystemEvent(conversationId, eventText)
        return ManualContextCompressionResult(compressed = true, message = eventText)
    }
}
