package com.harnessapk.activity

import com.harnessapk.remote.DEFAULT_BACKEND_ID
import com.harnessapk.storage.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

enum class ActivityTarget {
    CHAT,
    REMOTE_RUN,
}

data class ActivityItem(
    val id: String,
    val target: ActivityTarget,
    val targetId: String,
    val title: String,
    val summary: String,
    val statusLabel: String,
    val risk: String?,
    val updatedAt: Long,
)

data class ActivityState(
    val needsAction: List<ActivityItem> = emptyList(),
    val inProgress: List<ActivityItem> = emptyList(),
    val recentCompleted: List<ActivityItem> = emptyList(),
) {
    val pendingCount: Int get() = needsAction.size
}

data class LocalActivityExecution(
    val id: String,
    val conversationId: String,
    val title: String,
    val status: String,
    val updatedAt: Long,
)

data class RemoteActivityRun(
    val id: String,
    val projectName: String,
    val objective: String,
    val status: String,
    val latestLine: String,
    val updatedAt: Long,
    val backendId: String = DEFAULT_BACKEND_ID,
)

data class RemoteActivityApproval(
    val id: String,
    val runId: String,
    val target: String,
    val risk: String,
    val status: String,
    val requestedAt: Long,
)

interface ActivityFeedSource {
    val localOpen: Flow<List<LocalActivityExecution>>
    fun localRecent(since: Long, limit: Int): Flow<List<LocalActivityExecution>>
    val remoteOpen: Flow<List<RemoteActivityRun>>
    fun remoteRecent(since: Long, limit: Int): Flow<List<RemoteActivityRun>>
    val pendingApprovals: Flow<List<RemoteActivityApproval>>
}

internal val terminalRemoteStatuses = setOf("COMPLETED", "FAILED", "CANCELLED")

class ActivityRepository(
    source: ActivityFeedSource,
    now: () -> Long = System::currentTimeMillis,
) {
    private val since = now() - RECENT_WINDOW_MILLIS
    val state: Flow<ActivityState> = combine(
        source.localOpen,
        source.localRecent(since, RECENT_LIMIT),
        source.remoteOpen,
        source.remoteRecent(since, RECENT_LIMIT),
        source.pendingApprovals,
    ) { localOpen, localRecent, remoteOpen, remoteRecent, pendingApprovals ->
        val runsById = (remoteOpen + remoteRecent).associateBy(RemoteActivityRun::id)
        val pendingRunIds = pendingApprovals.mapTo(mutableSetOf(), RemoteActivityApproval::runId)
        ActivityState(
            needsAction = pendingApprovals
                .distinctBy(RemoteActivityApproval::id)
                .map { approval -> approval.toActivityItem(runsById[approval.runId]) }
                .sortedByDescending(ActivityItem::updatedAt),
            inProgress = (
                localOpen.map(LocalActivityExecution::toActivityItem) +
                    remoteOpen.filterNot { it.id in pendingRunIds }.map(RemoteActivityRun::toActivityItem)
                ).sortedByDescending(ActivityItem::updatedAt),
            recentCompleted = (
                localRecent.map(LocalActivityExecution::toActivityItem) +
                    remoteRecent.map(RemoteActivityRun::toActivityItem)
                ).sortedByDescending(ActivityItem::updatedAt).take(RECENT_LIMIT),
        )
    }

    companion object {
        private const val RECENT_LIMIT = 50
        private const val RECENT_WINDOW_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}

class RoomActivityFeedSource(database: AppDatabase) : ActivityFeedSource {
    private val chatDao = database.chatExecutionEntryDao()
    private val conversationDao = database.conversationDao()
    private val remoteDao = database.remoteDao()

    override val localOpen: Flow<List<LocalActivityExecution>> = combine(
        chatDao.observeByStatuses(listOf("QUEUED", "RUNNING")),
        conversationDao.observeActive(),
    ) { rows, conversations ->
        val titles = conversations.associate { it.id to it.title }
        rows.map { row ->
            LocalActivityExecution(row.id, row.conversationId, titles[row.conversationId] ?: "对话任务", row.status, row.updatedAt)
        }
    }

    override fun localRecent(since: Long, limit: Int): Flow<List<LocalActivityExecution>> = combine(
        chatDao.observeRecentTerminal(since, limit),
        conversationDao.observeActive(),
    ) { rows, conversations ->
        val titles = conversations.associate { it.id to it.title }
        rows.map { row ->
            LocalActivityExecution(row.id, row.conversationId, titles[row.conversationId] ?: "对话任务", row.status, row.updatedAt)
        }
    }

    override val remoteOpen: Flow<List<RemoteActivityRun>> = remoteDao.observeOpenRuns().map { rows ->
        rows.map {
            RemoteActivityRun(
                it.id, it.projectNameSnapshot, it.objective, it.status,
                it.latestLine, it.updatedAt, it.backendId,
            )
        }
    }

    override fun remoteRecent(since: Long, limit: Int): Flow<List<RemoteActivityRun>> =
        remoteDao.observeRecentTerminalRuns(since, limit).map { rows ->
            rows.map {
                RemoteActivityRun(
                    it.id, it.projectNameSnapshot, it.objective, it.status,
                    it.latestLine, it.updatedAt, it.backendId,
                )
            }
        }

    override val pendingApprovals: Flow<List<RemoteActivityApproval>> = remoteDao.observePendingApprovals().map { rows ->
        rows.map { RemoteActivityApproval(it.id, it.runId, it.target, it.risk, it.status, it.requestedAt) }
    }
}

private fun LocalActivityExecution.toActivityItem() = ActivityItem(
    id = "local:$id",
    target = ActivityTarget.CHAT,
    targetId = conversationId,
    title = title,
    summary = "本地对话",
    statusLabel = when (status) {
        "QUEUED" -> "等待处理"
        "RUNNING" -> "正在回复"
        "SUCCEEDED" -> "已完成"
        "FAILED" -> "失败"
        "CANCELLED" -> "已取消"
        else -> "已结束"
    },
    risk = null,
    updatedAt = updatedAt,
)

private fun RemoteActivityRun.toActivityItem() = ActivityItem(
    id = "run:$id",
    target = ActivityTarget.REMOTE_RUN,
    targetId = id,
    title = if (backendId == DEFAULT_BACKEND_ID) projectName else "$projectName · ${backendDisplayLabel(backendId)}",
    summary = objective,
    statusLabel = when (status) {
        "QUEUED" -> "等待 Mac 接收"
        "STARTING" -> "Mac 正在启动"
        "RUNNING" -> "正在运行"
        "WAITING_USER" -> "等待用户输入"
        "RECONCILING" -> "正在核对"
        "COMPLETED" -> "已完成"
        "FAILED" -> "失败"
        "CANCELLED" -> "已停止"
        else -> latestLine
    },
    risk = null,
    updatedAt = updatedAt,
)

private fun backendDisplayLabel(backendId: String): String = when (backendId) {
    DEFAULT_BACKEND_ID -> "Codex"
    "dsh" -> "DeepSeek Harness"
    else -> backendId
}

private fun RemoteActivityApproval.toActivityItem(run: RemoteActivityRun?) = ActivityItem(
    id = "approval:$id",
    target = ActivityTarget.REMOTE_RUN,
    targetId = runId,
    title = "需要审批",
    summary = run?.projectName ?: target,
    statusLabel = "需要处理",
    risk = risk,
    updatedAt = requestedAt,
)
