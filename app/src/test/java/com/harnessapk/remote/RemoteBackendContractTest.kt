package com.harnessapk.remote

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBackendContractTest {

    @Test
    fun commandJsonIncludesBackendIdWhenSet() {
        val raw = RemoteCommand(type = "turn.start", requestId = "r1", backendId = "dsh", threadId = "t1", text = "hi")
            .toJson().toString()
        assertTrue(raw.contains("\"backendId\":\"dsh\""))
    }

    @Test
    fun commandJsonOmitsBackendIdWhenNull() {
        val raw = RemoteCommand(type = "thread.list", requestId = "r2").toJson().toString()
        assertFalse(raw.contains("backendId"))
    }

    @Test
    fun parseRemoteEventReadsBackendId() {
        val event = parseRemoteEvent(
            """{"type":"codex.event","backendId":"dsh","method":"turn/completed","createdAt":123}""",
        )
        assertEquals("dsh", event.backendId)
        assertEquals("turn/completed", event.method)
    }

    @Test
    fun legacyEventWithoutBackendIdParsesNull() {
        val event = parseRemoteEvent(
            """{"type":"codex.event","method":"turn/completed","createdAt":123}""",
        )
        assertNull(event.backendId)
    }

    @Test
    fun parseRemoteBackendsParsesPerBackendCapabilities() {
        val payload = buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put(
                "backends",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive("codex"))
                            put("name", JsonPrimitive("Codex"))
                            put(
                                "capabilities",
                                buildJsonArray {
                                    add(JsonPrimitive("run.lifecycle.v1"))
                                    add(JsonPrimitive("approvals.v1"))
                                },
                            )
                        },
                    )
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive("dsh"))
                            put("name", JsonPrimitive("DeepSeek Harness"))
                            put(
                                "capabilities",
                                buildJsonArray { add(JsonPrimitive("run.lifecycle.v1")) },
                            )
                        },
                    )
                },
            )
        }
        val event = RemoteEvent(type = "host.status", payload = payload)
        val backends = requireNotNull(parseRemoteBackends(event))
        assertEquals(2, backends.size)
        assertEquals("codex", backends[0].id)
        assertTrue("approvals.v1" in backends[0].capabilities)
        assertEquals("DeepSeek Harness", backends[1].name)
        assertFalse("approvals.v1" in backends[1].capabilities)
    }

    @Test
    fun legacyHostStatusWithoutBackendsReturnsNull() {
        val payload = buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put("capabilities", buildJsonArray { add(JsonPrimitive("run.lifecycle.v1")) })
        }
        val event = RemoteEvent(type = "host.status", payload = payload)
        assertNull(parseRemoteBackends(event))
    }

    @Test
    fun explicitEmptyBackendListStaysEmpty() {
        val payload = buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put("backends", buildJsonArray {})
        }
        val event = RemoteEvent(type = "host.status", payload = payload)
        assertEquals(emptyList<RemoteBackend>(), parseRemoteBackends(event))
    }

    @Test
    fun blankThreadNameFallsBackToPreviewTitle() {
        val payload = buildJsonObject {
            put(
                "result",
                buildJsonObject {
                    put(
                        "data",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive("thread-1"))
                                    put("name", JsonPrimitive("   "))
                                    put("preview", JsonPrimitive("真实首行\n第二行"))
                                    put("updatedAt", JsonPrimitive(1))
                                },
                            )
                        },
                    )
                },
            )
        }

        assertEquals("真实首行", parseThreads(RemoteEvent(type = "rpc.response", payload = payload)).single().title)
    }

    @Test
    fun legacyHostCapabilitiesParsingStillWorks() {
        val payload = buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put(
                "capabilities",
                buildJsonArray {
                    add(JsonPrimitive("run.lifecycle.v1"))
                    add(JsonPrimitive("logical-replay.v1"))
                },
            )
        }
        val event = RemoteEvent(type = "host.status", payload = payload)
        assertEquals(
            setOf("run.lifecycle.v1", "logical-replay.v1"),
            parseRemoteHostCapabilities(event),
        )
    }
}

class RemoteBackendSelectionTest {

