package com.harnessapk.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRepositoryTest {
    private val profile = RemoteProfile(
        relayUrl = "https://relay.example.com",
        hostId = "mac",
        hostName = "Mac",
        deviceId = "phone",
        deviceToken = "token",
        pairingTicket = "ticket",
        pairingSecret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 }),
    )

    @Test
    fun failedTurnSendDoesNotCreateOptimisticWorkingState() {
        val repository = repository()
        repository.selectThread("thread-a")

        repository.startTurn("hello")

        assertFalse(repository.state.value.isWorking)
        assertTrue(repository.state.value.timeline.isEmpty())
    }

    @Test
    fun failedApprovalSendKeepsApprovalForRetry() {
        val repository = repository()
        val approval = RemoteApproval(
            requestId = kotlinx.serialization.json.JsonPrimitive(7),
            method = "item/commandExecution/requestApproval",
            threadId = "thread-a",
            turnId = "turn-a",
            reason = "Run command",
            command = "git status",
        )
        repository.handleEvent(
            RemoteEvent(
                type = "approval.request",
                payload = kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"id":7,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread-a","turnId":"turn-a","reason":"Run command","command":"git status"}}""",
                ),
            ),
        )

        repository.respondToApproval(approval, "accept")

        assertEquals(listOf(approval), repository.state.value.approvals)
    }

    @Test
    fun structuredUserMessageContentRendersAsPlainConversationText() {
        val repository = repository()
        repository.selectThread("thread-a")

        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"item/completed","params":{"threadId":"thread-a","item":{"id":"user-1","type":"userMessage","content":[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}],"status":"completed"}}}""",
                ),
            ),
        )

        assertEquals(listOf("第一段\n第二段"), repository.state.value.timeline.map { it.text })
    }

    @Test
    fun completedAgentItemReconcilesItsStreamingDeltaWithoutDuplicateCard() {
        val repository = repository()
        repository.selectThread("thread-a")
        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"item/agentMessage/delta","params":{"threadId":"thread-a","turnId":"turn-a","itemId":"agent-1","delta":"O"}}""",
                ),
            ),
        )
        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"item/completed","params":{"threadId":"thread-a","turnId":"turn-a","item":{"id":"agent-1","type":"agentMessage","text":"OK","status":"completed"}}}""",
                ),
            ),
        )

        assertEquals(listOf(RemoteTimelineItem("agent-1", "agentMessage", "OK", "completed")), repository.state.value.timeline)
    }

    @Test
    fun switchingThreadsDoesNotOfferSteeringForAnotherThreadsActiveTurn() {
        val repository = repository()
        repository.selectThread("thread-a")
        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"turn/started","params":{"threadId":"thread-a","turn":{"id":"turn-a"}}}""",
                ),
            ),
        )
        assertTrue(repository.state.value.isWorking)

        repository.clearSelection()
        repository.selectThread("thread-b")
        assertFalse(repository.state.value.isWorking)

        repository.clearSelection()
        repository.selectThread("thread-a")
        assertTrue(repository.state.value.isWorking)
    }

    @Test
    fun completionForBackgroundThreadClearsItsSteeringState() {
        val repository = repository()
        repository.selectThread("thread-a")
        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"turn/started","params":{"threadId":"thread-a","turn":{"id":"turn-a"}}}""",
                ),
            ),
        )
        repository.clearSelection()
        repository.selectThread("thread-b")

        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"turn/completed","params":{"threadId":"thread-a","turn":{"id":"turn-a"}}}""",
                ),
            ),
        )
        repository.clearSelection()
        repository.selectThread("thread-a")

        assertFalse(repository.state.value.isWorking)
    }

    @Test
    fun failedTurnStartClearsOnlyItsOptimisticWorkingState() {
        val repository = repository()
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            selectedThreadId = "thread-a",
            activeThreadId = "thread-a",
            isWorking = true,
        )
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(repository) as MutableMap<String, PendingRemoteCommand>
        pending["turn.start:failed"] = PendingRemoteCommand("turn.start", threadId = "thread-a")

        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                requestId = "turn.start:failed",
                payload = Json.parseToJsonElement("""{"error":{"message":"turn rejected"}}"""),
            ),
        )

        assertFalse(repository.state.value.isWorking)
        assertEquals(null, repository.state.value.activeThreadId)
        assertEquals(null, repository.state.value.activeTurnId)
    }

    @Test
    fun completedReasoningWithoutReadableSummaryDoesNotCreateAnEmptyCard() {
        val repository = repository()
        repository.selectThread("thread-a")

        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"item/completed","params":{"threadId":"thread-a","item":{"id":"reasoning-1","type":"reasoning","summary":"","text":"","status":"completed"}}}""",
                ),
            ),
        )

        assertTrue(repository.state.value.timeline.isEmpty())
    }

    @Test
    fun serverUserItemReplacesMatchingOptimisticMessage() {
        val optimistic = RemoteTimelineItem(
            id = "pending-user:turn.start:request-1",
            kind = "userMessage",
            text = "同一条消息",
            status = "sending",
        )
        val serverItem = RemoteTimelineItem("user-real", "userMessage", "同一条消息", "completed")

        assertEquals(listOf(serverItem), mergeRemoteTimeline(listOf(optimistic), serverItem))
    }

    @Test
    fun successfulHostStatusClearsStaleConnectionError() {
        val repository = repository()
        repository.handleEvent(RemoteEvent(type = "error", message = "temporary failure"))
        assertEquals("temporary failure", repository.state.value.errorMessage)

        repository.handleEvent(
            RemoteEvent(
                type = "host.status",
                payload = Json.parseToJsonElement("""{"schemaVersion":1,"capabilities":[]}"""),
            ),
        )

        assertEquals(null, repository.state.value.errorMessage)
        assertEquals(RemoteConnectionStatus.CONNECTED, repository.state.value.connectionStatus)
    }

    @Test
    fun newlyCreatedThreadOpensWithoutReadingUnmaterializedHistory() {
        val repository = repository()

        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.start:request-1",
                payload = Json.parseToJsonElement("""{"result":{"thread":{"id":"thread-new"}}}"""),
            ),
        )

        assertEquals("thread-new", repository.state.value.selectedThreadId)
        assertTrue(repository.state.value.timeline.isEmpty())
        assertEquals(null, repository.state.value.errorMessage)
    }

    @Test
    fun refreshingWorkspaceCandidatesKeepsTheLastSuccessfulChoicesVisible() {
        val repository = repository()
        val cached = WorkspaceCandidate(
            workspaceId = "workspace-1",
            displayName = "Harness APK",
            cwd = "/Users/tony/Documents/harness-apk",
            repositoryLabel = "harness-apk",
            branch = "test",
            repositoryFingerprint = "fingerprint",
            lastUsedAt = 1L,
        )
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            workspaceCandidates = listOf(cached),
            workspaceCandidatesLoaded = true,
        )

        repository.requestWorkspaceCandidates()

        assertEquals(listOf(cached), repository.state.value.workspaceCandidates)
        assertTrue(repository.state.value.workspaceCandidatesLoaded)
    }

    @Test
    fun threadReadErrorIsFriendlyRedactedAndStopsLoading() {
        val repository = repository()
        repository.selectThread("thread-secret-123")

        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.read:request-1",
                payload = Json.parseToJsonElement(
                    """{"error":{"message":"thread thread-secret-123 at /Users/tony/private-repo is not materialized yet"}}""",
                ),
            ),
        )

        assertFalse(repository.state.value.isTimelineLoading)
        assertEquals("会话正在初始化，请先发送第一条消息", repository.state.value.errorMessage)
        assertFalse(repository.state.value.errorMessage.orEmpty().contains("thread-secret-123"))
        assertFalse(repository.state.value.errorMessage.orEmpty().contains("/Users/tony"))
    }

    @Test
    fun olderThreadPagePrependsWithoutReplacingLatestHistory() {
        val repository = repository()
        repository.selectThread("thread-a")
        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.read:request-1",
                payload = Json.parseToJsonElement(
                    """{"result":{"thread":{"id":"thread-a","turns":[{"id":"turn-new","items":[{"id":"agent-new","type":"agentMessage","text":"最新消息"}]}]},"mobileHistory":{"olderCursor":"cursor-older"}}}""",
                ),
            ),
        )

        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.read.older:request-2",
                payload = Json.parseToJsonElement(
                    """{"result":{"thread":{"id":"thread-a","turns":[{"id":"turn-old","items":[{"id":"user-old","type":"userMessage","text":"更早消息"}]}]},"mobileHistory":{"olderCursor":null}}}""",
                ),
            ),
        )

        assertEquals(listOf("user-old", "agent-new"), repository.state.value.timeline.map { it.id })
        assertEquals(listOf("更早消息", "最新消息"), repository.state.value.timeline.map { it.text })
    }

    @Test
    fun olderThreadPageFromPreviousSelectionCannotPolluteCurrentConversation() {
        val repository = repository()
        repository.selectThread("thread-a")
        repository.selectThread("thread-b")
        val stateBeforeStaleResponse = repository.state.value

        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.read.older:stale-request",
                payload = Json.parseToJsonElement(
                    """{"result":{"thread":{"id":"thread-a","turns":[{"id":"turn-old","items":[{"id":"user-old","type":"userMessage","text":"旧会话内容"}]}]},"mobileHistory":{"olderCursor":null}}}""",
                ),
            ),
        )

        assertEquals(stateBeforeStaleResponse, repository.state.value)
    }

    @Test
    fun historySnapshotCannotOverwriteNewerRealtimeItem() {
        val repository = repository()
        repository.selectThread("thread-a")
        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"item/completed","params":{"threadId":"thread-a","item":{"id":"agent-new","type":"agentMessage","text":"实时完整回答","status":"completed"}}}""",
                ),
            ),
        )

        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.read:late-snapshot",
                payload = Json.parseToJsonElement(
                    """{"result":{"thread":{"id":"thread-a","turns":[{"id":"turn-new","items":[{"id":"user-new","type":"userMessage","text":"最近问题"},{"id":"agent-new","type":"agentMessage","text":"旧摘要","status":"streaming"}]}]},"mobileHistory":{"olderCursor":"older"}}}""",
                ),
            ),
        )

        assertEquals(listOf("最近问题", "实时完整回答"), repository.state.value.timeline.map { it.text })
        assertEquals("completed", repository.state.value.timeline.last().status)
    }

    @Test
    fun unchangedOlderCursorWithNoNewItemsStopsPagination() {
        val repository = repository()
        repository.selectThread("thread-a")
        val unchangedPage =
            """{"result":{"thread":{"id":"thread-a","turns":[{"id":"turn-new","items":[{"id":"agent-new","type":"agentMessage","text":"最新消息"}]}]},"mobileHistory":{"olderCursor":"same-cursor"}}}"""
        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.read:initial",
                payload = Json.parseToJsonElement(unchangedPage),
            ),
        )
        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.read.older:repeated",
                payload = Json.parseToJsonElement(unchangedPage),
            ),
        )

        assertEquals(null, repository.state.value.olderTimelineCursor)
        assertEquals(listOf("agent-new"), repository.state.value.timeline.map { it.id })
    }

    @Test
    fun staleThreadReadErrorCannotStopTheNewSelectionLoadingState() {
        val repository = repository()
        repository.selectThread("thread-b")
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(isTimelineLoading = true, errorMessage = null)
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(repository) as MutableMap<String, PendingRemoteCommand>
        pending["thread.read:stale"] = PendingRemoteCommand("thread.read", threadId = "thread-a")
        val stateBeforeStaleError = repository.state.value

        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.read:stale",
                payload = Json.parseToJsonElement("""{"error":{"message":"old thread failed"}}"""),
            ),
        )

        assertEquals(stateBeforeStaleError, repository.state.value)
    }

    @Test
    fun staleSuccessFromAnEarlierVisitCannotPolluteAReselectedThread() {
        val repository = repository()
        repository.selectThread("thread-a")
        repository.selectThread("thread-b")
        repository.selectThread("thread-a")
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(repository) as MutableMap<String, PendingRemoteCommand>
        pending["thread.read:stale-cycle"] = PendingRemoteCommand(
            kind = "thread.read",
            threadId = "thread-a",
            selectionGeneration = 1,
        )
        val stateBeforeStaleResponse = repository.state.value

        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.read:stale-cycle",
                payload = Json.parseToJsonElement(
                    """{"result":{"thread":{"id":"thread-a","turns":[{"id":"old-turn","items":[{"id":"old-item","type":"agentMessage","text":"旧访问周期"}]}]},"mobileHistory":{"olderCursor":"old-cursor"}}}""",
                ),
            ),
        )

        assertEquals(stateBeforeStaleResponse, repository.state.value)
    }

    @Test
    fun staleErrorFromAnEarlierVisitCannotStopAReselectedThreadLoading() {
        val repository = repository()
        repository.selectThread("thread-a")
        repository.selectThread("thread-b")
        repository.selectThread("thread-a")
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(repository) as MutableMap<String, PendingRemoteCommand>
        pending["thread.read:stale-cycle-error"] = PendingRemoteCommand(
            kind = "thread.read",
            threadId = "thread-a",
            selectionGeneration = 1,
        )
        val stateBeforeStaleError = repository.state.value

        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.read:stale-cycle-error",
                payload = Json.parseToJsonElement("""{"error":{"message":"old visit failed"}}"""),
            ),
        )

        assertEquals(stateBeforeStaleError, repository.state.value)
    }

    @Test
    fun threadListPreviewsAreBoundedForReadableCardsAndSemantics() {
        val preview = "x".repeat(2_000)
        val threads = parseThreads(
            RemoteEvent(
                type = "rpc.response",
                payload = Json.parseToJsonElement(
                    """{"result":{"data":[{"id":"thread-1","preview":"$preview","updatedAt":1}]}}""",
                ),
            ),
        )

        assertEquals(1, threads.size)
        assertEquals(60, threads.single().title.length)
        assertEquals(240, threads.single().preview.length)
    }

    @Test
    fun commandAndFileEventsRenderAsReadableSummariesInsteadOfRawJson() {
        val repository = repository()
        repository.selectThread("thread-a")

        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"item/completed","params":{"threadId":"thread-a","item":{"id":"command-1","type":"commandExecution","command":["git","status"],"status":"completed"}}}""",
                ),
            ),
        )
        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"item/completed","params":{"threadId":"thread-a","item":{"id":"file-1","type":"fileChange","changes":[{"path":"app/src/Main.kt","kind":"update"},{"path":"docs/readme.md","kind":"create"}],"status":"completed"}}}""",
                ),
            ),
        )

        assertEquals("git status", repository.state.value.timeline[0].text)
        assertEquals("修改 `app/src/Main.kt`\n新建 `docs/readme.md`", repository.state.value.timeline[1].text)
        assertFalse(repository.state.value.timeline.any { it.text.startsWith("[") || it.text.startsWith("{") })
    }

    private fun repository(remoteProfile: RemoteProfile = profile): RemoteRepository = RemoteRepository(
        profileStore = FakeRemoteProfileProvider(remoteProfile),
        httpClient = OkHttpClient(),
        scope = CoroutineScope(SupervisorJob()),
    )
}

private class FakeRemoteProfileProvider(initial: RemoteProfile?) : RemoteProfileProvider {
    override val profile = MutableStateFlow(initial)
}
