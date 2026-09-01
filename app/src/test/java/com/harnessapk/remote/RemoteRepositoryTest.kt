package com.harnessapk.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun commandTimeoutSurfacesErrorWhenNoResponse() {
        val repository = repository(timeoutMillis = 60L)
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pendingCommands = pendingField.get(repository) as MutableMap<String, String>
        pendingCommands["thread.start:test"] = "thread.start"
        repository.armCommandTimeout("thread.start:test", "thread.start")
        Thread.sleep(200)
        assertTrue(repository.state.value.errorMessage?.contains("超时") == true)
    }

    @Test
    fun answeredCommandDoesNotSurfaceTimeoutError() {
        val repository = repository(timeoutMillis = 60L, listCommandTimeoutMillis = 60L)
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pendingCommands = pendingField.get(repository) as MutableMap<String, String>
        pendingCommands["thread.list:answered"] = "thread.list"
        repository.armCommandTimeout("thread.list:answered", "thread.list")
        pendingCommands.remove("thread.list:answered")
        Thread.sleep(200)
        assertEquals(null, repository.state.value.errorMessage)
    }

    @Test
    fun turnStartTimeoutUsesTheExtendedTurnBudget() {
        val repository = repository(timeoutMillis = 15_000L, turnCommandTimeoutMillis = 60L)
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pendingCommands = pendingField.get(repository) as MutableMap<String, String>
        pendingCommands["turn.start:slow"] = "turn.start"
        repository.armCommandTimeout("turn.start:slow", "turn.start")
        Thread.sleep(200)
        assertTrue(repository.state.value.errorMessage?.contains("超时") == true)
    }

    @Test
    fun watchdogBudgetExemptsEventAnsweredCommandsAndExtendsTurnStart() {
        val repository = repository(timeoutMillis = 60L, turnCommandTimeoutMillis = 1_000L, listCommandTimeoutMillis = 2_000L)

        assertEquals(null, repository.watchdogBudgetFor("host.status"))
        assertEquals(null, repository.watchdogBudgetFor("approval.respond"))
        assertEquals(1_000L, repository.watchdogBudgetFor("turn.start"))
        assertEquals(2_000L, repository.watchdogBudgetFor("thread.list"))
        assertEquals(2_000L, repository.watchdogBudgetFor("thread.read"))
        assertEquals(60L, repository.watchdogBudgetFor("turn.steer"))
    }

    @Test
    fun commandTimeoutSurfacesErrorWithoutConsumingResponse() {
        val repository = repository(timeoutMillis = 60L)
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pendingCommands = pendingField.get(repository) as MutableMap<String, String>
        pendingCommands["thread.start:late"] = "thread.start"
        repository.armCommandTimeout("thread.start:late", "thread.start")
        Thread.sleep(200)
        assertTrue(repository.state.value.errorMessage?.contains("超时") == true)

        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                requestId = "thread.start:late",
                payload = kotlinx.serialization.json.Json.parseToJsonElement("""{"result":{"id":"t-late"}}"""),
            ),
        )

        assertEquals("t-late", repository.state.value.selectedThreadId)
    }

    @Test
    fun turnStartTimeoutClearsTheOptimisticWorkingState() {
        val repository = repository(timeoutMillis = 15_000L, turnCommandTimeoutMillis = 60L)
        repository.handleEvent(
            RemoteEvent(
                type = "codex.event",
                payload = kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"method":"turn/started","params":{"threadId":"thread-a","turn":{"id":"turn-a"}}}""",
                ),
            ),
        )
        assertTrue(repository.state.value.isWorking)
        val pendingField = RemoteRepository::class.java.getDeclaredField("pendingCommands").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pendingCommands = pendingField.get(repository) as MutableMap<String, String>
        pendingCommands["turn.start:stuck"] = "turn.start"

        repository.armCommandTimeout("turn.start:stuck", "turn.start")
        Thread.sleep(200)

        assertTrue(repository.state.value.errorMessage?.contains("超时") == true)
        assertFalse(repository.state.value.isWorking)
    }

    @Test
    fun rpcResponseWithoutRequestIdIsIgnoredInsteadOfCrashing() {
        val repository = repository()

        repository.handleEvent(
            RemoteEvent(
                type = "rpc.response",
                payload = kotlinx.serialization.json.Json.parseToJsonElement("""{"result":{}}"""),
            ),
        )

        assertEquals(null, repository.state.value.errorMessage)
    }

    @Test
    fun threadStartResponseParsesNestedShapeAndSelectsOptimisticThread() {
        val repo = repository()
        repo.createThread("/Users/tony/Documents/x")
        repo.handleRpcResponse(
            "thread.start",
            RemoteEvent(
                type = "rpc.response",
                payload = kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"result":{"thread":{"id":"t-1","cwd":"/Users/tony/Documents/x"}}}""",
                ),
            ),
        )
        assertEquals("t-1", repo.state.value.selectedThreadId)
        assertEquals("新线程", repo.state.value.threads.first().title)
        assertEquals("/Users/tony/Documents/x", repo.state.value.threads.first().cwd)
    }

    @Test
    fun threadStartResponseParsesFlatIdShape() {
        val repo = repository()
        repo.createThread("/tmp")
        repo.handleRpcResponse(
            "thread.start",
            RemoteEvent(
                type = "rpc.response",
                payload = kotlinx.serialization.json.Json.parseToJsonElement("""{"result":{"id":"t-2"}}"""),
            ),
        )
        assertEquals("t-2", repo.state.value.selectedThreadId)
    }

    @Test
    fun threadStartResponseParsesThreadIdShape() {
        val repo = repository()
        repo.createThread("/tmp")
        repo.handleRpcResponse(
            "thread.start",
            RemoteEvent(
                type = "rpc.response",
                payload = kotlinx.serialization.json.Json.parseToJsonElement("""{"result":{"threadId":"t-3"}}"""),
            ),
        )
        assertEquals("t-3", repo.state.value.selectedThreadId)
    }

    @Test
    fun threadListMergePreservesSelectedOptimisticThread() {
        val repo = repository()
        repo.createThread("/x")
        repo.handleRpcResponse(
            "thread.start",
            RemoteEvent(
                type = "rpc.response",
                payload = kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"result":{"thread":{"id":"t-keep","cwd":"/x"}}}""",
                ),
            ),
        )
        assertEquals("t-keep", repo.state.value.threads.first().id)

        repo.handleRpcResponse(
            "thread.list",
            RemoteEvent(
                type = "rpc.response",
                payload = kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"result":{"data":[{"id":"t-other","name":"B","updatedAt":1,"cwd":"/b"}]}}""",
                ),
            ),
        )

        assertEquals("t-keep", repo.state.value.threads.first().id)
        assertTrue(repo.state.value.threads.any { it.id == "t-other" })
    }

    private fun repository(
        timeoutMillis: Long = 30_000L,
        turnCommandTimeoutMillis: Long = 130_000L,
        listCommandTimeoutMillis: Long = 35_000L,
    ): RemoteRepository = RemoteRepository(
        profileStore = FakeRemoteProfileProvider(profile),
        httpClient = OkHttpClient(),
        scope = CoroutineScope(SupervisorJob()),
        commandTimeoutMillis = timeoutMillis,
        turnCommandTimeoutMillis = turnCommandTimeoutMillis,
        listCommandTimeoutMillis = listCommandTimeoutMillis,
    )
}

private class FakeRemoteProfileProvider(initial: RemoteProfile?) : RemoteProfileProvider {
    override val profile = MutableStateFlow(initial)
}
