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
    fun steerWaitsUntilTheInitialTurnIdIsKnown() {
        val repository = repositoryWithPendingTurnStart("turn.start:pending")

        repository.steer("follow up")

        assertEquals("正在等待 Mac 建立当前任务，请稍后再发送", repository.state.value.errorMessage)
    }

    @Test
    fun steerRejectsATurnIdOwnedByAnotherThread() {
        val repository = repository()
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            selectedThreadId = "thread-b",
            activeThreadId = "thread-a",
            activeTurnId = "turn-a",
        )

        val sent = repository.steer("follow up")

        assertFalse(sent)
        assertEquals("正在等待 Mac 建立当前任务，请稍后再发送", repository.state.value.errorMessage)
    }

    @Test
    fun delayedRpcResponseFromPreviousBackendIsIgnoredAfterBackendSwitch() {
        val repository = repository()
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            backends = listOf(
                RemoteBackend("codex", "Codex", emptySet()),
                RemoteBackend("dsh", "DeepSeek Harness", emptySet()),
            ),
            selectedBackendId = "codex",
        )
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(repository) as MutableMap<String, PendingRemoteCommand>
        pending["thread.list:codex-old"] = PendingRemoteCommand("thread.list")

        repository.selectBackend("dsh")
        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                backendId = "codex",
                requestId = "thread.list:codex-old",
                payload = Json.parseToJsonElement(
                    """{"result":{"data":[{"id":"codex-thread","preview":"旧后端结果","updatedAt":1}]}}""",
                ),
            ),
        )

        assertEquals("dsh", repository.state.value.selectedBackendId)
        assertTrue(repository.state.value.threads.isEmpty())
    }

    @Test
    fun taggedErrorFromPreviousBackendIsIgnoredAfterBackendSwitch() {
        val repository = repository()
        setRepositoryState(
            repository,
            repository.state.value.copy(
                backends = listOf(
                    RemoteBackend("codex", "Codex", emptySet()),
                    RemoteBackend("dsh", "DeepSeek Harness", emptySet()),
                ),
                selectedBackendId = "dsh",
                selectedThreadId = "dsh-thread",
                activeThreadId = "dsh-thread",
                activeTurnId = "dsh-turn",
                isWorking = true,
            ),
        )

        repository.handleEvent(RemoteEvent(type = "error", backendId = "codex", message = "旧后端失败"))

        assertEquals(null, repository.state.value.errorMessage)
        assertEquals("dsh-thread", repository.state.value.activeThreadId)
        assertEquals("dsh-turn", repository.state.value.activeTurnId)
        assertTrue(repository.state.value.isWorking)
    }

    @Test
    fun taggedWorkspaceCandidatesFromPreviousBackendAreIgnoredAfterBackendSwitch() {
        val repository = repository()
        val existing = WorkspaceCandidate(
            workspaceId = "dsh-workspace",
            displayName = "DSH Workspace",
            cwd = "/dsh",
            repositoryLabel = "dsh",
            branch = "main",
            repositoryFingerprint = "dsh-fingerprint",
            lastUsedAt = 1L,
        )
        setRepositoryState(
            repository,
            repository.state.value.copy(
                backends = listOf(
                    RemoteBackend("codex", "Codex", emptySet()),
                    RemoteBackend("dsh", "DeepSeek Harness", emptySet()),
                ),
                selectedBackendId = "dsh",
                workspaceCandidates = listOf(existing),
                workspaceCandidatesLoaded = true,
            ),
        )

        repository.handleEvent(
            RemoteEvent(
                type = "workspace.candidates",
                backendId = "codex",
                payload = Json.parseToJsonElement(
                    """{"candidates":[{"workspaceId":"codex-workspace","displayName":"Codex Workspace","cwd":"/codex","repositoryLabel":"codex","branch":"main","repositoryFingerprint":"codex-fingerprint","lastUsedAt":2}]}""",
                ),
            ),
        )

        assertEquals(listOf(existing), repository.state.value.workspaceCandidates)
    }

    @Test
    fun staleSameBackendGenericResponsesCannotConsumeNewerPendingRequest() {
        val repository = repository()
        val existing = WorkspaceCandidate(
            workspaceId = "current", displayName = "Current", cwd = "/current",
            repositoryLabel = "repo", branch = "main", repositoryFingerprint = "fingerprint", lastUsedAt = 1L,
        )
        setRepositoryState(
            repository,
            repository.state.value.copy(
                selectedBackendId = "dsh",
                workspaceCandidates = listOf(existing),
                workspaceCandidatesLoaded = true,
                selectedThreadId = "thread-new",
                activeThreadId = "thread-new",
                activeTurnId = "turn-new",
                isWorking = true,
            ),
        )
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(repository) as MutableMap<String, PendingRemoteCommand>
        pending["workspace.list:new"] = PendingRemoteCommand("workspace.list", backendId = "dsh")

        repository.handleEvent(
            RemoteEvent(type = "error", backendId = "dsh", requestId = "turn.start:old", message = "旧请求失败"),
        )
        repository.handleEvent(
            RemoteEvent(
                type = "workspace.candidates", backendId = "dsh", requestId = "workspace.list:old",
                payload = Json.parseToJsonElement(
                    """{"candidates":[{"workspaceId":"old","displayName":"Old","cwd":"/old","repositoryLabel":"repo","branch":"main","repositoryFingerprint":"old","lastUsedAt":2}]}""",
                ),
            ),
        )

        assertEquals(null, repository.state.value.errorMessage)
        assertEquals("thread-new", repository.state.value.activeThreadId)
        assertEquals("turn-new", repository.state.value.activeTurnId)
        assertTrue(repository.state.value.isWorking)
        assertEquals(listOf(existing), repository.state.value.workspaceCandidates)
        assertTrue("workspace.list:new" in pending)
    }

    @Test
    fun pendingCommandAcceptsLegacyUntaggedResponseButRejectsWrongBackendTag() {
        val repository = repository()
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(repository) as MutableMap<String, PendingRemoteCommand>
        pending["thread.list:legacy-pending"] = PendingRemoteCommand(
            kind = "thread.list",
            backendId = DEFAULT_BACKEND_ID,
        )
        val responsePayload = Json.parseToJsonElement(
            """{"result":{"data":[{"id":"legacy-thread","preview":"兼容旧主机","updatedAt":1}]}}""",
        )

        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                backendId = "dsh",
                requestId = "thread.list:legacy-pending",
                payload = responsePayload,
            ),
        )
        assertTrue(repository.state.value.threads.isEmpty())

        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.list:legacy-pending",
                payload = responsePayload,
            ),
        )
        assertEquals(listOf("legacy-thread"), repository.state.value.threads.map(RemoteThread::id))

        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.list:legacy-pending",
                payload = Json.parseToJsonElement(
                    """{"result":{"data":[{"id":"resurrected-thread","preview":"不应复活","updatedAt":2}]}}""",
                ),
            ),
        )
        assertEquals(listOf("legacy-thread"), repository.state.value.threads.map(RemoteThread::id))
    }

    @Test
    fun transientPartialRosterRestoresTheUsersPreferredBackendWhenItReturns() {
        val repository = repository()
        val fullRoster = RemoteEvent(
            type = "host.status",
            payload = Json.parseToJsonElement(
                """{"schemaVersion":1,"capabilities":[],"backends":[
                    {"id":"codex","name":"Codex","capabilities":[]},
                    {"id":"dsh","name":"DeepSeek Harness","capabilities":[]}
                ]}""",
            ),
        )
        repository.handleEvent(fullRoster)
        repository.selectBackend("dsh")

        repository.handleEvent(
            RemoteEvent(
                type = "host.status",
                payload = Json.parseToJsonElement(
                    """{"schemaVersion":1,"capabilities":[],"backends":[{"id":"codex","name":"Codex","capabilities":[]}]}""",
                ),
            ),
        )
        assertEquals("codex", repository.state.value.selectedBackendId)

        repository.handleEvent(fullRoster)

        assertEquals("dsh", repository.state.value.selectedBackendId)
    }

    @Test
    fun hostStatusChangingBackendClearsBackendOwnedConversationState() {
        val repository = repository()
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            backends = listOf(RemoteBackend("codex", "Codex", emptySet())),
            selectedBackendId = "codex",
            threads = listOf(RemoteThread("thread-a", "旧会话", "旧内容", "/old", 1L, "active")),
            selectedThreadId = "thread-a",
            activeThreadId = "thread-a",
            activeTurnId = "turn-a",
            timeline = listOf(RemoteTimelineItem("item-a", "agentMessage", "旧回复", "completed")),
            approvals = listOf(
                RemoteApproval(Json.parseToJsonElement("1"), "approval", "thread-a", "turn-a", "旧审批", null),
            ),
            isWorking = true,
            isThreadListLoading = true,
            isTimelineLoading = true,
            olderTimelineCursor = "old-cursor",
            isOlderTimelineLoading = true,
            isCreatingThread = true,
        )

        repository.handleEvent(
            RemoteEvent(
                type = "host.status",
                payload = Json.parseToJsonElement(
                    """{"schemaVersion":1,"capabilities":[],"backends":[{"id":"dsh","name":"DeepSeek Harness","capabilities":[]}]}""",
                ),
            ),
        )

        val state = repository.state.value
        assertEquals("dsh", state.selectedBackendId)
        assertTrue(state.threads.isEmpty())
        assertEquals(null, state.selectedThreadId)
        assertEquals(null, state.activeThreadId)
        assertEquals(null, state.activeTurnId)
        assertTrue(state.timeline.isEmpty())
        assertTrue(state.approvals.isEmpty())
        assertFalse(state.isWorking)
        assertFalse(state.isThreadListLoading)
        assertFalse(state.isTimelineLoading)
        assertEquals(null, state.olderTimelineCursor)
        assertFalse(state.isOlderTimelineLoading)
        assertFalse(state.isCreatingThread)
    }

    @Test
    fun hostStatusChangingBackendRequestsFreshThreadListForNewBackend() {
        val repository = repository()
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            backends = listOf(RemoteBackend("codex", "Codex", emptySet())),
            selectedBackendId = "codex",
        )
        repository.handleEvent(
            RemoteEvent(
                type = "host.status",
                payload = Json.parseToJsonElement(
                    """{"schemaVersion":1,"capabilities":[],"backends":[{"id":"dsh","name":"DeepSeek Harness","capabilities":[]}]}""",
                ),
            ),
        )

        assertEquals("Mac 尚未连接，请稍后重试", repository.state.value.errorMessage)
        assertFalse(repository.state.value.isThreadListLoading)
    }

    @Test
    fun backendRosterRecoveryRefreshesWhenDefaultBackendReturnsAfterEmptyStatus() {
        val repository = repository()
        repository.handleEvent(
            RemoteEvent(
                type = "host.status",
                payload = Json.parseToJsonElement(
                    """{"schemaVersion":1,"capabilities":[],"backends":[]}""",
                ),
            ),
        )
        assertEquals(null, repository.state.value.errorMessage)

        repository.handleEvent(
            RemoteEvent(
                type = "host.status",
                payload = Json.parseToJsonElement(
                    """{"schemaVersion":1,"capabilities":[],"backends":[{"id":"codex","name":"Codex","capabilities":[]}]}""",
                ),
            ),
        )

        assertEquals("Mac 尚未连接，请稍后重试", repository.state.value.errorMessage)
        assertFalse(repository.state.value.isThreadListLoading)
    }

    @Test
    fun explicitEmptyBackendStatusClearsBackendOwnedConversationState() {
        val repository = repository()
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            backends = listOf(RemoteBackend("codex", "Codex", setOf("run.lifecycle.v1"))),
            selectedBackendId = "codex",
            capabilities = setOf("run.lifecycle.v1"),
            threads = listOf(RemoteThread("thread-a", "旧会话", "旧内容", "/old", 1L, "active")),
            selectedThreadId = "thread-a",
            activeThreadId = "thread-a",
            activeTurnId = "turn-a",
            timeline = listOf(RemoteTimelineItem("item-a", "agentMessage", "旧回复", "completed")),
            isWorking = true,
        )

        repository.handleEvent(
            RemoteEvent(
                type = "host.status",
                payload = Json.parseToJsonElement(
                    """{"schemaVersion":1,"capabilities":["legacy-capability"],"backends":[]}""",
                ),
            ),
        )

        val state = repository.state.value
        assertTrue(state.backends.isEmpty())
        assertTrue(state.capabilities.isEmpty())
        assertTrue(state.threads.isEmpty())
        assertEquals(null, state.selectedThreadId)
        assertEquals(null, state.activeThreadId)
        assertEquals(null, state.activeTurnId)
        assertTrue(state.timeline.isEmpty())
        assertFalse(state.isWorking)
    }

    @Test
    fun selectingBackendClearsBackendOwnedWorkspaceCandidates() {
        val repository = repository()
        setRepositoryState(
            repository,
            repository.state.value.copy(
                backends = listOf(
                    RemoteBackend("codex", "Codex", emptySet()),
                    RemoteBackend("dsh", "DeepSeek Harness", emptySet()),
                ),
                selectedBackendId = "codex",
                workspaceCandidates = listOf(workspaceCandidate("codex-workspace")),
                workspaceCandidatesLoaded = true,
            ),
        )

        repository.selectBackend("dsh")

        assertTrue(repository.state.value.workspaceCandidates.isEmpty())
        assertFalse(repository.state.value.workspaceCandidatesLoaded)
    }

    @Test
    fun hostRosterBackendChangeClearsBackendOwnedWorkspaceCandidates() {
        val repository = repository()
        setRepositoryState(
            repository,
            repository.state.value.copy(
                backends = listOf(RemoteBackend("codex", "Codex", emptySet())),
                selectedBackendId = "codex",
                workspaceCandidates = listOf(workspaceCandidate("codex-workspace")),
                workspaceCandidatesLoaded = true,
            ),
        )

        repository.handleEvent(
            RemoteEvent(
                type = "host.status",
                payload = Json.parseToJsonElement(
                    """{"schemaVersion":1,"backends":[{"id":"dsh","name":"DeepSeek Harness","capabilities":[]}]}""",
                ),
            ),
        )

        assertTrue(repository.state.value.workspaceCandidates.isEmpty())
        assertFalse(repository.state.value.workspaceCandidatesLoaded)
    }

    @Test
    fun explicitEmptyBackendStatusClearsBackendOwnedWorkspaceCandidates() {
        val repository = repository()
        setRepositoryState(
            repository,
            repository.state.value.copy(
                backends = listOf(RemoteBackend("codex", "Codex", emptySet())),
                selectedBackendId = "codex",
                workspaceCandidates = listOf(workspaceCandidate("codex-workspace")),
                workspaceCandidatesLoaded = true,
            ),
        )

        repository.handleEvent(
            RemoteEvent(
                type = "host.status",
                payload = Json.parseToJsonElement("""{"schemaVersion":1,"backends":[]}"""),
            ),
        )

        assertTrue(repository.state.value.workspaceCandidates.isEmpty())
        assertFalse(repository.state.value.workspaceCandidatesLoaded)
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
    fun approvalRequestMarksTheConversationAsWaitingForApproval() {
        val repository = repository()
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.list:seed",
                payload = Json.parseToJsonElement(
                    """{"result":{"data":[{"id":"thread-a","preview":"任务 A","updatedAt":2}]}}""",
                ),
            ),
        )
        repository.selectThread("thread-a")

        repository.handleEvent(
            RemoteEvent(
                type = "approval.request",
                payload = Json.parseToJsonElement(
                    """{"id":7,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread-a","turnId":"turn-a","reason":"Run command","command":"git status"}}""",
                ),
            ),
        )

        assertEquals(RemoteThreadExecutionState.WAITING_APPROVAL, repository.state.value.threads.single().execution.state)
        assertTrue(repository.state.value.isWorking)
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
    fun realtimeTurnStartMarksItsConversationRunningBeforeTheUserOpensIt() {
        val repository = repository()
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.list:seed",
                payload = Json.parseToJsonElement(
                    """{"result":{"data":[
                        {"id":"thread-a","preview":"任务 A","updatedAt":2},
                        {"id":"thread-b","preview":"任务 B","updatedAt":1}
                    ]}}""",
                ),
            ),
        )

        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"turn/started","params":{"threadId":"thread-a","turn":{"id":"turn-a","status":"inProgress"}}}""",
                ),
            ),
        )

        assertEquals(RemoteThreadExecutionState.RUNNING, repository.state.value.threads[0].execution.state)
        assertEquals(RemoteThreadExecutionState.UNKNOWN, repository.state.value.threads[1].execution.state)
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
    fun realtimeFailedTurnKeepsAVisibleFailedTerminalState() {
        val repository = repository()
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.list:seed",
                payload = Json.parseToJsonElement(
                    """{"result":{"data":[{"id":"thread-a","preview":"任务 A","updatedAt":2}]}}""",
                ),
            ),
        )
        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"turn/started","params":{"threadId":"thread-a","turn":{"id":"turn-a"}}}""",
                ),
            ),
        )

        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"turn/completed","params":{"threadId":"thread-a","turn":{"id":"turn-a","status":"failed","startedAt":10,"completedAt":20}}}""",
                ),
            ),
        )

        val execution = repository.state.value.threads.single().execution
        assertEquals(RemoteThreadExecutionState.FAILED, execution.state)
        assertEquals(20_000L, execution.completedAtMillis)
    }

    @Test
    fun delayedStartedNotificationCannotResurrectTerminalTurn() {
        val repository = repository()
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.list:seed",
                payload = Json.parseToJsonElement(
                    """{"result":{"data":[{"id":"thread-a","preview":"任务 A","updatedAt":2}]}}""",
                ),
            ),
        )
        repository.selectThread("thread-a")
        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"turn/started","params":{"threadId":"thread-a","turn":{"id":"turn-a"}}}""",
                ),
            ),
        )
        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"turn/completed","params":{"threadId":"thread-a","turn":{"id":"turn-a","status":"failed"}}}""",
                ),
            ),
        )

        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"turn/started","params":{"threadId":"thread-a","turn":{"id":"turn-a"}}}""",
                ),
            ),
        )

        assertEquals(RemoteThreadExecutionState.FAILED, repository.state.value.threads.single().execution.state)
        assertEquals(null, repository.state.value.activeThreadId)
        assertEquals(null, repository.state.value.activeTurnId)
        assertFalse(repository.state.value.isWorking)
    }

    @Test
    fun threadStatusChangeDistinguishesWaitingForUserFromGenericRunning() {
        val repository = repository()
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.list:seed",
                payload = Json.parseToJsonElement(
                    """{"result":{"data":[{"id":"thread-a","preview":"任务 A","updatedAt":2}]}}""",
                ),
            ),
        )

        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"thread/status/changed","params":{"threadId":"thread-a","status":{"type":"active","activeFlags":["waitingOnUserInput"]}}}""",
                ),
            ),
        )

        assertEquals(RemoteThreadExecutionState.WAITING_USER, repository.state.value.threads.single().execution.state)
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

        repository.handlePendingRpcResponse(
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
    fun successfulTurnStartStopsShowingTheOptimisticMessageAsSending() {
        val repository = repositoryWithPendingTurnStart("turn.start:success")

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "turn.start:success",
                payload = Json.parseToJsonElement("""{"result":{"turn":{"id":"turn-real"}}}"""),
            ),
        )

        assertEquals("sent", repository.state.value.timeline.single().status)
        assertTrue(repository.state.value.isWorking)
        assertEquals("turn-real", repository.state.value.activeTurnId)
    }

    @Test
    fun turnStartResponseRestoresRunningAfterOptimisticRouteLossWithoutTerminalEvidence() {
        val repository = repositoryWithPendingTurnStart("turn.start:route-loss")
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            activeThreadId = null,
            activeTurnId = null,
            isWorking = false,
            threads = listOf(
                RemoteThread(
                    id = "thread-a",
                    title = "会话",
                    preview = "继续任务",
                    cwd = "/workspace",
                    updatedAt = 1L,
                    status = "active",
                    execution = RemoteThreadExecution(RemoteThreadExecutionState.UNKNOWN),
                ),
            ),
        )

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                backendId = DEFAULT_BACKEND_ID,
                requestId = "turn.start:route-loss",
                payload = Json.parseToJsonElement(
                    """{"result":{"turn":{"id":"turn-real","status":"inProgress"}}}""",
                ),
            ),
        )

        val state = repository.state.value
        assertEquals("thread-a", state.activeThreadId)
        assertEquals("turn-real", state.activeTurnId)
        assertTrue(state.isWorking)
        assertEquals(RemoteThreadExecutionState.RUNNING, state.threads.single().execution.state)
    }

    @Test
    fun terminalTurnStartResponseDoesNotBecomeActiveWithoutSeparateEvent() {
        val repository = repositoryWithPendingTurnStart("turn.start:terminal-response")
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            threads = listOf(RemoteThread("thread-a", "会话", "继续任务", "/workspace", 1L, "active")),
        )

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "turn.start:terminal-response",
                payload = Json.parseToJsonElement(
                    """{"result":{"turn":{"id":"turn-real","status":"completed"}}}""",
                ),
            ),
        )

        val state = repository.state.value
        assertEquals(null, state.activeThreadId)
        assertEquals(null, state.activeTurnId)
        assertFalse(state.isWorking)
        assertEquals(RemoteThreadExecutionState.COMPLETED, state.threads.single().execution.state)
    }

    @Test
    fun lateTurnStartResponseDoesNotOverwriteAnAlreadyCompletedTurn() {
        val repository = repositoryWithPendingTurnStart("turn.start:late")
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            threads = listOf(
                RemoteThread("thread-a", "会话", "", "/workspace", 1L, "active"),
            ),
        )
        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"turn/completed","params":{"threadId":"thread-a","turn":{"id":"turn-real","status":"completed"}}}""",
                ),
            ),
        )
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "turn.start:late",
                payload = Json.parseToJsonElement("""{"result":{"turn":{"id":"turn-real","status":"inProgress"}}}"""),
            ),
        )

        val state = repository.state.value
        assertFalse(state.isWorking)
        assertEquals(RemoteThreadExecutionState.COMPLETED, state.threads.single().execution.state)
    }

    @Test
    fun completionForDifferentTurnDoesNotSuppressPendingTurnStartResponse() {
        val repository = repositoryWithPendingTurnStart("turn.start:new-after-old")
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            threads = listOf(
                RemoteThread(
                    id = "thread-a",
                    title = "会话",
                    preview = "继续任务",
                    cwd = "/workspace",
                    updatedAt = 1L,
                    status = "active",
                    execution = RemoteThreadExecution(RemoteThreadExecutionState.RUNNING),
                ),
            ),
        )

        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"turn/completed","params":{"threadId":"thread-a","turn":{"id":"turn-old","status":"completed"}}}""",
                ),
            ),
        )
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "turn.start:new-after-old",
                payload = Json.parseToJsonElement(
                    """{"result":{"turn":{"id":"turn-new","status":"inProgress"}}}""",
                ),
            ),
        )

        val state = repository.state.value
        assertEquals("thread-a", state.activeThreadId)
        assertEquals("turn-new", state.activeTurnId)
        assertTrue(state.isWorking)
        assertEquals(RemoteThreadExecutionState.RUNNING, state.threads.single().execution.state)
    }

    @Test
    fun lateTurnStartResponseDoesNotRestoreWorkingWhenThreadRowIsStillMissing() {
        val repository = repositoryWithPendingTurnStart("turn.start:late-missing-row")
        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"turn/completed","params":{"threadId":"thread-a","turn":{"id":"turn-real","status":"completed"}}}""",
                ),
            ),
        )
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "turn.start:late-missing-row",
                payload = Json.parseToJsonElement("""{"result":{"turn":{"id":"turn-real","status":"inProgress"}}}"""),
            ),
        )

        val state = repository.state.value
        assertFalse(state.isWorking)
        assertEquals(null, state.activeThreadId)
        assertEquals(null, state.activeTurnId)
    }

    @Test
    fun lateTurnStartResponseDoesNotReplaceAnotherActiveThread() {
        val repository = repositoryWithPendingTurnStart("turn.start:late-a")
        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"turn/completed","params":{"threadId":"thread-a","turn":{"id":"turn-a","status":"completed"}}}""",
                ),
            ),
        )
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            selectedThreadId = "thread-b",
            activeThreadId = "thread-b",
            activeTurnId = "turn-b",
            isWorking = true,
        )

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "turn.start:late-a",
                payload = Json.parseToJsonElement("""{"result":{"turn":{"id":"turn-a","status":"inProgress"}}}"""),
            ),
        )

        val state = repository.state.value
        assertEquals("thread-b", state.activeThreadId)
        assertEquals("turn-b", state.activeTurnId)
        assertTrue(state.isWorking)
    }

    @Test
    fun staleCompletionDoesNotTerminateNewerActiveTurnOnSameThread() {
        val repository = repository()
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            threads = listOf(
                RemoteThread(
                    id = "thread-a",
                    title = "会话",
                    preview = "新任务",
                    cwd = "/workspace",
                    updatedAt = 1L,
                    status = "active",
                    execution = RemoteThreadExecution(RemoteThreadExecutionState.RUNNING, turnId = "turn-new"),
                ),
            ),
            selectedThreadId = "thread-a",
            activeThreadId = "thread-a",
            activeTurnId = "turn-new",
            isWorking = true,
        )

        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"turn/completed","params":{"threadId":"thread-a","turn":{"id":"turn-old","status":"completed"}}}""",
                ),
            ),
        )

        val state = repository.state.value
        assertEquals("thread-a", state.activeThreadId)
        assertEquals("turn-new", state.activeTurnId)
        assertTrue(state.isWorking)
        assertEquals("turn-new", state.threads.single().execution.turnId)
        assertEquals(RemoteThreadExecutionState.RUNNING, state.threads.single().execution.state)
    }

    @Test
    fun explicitCompletionDoesNotTerminateOptimisticTurnBeforeItsIdIsKnown() {
        val repository = repository()
        setRepositoryState(
            repository,
            repository.state.value.copy(
                threads = listOf(
                    RemoteThread(
                        id = "thread-a",
                        title = "会话",
                        preview = "新任务",
                        cwd = "/workspace",
                        updatedAt = 1L,
                        status = "active",
                        execution = RemoteThreadExecution(RemoteThreadExecutionState.RUNNING),
                    ),
                ),
                selectedThreadId = "thread-a",
                activeThreadId = "thread-a",
                activeTurnId = null,
                isWorking = true,
            ),
        )

        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"turn/completed","params":{"threadId":"thread-a","turn":{"id":"turn-old","status":"completed"}}}""",
                ),
            ),
        )

        val state = repository.state.value
        assertEquals("thread-a", state.activeThreadId)
        assertEquals(null, state.activeTurnId)
        assertTrue(state.isWorking)
        assertEquals(RemoteThreadExecutionState.RUNNING, state.threads.single().execution.state)
    }

    @Test
    fun inactiveStatusBeforeTurnStartResponseDoesNotLeaveTheThreadRowRunning() {
        val repository = repositoryWithPendingTurnStart("turn.start:inactive")
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            threads = listOf(RemoteThread("thread-a", "会话", "", "/workspace", 1L, "active")),
        )
        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"thread/status/changed","params":{"threadId":"thread-a","status":{"type":"idle"}}}""",
                ),
            ),
        )

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "turn.start:inactive",
                payload = Json.parseToJsonElement("""{"result":{"turn":{"id":"turn-real","status":"inProgress"}}}"""),
            ),
        )

        val state = repository.state.value
        assertFalse(state.isWorking)
        assertEquals(null, state.activeThreadId)
        assertEquals(null, state.activeTurnId)
        assertFalse(state.threads.single().execution.state.isActive)
    }

    @Test
    fun completedContinuationBeforeTurnStartResponseStaysTerminal() {
        val requestId = "turn.start:completed-continuation"
        val repository = repositoryWithPendingTurnStart(requestId)
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            threads = listOf(
                RemoteThread(
                    id = "thread-a",
                    title = "历史大会话",
                    preview = "旧消息",
                    cwd = "/workspace",
                    updatedAt = 1L,
                    status = "active",
                    execution = RemoteThreadExecution(RemoteThreadExecutionState.RUNNING),
                ),
            ),
        )
        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = Json.parseToJsonElement(
                    """{"method":"turn/completed","params":{"threadId":"thread-new","turn":{"id":"turn-continuation","status":"completed"}}}""",
                ),
            ),
        )

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = requestId,
                payload = Json.parseToJsonElement(
                    """{
                      "result":{
                        "turn":{"id":"turn-continuation"},
                        "continuation":{
                          "schemaVersion":1,
                          "threadId":"thread-new",
                          "continuedFromThreadId":"thread-a",
                          "cwd":"/workspace",
                          "historyMode":"recent"
                        }
                      }
                    }""",
                ),
            ),
        )

        val state = repository.state.value
        assertEquals("thread-new", state.selectedThreadId)
        assertEquals(null, state.activeThreadId)
        assertEquals(null, state.activeTurnId)
        assertFalse(state.isWorking)
        assertEquals(RemoteThreadExecutionState.COMPLETED, state.threads.first().execution.state)
    }

    @Test
    fun lateContinuationDoesNotSelectOrOverwriteAnotherActiveThread() {
        val requestId = "turn.start:late-continuation"
        val repository = repositoryWithPendingTurnStart(requestId)
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            selectedThreadId = "thread-b",
            activeThreadId = "thread-b",
            activeTurnId = "turn-b",
            isWorking = true,
            timeline = listOf(RemoteTimelineItem("thread-b-item", "agentMessage", "当前任务", "streaming")),
            threads = listOf(
                RemoteThread("thread-b", "当前会话", "当前任务", "/current", 2L, "active", execution = RemoteThreadExecution(RemoteThreadExecutionState.RUNNING, turnId = "turn-b")),
                RemoteThread("thread-a", "历史大会话", "旧消息", "/workspace", 1L, "active", execution = RemoteThreadExecution(RemoteThreadExecutionState.RUNNING)),
            ),
        )

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                backendId = DEFAULT_BACKEND_ID,
                requestId = requestId,
                payload = Json.parseToJsonElement(
                    """{
                      "result":{
                        "turn":{"id":"turn-continuation","status":"inProgress"},
                        "continuation":{
                          "schemaVersion":1,
                          "threadId":"thread-new",
                          "continuedFromThreadId":"thread-a",
                          "cwd":"/workspace",
                          "historyMode":"recent"
                        }
                      }
                    }""",
                ),
            ),
        )

        val state = repository.state.value
        assertEquals("thread-b", state.selectedThreadId)
        assertEquals("thread-b", state.activeThreadId)
        assertEquals("turn-b", state.activeTurnId)
        assertTrue(state.isWorking)
        assertEquals(listOf("当前任务"), state.timeline.map(RemoteTimelineItem::text))
        assertEquals(RemoteThreadExecutionState.RUNNING, state.threads.first { it.id == "thread-new" }.execution.state)
    }

    @Test
    fun lateContinuationDoesNotSelectWhenUserMovedButPendingSourceRemainsActive() {
        val requestId = "turn.start:selection-generation"
        val repository = repositoryWithPendingTurnStart(requestId, selectionGeneration = 0L)
        setRepositoryState(
            repository,
            repository.state.value.copy(
                backends = listOf(RemoteBackend(DEFAULT_BACKEND_ID, "Codex", emptySet())),
                threads = listOf(
                    RemoteThread("thread-a", "历史大会话", "旧消息", "/workspace", 1L, "active"),
                    RemoteThread("thread-b", "用户选择", "当前内容", "/other", 2L, "active"),
                ),
            ),
        )

        repository.selectThread("thread-b")
        assertEquals("thread-a", repository.state.value.activeThreadId)

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = requestId,
                payload = Json.parseToJsonElement(
                    """{
                      "result":{
                        "turn":{"id":"turn-continuation","status":"inProgress"},
                        "continuation":{
                          "schemaVersion":1,
                          "threadId":"thread-new",
                          "continuedFromThreadId":"thread-a",
                          "cwd":"/workspace",
                          "historyMode":"recent"
                        }
                      }
                    }""",
                ),
            ),
        )

        val state = repository.state.value
        assertEquals("thread-b", state.selectedThreadId)
        assertEquals("thread-a", state.activeThreadId)
        assertTrue(state.timeline.isEmpty())
        assertTrue(state.threads.any { it.id == "thread-new" })
    }

    @Test
    fun oversizedThreadContinuationKeepsSourceAndAutomaticallySelectsTheNewThread() {
        val requestId = "turn.start:continuation"
        val repository = repositoryWithPendingTurnStart(requestId)
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            threads = listOf(
                RemoteThread(
                    id = "thread-a",
                    title = "历史大会话",
                    preview = "旧消息",
                    cwd = "/workspace",
                    updatedAt = 1L,
                    status = "active",
                    execution = RemoteThreadExecution(RemoteThreadExecutionState.RUNNING),
                ),
            ),
        )

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = requestId,
                payload = Json.parseToJsonElement(
                    """{
                      "result":{
                        "turn":{"id":"turn-continuation"},
                        "continuation":{
                          "schemaVersion":1,
                          "threadId":"thread-new",
                          "continuedFromThreadId":"thread-a",
                          "cwd":"/workspace",
                          "historyMode":"recent"
                        }
                      }
                    }""",
                ),
            ),
        )

        val state = repository.state.value
        assertEquals("thread-new", state.selectedThreadId)
        assertEquals("thread-new", state.activeThreadId)
        assertEquals("turn-continuation", state.activeTurnId)
        assertTrue(state.isWorking)
        assertEquals(listOf("thread-new", "thread-a"), state.threads.map(RemoteThread::id))
        assertEquals(RemoteThreadExecutionState.RUNNING, state.threads.first().execution.state)
        assertEquals(RemoteThreadExecutionState.UNKNOWN, state.threads.last().execution.state)
        assertEquals(2, state.timeline.size)
        assertEquals("continuation", state.timeline.first().kind)
        assertTrue(state.timeline.first().text.contains("原会话仍保留"))
        assertEquals("继续任务", state.timeline.last().text)
        assertEquals("sent", state.timeline.last().status)
        assertFalse(state.timeline.any { it.text.contains("harness.lazyContinuation") })
        assertEquals(null, state.olderTimelineCursor)
    }

    @Test
    fun failedTurnStartShowsFailureInsteadOfPermanentSending() {
        val repository = repositoryWithPendingTurnStart("turn.start:failed")

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "turn.start:failed",
                payload = Json.parseToJsonElement(
                    """{"error":{"message":"thread could not be resumed"},"retrySafe":false}""",
                ),
            ),
        )

        assertEquals("sendFailed", repository.state.value.timeline.single().status)
        assertFalse(repository.state.value.isWorking)
        assertEquals(null, repository.state.value.activeThreadId)
    }

    @Test
    fun definiteTurnStartFailureOverridesAStaleRunningSummaryAfterTheActiveRouteWasCleared() {
        val repository = repositoryWithPendingTurnStart("turn.start:failed-after-summary")
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            activeThreadId = null,
            isWorking = true,
            threads = listOf(
                RemoteThread(
                    id = "thread-a",
                    title = "任务 A",
                    preview = "继续任务",
                    cwd = "/workspace",
                    updatedAt = 1L,
                    status = "active",
                    execution = RemoteThreadExecution(
                        state = RemoteThreadExecutionState.RUNNING,
                        startedAtMillis = 2_000L,
                    ),
                ),
            ),
        )

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "turn.start:failed-after-summary",
                payload = Json.parseToJsonElement(
                    """{"error":{"message":"persisted thread is too large to resume remotely (404 MiB); start a new thread from the same workspace"},"retrySafe":true}""",
                ),
            ),
        )

        assertEquals("sendFailed", repository.state.value.timeline.single().status)
        assertFalse(repository.state.value.isWorking)
        assertEquals(RemoteThreadExecutionState.FAILED, repository.state.value.threads.single().execution.state)
        assertEquals("Mac Bridge 版本过旧，请升级后重试大会话", repository.state.value.errorMessage)
    }

    @Test
    fun unknownTurnStartShowsReconciliationInsteadOfPermanentSending() {
        val repository = repositoryWithPendingTurnStart("turn.start:unknown")

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "turn.start:unknown",
                payload = Json.parseToJsonElement(
                    """{"outcome":"UNKNOWN","status":"RECONCILING","retrySafe":false,"requiresSnapshot":true}""",
                ),
            ),
        )

        assertEquals("reconciling", repository.state.value.timeline.single().status)
        assertFalse(repository.state.value.isWorking)
        assertEquals(null, repository.state.value.activeThreadId)
    }

    @Test
    fun stalePreviousCompletionDoesNotOverwriteAJustStartedTurn() {
        val repository = repository()
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            selectedThreadId = "thread-a",
            threads = listOf(
                RemoteThread(
                    id = "thread-a",
                    title = "任务 A",
                    preview = "继续任务",
                    cwd = "/workspace",
                    updatedAt = 1L,
                    status = "active",
                    execution = RemoteThreadExecution(
                        state = RemoteThreadExecutionState.RUNNING,
                        startedAtMillis = 2_000L,
                    ),
                ),
            ),
        )
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(repository) as MutableMap<String, PendingRemoteCommand>
        pending["thread.summary:stale"] = PendingRemoteCommand("thread.summary", threadId = "thread-a")

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.summary:stale",
                payload = Json.parseToJsonElement(
                    """{"result":{"threadId":"thread-a","latestUserMessage":"上一轮","execution":{"state":"COMPLETED","turnId":"turn-old","startedAt":1,"completedAt":1}}}""",
                ),
            ),
        )

        assertEquals(RemoteThreadExecutionState.RUNNING, repository.state.value.threads.single().execution.state)
    }

    @Test
    fun reconnectThenLegacyHostStatusCannotPreserveStaleBackendStateOrPendingCommands() {
        val repository = repository()
        setRepositoryState(
            repository,
            repository.state.value.copy(
                backends = listOf(RemoteBackend("codex", "Codex", emptySet())),
                selectedBackendId = "codex",
                threads = listOf(RemoteThread("thread-old", "旧任务", "旧任务", "/old", 1L, "active")),
                selectedThreadId = "thread-old",
                activeThreadId = "thread-old",
                activeTurnId = "turn-old",
                isWorking = true,
                timeline = listOf(RemoteTimelineItem("old", "agentMessage", "旧结果")),
                workspaceCandidates = listOf(
                    WorkspaceCandidate("old", "Old", "/old", "repo", "main", "old", 1L),
                ),
                workspaceCandidatesLoaded = true,
            ),
        )
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(repository) as MutableMap<String, PendingRemoteCommand>
        pending["thread.read:old"] = PendingRemoteCommand("thread.read", threadId = "thread-old", backendId = "codex")
        val reconnect = RemoteRepository::class.java.getDeclaredMethod("scheduleReconnect", String::class.java).apply {
            isAccessible = true
        }

        reconnect.invoke(repository, "断线")
        repository.handleEvent(
            RemoteEvent(
                type = "host.status",
                payload = Json.parseToJsonElement("""{"schemaVersion":1,"capabilities":[]}"""),
            ),
        )

        assertTrue(repository.state.value.threads.isEmpty())
        assertEquals(null, repository.state.value.selectedThreadId)
        assertEquals(null, repository.state.value.activeThreadId)
        assertEquals(null, repository.state.value.activeTurnId)
        assertFalse(repository.state.value.isWorking)
        assertTrue(repository.state.value.timeline.isEmpty())
        assertTrue(repository.state.value.workspaceCandidates.isEmpty())
        assertTrue(pending.isEmpty())
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
    fun explicitEmptyBackendStatusDoesNotInventCodex() {
        val repository = repository()

        repository.handleEvent(
            RemoteEvent(
                type = "host.status",
                payload = Json.parseToJsonElement("""{"schemaVersion":1,"capabilities":[],"backends":[]}"""),
            ),
        )

        assertTrue(repository.state.value.backends.isEmpty())
    }

    @Test
    fun newlyCreatedThreadOpensWithoutReadingUnmaterializedHistory() {
        val repository = repository()

        repository.handlePendingRpcResponse(
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

    private fun repositoryWithPendingTurnStart(
        requestId: String,
        selectionGeneration: Long? = null,
    ): RemoteRepository {
        val repository = repository()
        val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
        mutableState.value = mutableState.value.copy(
            selectedThreadId = "thread-a",
            activeThreadId = "thread-a",
            isWorking = true,
            timeline = listOf(
                RemoteTimelineItem("pending-user:$requestId", "userMessage", "继续任务", "sending"),
            ),
        )
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(repository) as MutableMap<String, PendingRemoteCommand>
        pending[requestId] = PendingRemoteCommand(
            kind = "turn.start",
            threadId = "thread-a",
            selectionGeneration = selectionGeneration,
        )
        return repository
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

        repository.handlePendingRpcResponse(
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
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.read:request-1",
                payload = Json.parseToJsonElement(
                    """{"result":{"thread":{"id":"thread-a","turns":[{"id":"turn-new","items":[{"id":"agent-new","type":"agentMessage","text":"最新消息"}]}]},"mobileHistory":{"olderCursor":"cursor-older"}}}""",
                ),
            ),
        )

        repository.handlePendingRpcResponse(
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

        repository.handlePendingRpcResponse(
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

        repository.handlePendingRpcResponse(
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
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.read:initial",
                payload = Json.parseToJsonElement(unchangedPage),
            ),
        )
        repository.handlePendingRpcResponse(
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

        repository.handlePendingRpcResponse(
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

        repository.handlePendingRpcResponse(
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

        repository.handlePendingRpcResponse(
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
    fun delayedOlderThreadListCannotOverwriteNewerListResponse() {
        val repository = repository()
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(repository) as MutableMap<String, PendingRemoteCommand>
        pending["thread.list:old"] = PendingRemoteCommand("thread.list", listGeneration = 1)
        pending["thread.list:new"] = PendingRemoteCommand("thread.list", listGeneration = 2)
        RemoteRepository::class.java.getDeclaredField("nextThreadListGeneration").apply {
            isAccessible = true
            setLong(repository, 2L)
        }
        setRepositoryState(
            repository,
            repository.state.value.copy(
                threads = listOf(RemoteThread("baseline", "当前列表", "", "/work", 1L, "idle")),
                isThreadListLoading = true,
            ),
        )

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response", requestId = "thread.list:old",
                payload = Json.parseToJsonElement("""{"result":{"data":[{"id":"old-thread","preview":"旧列表","updatedAt":1}]}}"""),
            ),
        )
        assertEquals(listOf("baseline"), repository.state.value.threads.map(RemoteThread::id))
        assertTrue(repository.state.value.isThreadListLoading)

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response", requestId = "thread.list:new",
                payload = Json.parseToJsonElement("""{"result":{"data":[{"id":"new-thread","preview":"新列表","updatedAt":2}]}}"""),
            ),
        )
        assertEquals(listOf("new-thread"), repository.state.value.threads.map(RemoteThread::id))
        assertFalse(repository.state.value.isThreadListLoading)
    }

    @Test
    fun equalRevisionListRefreshPreservesSummaryDerivedTitle() {
        val repository = repository()
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response", requestId = "thread.list:seed",
                payload = Json.parseToJsonElement("""{"result":{"data":[{"id":"thread-1","name":"未命名会话","updatedAt":2}]}}"""),
            ),
        )
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response", requestId = "thread.summary:seed",
                payload = Json.parseToJsonElement("""{"result":{"threadId":"thread-1","latestUserMessage":"真实问题"}}"""),
            ),
        )
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response", requestId = "thread.list:refresh",
                payload = Json.parseToJsonElement("""{"result":{"data":[{"id":"thread-1","name":"未命名会话","updatedAt":2}]}}"""),
            ),
        )

        assertEquals("真实问题", repository.state.value.threads.single().title)
        assertEquals("真实问题", repository.state.value.threads.single().latestUserMessage)
    }

    @Test
    fun staleThreadSummaryCannotOverwriteANewerThreadRevision() {
        val repository = repository()
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(repository) as MutableMap<String, PendingRemoteCommand>
        setRepositoryState(
            repository,
            repository.state.value.copy(
                threads = listOf(RemoteThread("thread-1", "新标题", "", "/work", 2_000L, "idle")),
            ),
        )
        pending["thread.summary:old"] = PendingRemoteCommand(
            "thread.summary", threadId = "thread-1", threadRevision = 1_000L,
        )

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response", requestId = "thread.summary:old",
                payload = Json.parseToJsonElement("""{"result":{"threadId":"thread-1","latestUserMessage":"旧问题"}}"""),
            ),
        )

        val thread = repository.state.value.threads.single()
        assertEquals("新标题", thread.title)
        assertEquals(null, thread.latestUserMessage)
    }

    @Test
    fun threadListAcceptsLatestUserMessageWithoutBreakingLegacyPreview() {
        val threads = parseThreads(
            RemoteEvent(
                type = "rpc.response",
                payload = Json.parseToJsonElement(
                    """{"result":{"data":[{"id":"thread-1","preview":"最早一句","latestUserMessage":"最新一句","updatedAt":1}]}}""",
                ),
            ),
        )

        assertEquals("最早一句", threads.single().preview)
        assertEquals("最新一句", threads.single().latestUserMessage)
    }

    @Test
    fun threadListPreservesActiveExecutionAndWaitingFlags() {
        val threads = parseThreads(
            RemoteEvent(
                type = "rpc.response",
                payload = Json.parseToJsonElement(
                    """{"result":{"data":[{"id":"thread-1","preview":"等待处理","updatedAt":1,"status":{"type":"active","activeFlags":["waitingOnApproval"]}}]}}""",
                ),
            ),
        )

        assertEquals(RemoteThreadExecutionState.WAITING_APPROVAL, threads.single().execution.state)
    }

    @Test
    fun lazyThreadSummaryResponseUpdatesOnlyItsMatchingConversationCard() {
        val repository = repository()
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.list:seed",
                payload = Json.parseToJsonElement(
                    """{"result":{"data":[
                        {"id":"thread-1","preview":"第一条旧摘要","updatedAt":2},
                        {"id":"thread-2","preview":"另一条旧摘要","updatedAt":1}
                    ]}}""",
                ),
            ),
        )

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.summary:request",
                payload = Json.parseToJsonElement(
                    """{"result":{"threadId":"thread-1","latestUserMessage":"最近的用户问题"}}""",
                ),
            ),
        )

        assertEquals("最近的用户问题", repository.state.value.threads[0].latestUserMessage)
        assertEquals(null, repository.state.value.threads[1].latestUserMessage)
    }

    @Test
    fun lazyThreadSummaryReplacesOnlyPlaceholderConversationTitle() {
        val repository = repository()
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.list:seed",
                payload = Json.parseToJsonElement(
                    """{"result":{"data":[
                        {"id":"thread-1","name":"未命名会话","updatedAt":2},
                        {"id":"thread-2","name":"已有正式标题","updatedAt":1}
                    ]}}""",
                ),
            ),
        )

        for (threadId in listOf("thread-1", "thread-2")) {
            repository.handlePendingRpcResponse(
                RemoteEvent(
                    type = "rpc.response",
                    requestId = "thread.summary:$threadId",
                    payload = Json.parseToJsonElement(
                        """{"result":{"threadId":"$threadId","latestUserMessage":"最近的用户问题"}}""",
                    ),
                ),
            )
        }

        assertEquals("最近的用户问题", repository.state.value.threads[0].title)
        assertEquals("已有正式标题", repository.state.value.threads[1].title)
    }

    @Test
    fun lazyThreadSummaryRestoresRunningStateWhenOpeningAnAlreadyActiveConversation() {
        val repository = repository()
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.list:seed",
                payload = Json.parseToJsonElement(
                    """{"result":{"data":[{"id":"thread-1","preview":"正在执行","updatedAt":2,"status":{"type":"notLoaded"}}]}}""",
                ),
            ),
        )
        repository.selectThread("thread-1")

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.summary:request",
                payload = Json.parseToJsonElement(
                    """{"result":{"threadId":"thread-1","latestUserMessage":"继续执行","execution":{"state":"RUNNING","turnId":"turn-1","startedAt":1234,"completedAt":null}}}""",
                ),
            ),
        )

        assertEquals(RemoteThreadExecutionState.RUNNING, repository.state.value.threads.single().execution.state)
        assertEquals("turn-1", repository.state.value.activeTurnId)
        assertTrue(repository.state.value.isWorking)
    }

    @Test
    fun lazyThreadSummaryKeepsTerminalStateAndClearsSteeringAfterCompletion() {
        val repository = repository()
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.list:seed",
                payload = Json.parseToJsonElement(
                    """{"result":{"data":[{"id":"thread-1","preview":"执行任务","updatedAt":2}]}}""",
                ),
            ),
        )
        repository.selectThread("thread-1")
        fun summary(state: String, completedAt: String) = RemoteEvent(
            type = "rpc.response",
            requestId = "thread.summary:$state",
            payload = Json.parseToJsonElement(
                """{"result":{"threadId":"thread-1","latestUserMessage":"执行任务","execution":{"state":"$state","turnId":"turn-1","startedAt":1234,"completedAt":$completedAt}}}""",
            ),
        )
        repository.handlePendingRpcResponse(summary("RUNNING", "null"))

        repository.handlePendingRpcResponse(summary("COMPLETED", "1300"))

        assertEquals(RemoteThreadExecutionState.COMPLETED, repository.state.value.threads.single().execution.state)
        assertEquals(null, repository.state.value.activeThreadId)
        assertEquals(null, repository.state.value.activeTurnId)
        assertFalse(repository.state.value.isWorking)
    }

    @Test
    fun threadListRefreshKeepsHydratedSummaryUntilConversationChanges() {
        val repository = repository()
        fun list(updatedAt: Int) = RemoteEvent(
            type = "rpc.response",
            requestId = "thread.list:refresh-$updatedAt",
            payload = Json.parseToJsonElement(
                """{"result":{"data":[{"id":"thread-1","preview":"最早一句","updatedAt":$updatedAt}]}}""",
            ),
        )
        repository.handlePendingRpcResponse(list(updatedAt = 2))
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.summary:request",
                payload = Json.parseToJsonElement(
                    """{"result":{"threadId":"thread-1","latestUserMessage":"最近一句","execution":{"state":"COMPLETED","turnId":"turn-1","startedAt":1,"completedAt":2}}}""",
                ),
            ),
        )

        repository.handlePendingRpcResponse(list(updatedAt = 2))
        assertEquals("最近一句", repository.state.value.threads.single().latestUserMessage)
        assertEquals(RemoteThreadExecutionState.COMPLETED, repository.state.value.threads.single().execution.state)

        repository.handlePendingRpcResponse(list(updatedAt = 3))
        assertEquals(null, repository.state.value.threads.single().latestUserMessage)
        assertEquals(RemoteThreadExecutionState.UNKNOWN, repository.state.value.threads.single().execution.state)
    }

    @Test
    fun backgroundThreadSummaryDoesNotClearAnUnrelatedVisibleError() {
        val repository = repository()
        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.list:seed",
                payload = Json.parseToJsonElement(
                    """{"result":{"data":[{"id":"thread-1","preview":"最早一句","updatedAt":1}]}}""",
                ),
            ),
        )
        repository.handleEvent(RemoteEvent(type = "error", message = "需要用户处理的错误"))

        repository.handlePendingRpcResponse(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.summary:request",
                payload = Json.parseToJsonElement(
                    """{"result":{"threadId":"thread-1","latestUserMessage":"最近一句"}}""",
                ),
            ),
        )

        assertEquals("需要用户处理的错误", repository.state.value.errorMessage)
        assertEquals("最近一句", repository.state.value.threads.single().latestUserMessage)
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

private fun setRepositoryState(repository: RemoteRepository, state: RemoteUiState) {
    val stateField = RemoteRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val mutableState = stateField.get(repository) as MutableStateFlow<RemoteUiState>
    mutableState.value = state
}

private fun workspaceCandidate(id: String) = WorkspaceCandidate(
    workspaceId = id,
    displayName = id,
    cwd = "/$id",
    repositoryLabel = id,
    branch = "main",
    repositoryFingerprint = "$id-fingerprint",
    lastUsedAt = 1L,
)

private fun RemoteRepository.handlePendingRpcResponse(event: RemoteEvent) {
    val requestId = requireNotNull(event.requestId)
    val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val pending = pendingField.get(this) as MutableMap<String, PendingRemoteCommand>
    pending.putIfAbsent(
        requestId,
        PendingRemoteCommand(
            kind = requestId.substringBefore(':'),
            backendId = state.value.selectedBackendId,
        ),
    )
    handleEvent(event)
}

private class FakeRemoteProfileProvider(initial: RemoteProfile?) : RemoteProfileProvider {
    override val profile = MutableStateFlow(initial)
}
