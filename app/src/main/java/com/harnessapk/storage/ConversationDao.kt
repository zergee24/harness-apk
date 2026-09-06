package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<ConversationEntity>>

    /**
     * Batch projection consumed by the simple life overview. The subqueries
     * are intentionally part of one Room query so rendering 500 rows does not
     * turn into N+1 message/execution reads.
     */
    @Query(
        """
        SELECT
            c.id AS id,
            c.title AS title,
            c.createdAt AS createdAt,
            c.updatedAt AS updatedAt,
            c.isArchived AS isArchived,
            c.projectId AS projectId,
            c.agentId AS agentId,
            c.agentVersion AS agentVersion,
            c.defaultProviderId AS defaultProviderId,
            c.defaultModel AS defaultModel,
            c.promptOriginal AS promptOriginal,
            c.promptOptimized AS promptOptimized,
            c.promptFinal AS promptFinal,
            (
                SELECT COUNT(*)
                FROM messages m
                WHERE m.conversationId = c.id
            ) AS messageCount,
            (
                SELECT MAX(m.createdAt)
                FROM messages m
                WHERE m.conversationId = c.id
            ) AS lastMessageAt,
            (
                SELECT NULLIF(TRIM(m.content), '')
                FROM messages m
                WHERE m.conversationId = c.id
                  AND m.role = 'USER'
                ORDER BY m.createdAt DESC, m.id DESC
                LIMIT 1
            ) AS lastUserText,
            (
                SELECT NULLIF(TRIM(m.content), '')
                FROM messages m
                WHERE m.conversationId = c.id
                  AND m.role = 'USER'
                ORDER BY m.createdAt ASC, m.id ASC
                LIMIT 1
            ) AS firstUserText,
            (
                SELECT NULLIF(TRIM(m.content), '')
                FROM messages m
                WHERE m.conversationId = c.id
                  AND m.role = 'ASSISTANT'
                  AND m.status = 'SUCCEEDED'
                ORDER BY m.createdAt DESC, m.id DESC
                LIMIT 1
            ) AS lastAssistantText,
            (
                SELECT e.status
                FROM chat_execution_entries e
                WHERE e.conversationId = c.id
                ORDER BY e.updatedAt DESC, e.sequence DESC, e.id DESC
                LIMIT 1
            ) AS latestExecutionStatus,
            (
                SELECT e.requestContextJson
                FROM chat_execution_entries e
                WHERE e.conversationId = c.id
                ORDER BY e.updatedAt DESC, e.sequence DESC, e.id DESC
                LIMIT 1
            ) AS latestRequestContextJson,
            (
                SELECT e.requestContextJson
                FROM chat_execution_entries e
                WHERE e.conversationId = c.id
                ORDER BY e.createdAt ASC, e.sequence ASC, e.id ASC
                LIMIT 1
            ) AS firstUserRequestContextJson,
            (
                SELECT COUNT(*)
                FROM chat_execution_entries e
                WHERE e.conversationId = c.id
                  AND e.status IN ('QUEUED', 'RUNNING')
            ) AS openExecutionCount
        FROM conversations c
        WHERE c.projectId IS NULL
          AND (:includeArchived = 1 OR c.isArchived = 0)
        ORDER BY c.updatedAt DESC, c.id DESC
        """,
    )
    fun observeLifeOverviewRows(includeArchived: Boolean): Flow<List<LifeConversationRoomRow>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ConversationEntity?

    @Query(
        """
        SELECT * FROM conversations
        WHERE isArchived = 0
        ORDER BY updatedAt DESC, id DESC LIMIT 1
        """,
    )
    suspend fun findLatestActive(): ConversationEntity?

    @Query(
        """
        SELECT * FROM conversations
        WHERE isArchived = 0 AND projectId = :projectId
        ORDER BY updatedAt DESC, id DESC LIMIT 1
        """,
    )
    suspend fun findLatestActiveInProject(projectId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ConversationEntity)

    @Update
    suspend fun update(entity: ConversationEntity)

    @Query(
        """
        UPDATE conversations
        SET agentId = :agentId, agentVersion = :agentVersion, updatedAt = :updatedAt
        WHERE id = :id
          AND NOT EXISTS (
              SELECT 1 FROM messages
              WHERE conversationId = :id AND role = 'USER'
          )
        """,
    )
    suspend fun updateIdentityIfNoUserMessages(
        id: String,
        agentId: String?,
        agentVersion: Int?,
        updatedAt: Long,
    ): Int

    @Query("UPDATE conversations SET projectId = NULL WHERE projectId = :projectId")
    suspend fun clearProject(projectId: String)

    @Query("UPDATE conversations SET isArchived = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun archive(id: String, updatedAt: Long)

    /**
     * Archive only an idle life conversation. The legacy archive() method is
     * kept unchanged for existing work callers; the V2 overview uses this
     * guarded mutation and deliberately does not touch updatedAt.
     */
    @Query(
        """
        UPDATE conversations
        SET isArchived = 1
        WHERE id = :id
          AND projectId IS NULL
          AND isArchived = 0
          AND NOT EXISTS (
              SELECT 1
              FROM chat_execution_entries e
              WHERE e.conversationId = :id
                AND e.status IN ('QUEUED', 'RUNNING')
          )
        """,
    )
    suspend fun archiveLifeConversationIfIdle(id: String): Int

    @Query(
        """
        UPDATE conversations
        SET isArchived = 0
        WHERE id = :id
          AND projectId IS NULL
          AND isArchived = 1
        """,
    )
    suspend fun restoreLifeConversation(id: String): Int

    @Query("SELECT COUNT(*) FROM conversations WHERE agentId = :agentId AND agentVersion = :version")
    suspend fun countByAgentVersion(agentId: String, version: Int): Int

    @Query(
        """
        UPDATE conversations
        SET agentId = NULL, agentVersion = NULL, updatedAt = :updatedAt
        WHERE agentId = :agentId AND agentVersion = :version
        """,
    )
    suspend fun clearAgentReference(agentId: String, version: Int, updatedAt: Long): Int
}
