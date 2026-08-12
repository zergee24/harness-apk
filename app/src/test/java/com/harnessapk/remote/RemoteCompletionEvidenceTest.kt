package com.harnessapk.remote

import com.harnessapk.storage.RemoteRunEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCompletionEvidenceTest {
    @Test
    fun unknownItemShowsGenericStatusAndKeepsDiagnosticPayload() {
        val event = event(
            id = "event-1",
            itemId = "item-unknown",
            presentationKind = "FUTURE_TOOL",
            payload = """{"futureField":"diagnostic-value"}""",
        )

        val item = presentRemoteTimeline(event)

        assertEquals("正在处理", item.title)
        assertTrue(item.diagnosticPayload.contains("diagnostic-value"))
    }

    @Test
    fun repeatedAgentDeltaUpsertsOneTimelineItem() {
        val collapsed = collapseRemoteTimeline(
            listOf(
                event("event-1", "agent-1", "AGENT_DELTA", """{"delta":"正在"}"""),
                event("event-2", "agent-1", "AGENT_DELTA", """{"delta":"分析"}"""),
            ),
        )

        assertEquals(1, collapsed.size)
        assertEquals("正在分析", collapsed.single().detail)
    }

    @Test
    fun missingTestEventRendersUnverified() {
        val evidence = parseRemoteCompletionEvidence(
            """{"summary":"完成修复","changedFiles":[],"tests":[],"unresolved":[],"completedAt":10}""",
        )

        assertEquals("测试未验证", evidence.testSummary)
        assertEquals("文件未验证", evidence.fileSummary)
        assertEquals("Git 未验证", evidence.gitSummary)
    }

    private fun event(
        id: String,
        itemId: String?,
        presentationKind: String,
        payload: String,
    ) = RemoteRunEventEntity(
        logicalEventId = id,
        runId = "run-1",
        hostId = "host-1",
        deviceId = "device-1",
        sequence = id.substringAfterLast('-').toLong(),
        type = "run.timeline",
        itemId = itemId,
        presentationKind = presentationKind,
        payloadJson = payload,
        createdAt = 1L,
    )
}
