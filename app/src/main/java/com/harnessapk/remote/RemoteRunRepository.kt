package com.harnessapk.remote

import com.harnessapk.storage.RemoteDao
import com.harnessapk.storage.RemoteRunEntity

enum class RemoteRunStatus {
    QUEUED,
    STARTING,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_USER,
    RECONCILING,
    COMPLETED,
    FAILED,
    CANCELLED,
    UNKNOWN,
}

class RemoteRunRepository(
    private val dao: RemoteDao,
) {
    suspend fun find(runId: String): RemoteRunEntity? = dao.run(runId)

    suspend fun insert(run: RemoteRunEntity) = dao.insertRun(run)

    suspend fun upsert(run: RemoteRunEntity) = dao.upsertRun(run)

    companion object {
        private val terminalStatuses = setOf(
            RemoteRunStatus.COMPLETED,
            RemoteRunStatus.FAILED,
            RemoteRunStatus.CANCELLED,
        )

        private val transitions = mapOf(
            RemoteRunStatus.QUEUED to setOf(RemoteRunStatus.STARTING, RemoteRunStatus.CANCELLED),
            RemoteRunStatus.STARTING to setOf(
                RemoteRunStatus.RUNNING,
                RemoteRunStatus.RECONCILING,
                RemoteRunStatus.FAILED,
                RemoteRunStatus.CANCELLED,
            ),
            RemoteRunStatus.RUNNING to setOf(
                RemoteRunStatus.WAITING_APPROVAL,
                RemoteRunStatus.WAITING_USER,
                RemoteRunStatus.RECONCILING,
                RemoteRunStatus.COMPLETED,
                RemoteRunStatus.FAILED,
                RemoteRunStatus.CANCELLED,
            ),
            RemoteRunStatus.WAITING_APPROVAL to setOf(
                RemoteRunStatus.RUNNING,
                RemoteRunStatus.RECONCILING,
                RemoteRunStatus.FAILED,
                RemoteRunStatus.CANCELLED,
            ),
            RemoteRunStatus.WAITING_USER to setOf(
                RemoteRunStatus.RUNNING,
                RemoteRunStatus.RECONCILING,
                RemoteRunStatus.FAILED,
                RemoteRunStatus.CANCELLED,
            ),
            RemoteRunStatus.RECONCILING to setOf(
                RemoteRunStatus.RUNNING,
                RemoteRunStatus.WAITING_APPROVAL,
                RemoteRunStatus.WAITING_USER,
                RemoteRunStatus.COMPLETED,
                RemoteRunStatus.FAILED,
                RemoteRunStatus.CANCELLED,
                RemoteRunStatus.UNKNOWN,
            ),
            RemoteRunStatus.UNKNOWN to setOf(
                RemoteRunStatus.RECONCILING,
                RemoteRunStatus.FAILED,
                RemoteRunStatus.CANCELLED,
            ),
        )

        fun reduceStatus(current: RemoteRunStatus, incoming: RemoteRunStatus): RemoteRunStatus {
            if (current == incoming || current in terminalStatuses) return current
            return if (incoming in transitions[current].orEmpty()) incoming else current
        }
    }
}

internal fun remoteRunStatus(value: String): RemoteRunStatus =
    RemoteRunStatus.entries.firstOrNull { it.name == value } ?: RemoteRunStatus.UNKNOWN
