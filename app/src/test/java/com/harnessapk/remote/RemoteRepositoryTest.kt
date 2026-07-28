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

    private fun repository(): RemoteRepository = RemoteRepository(
        profileStore = FakeRemoteProfileProvider(profile),
        httpClient = OkHttpClient(),
        scope = CoroutineScope(SupervisorJob()),
    )
}

private class FakeRemoteProfileProvider(initial: RemoteProfile?) : RemoteProfileProvider {
    override val profile = MutableStateFlow(initial)
}