    @Test
    fun preparedCommandUsesOneCapturedBackendForPendingOwnershipAndPayload() {
        val prepared = prepareRemoteCommand(
            command = RemoteCommand(type = "turn.start", requestId = "r0", threadId = "t1", text = "hi"),
            pending = PendingRemoteCommand(kind = "turn.start", threadId = "t1"),
            selectedBackendId = "dsh",
        )

        assertEquals("dsh", prepared.pending?.backendId)
        assertTrue(prepared.payload.toString().contains("\"backendId\":\"dsh\""))
    }

    @Test
    fun preparedOutboxReplayKeepsItsEmbeddedBackend() {
        val payload = buildJsonObject {
            put("type", JsonPrimitive("run.start"))
            put("requestId", JsonPrimitive("r0-replay"))
            put("backendId", JsonPrimitive("codex"))
        }

        val prepared = prepareRemotePayload(
            requestId = "r0-replay",
            payload = payload,
            pending = null,
            selectedBackendId = "dsh",
        )

        assertTrue(prepared.payload.toString().contains("\"backendId\":\"codex\""))
        assertNull(prepared.pending)
    }

    @Test
    fun injectBackendIdAddsSelectedBackendWhenAbsent() {
        val payload = buildJsonObject {
            put("type", JsonPrimitive("thread.list"))
            put("requestId", JsonPrimitive("r1"))
        }
        val injected = injectBackendId(payload, "dsh").toString()
        assertTrue(injected.contains("\"backendId\":\"dsh\""))
    }

    @Test
    fun injectBackendIdPreservesExistingBackend() {
        val payload = buildJsonObject {
            put("type", JsonPrimitive("run.start"))
            put("requestId", JsonPrimitive("r2"))
            put("backendId", JsonPrimitive("codex"))
        }
        val injected = injectBackendId(payload, "dsh").toString()
        assertTrue(injected.contains("\"backendId\":\"codex\""))
        assertFalse(injected.contains("\"backendId\":\"dsh\""))
    }

    @Test
    fun fallbackBackendsForLegacyHostStatus() {
        val fallback = fallbackRemoteBackends(setOf("run.lifecycle.v1", "approvals.v1"))
        assertEquals(1, fallback.size)
        assertEquals("codex", fallback[0].id)
        assertEquals("Codex", fallback[0].name)
        assertTrue("approvals.v1" in fallback[0].capabilities)
    }

    @Test
    fun reconcileSelectedBackendFallsBackToCodexWhenMissing() {
        val backends = listOf(RemoteBackend("codex", "Codex", emptySet()), RemoteBackend("dsh", "DeepSeek Harness", emptySet()))
        assertEquals("dsh", reconcileSelectedBackend("dsh", backends))
        assertEquals("codex", reconcileSelectedBackend("aux", backends))
        assertEquals("codex", reconcileSelectedBackend("codex", emptyList()))
    }

    @Test
    fun reconcileSelectedBackendFallsBackToOnlyAdvertisedBackend() {
        val dshOnly = listOf(RemoteBackend("dsh", "DeepSeek Harness", emptySet()))
        assertEquals("dsh", reconcileSelectedBackend("codex", dshOnly))
        assertEquals("codex", reconcileSelectedBackend("codex", emptyList()))
    }

    @Test
    fun parseRemoteLogicalEventReadsBackendId() {
        val event = parseRemoteLogicalEvent(
            """{"schemaVersion":1,"eventId":"e1","hostId":"h","deviceId":"d","runId":"r","backendId":"dsh","sequence":3,"type":"run.timeline","payload":{"latestLine":"正在整理结果"},"createdAt":123}""",
        )
        assertEquals("dsh", event.backendId)
        assertEquals("run.timeline", event.type)
    }

    @Test
    fun legacyLogicalEventWithoutBackendIdParsesNull() {
        val event = parseRemoteLogicalEvent(
            """{"schemaVersion":1,"eventId":"e2","hostId":"h","deviceId":"d","runId":"r","sequence":4,"type":"run.completed","payload":{},"createdAt":124}""",
        )
        assertNull(event.backendId)
    }
}
