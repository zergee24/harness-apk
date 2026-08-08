package com.harnessapk.remote

import com.harnessapk.storage.RemoteCommandOutboxEntity
import com.harnessapk.storage.RemoteRunEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteRunCommandTest {
    @Test
    fun duplicateSteerTapReusesOneCommandIdUntilTerminalResult() = runBlocking {
        val store = CommandStoreForRunControl()
        val state = FakeRunCommandState(store, run(status = "RUNNING"))
        var nextId = 0
        val coordinator = RemoteRunCommandCoordinator(
            RemoteCommandOutbox(store),
            state,
            idFactory = { "command-${++nextId}" },
            now = { 100L },
        )

        val first = coordinator.steer("run-1", "补充测试")
        val second = coordinator.steer("run-1", "补充测试")

        assertEquals(first.commandId, second.commandId)
        assertEquals(1, store.rows.size)
        assertEquals("RUNNING", state.run.status)
    }

    @Test
    fun concurrentSteerTapsStillPersistOneCommand() = runBlocking {
        val store = CommandStoreForRunControl()
        val state = FakeRunCommandState(store, run(status = "RUNNING"))
        var nextId = 0
        val coordinator = RemoteRunCommandCoordinator(
            RemoteCommandOutbox(store),
            state,
            idFactory = { "command-${++nextId}" },
            now = { 100L },
        )

        val commands = (1..8).map { async { coordinator.steer("run-1", "补充测试") } }.awaitAll()

        assertEquals(1, commands.map { it.commandId }.distinct().size)
        assertEquals(1, store.rows.size)
    }

    @Test
    fun stopRemainsPendingUntilInterruptResultOrSnapshotArrives() = runBlocking {
        val store = CommandStoreForRunControl()
        val state = FakeRunCommandState(store, run(status = "RUNNING"))
        val coordinator = RemoteRunCommandCoordinator(
            RemoteCommandOutbox(store),
            state,
            idFactory = { "interrupt-1" },
            now = { 100L },
        )

        val command = coordinator.interrupt("run-1")

        assertEquals("PENDING", command.status.name)
        assertEquals("RUNNING", state.run.status)
        assertEquals("run.interrupt", store.rows.single().type)
    }

    private fun run(status: String) = RemoteRunEntity(
        id = "run-1", projectId = "project-1", projectNameSnapshot = "Harness APK",
        bindingId = "binding-1", bindingSnapshotJson = "{}", hostId = "host-1",
        threadId = "thread-1", turnId = "turn-1", objective = "实现 M2", status = status,
        latestLine = "正在运行", lastLogicalSequence = 1L, startedAt = 1L, updatedAt = 1L,
        completedAt = null, completionJson = null, errorMessage = null,
    )
}

private class FakeRunCommandState(
    private val store: CommandStoreForRunControl,
    var run: RemoteRunEntity,
) : RemoteRunCommandState {
    override suspend fun run(runId: String): RemoteRunEntity? = run.takeIf { it.id == runId }
    override suspend fun pendingCommandId(runId: String, type: String): String? = store.rows
        .firstOrNull { it.runId == runId && it.type == type && it.status in setOf("PENDING", "SENT", "ACCEPTED") }
        ?.commandId
}

private class CommandStoreForRunControl : RemoteCommandStore {
    val rows = mutableListOf<RemoteCommandOutboxEntity>()
    override suspend fun insert(command: RemoteCommandOutboxEntity) { rows += command }
    override suspend fun find(commandId: String): RemoteCommandOutboxEntity? = rows.firstOrNull { it.commandId == commandId }
    override suspend fun upsert(command: RemoteCommandOutboxEntity) {
        rows.removeAll { it.commandId == command.commandId }
        rows += command
    }
    override suspend fun retryable(now: Long): List<RemoteCommandOutboxEntity> = rows
}
