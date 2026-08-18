package com.harnessapk.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal fun remoteRpcErrorMessage(payload: JsonElement?): String {
    val error = (payload as? JsonObject)?.get("error")
    val raw = when (error) {
        is JsonObject -> error.string("message")
        is JsonPrimitive -> error.contentOrNull
        else -> null
    }.orEmpty()
    return when {
        raw.contains("not materialized", ignoreCase = true) -> "会话正在初始化，请先发送第一条消息"
        raw.contains("token too long", ignoreCase = true) -> "会话内容过大，Mac Bridge 需要升级后重试"
        raw.contains("too large to resume remotely", ignoreCase = true) ->
            "Mac Bridge 版本过旧，请升级后重试大会话"
        raw.contains("deadline exceeded", ignoreCase = true) -> "Mac 恢复会话超时，请稍后重试或新建会话"
        else -> "Mac 返回错误，请稍后重试"
    }
}

internal data class PendingRemoteCommand(
    val kind: String,
    val threadId: String? = null,
    val olderCursor: String? = null,
    val selectionGeneration: Long? = null,
    val backendId: String = DEFAULT_BACKEND_ID,
)

internal data class PreparedRemoteCommand(
    val requestId: String,
    val payload: JsonObject,
    val pending: PendingRemoteCommand?,
)

internal fun prepareRemotePayload(
    requestId: String,
    payload: JsonObject,
    pending: PendingRemoteCommand?,
    selectedBackendId: String,
): PreparedRemoteCommand {
    val effectivePayload = injectBackendId(payload, selectedBackendId)
    val embeddedBackendId = effectivePayload["backendId"]?.jsonPrimitive?.contentOrNull ?: selectedBackendId
    return PreparedRemoteCommand(
        requestId = requestId,
        payload = effectivePayload,
        pending = pending?.copy(backendId = embeddedBackendId),
    )
}

internal fun prepareRemoteCommand(
    command: RemoteCommand,
    pending: PendingRemoteCommand?,
    selectedBackendId: String,
): PreparedRemoteCommand = prepareRemotePayload(
    requestId = command.requestId,
    payload = command.toJson(),
    pending = pending,
    selectedBackendId = selectedBackendId,
)

