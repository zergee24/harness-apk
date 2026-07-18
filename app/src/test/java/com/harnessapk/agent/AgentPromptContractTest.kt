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
    fun worldviewRendersOnlyWhitelistedSemanticFields() {
        val prompt = buildAgentSystemPrompt(
            version(
                """{"id":"view-investigation","statement":"调查应先于结论","scope":"方法论","period":"长期","conditions":"证据充分时","topic":"研究方法","evidence":["chunk-secret-42"],"confidence":1.0}""",
            ),
            emptyList(),
        )

        assertTrue(prompt.contains("调查应先于结论"))
        assertTrue(prompt.contains("适用范围：方法论"))
        assertTrue(prompt.contains("时间范围：长期"))
        assertTrue(prompt.contains("条件：证据充分时"))
        assertTrue(prompt.contains("主题：研究方法"))
        assertFalse(prompt.contains("chunk-secret-42"))
        assertFalse(prompt.contains("view-investigation"))
        assertFalse(prompt.contains("evidence"))
        assertFalse(prompt.contains("confidence"))
        assertFalse(prompt.contains("{"))
        assertFalse(prompt.contains("\""))
    }

    @Test
    fun worldviewRendersNonblankStringConditionsArrayInNaturalChinese() {
        val prompt = buildAgentSystemPrompt(
            version(
                """{"statement":"调查应先于结论","conditions":["事实充分","范围明确"]}""",
            ),
            emptyList(),
        )

        assertTrue(prompt.contains("条件：事实充分；范围明确"))
        assertFalse(prompt.contains("["))
        assertFalse(prompt.contains("\"conditions\""))
    }

    @Test
    fun worldviewRejectsInvalidConditionsArraysWithoutLeakingNestedInternalIds() {
        val prompt = buildAgentSystemPrompt(
            version(
                """
                {"statement":"空数组条件","conditions":[]}
                {"statement":"空白条件","conditions":["有效条件","   "]}
                {"statement":"混合条件","conditions":["有效条件",42]}
                {"statement":"对象条件","conditions":[{"id":"object-secret-id"}]}
                {"statement":"嵌套条件","conditions":[["nested-secret-id"]]}
                {"statement":"空值条件","conditions":[null]}
                """.trimIndent(),
            ),
            emptyList(),
        )

        assertTrue(prompt.contains("空数组条件"))
        assertTrue(prompt.contains("空白条件"))
        assertTrue(prompt.contains("混合条件"))
        assertTrue(prompt.contains("对象条件"))
        assertTrue(prompt.contains("嵌套条件"))
        assertTrue(prompt.contains("空值条件"))
        assertFalse(prompt.contains("条件："))
        assertFalse(prompt.contains("object-secret-id"))
        assertFalse(prompt.contains("nested-secret-id"))
        assertFalse(prompt.contains("{"))
        assertFalse(prompt.contains("["))
    }

    @Test
    fun worldviewOmitsUnknownNestedFieldsContainingInternalIds() {
        val prompt = buildAgentSystemPrompt(
            version(
                """{"statement":"先观察再判断","debug":{"chunkId":"nested-secret-7","trace":"trace-8"},"metadata":{"id":"unknown-id"}}""",
            ),
            emptyList(),
        )

        assertTrue(prompt.contains("先观察再判断"))
        assertFalse(prompt.contains("nested-secret-7"))
        assertFalse(prompt.contains("trace-8"))
        assertFalse(prompt.contains("unknown-id"))
        assertFalse(prompt.contains("debug"))
    }

    @Test
    fun worldviewSkipsMalformedAndStatementlessRowsWhileKeepingLaterRowsInOrder() {
        val prompt = buildAgentSystemPrompt(
            version(
                """
                {not-json}
                {"scope":"方法论","id":"missing-statement"}
                {"statement":"第一条立场"}
                {"statement":"   ","topic":"空白"}
                {"statement":"第二条立场","topic":"实践"}
                """.trimIndent(),
            ),
            emptyList(),
        )

        assertTrue(prompt.contains("第一条立场"))
        assertTrue(prompt.contains("第二条立场"))
        assertTrue(prompt.indexOf("第一条立场") < prompt.indexOf("第二条立场"))
        assertFalse(prompt.contains("missing-statement"))
        assertFalse(prompt.contains("{not-json}"))
        assertFalse(prompt.contains("主题：空白"))
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

    private fun version(worldviewJsonl: String = "{\"statement\":\"调查先于结论\"}") = AgentVersionEntity(
        agentId = "agent-1",
        version = 1,
        schemaVersion = 1,
        bundlePath = "/tmp/agent.hbundle",
        bundleSha256 = "sha",
        manifestJson = "{}",
        persona = "我重视从事实出发。",
        worldviewJsonl = worldviewJsonl,
        installedAt = 1L,
        state = "READY",
    )

    private fun evidence() = listOf(
        AgentEvidence("chunk-1", "实践论", "第一章", "调查研究必须从事实出发。", 8),
    )
}
