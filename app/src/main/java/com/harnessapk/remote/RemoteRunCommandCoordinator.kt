package com.harnessapk.remote

import com.harnessapk.storage.RemoteDao
import com.harnessapk.storage.RemoteRunEntity
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface RemoteRunCommandState {
    suspend fun run(runId: String): RemoteRunEntity?
    suspend fun pendingCommandId(runId: String, type: String): String?
}

class RoomRemoteRunCommandState(
    private val dao: RemoteDao,
) : RemoteRunCommandState {
    override suspend fun run(runId: String): RemoteRunEntity? = dao.run(runId)

    override suspend fun pendingCommandId(runId: String, type: String): String? =
        dao.pendingCommandId(runId, type)
}

class RemoteRunCommandCoordinator(
    private val outbox: RemoteCommandOutbox,
    private val state: RemoteRunCommandState,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun steer(runId: String, text: String): RebuiltRemoteCommand = mutex.withLock {
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "补充方向不能为空" }
        val run = requireControllableRun(runId)
        state.pendingCommandId(runId, "run.steer")?.let { pendingId ->
            return@withLock requireNotNull(outbox.rebuild(pendingId)) { "pending steer command not found" }
        }
        val commandId = idFactory()
        outbox.enqueue(
            commandId = commandId,
            runId = runId,
            type = "run.steer",
            payload = RemoteM2Command.Steer(
                commandId = commandId,
                runId = runId,
                expectedTurnId = requireNotNull(run.turnId) { "run turnId is required" },
                text = normalized,
            ).toJson(),
            now = now(),
        )
    }

    suspend fun interrupt(runId: String): RebuiltRemoteCommand = mutex.withLock {
        val run = requireControllableRun(runId)
        state.pendingCommandId(runId, "run.interrupt")?.let { pendingId ->
            return@withLock requireNotNull(outbox.rebuild(pendingId)) { "pending interrupt command not found" }
        }
        val commandId = idFactory()
        outbox.enqueue(
            commandId = commandId,
            runId = runId,
            type = "run.interrupt",
            payload = RemoteM2Command.Interrupt(
                commandId = commandId,
                runId = runId,
                expectedTurnId = requireNotNull(run.turnId) { "run turnId is required" },
            ).toJson(),
            now = now(),
        )
    }

    private suspend fun requireControllableRun(runId: String): RemoteRunEntity {
        val run = requireNotNull(state.run(runId)) { "remote run not found" }
        require(run.status in CONTROLLABLE_STATUSES) { "remote run is not active" }
        require(!run.threadId.isNullOrBlank()) { "run threadId is required" }
        require(!run.turnId.isNullOrBlank()) { "run turnId is required" }
        return run
    }

    private companion object {
        val CONTROLLABLE_STATUSES = setOf(
            RemoteRunStatus.RUNNING.name,
            RemoteRunStatus.WAITING_APPROVAL.name,
            RemoteRunStatus.WAITING_USER.name,
            RemoteRunStatus.RECONCILING.name,
        )
    }
}