class RemoteRepository(
    private val profileStore: RemoteProfileProvider,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
) : RemoteCommandSender, RemoteSyncSender {
    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()
    private val _notifications = MutableSharedFlow<RemoteNotification>(extraBufferCapacity = 8)
    val notifications: SharedFlow<RemoteNotification> = _notifications
    private val outgoingSequence = AtomicLong(0)
    private val seenMessages = LinkedHashSet<String>()
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var explicitDisconnect = false
    private var reconnectAttempt = 0
    private val pendingCommands = mutableMapOf<String, PendingRemoteCommand>()
    private val requestedThreadSummaries = mutableSetOf<String>()
    private val terminalTurnExecutions = LinkedHashMap<String, RemoteThreadExecution>()
    private val explicitlyInactiveThreads = mutableSetOf<String>()
    private val commandOwnershipLock = Any()
    private var selectionGeneration = 0L
    private var preferredBackendId = DEFAULT_BACKEND_ID
    private var backendSelectionIsFallback = false
    @Volatile
    private var syncCoordinator: RemoteSyncCoordinator? = null
    @Volatile
    private var connectedHandler: (suspend (hostId: String, deviceId: String) -> Unit)? = null

    fun attachSyncCoordinator(coordinator: RemoteSyncCoordinator) {
        syncCoordinator = coordinator
    }

    fun attachConnectedHandler(handler: suspend (hostId: String, deviceId: String) -> Unit) {
        connectedHandler = handler
    }

    fun connect() {
        val profile = profileStore.profile.value ?: return
        if (_state.value.connectionStatus == RemoteConnectionStatus.CONNECTED || _state.value.connectionStatus == RemoteConnectionStatus.CONNECTING) return
        explicitDisconnect = false
        _state.value = _state.value.copy(connectionStatus = RemoteConnectionStatus.CONNECTING, errorMessage = null)
        val url = profile.relayUrl.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
            "/v1/ws?role=device&id=${profile.deviceId}"
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${profile.deviceToken}").build()
        socket = httpClient.newWebSocket(request, listener)
    }

    fun disconnect() {
        explicitDisconnect = true
        reconnectAttempt = 0
        reconnectJob?.cancel()
        socket?.close(1000, "user disconnected")
        socket = null
        pendingCommands.clear()
        requestedThreadSummaries.clear()
        terminalTurnExecutions.clear()
        explicitlyInactiveThreads.clear()
        selectionGeneration += 1
        _state.value = _state.value.copy(
            connectionStatus = RemoteConnectionStatus.DISCONNECTED,
            activeThreadId = null,
            activeTurnId = null,
            isWorking = false,
            isThreadListLoading = false,
            isTimelineLoading = false,
            olderTimelineCursor = null,
            isOlderTimelineLoading = false,
            isCreatingThread = false,
            capabilities = emptySet(),
            backends = emptyList(),
        )
    }

    fun refreshThreads() = synchronized(commandOwnershipLock) {
        _state.value = _state.value.copy(isThreadListLoading = true, errorMessage = null)
        if (!send(RemoteCommand(type = "thread.list", requestId = requestId("thread.list")))) {
            _state.value = _state.value.copy(isThreadListLoading = false)
        }
    }
    fun loadThreadSummary(threadId: String) {
        synchronized(commandOwnershipLock) {
            val current = _state.value
            val availability = remoteFeatureAvailability(current.capabilities)
            val thread = current.threads.firstOrNull { it.id == threadId } ?: return
            val needsLatestUserMessage = availability.canLoadLatestUserMessage && thread.latestUserMessage == null
            val needsExecution = availability.canLoadThreadExecutionStatus &&
                (thread.execution.state == RemoteThreadExecutionState.UNKNOWN || thread.execution.state.isActive)
            if (!needsLatestUserMessage && !needsExecution) return
            if (!requestedThreadSummaries.add(threadId)) return
            if (!send(RemoteCommand(type = "thread.summary", requestId = requestId("thread.summary", threadId), threadId = threadId))) {
                requestedThreadSummaries.remove(threadId)
            }
        }
    }
    fun requestWorkspaceCandidates() {
        val current = _state.value
        _state.value = current.copy(workspaceCandidatesLoaded = current.workspaceCandidates.isNotEmpty())
        if (!send(RemoteCommand(type = "workspace.list", requestId = requestId("workspace.list")))) {
            _state.value = _state.value.copy(workspaceCandidatesLoaded = true)
        }
    }
    fun selectThread(threadId: String) = synchronized(commandOwnershipLock) {
        selectionGeneration += 1
        val generation = selectionGeneration
        _state.value = _state.value.copy(
            selectedThreadId = threadId,
            timeline = emptyList(),
            approvals = emptyList(),
            isWorking = _state.value.activeThreadId == threadId,
            isTimelineLoading = true,
            olderTimelineCursor = null,
            isOlderTimelineLoading = false,
            errorMessage = null,
        )
        loadThreadSummary(threadId)
        if (!send(RemoteCommand(type = "thread.read", requestId = requestId("thread.read", threadId, selectionGeneration = generation), threadId = threadId))) {
            _state.value = _state.value.copy(isTimelineLoading = false)
        }
    }

    fun loadOlderHistory() {
        val current = _state.value
        val threadId = current.selectedThreadId ?: return
        val cursor = current.olderTimelineCursor ?: return
        if (current.isTimelineLoading || current.isOlderTimelineLoading) return
        _state.value = current.copy(isOlderTimelineLoading = true, errorMessage = null)
        val requestId = requestId("thread.read.older", threadId, cursor, selectionGeneration)
        val params = buildJsonObject { put("cursor", JsonPrimitive(cursor)) }
        if (!send(RemoteCommand(type = "thread.read", requestId = requestId, threadId = threadId, params = params))) {
            _state.value = _state.value.copy(isOlderTimelineLoading = false)
        }
    }

    fun clearSelection() {
        val refreshAfterClear = _state.value.connectionStatus == RemoteConnectionStatus.CONNECTED
        selectionGeneration += 1
        _state.value = _state.value.copy(
            selectedThreadId = null,
            timeline = emptyList(),
            approvals = emptyList(),
            isWorking = false,
            isTimelineLoading = false,
            olderTimelineCursor = null,
            isOlderTimelineLoading = false,
        )
        if (refreshAfterClear) refreshThreads()
    }
    fun createThread(cwd: String) {
        _state.value = _state.value.copy(isCreatingThread = true, errorMessage = null)
        if (!send(RemoteCommand(type = "thread.start", requestId = requestId("thread.start"), cwd = cwd.trim()))) {
            _state.value = _state.value.copy(isCreatingThread = false)
        }
    }
    fun startTurn(text: String): Boolean = synchronized(commandOwnershipLock) {
        val threadId = _state.value.selectedThreadId ?: return@synchronized false
        val requestId = requestId("turn.start", threadId)
        val generation = selectionGeneration
        return send(
            RemoteCommand(type = "turn.start", requestId = requestId, threadId = threadId, text = text),
            pending = PendingRemoteCommand(
                kind = "turn.start",
                threadId = threadId,
                selectionGeneration = generation,
            ),
        ).also { sent ->
            if (sent) {
                explicitlyInactiveThreads.remove(threadId)
                _state.value = _state.value.copy(
                    activeThreadId = threadId,
                    activeTurnId = null,
                    isWorking = true,
                    timeline = _state.value.timeline + RemoteTimelineItem("pending-user:$requestId", "userMessage", text, "sending"),
                    threads = _state.value.threads.withExecution(
                        threadId,
                        RemoteThreadExecution(
                            state = RemoteThreadExecutionState.RUNNING,
                            startedAtMillis = System.currentTimeMillis(),
                        ),
                    ),
                )
            }
        }
    }
    fun steer(text: String): Boolean {
        val current = _state.value
        val threadId = current.selectedThreadId ?: return false
        val activeTurnId = current.activeTurnId
        if (activeTurnId == null || current.activeThreadId != threadId) {
            _state.value = current.copy(errorMessage = "正在等待 Mac 建立当前任务，请稍后再发送")
            return false
        }
        return send(
            RemoteCommand(
                type = "turn.steer",
                requestId = requestId("turn.steer"),
                threadId = threadId,
                text = text,
                expectedTurnId = activeTurnId,
            ),
        )
    }
    fun interrupt() {
        val current = _state.value
        send(RemoteCommand(type = "turn.interrupt", requestId = requestId("turn.interrupt"), threadId = current.selectedThreadId, turnId = current.activeTurnId))
    }
    fun respondToApproval(approval: RemoteApproval, decision: String) {
        if (send(RemoteCommand(type = "approval.respond", requestId = requestId("approval.respond"), serverRequestId = approval.requestId, decision = decision, threadId = approval.threadId, turnId = approval.turnId))) {
            _state.value = _state.value.copy(approvals = _state.value.approvals - approval)
        }
    }

    private fun requestId(
        kind: String,
        threadId: String? = null,
        olderCursor: String? = null,
        selectionGeneration: Long? = null,
    ): String = "$kind:${UUID.randomUUID()}"

    private fun send(
        command: RemoteCommand,
        pending: PendingRemoteCommand? = null,
    ): Boolean = synchronized(commandOwnershipLock) {
        val effectivePending = pending ?: PendingRemoteCommand(
            kind = command.requestId.substringBefore(':'),
            threadId = command.threadId,
            olderCursor = (command.params as? JsonObject)?.string("cursor"),
            selectionGeneration = selectionGeneration.takeIf { command.type == "thread.read" },
        )
        val prepared = prepareRemoteCommand(
            command = command,
            pending = effectivePending,
            selectedBackendId = _state.value.selectedBackendId,
        )
        prepared.pending?.let { pendingCommands[prepared.requestId] = it }
        sendPreparedPayload(prepared)
    }

    override fun send(command: RebuiltRemoteCommand): Boolean = synchronized(commandOwnershipLock) {
        sendPreparedPayload(
            prepareRemotePayload(
                requestId = command.commandId,
                payload = command.payload,
                pending = null,
                selectedBackendId = _state.value.selectedBackendId,
            ),
        )
    }

    override fun send(command: JsonObject): Boolean = synchronized(commandOwnershipLock) {
        val requestId = command.string("requestId") ?: "sync:${UUID.randomUUID()}"
        sendPreparedPayload(
            prepareRemotePayload(
                requestId = requestId,
                payload = command,
                pending = null,
                selectedBackendId = _state.value.selectedBackendId,
            ),
        )
    }

    private fun sendPreparedPayload(prepared: PreparedRemoteCommand): Boolean {
        val profile = profileStore.profile.value
        if (profile == null) {
            pendingCommands.remove(prepared.requestId)
            return false
        }
        val activeSocket = socket
        if (activeSocket == null) {
            pendingCommands.remove(prepared.requestId)
            _state.value = _state.value.copy(errorMessage = "Mac 尚未连接，请稍后重试")
            return false
        }
        val now = System.currentTimeMillis()
        val wire = RemoteWireMessage(
            messageId = UUID.randomUUID().toString(), hostId = profile.hostId, deviceId = profile.deviceId,
            pairingTicket = profile.pairingTicket.takeIf(String::isNotBlank), sequence = outgoingSequence.incrementAndGet(),
            expiresAt = now + 5 * 60_000L, nonce = "", ciphertext = "",
        )
        val encrypted = RemoteCrypto.encrypt(profile.pairingSecret, wire, prepared.payload)
        if (!activeSocket.send(encrypted.toJson().toString())) {
            pendingCommands.remove(prepared.requestId)
            _state.value = _state.value.copy(errorMessage = "Mac 尚未连接，请稍后重试")
            return false
        }
        return true
    }

    private fun sendAck(messageId: String) {
        val profile = profileStore.profile.value ?: return
        val now = System.currentTimeMillis()
        val wire = RemoteWireMessage(
            messageId = "ack:${UUID.randomUUID()}", hostId = profile.hostId, deviceId = profile.deviceId,
            pairingTicket = profile.pairingTicket.takeIf(String::isNotBlank), sequence = outgoingSequence.incrementAndGet(),
            expiresAt = now + 5 * 60_000L, nonce = "", ciphertext = "", ackOf = messageId,
        )
        val payload = buildJsonObject { put("type", JsonPrimitive("ack")) }
        socket?.send(RemoteCrypto.encrypt(profile.pairingSecret, wire, payload).toJson().toString())
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (socket !== webSocket) return
            reconnectAttempt = 0
            _state.value = _state.value.copy(connectionStatus = RemoteConnectionStatus.CONNECTED, errorMessage = null)
            send(RemoteCommand(type = "host.status", requestId = requestId("host.status")))
            refreshThreads()
            val profile = profileStore.profile.value
            if (profile != null) {
                scope.launch {
                    syncCoordinator?.resume(profile.hostId, profile.deviceId)
                    connectedHandler?.invoke(profile.hostId, profile.deviceId)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket !== webSocket) return
            runCatching { handleWire(text) }.onFailure { error ->
                _state.value = _state.value.copy(errorMessage = error.message ?: "远程消息解析失败")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket) scheduleReconnect(reason)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket === webSocket) scheduleReconnect(t.message ?: "连接失败")
        }
    }

    private fun scheduleReconnect(reason: String) {
        socket = null
        synchronized(commandOwnershipLock) {
            pendingCommands.clear()
            requestedThreadSummaries.clear()
            terminalTurnExecutions.clear()
            explicitlyInactiveThreads.clear()
            selectionGeneration += 1
            _state.value = _state.value.copy(
                connectionStatus = if (explicitDisconnect) RemoteConnectionStatus.DISCONNECTED else RemoteConnectionStatus.ERROR,
                errorMessage = reason,
                threads = emptyList(),
                selectedThreadId = null,
                activeThreadId = null,
                activeTurnId = null,
                timeline = emptyList(),
                approvals = emptyList(),
                isWorking = false,
                isThreadListLoading = false,
                isTimelineLoading = false,
                olderTimelineCursor = null,
                isOlderTimelineLoading = false,
                isCreatingThread = false,
                workspaceCandidates = emptyList(),
                workspaceCandidatesLoaded = false,
                capabilities = emptySet(),
                backends = emptyList(),
            )
        }
        if (explicitDisconnect) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectDelayMillis(reconnectAttempt))
            reconnectAttempt++
            connect()
        }
    }

    private fun reconnectDelayMillis(attempt: Int): Long {
        val exponential = 3_000L shl attempt.coerceAtMost(4)
        return exponential.coerceAtMost(48_000L)
    }

    private fun handleWire(text: String) {
        val profile = requireNotNull(profileStore.profile.value)
        val wire = parseWire(text)
        val firstSeen = synchronized(seenMessages) {
            if (wire.messageId in seenMessages) false
            else {
                seenMessages += wire.messageId
                while (seenMessages.size > 512) seenMessages.remove(seenMessages.first())
                true
            }
        }
        sendAck(wire.messageId)
        if (!firstSeen) return
        val plain = RemoteCrypto.decrypt(profile.pairingSecret, wire)
        val root = Json.parseToJsonElement(plain).jsonObject
        if (root["schemaVersion"] != null && root["eventId"] != null) {
            val event = parseRemoteLogicalEvent(plain)
            syncCoordinator?.let { coordinator ->
                scope.launch {
                    if (coordinator.onLogicalEvent(event) == ReduceResult.APPLIED) {
                        notifyLogicalEvent(event)
                    }
                }
            }
        } else {
            handleEvent(parseRemoteEvent(plain))
        }
    }

    private fun backendDisplayName(backendId: String?): String {
        val state = _state.value
        val resolvedId = backendId ?: state.selectedBackendId
        return state.backends.firstOrNull { it.id == resolvedId }?.name
            ?: if (resolvedId == "dsh") "DeepSeek Harness" else "Codex"
    }

    private fun notifyLogicalEvent(event: RemoteLogicalEvent) {
        val payload = event.payload as? JsonObject ?: JsonObject(emptyMap())
        when (event.type) {
            "run.approval.requested" -> _notifications.tryEmit(
                RemoteNotification(
                    title = "${backendDisplayName(event.backendId)} 等待审批",
                    message = payload.string("target")?.let(::redactRemoteSensitiveText) ?: "Mac 上的任务需要你的确认",
                    runId = event.runId,
                    approvalId = payload.string("approvalId"),
                    risk = maxRemoteApprovalRisk(
                        parseRemoteApprovalRisk(payload.string("risk").orEmpty()),
                        classifyRemoteApprovalRisk(
                            payload.string("target").orEmpty(),
                            payload.string("actionType").orEmpty(),
                        ),
                    ),
                ),
            )
            "run.completed" -> _notifications.tryEmit(
                RemoteNotification("${backendDisplayName(event.backendId)} 已完成", "远程任务已结束，点击查看结果", runId = event.runId),
            )
            "run.failed" -> _notifications.tryEmit(
                RemoteNotification("${backendDisplayName(event.backendId)} 任务失败", "打开 Harness 查看失败详情", runId = event.runId),
            )
        }
    }

    internal fun handleEvent(event: RemoteEvent) {
        when (event.type) {
            "host.status" -> handleHostStatus(event)
            "error" -> synchronized(commandOwnershipLock) {
                if (!matchesSelectedPendingRequest(event)) return
                event.requestId?.let(pendingCommands::remove)
                val agentName = backendDisplayName(event.backendId)
                val message = event.message ?: "$agentName 远程任务失败"
                _state.value = _state.value.copy(
                    errorMessage = redactRemoteSensitiveText(message).take(240),
                    activeThreadId = null,
                    activeTurnId = null,
                    isWorking = false,
                    isThreadListLoading = false,
                    isTimelineLoading = false,
                    isCreatingThread = false,
                )
                _notifications.tryEmit(RemoteNotification("$agentName 任务失败", message))
            }
            "rpc.response" -> handleRpcResponse(event)
            "workspace.candidates" -> synchronized(commandOwnershipLock) {
                if (!matchesSelectedPendingRequest(event)) return
                event.requestId?.let(pendingCommands::remove)
                _state.value = _state.value.copy(
                    workspaceCandidates = parseWorkspaceCandidates(event),
                    workspaceCandidatesLoaded = true,
                )
            }
            "sync.gap" -> {
                val hostId = profileStore.profile.value?.hostId ?: return
                syncCoordinator?.let { coordinator -> scope.launch { coordinator.onGap(hostId) } }
            }
            "sync.snapshot" -> {
                val payload = event.payload as? JsonObject ?: return
                syncCoordinator?.let { coordinator ->
                    scope.launch { coordinator.onSnapshot(parseRemoteRunSnapshot(payload)) }
                }
            }
            "approval.request" -> {
                if (matchesSelectedBackend(event)) handleApproval(event)
            }
            "codex.event" -> {
                if (matchesSelectedBackend(event)) handleCodexEvent(event)
            }
        }
    }

    private fun handleHostStatus(event: RemoteEvent) = synchronized(commandOwnershipLock) {
        val parsed = parseRemoteBackends(event)
        val hostCapabilities = parseRemoteHostCapabilities(event)
        val backends = parsed ?: fallbackRemoteBackends(hostCapabilities)
        val current = _state.value
        val restorePreferred = backendSelectionIsFallback && backends.any { it.id == preferredBackendId }
        val selected = if (restorePreferred) {
            preferredBackendId
        } else {
            reconcileSelectedBackend(current.selectedBackendId, backends)
        }
        backendSelectionIsFallback = when {
            restorePreferred -> false
            backends.isEmpty() -> selected != preferredBackendId
            backends.none { it.id == current.selectedBackendId } -> selected != preferredBackendId
            else -> backendSelectionIsFallback
        }
        val backendRosterChanged = parsed != null && current.backends != backends
        val backendChanged = selected != current.selectedBackendId || backends.isEmpty() || backendRosterChanged
        val capabilities = backends.firstOrNull { it.id == selected }?.capabilities
            ?: if (parsed == null) hostCapabilities else emptySet()
        if (backendChanged) {
            selectionGeneration += 1
            pendingCommands.clear()
            requestedThreadSummaries.clear()
            terminalTurnExecutions.clear()
            explicitlyInactiveThreads.clear()
        }
        _state.value = current.copy(
            connectionStatus = RemoteConnectionStatus.CONNECTED,
            backends = backends,
            selectedBackendId = selected,
            capabilities = capabilities,
            threads = if (backendChanged) emptyList() else current.threads,
            selectedThreadId = if (backendChanged) null else current.selectedThreadId,
            activeThreadId = if (backendChanged) null else current.activeThreadId,
            activeTurnId = if (backendChanged) null else current.activeTurnId,
            timeline = if (backendChanged) emptyList() else current.timeline,
            approvals = if (backendChanged) emptyList() else current.approvals,
            isWorking = if (backendChanged) false else current.isWorking,
            isThreadListLoading = if (backendChanged) false else current.isThreadListLoading,
            isTimelineLoading = if (backendChanged) false else current.isTimelineLoading,
            olderTimelineCursor = if (backendChanged) null else current.olderTimelineCursor,
            isOlderTimelineLoading = if (backendChanged) false else current.isOlderTimelineLoading,
            isCreatingThread = if (backendChanged) false else current.isCreatingThread,
            workspaceCandidates = if (backendChanged) emptyList() else current.workspaceCandidates,
            workspaceCandidatesLoaded = if (backendChanged) false else current.workspaceCandidatesLoaded,
            errorMessage = null,
        )
        if (backendChanged && backends.isNotEmpty()) refreshThreads()
    }

    /** Events from another backend are ignored; legacy events carry no id. */
    private fun matchesSelectedBackend(event: RemoteEvent): Boolean {
        val backendId = event.backendId ?: return true
        return backendId == _state.value.selectedBackendId
    }

    /** Tagged command results must still own a pending request; untagged legacy events remain unsolicited. */
    private fun matchesSelectedPendingRequest(event: RemoteEvent): Boolean {
        if (!matchesSelectedBackend(event)) return false
        val requestId = event.requestId ?: return true
        val pending = pendingCommands[requestId] ?: return false
        return pending.backendId == _state.value.selectedBackendId &&
            (event.backendId == null || event.backendId == pending.backendId)
    }

    /** Switches the active backend and reloads its thread list. */
    fun selectBackend(backendId: String) = synchronized(commandOwnershipLock) {
        val current = _state.value
        if (current.backends.none { it.id == backendId }) return@synchronized
        preferredBackendId = backendId
        backendSelectionIsFallback = false
        if (current.selectedBackendId == backendId) return@synchronized
        selectionGeneration += 1
        requestedThreadSummaries.clear()
        pendingCommands.clear()
        terminalTurnExecutions.clear()
        explicitlyInactiveThreads.clear()
        val capabilities = current.backends.firstOrNull { it.id == backendId }?.capabilities.orEmpty()
        _state.value = current.copy(
            selectedBackendId = backendId,
            capabilities = capabilities,
            threads = emptyList(),
            selectedThreadId = null,
            activeThreadId = null,
            activeTurnId = null,
            timeline = emptyList(),
            approvals = emptyList(),
            isWorking = false,
            isThreadListLoading = false,
            isTimelineLoading = false,
            olderTimelineCursor = null,
            isOlderTimelineLoading = false,
            isCreatingThread = false,
            workspaceCandidates = emptyList(),
            workspaceCandidatesLoaded = false,
        )
        refreshThreads()
    }

    private fun handleRpcResponse(event: RemoteEvent) = synchronized(commandOwnershipLock) {
        handleRpcResponseLocked(event)
    }

    private fun handleRpcResponseLocked(event: RemoteEvent) {
        val requestId = event.requestId ?: return
        val pending = pendingCommands[requestId] ?: return
        val currentBackendId = _state.value.selectedBackendId
        if (pending.backendId != currentBackendId) {
            pendingCommands.remove(requestId)
            return
        }
        if (event.backendId != null && event.backendId != pending.backendId) return
        pendingCommands.remove(requestId)
        val kind = pending.kind
        val pendingThreadId = pending.threadId
        val pendingSelectionGeneration = pending.selectionGeneration
        if (kind == "thread.read" || kind == "thread.read.older") {
            val staleThread = pendingThreadId != null && pendingThreadId != _state.value.selectedThreadId
            val staleSelection = pendingSelectionGeneration != null && pendingSelectionGeneration != selectionGeneration
            if (staleThread || staleSelection) return
        }
        val requestedOlderCursor = pending.olderCursor ?: _state.value.olderTimelineCursor
        val response = event.payload as? JsonObject
        if (kind == "turn.start" && response?.string("outcome") == "UNKNOWN") {
            val threadId = pendingThreadId ?: _state.value.selectedThreadId
            val current = _state.value
            _state.value = current.copy(
                activeThreadId = current.activeThreadId.takeUnless { it == threadId },
                activeTurnId = current.activeTurnId.takeUnless { current.activeThreadId == threadId },
                isWorking = current.isWorking && current.selectedThreadId != threadId,
                timeline = current.timeline.withPendingTurnStatus(event.requestId, "reconciling"),
                threads = current.threads.withExecution(
                    threadId,
                    RemoteThreadExecution(RemoteThreadExecutionState.UNKNOWN),
                ),
                errorMessage = "发送结果待确认，正在从 Mac 恢复状态",
            )
            threadId?.let(requestedThreadSummaries::remove)
            return
        }
        if (event.payload?.jsonObject?.get("error") != null) {
            if (kind == "thread.summary") {
                pendingThreadId?.let(requestedThreadSummaries::remove)
                return
            }
            val completed = completedCommandState(kind)
            val failedTurnStart = kind == "turn.start" && pendingThreadId != null
            val failedSelectedTurnStart = failedTurnStart && completed.selectedThreadId == pendingThreadId
            _state.value = completed.copy(
                activeThreadId = completed.activeThreadId.takeUnless {
                    failedTurnStart && it == pendingThreadId
                },
                activeTurnId = completed.activeTurnId.takeUnless {
                    failedTurnStart && completed.activeThreadId == pendingThreadId
                },
                isWorking = completed.isWorking && !failedSelectedTurnStart,
                timeline = completed.timeline.withPendingTurnStatus(
                    event.requestId,
                    if (kind == "turn.start") "sendFailed" else null,
                ),
                threads = if (failedTurnStart) {
                    completed.threads.withExecution(
                        pendingThreadId,
                        RemoteThreadExecution(RemoteThreadExecutionState.FAILED),
                    )
                } else {
                    completed.threads
                },
                errorMessage = remoteRpcErrorMessage(event.payload),
            )
            _notifications.tryEmit(RemoteNotification("${backendDisplayName(event.backendId)} 任务失败", "Mac 返回了错误，请打开 Harness 查看详情"))
            return
        }
        if (kind == "thread.read" || kind == "thread.read.older") {
            val responseThreadId = event.payload?.jsonObject?.get("result")?.jsonObject
                ?.get("thread")?.jsonObject?.string("id")
            if (responseThreadId != null && responseThreadId != _state.value.selectedThreadId) return
        }
        if (kind != "thread.summary") {
            _state.value = _state.value.copy(errorMessage = null)
        }
        when (kind) {
            "thread.list" -> {
                val previousById = _state.value.threads.associateBy(RemoteThread::id)
                val refreshed = parseThreads(event).map { thread ->
                    val previous = previousById[thread.id]
                    if (previous?.updatedAt == thread.updatedAt) {
                        thread.copy(
                            latestUserMessage = thread.latestUserMessage ?: previous.latestUserMessage,
                            execution = if (thread.execution.state == RemoteThreadExecutionState.UNKNOWN) {
                                previous.execution
                            } else {
                                thread.execution
                            },
                        )
                    } else {
                        thread
                    }
                }
                requestedThreadSummaries.retainAll(refreshed.mapTo(mutableSetOf(), RemoteThread::id))
                _state.value = _state.value.copy(
                    threads = refreshed,
                    isThreadListLoading = false,
                )
            }
            "thread.summary" -> {
                val result = event.payload?.jsonObject?.get("result") as? JsonObject
                val threadId = result?.string("threadId")
                val latestUserMessage = result?.string("latestUserMessage")
                val execution = parseRemoteThreadExecution(result?.get("execution") as? JsonObject)
                if (threadId != null) {
                    requestedThreadSummaries.remove(threadId)
                    val current = _state.value
                    val selected = current.selectedThreadId == threadId
                    _state.value = current.copy(
                        threads = current.threads.map { thread ->
                            if (thread.id == threadId) {
                                val summaryText = latestUserMessage?.trim()?.takeIf(String::isNotBlank)
                                thread.copy(
                                    title = if (thread.title == "未命名会话" || thread.title == "未命名线程") {
                                        summaryText?.lineSequence()?.firstOrNull()?.take(60) ?: thread.title
                                    } else {
                                        thread.title
                                    },
                                    latestUserMessage = summaryText?.take(240) ?: thread.latestUserMessage,
                                    execution = reconcileRemoteThreadExecution(thread.execution, execution),
                                )
                            } else {
                                thread
                            }
                        },
                        activeThreadId = when {
                            selected && execution.state.isActive -> threadId
                            selected && current.activeThreadId == threadId -> null
                            else -> current.activeThreadId
                        },
                        activeTurnId = when {
                            selected && execution.state.isActive -> execution.turnId
                            selected && current.activeThreadId == threadId -> null
                            else -> current.activeTurnId
                        },
                        isWorking = if (selected) execution.state.isActive else current.isWorking,
                    )
                }
            }
            "thread.start" -> {
                val id = event.payload?.jsonObject?.get("result")?.jsonObject?.get("thread")?.jsonObject?.string("id")
                if (id != null) {
                    selectionGeneration += 1
                    _state.value = _state.value.copy(
                        selectedThreadId = id,
                        timeline = emptyList(),
                        approvals = emptyList(),
                        isWorking = _state.value.activeThreadId == id,
                        isCreatingThread = false,
                        isTimelineLoading = false,
                        olderTimelineCursor = null,
                        isOlderTimelineLoading = false,
                    )
                } else {
                    _state.value = _state.value.copy(isCreatingThread = false)
                }
            }
            "thread.read" -> event.payload?.let { applyThreadHistory(it, prepend = false) }
                ?: run { _state.value = _state.value.copy(isTimelineLoading = false) }
            "thread.read.older" -> event.payload?.let {
                applyThreadHistory(it, prepend = true, requestedOlderCursor = requestedOlderCursor)
            }
                ?: run { _state.value = _state.value.copy(isOlderTimelineLoading = false) }
            "turn.start" -> {
                val result = event.payload?.jsonObject?.get("result") as? JsonObject
                val responseTurn = result?.get("turn") as? JsonObject
                val turnId = responseTurn?.string("id")
                val responseExecution = parseRemoteTurnExecution(
                    responseTurn,
                    fallback = RemoteThreadExecutionState.RUNNING,
                )
                val continuation = (result?.get("continuation") as? JsonObject)?.takeIf {
                    val sourceThreadId = it.string("continuedFromThreadId")
                    val continuationThreadId = it.string("threadId")
                    continuationThreadId?.isNotBlank() == true && sourceThreadId == pendingThreadId
                }
                val threadId = continuation?.string("threadId") ?: pendingThreadId ?: _state.value.selectedThreadId
                val current = _state.value
                val startedAtMillis = current.threads.firstOrNull { it.id == threadId }
                    ?.execution
                    ?.startedAtMillis
                    ?: System.currentTimeMillis()
                val existingExecution = current.threads.firstOrNull { it.id == threadId }?.execution
                val terminalExecution = terminalTurnExecution(threadId, turnId)
                val terminalResponse = responseExecution.takeUnless { it.state.isActive }
                val anotherThreadIsActive = current.activeThreadId != null &&
                    current.activeThreadId != threadId &&
                    current.activeThreadId != pendingThreadId
                val continuationMayAutoSelect = continuation != null &&
                    pendingThreadId != null &&
                    current.selectedThreadId == pendingThreadId &&
                    (pendingSelectionGeneration == null || pendingSelectionGeneration == selectionGeneration)
                val terminalBeforeResponse = terminalExecution != null || terminalResponse != null ||
                    (threadId != null && threadId in explicitlyInactiveThreads)
                val execution = when {
                    terminalExecution != null -> terminalExecution
                    terminalResponse != null -> terminalResponse
                    terminalBeforeResponse -> existingExecution?.takeUnless { it.state.isActive }
                        ?: RemoteThreadExecution(RemoteThreadExecutionState.UNKNOWN, turnId = turnId)
                    existingExecution != null &&
                        !existingExecution.state.isActive &&
                        existingExecution.state != RemoteThreadExecutionState.UNKNOWN &&
                        existingExecution.turnId == turnId -> existingExecution
                    else -> RemoteThreadExecution(
                        state = RemoteThreadExecutionState.RUNNING,
                        turnId = turnId,
                        startedAtMillis = startedAtMillis,
                    )
                }
                rememberTerminalTurnExecution(threadId, execution)
                val sentTimeline = current.timeline.withPendingTurnStatus(event.requestId, "sent")
                if (continuation != null && threadId != null) {
                    val sourceThreadId = continuation.string("continuedFromThreadId").orEmpty()
                    val pendingItemId = "pending-user:$requestId"
                    val pendingItem = sentTimeline.firstOrNull { it.id == pendingItemId }
                    val sourceThread = current.threads.firstOrNull { it.id == sourceThreadId }
                    val continuationThread = current.threads.firstOrNull { it.id == threadId }?.copy(
                        execution = execution,
                    ) ?: RemoteThread(
                        id = threadId,
                        title = sourceThread?.title?.takeIf(String::isNotBlank)?.let { "$it · 续聊" } ?: "大会话续聊",
                        preview = pendingItem?.text.orEmpty(),
                        cwd = continuation.string("cwd") ?: sourceThread?.cwd,
                        updatedAt = System.currentTimeMillis(),
                        status = "active",
                        latestUserMessage = pendingItem?.text,
                        execution = execution,
                    )
                    val sourcePreserved = current.threads
                        .filterNot { it.id == threadId }
                        .map { thread ->
                            if (thread.id == sourceThreadId) {
                                thread.copy(execution = RemoteThreadExecution(RemoteThreadExecutionState.UNKNOWN))
                            } else {
                                thread
                            }
                        }
                    requestedThreadSummaries.remove(sourceThreadId)
                    val continuedThreads = listOf(continuationThread) + sourcePreserved
                    if (!continuationMayAutoSelect || anotherThreadIsActive) {
                        _state.value = current.copy(threads = continuedThreads)
                    } else {
                        selectionGeneration += 1
                        _state.value = current.copy(
                            selectedThreadId = threadId,
                            activeThreadId = threadId.takeIf { execution.state.isActive },
                            activeTurnId = turnId.takeIf { execution.state.isActive },
                            isWorking = execution.state.isActive,
                            timeline = listOf(
                                RemoteTimelineItem(
                                    id = "continuation:$threadId",
                                    kind = "continuation",
                                    text = "历史较长，已懒加载最近上下文并在同一工作目录创建续聊会话。原会话仍保留，可随时返回查看。",
                                ),
                            ) + listOfNotNull(pendingItem),
                            threads = continuedThreads,
                            approvals = emptyList(),
                            isTimelineLoading = false,
                            olderTimelineCursor = null,
                            isOlderTimelineLoading = false,
                        )
                    }
                } else {
                    _state.value = current.copy(
                        activeThreadId = if (anotherThreadIsActive) current.activeThreadId else {
                            threadId.takeIf { execution.state.isActive && !terminalBeforeResponse }
                        },
                        activeTurnId = if (anotherThreadIsActive) current.activeTurnId else {
                            turnId.takeIf { execution.state.isActive && !terminalBeforeResponse }
                        },
                        isWorking = if (anotherThreadIsActive) current.isWorking else {
                            execution.state.isActive && !terminalBeforeResponse && threadId != null && threadId == current.selectedThreadId
                        },
                        timeline = sentTimeline,
                        threads = current.threads.withExecution(threadId, execution),
                    )
                }
            }
        }
    }

    private fun applyThreadHistory(
        payload: JsonElement,
        prepend: Boolean,
        requestedOlderCursor: String? = null,
    ) {
        val result = payload.jsonObject["result"]?.jsonObject
        val thread = result?.get("thread")?.jsonObject
            ?: run {
                _state.value = _state.value.copy(isTimelineLoading = false, isOlderTimelineLoading = false)
                return
            }
        if (thread.string("id") != _state.value.selectedThreadId) return
        val items = thread["turns"]?.jsonArray?.toList().orEmpty().flatMap { turn ->
            turn.jsonObject["items"]?.jsonArray?.toList().orEmpty().mapNotNull(::timelineItem)
        }
        val currentTimeline = _state.value.timeline
        val timeline = mergeRemoteHistoryPage(currentTimeline, items)
        val olderCursor = (result["mobileHistory"] as? JsonObject)?.string("olderCursor")
        val addedUniqueItem = items.any { pageItem -> currentTimeline.none { it.id == pageItem.id } }
        val effectiveOlderCursor = olderCursor.takeUnless {
            prepend && it == requestedOlderCursor && !addedUniqueItem
        }
        _state.value = _state.value.copy(
            timeline = timeline,
            olderTimelineCursor = effectiveOlderCursor,
            isTimelineLoading = false,
            isOlderTimelineLoading = false,
        )
    }

    private fun completedCommandState(kind: String?): RemoteUiState = when (kind) {
        "thread.list" -> _state.value.copy(isThreadListLoading = false)
        "thread.read" -> _state.value.copy(isTimelineLoading = false)
        "thread.read.older" -> _state.value.copy(isOlderTimelineLoading = false)
        "thread.start" -> _state.value.copy(isCreatingThread = false)
        else -> _state.value
    }

    private fun handleCodexEvent(event: RemoteEvent) {
        val raw = event.payload?.jsonObject ?: return
        val method = raw.string("method") ?: event.method.orEmpty()
        val params = raw["params"]?.jsonObject ?: JsonObject(emptyMap())
        val eventThreadId = params.string("threadId")
        when (method) {
            "thread/status/changed" -> {
                val current = _state.value
                val execution = parseRemoteThreadStatus(params["status"] as? JsonObject)
                val selected = eventThreadId != null && eventThreadId == current.selectedThreadId
                eventThreadId?.let { threadId ->
                    if (execution.state.isActive) explicitlyInactiveThreads.remove(threadId)
                    else explicitlyInactiveThreads.add(threadId)
                }
                _state.value = current.copy(
                    threads = current.threads.withExecution(eventThreadId, execution),
                    activeThreadId = when {
                        selected && execution.state.isActive -> eventThreadId
                        selected && current.activeThreadId == eventThreadId -> null
                        else -> current.activeThreadId
                    },
                    activeTurnId = if (selected && !execution.state.isActive) null else current.activeTurnId,
                    isWorking = if (selected) execution.state.isActive else current.isWorking,
                )
                return
            }
            "turn/started" -> {
                val activeThreadId = eventThreadId ?: _state.value.selectedThreadId
                val turnId = params["turn"]?.jsonObject?.string("id")
                if (terminalTurnExecution(activeThreadId, turnId) != null) return
                activeThreadId?.let(explicitlyInactiveThreads::remove)
                _state.value = _state.value.copy(
                    activeThreadId = activeThreadId,
                    activeTurnId = turnId,
                    isWorking = activeThreadId != null && activeThreadId == _state.value.selectedThreadId,
                    threads = _state.value.threads.withExecution(
                        activeThreadId,
                        RemoteThreadExecution(RemoteThreadExecutionState.RUNNING, turnId = turnId),
                    ),
                )
                return
            }
            "turn/completed" -> {
                val current = _state.value
                val completedThreadId = eventThreadId ?: current.activeThreadId
                val execution = parseRemoteTurnExecution(
                    params["turn"] as? JsonObject,
                    fallback = RemoteThreadExecutionState.COMPLETED,
                )
                rememberTerminalTurnExecution(completedThreadId, execution)
                val matchesActiveThread = eventThreadId == null || eventThreadId == current.activeThreadId
                val matchesActiveTurn = execution.turnId == null ||
                    (current.activeTurnId != null && execution.turnId == current.activeTurnId)
                val completesActiveTurn = matchesActiveThread && matchesActiveTurn
                val staleCompletionForActiveThread = completedThreadId != null &&
                    completedThreadId == current.activeThreadId && !matchesActiveTurn
                _state.value = current.copy(
                    activeThreadId = current.activeThreadId.takeUnless { completesActiveTurn },
                    activeTurnId = current.activeTurnId.takeUnless { completesActiveTurn },
                    isWorking = current.isWorking && !completesActiveTurn,
                    threads = if (staleCompletionForActiveThread) {
                        current.threads
                    } else {
                        current.threads.withExecution(completedThreadId, execution)
                    },
                )
                val agentName = backendDisplayName(event.backendId)
                val notification = when (execution.state) {
                    RemoteThreadExecutionState.FAILED -> RemoteNotification("$agentName 任务失败", "打开 Harness 查看失败详情")
                    RemoteThreadExecutionState.INTERRUPTED -> RemoteNotification("$agentName 已停止", "远程任务已中断")
                    else -> RemoteNotification("$agentName 已完成", "远程任务已结束，点击查看结果")
                }
                _notifications.tryEmit(notification)
                refreshThreads()
                return
            }
        }
        if (!matchesSelectedThread(eventThreadId)) return
        when (method) {
            "item/started", "item/completed" -> params["item"]?.let { item ->
                timelineItem(item)?.let { addOrReplaceTimeline(it) }
            }
            "item/agentMessage/delta" -> appendAgentDelta(params.string("itemId"), params.string("delta").orEmpty())
        }
    }

    private fun terminalTurnKey(threadId: String, turnId: String): String = "$threadId\u0000$turnId"

    private fun terminalTurnExecution(threadId: String?, turnId: String?): RemoteThreadExecution? {
        if (threadId == null || turnId == null) return null
        return terminalTurnExecutions[terminalTurnKey(threadId, turnId)]
    }

    private fun rememberTerminalTurnExecution(threadId: String?, execution: RemoteThreadExecution) {
        val turnId = execution.turnId ?: return
        if (threadId == null || execution.state.isActive) return
        terminalTurnExecutions[terminalTurnKey(threadId, turnId)] = execution
        while (terminalTurnExecutions.size > 64) {
            terminalTurnExecutions.remove(terminalTurnExecutions.keys.first())
        }
    }

    private fun List<RemoteThread>.withExecution(
        threadId: String?,
        execution: RemoteThreadExecution,
    ): List<RemoteThread> = if (threadId == null) {
        this
    } else {
        map { thread -> if (thread.id == threadId) thread.copy(execution = execution) else thread }
    }

    private fun handleApproval(event: RemoteEvent) {
        val raw = event.payload?.jsonObject ?: return
        val params = raw["params"]?.jsonObject ?: return
        val id = raw["id"] ?: return
        if (!matchesSelectedThread(params.string("threadId"))) return
        val command = params["command"]?.let { value ->
            if (value is JsonPrimitive) value.contentOrNull else value.toString()
        }
        val current = _state.value
        val agentName = backendDisplayName(event.backendId)
        val threadId = params.string("threadId")
        val turnId = params.string("turnId")
        _state.value = current.copy(
            approvals = current.approvals + RemoteApproval(
                requestId = id,
                method = raw.string("method").orEmpty(),
                threadId = threadId,
                turnId = turnId,
                reason = params.string("reason") ?: "$agentName 请求执行受保护操作",
                command = command,
            ),
            threads = current.threads.withExecution(
                threadId,
                RemoteThreadExecution(RemoteThreadExecutionState.WAITING_APPROVAL, turnId = turnId),
            ),
            activeThreadId = threadId ?: current.activeThreadId,
            activeTurnId = turnId ?: current.activeTurnId,
            isWorking = threadId == null || threadId == current.selectedThreadId,
        )
        _notifications.tryEmit(RemoteNotification("$agentName 等待审批", "Mac 上的任务需要你的确认"))
    }

    private fun matchesSelectedThread(eventThreadId: String?): Boolean {
        val selected = _state.value.selectedThreadId
        return selected == null || eventThreadId == null || eventThreadId == selected
    }

    private fun timelineItem(element: JsonElement): RemoteTimelineItem? {
        val item = element.jsonObject
        val agentName = backendDisplayName(null)
        val id = item.string("id") ?: return null
        val kind = item.string("type").orEmpty()
        val status = item.string("status")
        val text = when (kind) {
            "agentMessage", "userMessage" -> item.string("text") ?: remoteMessageContentText(item["content"])
            "commandExecution" -> remoteCommandText(item["command"])
            "fileChange" -> remoteFileChangeText(item["changes"])
            "reasoning" -> item.string("summary")?.takeIf(String::isNotBlank)
                ?: item.string("text")?.takeIf(String::isNotBlank)
                ?: if (status in setOf("streaming", "inProgress", "running")) "$agentName 正在分析" else return null
            "webSearch" -> item.string("query") ?: item.string("text") ?: "$agentName 正在查找资料"
            else -> item.string("text") ?: item.string("status") ?: "远程事件更新"
        }
        return RemoteTimelineItem(id, kind, text, status)
    }

    private fun remoteCommandText(command: JsonElement?): String = when (command) {
        is JsonPrimitive -> command.contentOrNull.orEmpty()
        is kotlinx.serialization.json.JsonArray -> command.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }.joinToString(" ")
        else -> ""
    }.let(::redactRemoteSensitiveText)

    private fun remoteFileChangeText(changes: JsonElement?): String {
        val entries = (changes as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { change ->
            val item = change as? JsonObject ?: return@mapNotNull null
            val path = item.string("path")?.let(::redactRemoteSensitiveText)?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val action = when ((item.string("kind") ?: item.string("type")).orEmpty().lowercase()) {
                "create", "add", "added" -> "新建"
                "delete", "remove", "deleted" -> "删除"
                "rename", "renamed" -> "重命名"
                else -> "修改"
            }
            "$action `$path`"
        }
        return entries.joinToString("\n").ifBlank {
            val count = (changes as? kotlinx.serialization.json.JsonArray)?.size ?: 0
            if (count > 0) "$count 个文件变更" else "文件变更"
        }
    }

    private fun remoteMessageContentText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is kotlinx.serialization.json.JsonArray -> content.mapNotNull { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrNull
                is JsonObject -> part.string("text") ?: part.string("content")
                else -> null
            }?.takeIf(String::isNotBlank)
        }.joinToString("\n")
        else -> ""
    }

    private fun addOrReplaceTimeline(item: RemoteTimelineItem) {
        _state.value = _state.value.copy(timeline = mergeRemoteTimeline(_state.value.timeline, item))
    }

    private fun appendAgentDelta(itemId: String?, delta: String) {
        if (delta.isEmpty()) return
        val current = _state.value.timeline
        val stableId = itemId?.takeIf(String::isNotBlank)
            ?: "stream:${_state.value.selectedThreadId}:${_state.value.activeTurnId ?: "pending"}"
        val existing = current.firstOrNull { it.id == stableId }
        val streaming = existing?.copy(text = existing.text + delta, status = "streaming")
            ?: RemoteTimelineItem(stableId, "agentMessage", delta, "streaming")
        val next = if (existing == null) current + streaming else current.map { if (it.id == stableId) streaming else it }
        _state.value = _state.value.copy(timeline = next)
    }
}

