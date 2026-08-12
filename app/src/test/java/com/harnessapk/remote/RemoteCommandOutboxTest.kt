package com.harnessapk.remote

import com.harnessapk.storage.RemoteCommandOutboxEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteCommandOutboxTest {
    @Test
    fun pendingCommandCanBeRebuiltFromPayloadAfterRepositoryRecreation() = runBlocking {
        val store = InMemoryRemoteCommandStore()
        val firstProcess = RemoteCommandOutbox(store)
        firstProcess.enqueue(
            commandId = "run:run-1:interrupt",
            runId = "run-1",
            type = "run.interrupt",
            payload = buildJsonObject {
                put("turnId", "turn-1")
                put("runId", "run-1")
            },
            now = 100L,
        )

        val recreatedProcess = RemoteCommandOutbox(store)
        val rebuilt = recreatedProcess.rebuild("run:run-1:interrupt")
        val replayed = recreatedProcess.enqueue(
            commandId = "run:run-1:interrupt",
            runId = "run-1",
            type = "run.interrupt",
            payload = buildJsonObject {
                put("runId", "run-1")
                put("turnId", "turn-1")
            },
            now = 200L,
        )

        requireNotNull(rebuilt)
        assertEquals(RemoteCommandStatus.PENDING, rebuilt.status)
        assertEquals("run.interrupt", rebuilt.type)
        assertEquals("run-1", rebuilt.payload.string("runId"))
        assertEquals("turn-1", rebuilt.payload.string("turnId"))
        assertEquals(
            "{\"runId\":\"run-1\",\"turnId\":\"turn-1\"}",
            rebuilt.payloadJson,
        )
        assertEquals(rebuilt.commandId, replayed.commandId)
        assertEquals(rebuilt.payloadSha256, replayed.payloadSha256)
        assertEquals(rebuilt.payloadJson, replayed.payloadJson)
    }
}

private class InMemoryRemoteCommandStore : RemoteCommandStore {
    private val values = linkedMapOf<String, RemoteCommandOutboxEntity>()

    override suspend fun insert(command: RemoteCommandOutboxEntity) {
        check(values.putIfAbsent(command.commandId, command) == null)
    }

    override suspend fun find(commandId: String): RemoteCommandOutboxEntity? = values[commandId]

    override suspend fun upsert(command: RemoteCommandOutboxEntity) {
        values[command.commandId] = command
    }

    override suspend fun retryable(now: Long): List<RemoteCommandOutboxEntity> =
        values.values.filter { it.nextAttemptAt <= now }
}
