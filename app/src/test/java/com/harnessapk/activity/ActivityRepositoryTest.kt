package com.harnessapk.activity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityRepositoryTest {
    @Test
    fun pendingApprovalAppearsOnceInNeedsAction() = runBlocking {
        val source = FakeActivityFeedSource(
            remoteRunRows = listOf(remoteRun("run-1", "WAITING_APPROVAL")),
            approvalRows = listOf(remoteApproval("approval-1", "run-1")),
        )

        val state = ActivityRepository(source, now = { NOW }).state.first()

        assertEquals(listOf("approval:approval-1"), state.needsAction.map { it.id })
        assertTrue(state.inProgress.none { it.targetId == "run-1" })
        assertEquals(1, state.pendingCount)
    }

    @Test
    fun localChatAndRemoteRunMergeWithoutCopyingExecutionRows() = runBlocking {
        val source = FakeActivityFeedSource(
            localOpenRows = listOf(LocalActivityExecution("local-1", "conversation-1", "整理周报", "RUNNING", 90L)),
            remoteRunRows = listOf(remoteRun("run-1", "RUNNING")),
        )

        val state = ActivityRepository(source, now = { NOW }).state.first()

        assertEquals(setOf("local:local-1", "run:run-1"), state.inProgress.mapTo(mutableSetOf()) { it.id })
        assertEquals(ActivityTarget.CHAT, state.inProgress.first { it.id == "local:local-1" }.target)
        assertEquals(ActivityTarget.REMOTE_RUN, state.inProgress.first { it.id == "run:run-1" }.target)
    }

    private fun remoteRun(id: String, status: String) = RemoteActivityRun(
        id = id,
        projectName = "Harness APK",
        objective = "实现 M2",
        status = status,
        latestLine = "处理中",
        updatedAt = 100L,
    )

    private fun remoteApproval(id: String, runId: String) = RemoteActivityApproval(
        id = id,
        runId = runId,
        target = "./gradlew test",
        risk = "LOW",
        status = "PENDING",
        requestedAt = 100L,
    )

    companion object {
        private const val NOW = 8 * 24 * 60 * 60 * 1_000L
    }
}

private class FakeActivityFeedSource(
    localOpenRows: List<LocalActivityExecution> = emptyList(),
    private val localRecentRows: List<LocalActivityExecution> = emptyList(),
    remoteRunRows: List<RemoteActivityRun> = emptyList(),
    approvalRows: List<RemoteActivityApproval> = emptyList(),
) : ActivityFeedSource {
    private val allRemoteRuns = remoteRunRows
    override val localOpen = MutableStateFlow(localOpenRows)
    override fun localRecent(since: Long, limit: Int) = MutableStateFlow(localRecentRows)
    override val remoteOpen = MutableStateFlow(allRemoteRuns.filterNot { it.status in terminalRemoteStatuses })
    override fun remoteRecent(since: Long, limit: Int) = MutableStateFlow(allRemoteRuns.filter { it.status in terminalRemoteStatuses })
    override val pendingApprovals = MutableStateFlow(approvalRows.filter { it.status == "PENDING" })
}