private fun List<RemoteTimelineItem>.withPendingTurnStatus(
    requestId: String?,
    status: String?,
): List<RemoteTimelineItem> {
    if (requestId == null || status == null) return this
    val pendingId = "pending-user:$requestId"
    return map { item -> if (item.id == pendingId) item.copy(status = status) else item }
}

private fun reconcileRemoteThreadExecution(
    current: RemoteThreadExecution,
    incoming: RemoteThreadExecution,
): RemoteThreadExecution {
    val incomingIsOlderTerminal = current.state.isActive &&
        !incoming.state.isActive &&
        current.startedAtMillis != null &&
        incoming.completedAtMillis != null &&
        incoming.completedAtMillis < current.startedAtMillis
    return if (incomingIsOlderTerminal) current else incoming
}

internal fun mergeRemoteTimeline(
    current: List<RemoteTimelineItem>,
    item: RemoteTimelineItem,
): List<RemoteTimelineItem> {
    val existingIndex = current.indexOfFirst { it.id == item.id }
    if (existingIndex >= 0) return current.toMutableList().also { it[existingIndex] = item }
    if (item.kind == "userMessage") {
        val pendingIndex = current.indexOfLast {
            it.kind == "userMessage" && it.status in setOf("sending", "sent", "reconciling") && it.text == item.text
        }
        if (pendingIndex >= 0) return current.toMutableList().also { it[pendingIndex] = item }
    }
    return current + item
}

