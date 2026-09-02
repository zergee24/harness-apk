package com.harnessapk.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRepositoryTest {
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
    fun dashboardThreadEventMergesAndSortsByRecency() {
        val repo = repository()
        repo.handleEvent(
            event(
                "dashboard.thread",
                """{"threadId":"t-2","title":"B","updatedAtMs":200,"status":"running"}""",
            ),
        )
        repo.handleEvent(
            event(
                "dashboard.thread",
                """{"threadId":"t-1","title":"A","updatedAtMs":100,"status":"done"}""",
            ),
        )
        assertEquals(listOf("t-2", "t-1"), repo.dashboard.value.threads.map { it.threadId })

        // 同线程增量到达：去重并更新，不产生重复卡片
        repo.handleEvent(
            event(
                "dashboard.thread",
                """{"threadId":"t-2","title":"B","updatedAtMs":300,"status":"thinking"}""",
            ),
        )
        val threads = repo.dashboard.value.threads
        assertEquals(listOf("t-2", "t-1"), threads.map { it.threadId })
        assertEquals("thinking", threads.first { it.threadId == "t-2" }.status)
    }

    @Test
    fun dashboardThreadsEventReplacesWholeFrame() {
        val repo = repository()
        repo.handleEvent(event("dashboard.thread", """{"threadId":"t-9","title":"旧","updatedAtMs":1,"status":"idle"}"""))
        repo.handleEvent(
            event(
                "dashboard.threads",
                """{"threads":[{"threadId":"t-1","title":"A","updatedAtMs":10,"status":"done"},
                    {"threadId":"t-2","title":"B","updatedAtMs":20,"status":"idle"}]}""",
            ),
        )
        assertEquals(listOf("t-2", "t-1"), repo.dashboard.value.threads.map { it.threadId })
    }

    @Test
    fun focusResultEventSurfacesToCollectors() = runBlocking {
        val repo = repository()
        val collected = mutableListOf<DashboardFocusResult>()
        val job = launch {
            repo.focusResults.collect { collected.add(it) }
        }
        yield(); yield()
        repo.handleEvent(
            event(
                "dashboard.focus",
                """{"threadId":"t-1","ok":false,"message":"非法线程 ID"}""",
            ),
        )
        yield(); yield()
        job.cancel()
        assertEquals(1, collected.size)
        assertEquals("t-1", collected[0].threadId)
        assertEquals(false, collected[0].ok)
        assertEquals("非法线程 ID", collected[0].message)
    }

    @Test
    fun dashboardStartsEmptyWithoutConnection() {
        val repo = repository()
        assertTrue(repo.dashboard.value.threads.isEmpty())
    }

    private fun event(type: String, payload: String) = RemoteEvent(
        type = type,
        payload = Json.parseToJsonElement(payload),
    )

    private fun repository(): RemoteRepository = RemoteRepository(
        profileStore = FakeProfileProvider(profile),
        httpClient = OkHttpClient(),
        scope = CoroutineScope(SupervisorJob()),
    )
}

private class FakeProfileProvider(initial: RemoteProfile?) : RemoteProfileProvider {
    override val profile = MutableStateFlow(initial)
}
