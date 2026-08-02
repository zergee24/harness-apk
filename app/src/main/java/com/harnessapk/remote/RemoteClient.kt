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

class RemoteRepository(
    private val profileStore: RemoteProfileProvider,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
) {
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
    private val pendingCommands = mutableMapOf<String, String>()

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
        _state.value = _state.value.copy(connectionStatus = RemoteConnectionStatus.DISCONNECTED, isWorking = false)
    }

    fun refreshThreads() = send(RemoteCommand(type = "thread.list", requestId = requestId("thread.list")))
    fun selectThread(threadId: String) {
        _state.value = _state.value.copy(selectedThreadId = threadId, timeline = emptyList(), approvals = emptyList())
        send(RemoteCommand(type = "thread.read", requestId = requestId("thread.read"), threadId = threadId))
    }

    fun clearSelection() { _state.value = _state.value.copy(selectedThreadId = null, timeline = emptyList(), approvals = emptyList()) }
    fun createThread(cwd: String) = send(RemoteCommand(type = "thread.start", requestId = requestId("thread.start"), cwd = cwd.trim()))
    fun startTurn(text: String) {
        val threadId = _state.value.selectedThreadId ?: return
        if (send(RemoteCommand(type = "turn.start", requestId = requestId("turn.start"), threadId = threadId, text = text))) {
            _state.value = _state.value.copy(
                isWorking = true,
                timeline = _state.value.timeline + RemoteTimelineItem(UUID.randomUUID().toString(), "userMessage", text),
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

    private fun requestId(kind: String): String = "$kind:${UUID.randomUUID()}".also { pendingCommands[it] = kind }

    private fun send(command: RemoteCommand): Boolean {
        val profile = profileStore.profile.value
        if (profile == null) {
            pendingCommands.remove(command.requestId)
            return false
        }
        val activeSocket = socket
        if (activeSocket == null) {
            pendingCommands.remove(command.requestId)
            _state.value = _state.value.copy(errorMessage = "Mac 尚未连接，请稍后重试")
            return false
        }
        val now = System.currentTimeMillis()
        val wire = RemoteWireMessage(
            messageId = UUID.randomUUID().toString(), hostId = profile.hostId, deviceId = profile.deviceId,
            pairingTicket = profile.pairingTicket.takeIf(String::isNotBlank), sequence = outgoingSequence.incrementAndGet(),
            expiresAt = now + 5 * 60_000L, nonce = "", ciphertext = "",
        )
        val encrypted = RemoteCrypto.encrypt(profile.pairingSecret, wire, command.toJson())
        if (!activeSocket.send(encrypted.toJson().toString())) {
            pendingCommands.remove(command.requestId)
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
        _state.value = _state.value.copy(connectionStatus = if (explicitDisconnect) RemoteConnectionStatus.DISCONNECTED else RemoteConnectionStatus.ERROR, errorMessage = reason)
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
        val event = parseRemoteEvent(RemoteCrypto.decrypt(profile.pairingSecret, wire))
        handleEvent(event)
    }

    internal fun handleEvent(event: RemoteEvent) {
        when (event.type) {
            "host.status" -> _state.value = _state.value.copy(connectionStatus = RemoteConnectionStatus.CONNECTED)
            "error" -> {
                val message = event.message ?: "Codex 远程任务失败"
                _state.value = _state.value.copy(errorMessage = message, isWorking = false)
                _notifications.tryEmit(RemoteNotification("Codex 任务失败", message))
            }
            "rpc.response" -> handleRpcResponse(event)
            "approval.request" -> handleApproval(event)
            "codex.event" -> handleCodexEvent(event)
        }
    }

    private fun handleRpcResponse(event: RemoteEvent) {
        val kind = pendingCommands.remove(event.requestId)
        if (event.payload?.jsonObject?.get("error") != null) {
            _state.value = _state.value.copy(errorMessage = event.payload.toString())
            _notifications.tryEmit(RemoteNotification("Codex 任务失败", "Mac 返回了错误，请打开 Harness 查看详情"))
            return
        }
        when (kind) {
            "thread.list" -> _state.value = _state.value.copy(threads = parseThreads(event))
            "thread.start" -> {
                val id = event.payload?.jsonObject?.get("result")?.jsonObject?.get("thread")?.jsonObject?.string("id")
                if (id != null) selectThread(id)
                refreshThreads()
            }
            "thread.read" -> event.payload?.let(::applyThreadHistory)
            "turn.start" -> {
                val turnId = event.payload?.jsonObject?.get("result")?.jsonObject?.get("turn")?.jsonObject?.string("id")
                _state.value = _state.value.copy(activeTurnId = turnId, isWorking = true)
            }
        }
    }

    private fun applyThreadHistory(payload: JsonElement) {
        val thread = payload.jsonObject["result"]?.jsonObject?.get("thread")?.jsonObject ?: return
        val items = thread["turns"]?.jsonArray?.toList().orEmpty().flatMap { turn ->
            turn.jsonObject["items"]?.jsonArray?.toList().orEmpty().mapNotNull(::timelineItem)
        }
        _state.value = _state.value.copy(timeline = items)
    }

    private fun handleCodexEvent(event: RemoteEvent) {
        val raw = event.payload?.jsonObject ?: return
        val method = raw.string("method") ?: event.method.orEmpty()
        val params = raw["params"]?.jsonObject ?: JsonObject(emptyMap())
        if (!matchesSelectedThread(params.string("threadId"))) return
        when (method) {
            "turn/started" -> _state.value = _state.value.copy(activeTurnId = params["turn"]?.jsonObject?.string("id"), isWorking = true)
            "turn/completed" -> {
                _state.value = _state.value.copy(activeTurnId = null, isWorking = false)
                _notifications.tryEmit(RemoteNotification("Codex 已完成", "远程任务已结束，点击查看结果"))
                refreshThreads()
            }
            "item/started", "item/completed" -> params["item"]?.let { item ->
                timelineItem(item)?.let { addOrReplaceTimeline(it) }
            }
            "item/agentMessage/delta" -> appendAgentDelta(params.string("delta").orEmpty())
        }
    }

    private fun handleApproval(event: RemoteEvent) {
        val raw = event.payload?.jsonObject ?: return
        val params = raw["params"]?.jsonObject ?: return
        val id = raw["id"] ?: return
        if (!matchesSelectedThread(params.string("threadId"))) return
        val command = params["command"]?.let { value ->
            if (value is JsonPrimitive) value.contentOrNull else value.toString()
        }
        _state.value = _state.value.copy(approvals = _state.value.approvals + RemoteApproval(
            requestId = id, method = raw.string("method").orEmpty(), threadId = params.string("threadId"),
            turnId = params.string("turnId"), reason = params.string("reason") ?: "Codex 请求执行受保护操作", command = command,
        ))
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
        val text = when (kind) {
            "agentMessage", "userMessage" -> item.string("text") ?: item["content"]?.toString().orEmpty()
            "commandExecution" -> item["command"]?.let { if (it is JsonPrimitive) it.contentOrNull else it.toString() }.orEmpty()
            "fileChange" -> item["changes"]?.toString().orEmpty()
            else -> item.string("text") ?: item.toString()
        }
        return RemoteTimelineItem(id, kind, text, item.string("status"))
    }

    private fun addOrReplaceTimeline(item: RemoteTimelineItem) {
        val current = _state.value.timeline
        val next = if (current.any { it.id == item.id }) current.map { if (it.id == item.id) item else it } else current + item
        _state.value = _state.value.copy(timeline = next)
    }

    private fun appendAgentDelta(delta: String) {
        if (delta.isEmpty()) return
        val current = _state.value.timeline
        val last = current.lastOrNull()
        val next = if (last?.kind == "agentMessage" && last.status == "streaming") {
            current.dropLast(1) + last.copy(text = last.text + delta)
        } else current + RemoteTimelineItem("stream:${UUID.randomUUID()}", "agentMessage", delta, "streaming")
        _state.value = _state.value.copy(timeline = next)
    }
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
