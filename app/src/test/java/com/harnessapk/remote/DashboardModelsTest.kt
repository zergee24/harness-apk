package com.harnessapk.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardModelsTest {
    @Test
    fun parseDashboardThreadMapsAllFields() {
        val payload = Json.parseToJsonElement(
            """{"threadId":"t-1","title":"服务团队","cwd":"/tmp/p","gitBranch":"main",
                "updatedAtMs":1788342138584,"status":"running","approx":true,
                "note":"长时间无输出","lastEventAtMs":1788342138500}""",
        )
        val thread = parseDashboardThread(payload)!!
        assertEquals("t-1", thread.threadId)
        assertEquals("服务团队", thread.title)
        assertEquals("/tmp/p", thread.cwd)
        assertEquals("main", thread.gitBranch)
        assertEquals(1788342138584L, thread.updatedAtMs)
        assertEquals("running", thread.status)
        assertEquals(true, thread.approx)
        assertEquals("长时间无输出", thread.note)
        assertEquals(1788342138500L, thread.lastEventAtMs)
    }

    @Test
    fun parseDashboardThreadToleratesMissingFields() {
        val thread = parseDashboardThread(Json.parseToJsonElement("""{"threadId":"t-2"}"""))!!
        assertEquals("t-2", thread.threadId)
        assertEquals("未命名线程", thread.title)
        assertNull(thread.cwd)
        assertNull(thread.gitBranch)
        assertEquals(0L, thread.updatedAtMs)
        assertEquals("", thread.status)
        assertEquals(false, thread.approx)
    }

    @Test
    fun parseDashboardThreadRequiresId() {
        assertNull(parseDashboardThread(Json.parseToJsonElement("""{"title":"no id"}""")))
        assertNull(parseDashboardThread(null))
    }

    @Test
    fun parseDashboardThreadsReadsFrame() {
        val payload = Json.parseToJsonElement(
            """{"threads":[{"threadId":"a","title":"A","updatedAtMs":1,"status":"done"},
                {"threadId":"b","title":"B","updatedAtMs":2,"status":"idle"}]}""",
        )
        val threads = parseDashboardThreads(payload)
        assertEquals(listOf("a", "b"), threads.map { it.threadId })
    }

    @Test
    fun parseDashboardThreadsEmptyOnBadPayload() {
        assertEquals(emptyList<DashboardThread>(), parseDashboardThreads(null))
        assertEquals(emptyList<DashboardThread>(), parseDashboardThreads(Json.parseToJsonElement("""{"other":1}""")))
    }
}
