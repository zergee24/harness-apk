package com.harnessapk.ui.agent

import com.harnessapk.agentmemory.AgentMemory
import com.harnessapk.agentmemory.AgentMemoryKind
import com.harnessapk.agentmemory.MAX_AGENT_MEMORY_CONTENT_CHARS

data class AgentMemoryUiItem(
    val memory: AgentMemory,
    val typeLabel: String,
)

sealed interface AgentMemoryEditValidation {
    data class Valid(val content: String) : AgentMemoryEditValidation

    data class Invalid(val message: String) : AgentMemoryEditValidation
}

sealed interface AgentMemorySourceTarget {
    data class CurrentConversation(val messageId: String) : AgentMemorySourceTarget

    data class OtherConversation(
        val conversationId: String,
        val messageId: String,
    ) : AgentMemorySourceTarget

    data object Unavailable : AgentMemorySourceTarget
}

fun agentMemoryUiItems(
    agentId: String,
    memories: List<AgentMemory>,
): List<AgentMemoryUiItem> = memories
    .asSequence()
    .filter { it.agentId == agentId }
    .sortedWith(
        compareBy<AgentMemory> { it.kind.uiPriority() }
            .thenByDescending(AgentMemory::updatedAt)
            .thenBy(AgentMemory::id),
    )
    .map { memory ->
        AgentMemoryUiItem(
            memory = memory,
            typeLabel = memory.kind.uiLabel(),
        )
    }
    .toList()

fun validateAgentMemoryEdit(content: String): AgentMemoryEditValidation {
    val normalized = content.trim()
    return when {
        normalized.isEmpty() -> AgentMemoryEditValidation.Invalid("内容不能为空")
        normalized.length > MAX_AGENT_MEMORY_CONTENT_CHARS ->
            AgentMemoryEditValidation.Invalid("内容不能超过 $MAX_AGENT_MEMORY_CONTENT_CHARS 个字符")
        else -> AgentMemoryEditValidation.Valid(normalized)
    }
}

fun canOperateAgentMemory(agentId: String, memory: AgentMemory): Boolean =
    agentId.isNotBlank() && memory.agentId == agentId

fun resolveAgentMemorySourceTarget(
    currentConversationId: String,
    memory: AgentMemory,
    sourceAvailable: Boolean,
): AgentMemorySourceTarget {
    if (
        !sourceAvailable ||
        memory.sourceConversationId.isBlank() ||
        memory.sourceMessageId.isBlank()
    ) {
        return AgentMemorySourceTarget.Unavailable
    }
    return if (memory.sourceConversationId == currentConversationId) {
        AgentMemorySourceTarget.CurrentConversation(memory.sourceMessageId)
    } else {
        AgentMemorySourceTarget.OtherConversation(
            conversationId = memory.sourceConversationId,
            messageId = memory.sourceMessageId,
        )
    }
}

fun agentMemoryClearConfirmationText(): String =
    "清空当前人物的全部关系记忆？这不会删除会话、项目或人物包。"

private fun AgentMemoryKind.uiPriority(): Int = when (this) {
    AgentMemoryKind.ADDRESS_PREFERENCE -> 0
    AgentMemoryKind.USER_PREFERENCE -> 1
    AgentMemoryKind.RELATIONSHIP_EVENT -> 2
    AgentMemoryKind.SHARED_HISTORY -> 3
}

private fun AgentMemoryKind.uiLabel(): String = when (this) {
    AgentMemoryKind.ADDRESS_PREFERENCE -> "称呼偏好"
    AgentMemoryKind.USER_PREFERENCE -> "稳定偏好"
    AgentMemoryKind.RELATIONSHIP_EVENT -> "关系变化"
    AgentMemoryKind.SHARED_HISTORY -> "共同经历"
}
