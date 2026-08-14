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
        else -> "Mac 返回错误，请稍后重试"
    }
}

internal data class PendingRemoteCommand(
    val kind: String,
    val threadId: String? = null,
    val olderCursor: String? = null,
    val selectionGeneration: Long? = null,
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
    private var selectionGeneration = 0L
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
        )
    }

    fun refreshThreads() {
        _state.value = _state.value.copy(isThreadListLoading = true, errorMessage = null)
        if (!send(RemoteCommand(type = "thread.list", requestId = requestId("thread.list")))) {
            _state.value = _state.value.copy(isThreadListLoading = false)
        }
    }
    fun loadThreadSummary(threadId: String) {
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
    fun requestWorkspaceCandidates() {
        val current = _state.value
        _state.value = current.copy(workspaceCandidatesLoaded = current.workspaceCandidates.isNotEmpty())
        if (!send(RemoteCommand(type = "workspace.list", requestId = requestId("workspace.list")))) {
            _state.value = _state.value.copy(workspaceCandidatesLoaded = true)
        }
    }
    fun selectThread(threadId: String) {
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
    fun startTurn(text: String) {
        val threadId = _state.value.selectedThreadId ?: return
        val requestId = requestId("turn.start", threadId)
        if (send(RemoteCommand(type = "turn.start", requestId = requestId, threadId = threadId, text = text))) {
            _state.value = _state.value.copy(
                activeThreadId = threadId,
                activeTurnId = null,
                isWorking = true,
                timeline = _state.value.timeline + RemoteTimelineItem("pending-user:$requestId", "userMessage", text, "sending"),
            )
        }
    }
    fun steer(text: String) {
        val current = _state.value
        val threadId = current.selectedThreadId ?: return
        send(RemoteCommand(type = "turn.steer", requestId = requestId("turn.steer"), threadId = threadId, text = text, expectedTurnId = current.activeTurnId))
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
    ): String =
        "$kind:${UUID.randomUUID()}".also {
            pendingCommands[it] = PendingRemoteCommand(kind, threadId, olderCursor, selectionGeneration)
        }

    private fun send(command: RemoteCommand): Boolean {
        return sendPayload(command.requestId, command.toJson())
    }

    override fun send(command: RebuiltRemoteCommand): Boolean =
        sendPayload(command.commandId, command.payload)

    override fun send(command: JsonObject): Boolean =
        sendPayload(command.string("requestId") ?: "sync:${UUID.randomUUID()}", command)

    private fun sendPayload(requestId: String, payload: JsonObject): Boolean {
        val profile = profileStore.profile.value
        if (profile == null) {
            pendingCommands.remove(requestId)
            return false
        }
        val activeSocket = socket
        if (activeSocket == null) {
            pendingCommands.remove(requestId)
            _state.value = _state.value.copy(errorMessage = "Mac 尚未连接，请稍后重试")
            return false
        }
        val now = System.currentTimeMillis()
        val wire = RemoteWireMessage(
            messageId = UUID.randomUUID().toString(), hostId = profile.hostId, deviceId = profile.deviceId,
            pairingTicket = profile.pairingTicket.takeIf(String::isNotBlank), sequence = outgoingSequence.incrementAndGet(),
            expiresAt = now + 5 * 60_000L, nonce = "", ciphertext = "",
        )
        val encrypted = RemoteCrypto.encrypt(profile.pairingSecret, wire, payload)
        if (!activeSocket.send(encrypted.toJson().toString())) {
            pendingCommands.remove(requestId)
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
        _state.value = _state.value.copy(
            connectionStatus = if (explicitDisconnect) RemoteConnectionStatus.DISCONNECTED else RemoteConnectionStatus.ERROR,
            errorMessage = reason,
            isThreadListLoading = false,
            isTimelineLoading = false,
            isOlderTimelineLoading = false,
            isCreatingThread = false,
            capabilities = emptySet(),
        )
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

    private fun notifyLogicalEvent(event: RemoteLogicalEvent) {
        val payload = event.payload as? JsonObject ?: JsonObject(emptyMap())
        when (event.type) {
            "run.approval.requested" -> _notifications.tryEmit(
                RemoteNotification(
                    title = "Codex 等待审批",
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
                RemoteNotification("Codex 已完成", "远程任务已结束，点击查看结果", runId = event.runId),
            )
            "run.failed" -> _notifications.tryEmit(
                RemoteNotification("Codex 任务失败", "打开 Harness 查看失败详情", runId = event.runId),
            )
        }
    }

    internal fun handleEvent(event: RemoteEvent) {
        when (event.type) {
            "host.status" -> _state.value = _state.value.copy(
                connectionStatus = RemoteConnectionStatus.CONNECTED,
                capabilities = parseRemoteHostCapabilities(event),
                errorMessage = null,
            )
            "error" -> {
                val message = event.message ?: "Codex 远程任务失败"
                _state.value = _state.value.copy(
                    errorMessage = redactRemoteSensitiveText(message).take(240),
                    activeThreadId = null,
                    activeTurnId = null,
                    isWorking = false,
                    isThreadListLoading = false,
                    isTimelineLoading = false,
                    isCreatingThread = false,
                )
                _notifications.tryEmit(RemoteNotification("Codex 任务失败", message))
            }
            "rpc.response" -> handleRpcResponse(event)
            "workspace.candidates" -> _state.value = _state.value.copy(
                workspaceCandidates = parseWorkspaceCandidates(event),
                workspaceCandidatesLoaded = true,
            )
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
            "approval.request" -> handleApproval(event)
            "codex.event" -> handleCodexEvent(event)
        }
    }

    private fun handleRpcResponse(event: RemoteEvent) {
        val pending = pendingCommands.remove(event.requestId)
            ?: event.requestId?.substringBefore(':')?.takeIf {
                it in setOf("thread.list", "thread.summary", "thread.start", "thread.read", "thread.read.older", "turn.start")
            }?.let(::PendingRemoteCommand)
        val kind = pending?.kind
        val pendingThreadId = pending?.threadId
        val pendingSelectionGeneration = pending?.selectionGeneration
        if (kind == "thread.read" || kind == "thread.read.older") {
            val staleThread = pendingThreadId != null && pendingThreadId != _state.value.selectedThreadId
            val staleSelection = pendingSelectionGeneration != null && pendingSelectionGeneration != selectionGeneration
            if (staleThread || staleSelection) return
        }
        val requestedOlderCursor = pending?.olderCursor ?: _state.value.olderTimelineCursor
        if (event.payload?.jsonObject?.get("error") != null) {
            if (kind == "thread.summary") {
                pendingThreadId?.let(requestedThreadSummaries::remove)
                return
            }
            val completed = completedCommandState(kind)
            val failedActiveTurnStart = kind == "turn.start" &&
                pendingThreadId != null &&
                completed.activeThreadId == pendingThreadId
            _state.value = completed.copy(
                activeThreadId = completed.activeThreadId.takeUnless { failedActiveTurnStart },
                activeTurnId = completed.activeTurnId.takeUnless { failedActiveTurnStart },
                isWorking = completed.isWorking && !failedActiveTurnStart,
                errorMessage = remoteRpcErrorMessage(event.payload),
            )
            _notifications.tryEmit(RemoteNotification("Codex 任务失败", "Mac 返回了错误，请打开 Harness 查看详情"))
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
                                thread.copy(
                                    latestUserMessage = latestUserMessage?.take(240) ?: thread.latestUserMessage,
                                    execution = execution,
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
                val turnId = event.payload?.jsonObject?.get("result")?.jsonObject?.get("turn")?.jsonObject?.string("id")
                val threadId = pendingThreadId ?: _state.value.selectedThreadId
                _state.value = _state.value.copy(
                    activeThreadId = threadId,
                    activeTurnId = turnId,
                    isWorking = threadId != null && threadId == _state.value.selectedThreadId,
                )
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
                val completesActiveTurn = eventThreadId == null ||
                    current.activeThreadId == null ||
                    eventThreadId == current.activeThreadId
                _state.value = current.copy(
                    activeThreadId = current.activeThreadId.takeUnless { completesActiveTurn },
                    activeTurnId = current.activeTurnId.takeUnless { completesActiveTurn },
                    isWorking = current.isWorking && !completesActiveTurn,
                    threads = current.threads.withExecution(completedThreadId, execution),
                )
                val notification = when (execution.state) {
                    RemoteThreadExecutionState.FAILED -> RemoteNotification("Codex 任务失败", "打开 Harness 查看失败详情")
                    RemoteThreadExecutionState.INTERRUPTED -> RemoteNotification("Codex 已停止", "远程任务已中断")
                    else -> RemoteNotification("Codex 已完成", "远程任务已结束，点击查看结果")
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
        val threadId = params.string("threadId")
        val turnId = params.string("turnId")
        _state.value = current.copy(
            approvals = current.approvals + RemoteApproval(
                requestId = id,
                method = raw.string("method").orEmpty(),
                threadId = threadId,
                turnId = turnId,
                reason = params.string("reason") ?: "Codex 请求执行受保护操作",
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
        _notifications.tryEmit(RemoteNotification("Codex 等待审批", "Mac 上的任务需要你的确认"))
    }

    private fun matchesSelectedThread(eventThreadId: String?): Boolean {
        val selected = _state.value.selectedThreadId
        return selected == null || eventThreadId == null || eventThreadId == selected
    }

    private fun timelineItem(element: JsonElement): RemoteTimelineItem? {
        val item = element.jsonObject
        val id = item.string("id") ?: return null
        val kind = item.string("type").orEmpty()
        val status = item.string("status")
        val text = when (kind) {
            "agentMessage", "userMessage" -> item.string("text") ?: remoteMessageContentText(item["content"])
            "commandExecution" -> remoteCommandText(item["command"])
            "fileChange" -> remoteFileChangeText(item["changes"])
            "reasoning" -> item.string("summary")?.takeIf(String::isNotBlank)
                ?: item.string("text")?.takeIf(String::isNotBlank)
                ?: if (status in setOf("streaming", "inProgress", "running")) "Codex 正在分析" else return null
            "webSearch" -> item.string("query") ?: item.string("text") ?: "Codex 正在查找资料"
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

internal fun mergeRemoteTimeline(
    current: List<RemoteTimelineItem>,
    item: RemoteTimelineItem,
): List<RemoteTimelineItem> {
    val existingIndex = current.indexOfFirst { it.id == item.id }
    if (existingIndex >= 0) return current.toMutableList().also { it[existingIndex] = item }
    if (item.kind == "userMessage") {
        val pendingIndex = current.indexOfLast {
            it.kind == "userMessage" && it.status == "sending" && it.text == item.text
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
        it.kind == "userMessage" && it.status == "sending" && it.text in pageUserTexts
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
