package com.harnessapk.chat

import com.harnessapk.wiki.ConversationWikiException
import com.harnessapk.wiki.WikiRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExecutionModelsTest {
    @Test
    fun nextExecutionSequenceIncrementsWithinConversation() {
        val entries = listOf(
            executionEntry(sequence = 1),
            executionEntry(sequence = 4),
        )

        assertEquals(5L, nextExecutionSequence(entries))
    }

    @Test
    fun requestHistoryExcludesOtherQueuedUserMessages() {
        val messages = listOf(
            chatMessage(id = "user-complete", role = MessageRole.USER, content = "已完成"),
            chatMessage(id = "assistant-complete", role = MessageRole.ASSISTANT, content = "已回复"),
            chatMessage(id = "user-current", role = MessageRole.USER, content = "当前"),
            chatMessage(id = "user-future", role = MessageRole.USER, content = "未来"),
        )
        val entries = listOf(
            executionEntry(id = "complete", userMessageId = "user-complete", status = ChatExecutionStatus.SUCCEEDED),
            executionEntry(id = "current", userMessageId = "user-current", status = ChatExecutionStatus.RUNNING),
            executionEntry(id = "future", userMessageId = "user-future", status = ChatExecutionStatus.QUEUED),
        )

        assertEquals(
            listOf("已完成", "已回复", "当前"),
            executionRequestHistory(messages, entries, currentEntryId = "current").map(ChatMessage::content),
        )
    }

    @Test
    fun recoveryRequeuesOnlyRunningEntry() {
        assertEquals(
            ChatExecutionStatus.QUEUED,
            recoveredExecutionStatus(ChatExecutionStatus.RUNNING),
        )
        assertEquals(
            ChatExecutionStatus.QUEUED,
            recoveredExecutionStatus(ChatExecutionStatus.QUEUED),
        )
    }

    @Test
    fun foregroundServiceStopsOnlyWhenNoOpenExecutionRemains() {
        assertFalse(shouldStopForegroundService(activeCount = 1, hasOpenWork = false))
        assertFalse(shouldStopForegroundService(activeCount = 0, hasOpenWork = true))
        assertTrue(shouldStopForegroundService(activeCount = 0, hasOpenWork = false))
    }

    @Test
    fun runningExecutionIsRecoveredOnlyWithoutCurrentRunner() {
        assertTrue(shouldRecoverRunningExecution(hasActiveRunner = false))
        assertFalse(shouldRecoverRunningExecution(hasActiveRunner = true))
    }

    @Test
    fun requestContextRoundTripsThroughPersistentSnapshot() {
        val context = ChatExecutionRequestContext(
            webSearchEnabled = true,
            webSearchSettings = com.harnessapk.websearch.WebSearchSettings(maxResults = 8),
        )

        assertEquals(context, decodeExecutionRequestContext(encodeExecutionRequestContext(context)))
    }

    @Test
    fun contextSnapshotV2RoundTripsEveryImmutableField() {
        val snapshot = ContextSnapshotV2(
            projectId = "project-1",
            projectName = "长辈健康",
            projectContextSha256 = "a".repeat(64),
            agentId = "doctor",
            agentVersion = 7,
            wikiScope = listOf(WikiRef("health.guide", 3)),
            providerId = "openai",
            model = "gpt-5.6-terra",
            reasoningEffort = ReasoningEffort.XHIGH.name,
            webSearchEnabled = true,
            attachments = listOf(
                AttachmentSnapshot(
                    mimeType = "image/jpeg",
                    sizeBytes = 123L,
                    sha256 = "b".repeat(64),
                ),
            ),
            capturedAt = 1_234L,
        )
        val context = ChatExecutionRequestContext(
            wikiScopeSnapshot = snapshot.wikiScope,
            contextSnapshot = snapshot,
        )

        val encoded = encodeExecutionRequestContext(context)
        val decoded = decodeExecutionRequestContext(encoded)

        assertTrue(encoded.contains("\"schemaVersion\":2"))
        assertEquals(snapshot, decoded.contextSnapshot)
        assertEquals(snapshot.wikiScope, decoded.wikiScopeSnapshot)
    }

    @Test
    fun contextSnapshotV3RoundTripsProjectEvidenceWithoutBreakingV2() {
        val snapshot = ContextSnapshotV3(
            schemaVersion = 3,
            projectId = "project-1",
            projectName = "M3",
            projectContextSha256 = "a".repeat(64),
            agentId = "agent",
            agentVersion = 3,
            wikiScope = listOf(WikiRef("project.wiki", 2)),
            providerId = "openai",
            model = "gpt-5.6-terra",
            reasoningEffort = ReasoningEffort.HIGH.name,
            webSearchEnabled = false,
            attachments = emptyList(),
            capturedAt = 2_345L,
            retrievalRunId = "retrieval-1",
            projectEvidenceIds = listOf("evidence-1", "evidence-2"),
            relationshipMemoryIds = listOf("memory-1"),
        )

        val decoded = decodeExecutionRequestContext(
            encodeExecutionRequestContext(ChatExecutionRequestContext(contextSnapshot = snapshot)),
        ).contextSnapshot

        assertEquals(3, decoded?.schemaVersion)
        assertEquals("retrieval-1", decoded?.retrievalRunId)
        assertEquals(listOf("evidence-1", "evidence-2"), decoded?.projectEvidenceIds)
        assertEquals(listOf("memory-1"), decoded?.relationshipMemoryIds)
    }

    @Test
    fun legacyV1ContextDecodesWithoutInventingV2Snapshot() {
        val decoded = decodeExecutionRequestContext(
            """{"webSearchEnabled":true,"wikiScopeSnapshot":[]}""",
        )

        assertNull(decoded.contextSnapshot)
        assertEquals(emptyList<WikiRef>(), decoded.wikiScopeSnapshot)
    }

    @Test
    fun contextHashUsesUtf8Sha256AndNullWithoutProject() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            projectContextSha256(projectId = "project", projectContext = "abc"),
        )
        assertNull(projectContextSha256(projectId = null, projectContext = "abc"))
    }

    @Test
    fun executionContextRoundTripsExplicitEmptyWikiScope() {
        val context = ChatExecutionRequestContext(wikiScopeSnapshot = emptyList())

        assertEquals(
            emptyList<WikiRef>(),
            decodeExecutionRequestContext(encodeExecutionRequestContext(context)).wikiScopeSnapshot,
        )
    }

    @Test
    fun executionContextWithoutWikiScopeRemainsLegacyNull() {
        assertNull(decodeExecutionRequestContext("{}").wikiScopeSnapshot)
    }

    @Test
    fun executionContextRejectsDuplicateWikiScopeEntries() {
        val raw = """{"wikiScopeSnapshot":[{"wikiId":"history.zztj","version":1},{"wikiId":"history.zztj","version":1}]}"""

        assertTrue(
            runCatching { decodeExecutionRequestContext(raw) }.exceptionOrNull() is ConversationWikiException,
        )
    }

    @Test
    fun legacyScopeCapturesOnceWhileExplicitEmptyScopeNeverFallsBack() = runTest {
        var snapshotCalls = 0
        val snapshot: suspend () -> List<WikiRef> = {
            snapshotCalls += 1
            listOf(WikiRef("history.zztj", 1))
        }

        val capturedLegacy = captureLegacyWikiScopeSnapshot(
            context = ChatExecutionRequestContext(),
            currentScope = snapshot,
        )
        val retainedEmpty = captureLegacyWikiScopeSnapshot(
            context = ChatExecutionRequestContext(wikiScopeSnapshot = emptyList()),
            currentScope = snapshot,
        )

        assertEquals(listOf(WikiRef("history.zztj", 1)), capturedLegacy.wikiScopeSnapshot)
        assertEquals(emptyList<WikiRef>(), retainedEmpty.wikiScopeSnapshot)
        assertEquals(1, snapshotCalls)
    }

    @Test
    fun executionHistoryAddsCurrentUserMessageOnlyOnce() {
        val current = chatMessage(id = "user-current", role = MessageRole.USER, content = "当前")
        val history = listOf(
            chatMessage(id = "assistant", role = MessageRole.ASSISTANT, content = "历史回复"),
            current,
        )

        assertEquals(
            listOf("历史回复", "当前"),
            executionHistoryWithCurrent(history, current).map(ChatMessage::content),
        )
    }

    @Test
    fun foregroundNotificationSummarizesActiveConversations() {
        assertEquals("正在生成 2 个回复", foregroundNotificationText(activeCount = 2))
    }

    @Test
    fun finishedRunnerDoesNotRemoveItsReplacement() {
        val original = Any()
        val replacement = Any()

        assertFalse(shouldRemoveRunner(replacement, original))
        assertTrue(shouldRemoveRunner(original, original))
    }

    @Test
    fun automaticRetryBackoffIsBoundedAndIncreases() {
        assertEquals(1_000L, automaticRetryDelayMillis(1))
        assertEquals(2_500L, automaticRetryDelayMillis(2))
        assertEquals(2_500L, automaticRetryDelayMillis(3))
    }

    @Test
    fun persistedReplyCompletionNotifiesMemoryOnlyForSuccessAndIsolatesCallbackFailure() {
        val notified = mutableListOf<String>()

        ChatExecutionStatus.entries.forEach { status ->
            notifyAgentMemoryAfterTerminalPersistence(
                conversationId = "conversation-$status",
                status = status,
                onReplyCompleted = notified::add,
            )
        }
        notifyAgentMemoryAfterTerminalPersistence(
            conversationId = "callback-failure",
            status = ChatExecutionStatus.SUCCEEDED,
            onReplyCompleted = { throw IllegalStateException("memory callback failure") },
        )

        assertEquals(listOf("conversation-SUCCEEDED"), notified)
    }

    private fun executionEntry(
        id: String = "entry",
        userMessageId: String = "user",
        sequence: Long = 1L,
        status: ChatExecutionStatus = ChatExecutionStatus.QUEUED,
    ) = ChatExecutionEntry(
        id = id,
        conversationId = "conversation",
        userMessageId = userMessageId,
        assistantMessageId = null,
        targetAssistantMessageId = null,
        sequence = sequence,
        type = ChatExecutionType.NORMAL,
        status = status,
        providerId = null,
        model = null,
        reasoningEffort = ReasoningEffort.HIGH,
        requestContext = ChatExecutionRequestContext(),
        errorMessage = null,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun chatMessage(
        id: String,
        role: MessageRole,
        content: String,
    ) = ChatMessage(
        id = id,
        conversationId = "conversation",
        role = role,
        content = content,
        status = MessageStatus.SUCCEEDED,
        providerId = null,
        model = null,
        errorMessage = null,
    )
}
