package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(
    tableName = "project_retrieval_runs",
    indices = [Index("executionId"), Index("projectId"), Index("createdAt")],
)
data class ProjectRetrievalRunEntity(
    @PrimaryKey val id: String,
    val executionId: String,
    val projectId: String,
    val query: String,
    val selectedEvidenceIdsJson: String,
    val status: String,
    val citationVerificationStatus: String = "NOT_CHECKED",
    val unknownCitationTokensJson: String = "[]",
    val createdAt: Long,
)

@Entity(
    tableName = "project_evidence_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("executionId"), Index("messageId"), Index("projectId"), Index(value = ["executionId", "token"], unique = true)],
)
data class ProjectEvidenceSnapshotEntity(
    @PrimaryKey val id: String,
    val executionId: String,
    val messageId: String?,
    val token: String,
    val projectId: String,
    val sourceType: String,
    val authority: String,
    val sourceKey: String,
    val title: String,
    val locatorLabel: String,
    val relativePath: String?,
    val sourceMessageId: String?,
    val sourceSha256: String,
    val gitBlobId: String?,
    val excerpt: String,
    val capturedAt: Long,
)

@Entity(
    tableName = "markdown_draft_origins",
    foreignKeys = [
        ForeignKey(
            entity = MarkdownChangeDraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sourceType"),
        Index("sourceId"),
        Index("sourceProjectId"),
        Index(value = ["sourceType", "sourceId"], unique = true),
    ],
)
data class MarkdownDraftOriginEntity(
    @PrimaryKey val draftId: String,
    val sourceType: String,
    val sourceId: String,
    val sourceSha256: String,
    val sourceProjectId: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "context_fact_dedupe",
    primaryKeys = ["projectId", "semanticKey"],
    indices = [Index("sourceId"), Index("status")],
)
data class ContextFactDedupeEntity(
    val projectId: String,
    val semanticKey: String,
    val evidenceHash: String,
    val sourceId: String,
    val status: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "remote_run_completions",
    foreignKeys = [
        ForeignKey(
            entity = RemoteRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["completionId"], unique = true), Index("contentSha256")],
)
data class RemoteRunCompletionEntity(
    @PrimaryKey val runId: String,
    val schemaVersion: Int,
    val completionId: String,
    val contentSha256: String,
    val payloadJson: String,
    val verificationState: String,
    val capturedAt: Long,
)

@Dao
interface ProjectSearchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRetrievalRun(entity: ProjectRetrievalRunEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvidenceSnapshots(entities: List<ProjectEvidenceSnapshotEntity>)

    @Query("SELECT * FROM project_evidence_snapshots WHERE executionId = :executionId ORDER BY token")
    suspend fun evidenceForExecution(executionId: String): List<ProjectEvidenceSnapshotEntity>

    @Query("SELECT * FROM project_evidence_snapshots WHERE id = :evidenceId LIMIT 1")
    suspend fun evidenceById(evidenceId: String): ProjectEvidenceSnapshotEntity?

    @Query("SELECT * FROM project_evidence_snapshots WHERE messageId = :messageId ORDER BY token")
    suspend fun evidenceForMessage(messageId: String): List<ProjectEvidenceSnapshotEntity>

    @Query("UPDATE project_evidence_snapshots SET messageId = :messageId WHERE executionId = :executionId")
    suspend fun rebindEvidenceToMessage(executionId: String, messageId: String)

    @Query("SELECT * FROM project_retrieval_runs WHERE executionId = :executionId ORDER BY createdAt DESC LIMIT 1")
    suspend fun retrievalRunForExecution(executionId: String): ProjectRetrievalRunEntity?

    @Query(
        "UPDATE project_retrieval_runs SET citationVerificationStatus = :status, unknownCitationTokensJson = :unknownTokensJson WHERE executionId = :executionId",
    )
    suspend fun updateCitationVerification(executionId: String, status: String, unknownTokensJson: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDraftOrigin(entity: MarkdownDraftOriginEntity)

    @Query("SELECT * FROM markdown_draft_origins WHERE draftId = :draftId LIMIT 1")
    suspend fun draftOrigin(draftId: String): MarkdownDraftOriginEntity?

    @Query("SELECT * FROM markdown_draft_origins WHERE sourceType = :sourceType AND sourceId = :sourceId LIMIT 1")
    suspend fun draftOriginForSource(sourceType: String, sourceId: String): MarkdownDraftOriginEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContextFact(entity: ContextFactDedupeEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertContextFactIfAbsent(entity: ContextFactDedupeEntity): Long

    @Query(
        """
        SELECT semanticKey FROM context_fact_dedupe
        WHERE projectId = :projectId
          AND (status = 'APPLIED' OR sourceId = :currentSourceId)
        """,
    )
    suspend fun suppressedContextFactKeys(projectId: String, currentSourceId: String): List<String>

    @Query(
        "UPDATE context_fact_dedupe SET status = :status, updatedAt = :updatedAt WHERE sourceId = :sourceId AND status = 'PENDING'",
    )
    suspend fun updateContextFactStatusForSource(sourceId: String, status: String, updatedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRemoteCompletion(entity: RemoteRunCompletionEntity): Long

    @Query("SELECT * FROM remote_run_completions WHERE runId = :runId LIMIT 1")
    suspend fun remoteCompletion(runId: String): RemoteRunCompletionEntity?

    @Query(
        """
        SELECT documents.* FROM local_search_documents AS documents
        INNER JOIN local_search_fts AS searchIndex ON searchIndex.documentId = documents.id
        WHERE documents.projectId = :projectId
          AND documents.sourceType IN ('CONTEXT','MARKDOWN','PROJECT_MESSAGE','RUN_EVIDENCE')
          AND local_search_fts MATCH :match
        ORDER BY documents.sourceUpdatedAt DESC, documents.id ASC
        LIMIT :limit
        """,
    )
    suspend fun searchProjectFts(projectId: String, match: String, limit: Int): List<LocalSearchDocumentEntity>
}
