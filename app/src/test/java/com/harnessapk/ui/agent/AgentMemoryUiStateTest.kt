package com.harnessapk.ui.agent

import com.harnessapk.agentmemory.AgentMemory
import com.harnessapk.agentmemory.AgentMemoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryUiStateTest {
    @Test
    fun itemsFilterCurrentAgentAndUseFourLabelsInStableOrder() {
        val items = agentMemoryUiItems(
            agentId = "agent-a",
            memories = listOf(
                memory("shared", "agent-a", AgentMemoryKind.SHARED_HISTORY, "共同经历", 4L),
                memory("other", "agent-b", AgentMemoryKind.ADDRESS_PREFERENCE, "其他人物", 99L),
                memory("relationship", "agent-a", AgentMemoryKind.RELATIONSHIP_EVENT, "关系变化", 3L),
                memory("preference", "agent-a", AgentMemoryKind.USER_PREFERENCE, "稳定偏好", 2L),
                memory("address-b", "agent-a", AgentMemoryKind.ADDRESS_PREFERENCE, "较晚称呼", 5L),
                memory("address-a", "agent-a", AgentMemoryKind.ADDRESS_PREFERENCE, "同时间按 ID", 5L),
            ),
        )

        assertEquals(
            listOf("address-a", "address-b", "preference", "relationship", "shared"),
            items.map { it.memory.id },
        )
        assertEquals(
            listOf("称呼偏好", "称呼偏好", "稳定偏好", "关系变化", "共同经历"),
            items.map(AgentMemoryUiItem::typeLabel),
        )
        assertTrue(items.all { it.memory.agentId == "agent-a" })
    }

    @Test
    fun editValidationTrimsAndRejectsBlankOrOversizedContent() {
        assertEquals(
            AgentMemoryEditValidation.Valid("保留自然换行"),
            validateAgentMemoryEdit("  保留自然换行  "),
        )
        assertEquals("内容不能为空", (validateAgentMemoryEdit(" \n ") as AgentMemoryEditValidation.Invalid).message)
        assertEquals(
            "内容不能超过 2000 个字符",
            (validateAgentMemoryEdit("x".repeat(2_001)) as AgentMemoryEditValidation.Invalid).message,
        )
    }

    @Test
    fun operationAndSourceTargetsStayInsideCurrentAgentAndKeepExactIds() {
        val current = memory(
            id = "memory-a",
            agentId = "agent-a",
            kind = AgentMemoryKind.USER_PREFERENCE,
            content = "默认中文",
            updatedAt = 1L,
            sourceConversationId = "conversation-source",
            sourceMessageId = "message-source",
        )

        assertTrue(canOperateAgentMemory("agent-a", current))
        assertFalse(canOperateAgentMemory("agent-b", current))
        assertEquals(
            AgentMemorySourceTarget.CurrentConversation("message-source"),
            resolveAgentMemorySourceTarget(
                currentConversationId = "conversation-source",
                memory = current,
                sourceAvailable = true,
            ),
        )
        assertEquals(
            AgentMemorySourceTarget.OtherConversation("conversation-source", "message-source"),
            resolveAgentMemorySourceTarget(
                currentConversationId = "conversation-current",
                memory = current,
                sourceAvailable = true,
            ),
        )
        assertEquals(
            AgentMemorySourceTarget.Unavailable,
            resolveAgentMemorySourceTarget(
                currentConversationId = "conversation-current",
                memory = current,
                sourceAvailable = false,
            ),
        )
    }

    @Test
    fun clearConfirmationIsLimitedToCurrentAgentData() {
        assertEquals(
            "清空当前人物的全部关系记忆？这不会删除会话、项目或人物包。",
            agentMemoryClearConfirmationText(),
        )
    }

    private fun memory(
        id: String,
        agentId: String,
        kind: AgentMemoryKind,
        content: String,
        updatedAt: Long,
        sourceConversationId: String = "conversation-$id",
        sourceMessageId: String = "message-$id",
    ) = AgentMemory(
        id = id,
        agentId = agentId,
        kind = kind,
        content = content,
        sourceConversationId = sourceConversationId,
        sourceMessageId = sourceMessageId,
        confidence = 0.9,
        userEdited = false,
        createdAt = 1L,
        updatedAt = updatedAt,
    )
}
