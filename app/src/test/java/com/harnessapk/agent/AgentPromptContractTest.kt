package com.harnessapk.agent

import com.harnessapk.chat.MessageStatus
import com.harnessapk.chat.StreamingMessageSnapshot
import com.harnessapk.chat.UiMessagePartDraft
import com.harnessapk.chat.UiMessagePartType
import com.harnessapk.chat.legacyVisibleText
import com.harnessapk.storage.AgentVersionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptContractTest {
    @Test
    fun methodAdviceContractAnswersBeforeConditionsWithoutDatabaseDisclaimers() {
        val prompt = buildAgentSystemPrompt(version(), evidence())

        assertTrue(prompt.contains("先直接回答用户"))
        assertTrue(prompt.contains("默认使用自然段"))
        assertTrue(prompt.contains("只追问一个最关键的现实条件"))
        assertFalse(prompt.contains("当前资料不足"))
        assertFalse(prompt.contains("[资料 1]"))
    }

    @Test
    fun relationshipConversationDoesNotRequireOriginalEvidence() {
        val prompt = buildAgentSystemPrompt(version(), emptyList())

        assertTrue(prompt.contains("问候、承接前文和关系互动不要求原文证据"))
        assertFalse(prompt.contains("无法据此判断"))
    }

    @Test
    fun sanitizerRemovesInternalMarkersFromTextPartsOnly() {
        val sourcePart = UiMessagePartDraft(
            index = 1,
            type = UiMessagePartType.AGENT_SOURCES,
            content = "资料 1 · 实践论 · 第一章",
            stable = true,
        )
        val snapshot = StreamingMessageSnapshot(
            status = MessageStatus.SUCCEEDED,
            parts = listOf(
                UiMessagePartDraft(
                    index = 0,
                    type = UiMessagePartType.TEXT,
                    content = "先调查。[资料 1]再决定。[资料2][资料 3]",
                    metadata = mapOf("origin" to "stream"),
                    stable = true,
                ),
                sourcePart,
                UiMessagePartDraft(2, UiMessagePartType.TOOL_RESULT, "[资料4]", stable = true),
            ),
        )

        val sanitized = sanitizeAgentCitationMarkers(snapshot)

        assertEquals("先调查。再决定。", sanitized.legacyVisibleText())
        assertEquals(MessageStatus.SUCCEEDED, sanitized.status)
        assertEquals(0, sanitized.parts[0].index)
        assertEquals(mapOf("origin" to "stream"), sanitized.parts[0].metadata)
        assertEquals(sourcePart, sanitized.parts[1])
        assertEquals("[资料4]", sanitized.parts[2].content)
    }

    @Test
    fun sanitizerPreservesWhitespaceAroundMarkers() {
        val snapshot = StreamingMessageSnapshot(
            status = MessageStatus.SUCCEEDED,
            parts = listOf(
                UiMessagePartDraft(
                    index = 0,
                    type = UiMessagePartType.TEXT,
                    content = "First [资料1]second\n[资料 2]段二。[资料3][资料 4]",
                    stable = true,
                ),
            ),
        )

        val sanitized = sanitizeAgentCitationMarkers(snapshot)

        assertEquals("First second\n段二。", sanitized.legacyVisibleText())
    }

    private fun version() = AgentVersionEntity(
        agentId = "agent-1",
        version = 1,
        schemaVersion = 1,
        bundlePath = "/tmp/agent.hbundle",
        bundleSha256 = "sha",
        manifestJson = "{}",
        persona = "我重视从事实出发。",
        worldviewJsonl = "{\"statement\":\"调查先于结论\"}",
        installedAt = 1L,
        state = "READY",
    )

    private fun evidence() = listOf(
        AgentEvidence("chunk-1", "实践论", "第一章", "调查研究必须从事实出发。", 8),
    )
}
