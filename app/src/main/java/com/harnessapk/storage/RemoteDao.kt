package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBinding(binding: ProjectRemoteBindingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBinding(binding: ProjectRemoteBindingEntity)

    @Query("SELECT * FROM project_remote_bindings WHERE projectId = :projectId LIMIT 1")
    suspend fun bindingForProject(projectId: String): ProjectRemoteBindingEntity?

    @Query("DELETE FROM project_remote_bindings WHERE id = :bindingId")
    suspend fun deleteBindingById(bindingId: String)

    @Query("DELETE FROM project_remote_bindings WHERE projectId = :projectId")
    suspend fun deleteBindingByProject(projectId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: RemoteRunEntity)

    @Upsert
    suspend fun upsertRun(run: RemoteRunEntity)

    @Query("SELECT * FROM remote_runs WHERE id = :runId LIMIT 1")
    suspend fun run(runId: String): RemoteRunEntity?

    @Query("SELECT * FROM remote_runs WHERE id = :runId LIMIT 1")
    fun observeRun(runId: String): Flow<RemoteRunEntity?>

    @Query("SELECT * FROM remote_runs WHERE projectId = :projectId AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED') ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestOpenRunForProject(projectId: String): RemoteRunEntity?

    @Query("SELECT * FROM remote_runs WHERE hostId = :hostId AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun openRunsForHost(hostId: String): List<RemoteRunEntity>

    @Query("SELECT * FROM remote_runs WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED') ORDER BY updatedAt DESC")
    fun observeOpenRuns(): Flow<List<RemoteRunEntity>>

    @Query(
        """
        SELECT * FROM remote_runs
        WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND updatedAt >= :since
        ORDER BY updatedAt DESC LIMIT :limit
        """,
    )
    fun observeRecentTerminalRuns(since: Long, limit: Int): Flow<List<RemoteRunEntity>>

    @Query("DELETE FROM remote_runs WHERE id = :runId")
    suspend fun deleteRunById(runId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: RemoteRunEventEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM remote_run_events WHERE logicalEventId = :logicalEventId)")
    suspend fun eventExists(logicalEventId: String): Boolean

    @Query("SELECT * FROM remote_run_events WHERE runId = :runId ORDER BY sequence, createdAt")
    suspend fun eventsForRun(runId: String): List<RemoteRunEventEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertApproval(approval: RemoteApprovalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApproval(approval: RemoteApprovalEntity)

    @Query("SELECT * FROM remote_approvals WHERE id = :approvalId LIMIT 1")
    suspend fun approval(approvalId: String): RemoteApprovalEntity?

    @Query("SELECT * FROM remote_approvals WHERE runId = :runId ORDER BY requestedAt")
    suspend fun approvalsForRun(runId: String): List<RemoteApprovalEntity>

    @Query("SELECT * FROM remote_approvals WHERE runId = :runId ORDER BY requestedAt")
    fun observeApprovalsForRun(runId: String): Flow<List<RemoteApprovalEntity>>

    @Query("SELECT * FROM remote_approvals WHERE status = 'PENDING' ORDER BY requestedAt DESC")
    fun observePendingApprovals(): Flow<List<RemoteApprovalEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCommand(command: RemoteCommandOutboxEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCommand(command: RemoteCommandOutboxEntity)

    @Query("SELECT * FROM remote_command_outbox WHERE commandId = :commandId LIMIT 1")
    suspend fun command(commandId: String): RemoteCommandOutboxEntity?

    @Query("SELECT * FROM remote_command_outbox WHERE status IN ('PENDING', 'SENT') AND nextAttemptAt <= :now ORDER BY nextAttemptAt, createdAt")
    suspend fun retryableCommands(now: Long): List<RemoteCommandOutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: RemoteSyncCursorEntity)

    @Query("SELECT * FROM remote_sync_cursors WHERE hostId = :hostId AND deviceId = :deviceId LIMIT 1")
    suspend fun cursor(hostId: String, deviceId: String): RemoteSyncCursorEntity?

    @Query("SELECT * FROM remote_sync_cursors WHERE hostId = :hostId AND deviceId = :deviceId LIMIT 1")
    fun observeCursor(hostId: String, deviceId: String): Flow<RemoteSyncCursorEntity?>

    @Query(
        """
        UPDATE remote_runs
        SET status = 'RECONCILING', updatedAt = :updatedAt
        WHERE hostId = :hostId AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
        """,
    )
    suspend fun markOpenRunsReconciling(hostId: String, updatedAt: Long)
}