internal fun mergeRemoteHistoryPage(
    current: List<RemoteTimelineItem>,
    page: List<RemoteTimelineItem>,
): List<RemoteTimelineItem> {
    val uniquePage = page.distinctBy(RemoteTimelineItem::id)
    val pageIds = uniquePage.mapTo(hashSetOf(), RemoteTimelineItem::id)
    val pageUserTexts = uniquePage.asSequence()
        .filter { it.kind == "userMessage" }
        .map(RemoteTimelineItem::text)
        .toSet()
    val reconciledCurrent = current.filterNot {
        it.kind == "userMessage" && it.status in setOf("sending", "sent", "reconciling") && it.text in pageUserTexts
    }
    val currentById = reconciledCurrent.associateBy(RemoteTimelineItem::id)
    val pageWithRealtimeWins = uniquePage.map { currentById[it.id] ?: it }
    return pageWithRealtimeWins + reconciledCurrent.filter { it.id !in pageIds }
}

private fun RemoteWireMessage.toJson(): JsonObject = buildJsonObject {
    put("version", JsonPrimitive(version)); put("messageId", JsonPrimitive(messageId)); put("hostId", JsonPrimitive(hostId))
    put("deviceId", JsonPrimitive(deviceId)); pairingTicket?.let { put("pairingTicket", JsonPrimitive(it)) }
    put("sequence", JsonPrimitive(sequence)); put("expiresAt", JsonPrimitive(expiresAt)); put("nonce", JsonPrimitive(nonce)); put("ciphertext", JsonPrimitive(ciphertext))
    pushKind?.let { put("pushKind", JsonPrimitive(it)) }
    ackOf?.let { put("ackOf", JsonPrimitive(it)) }
}

private fun parseWire(raw: String): RemoteWireMessage {
    val root = Json.parseToJsonElement(raw).jsonObject
    return RemoteWireMessage(
        version = root.long("version")?.toInt() ?: 0, messageId = root.string("messageId").orEmpty(), hostId = root.string("hostId").orEmpty(),
        deviceId = root.string("deviceId").orEmpty(), pairingTicket = root.string("pairingTicket"), sequence = root.long("sequence") ?: 0,
        expiresAt = root.long("expiresAt") ?: 0, nonce = root.string("nonce").orEmpty(), ciphertext = root.string("ciphertext").orEmpty(),
        pushKind = root.string("pushKind"), ackOf = root.string("ackOf"),
    )
}
