package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentMemoryDao {
    @Query("SELECT * FROM agent_memories WHERE agentId = :agentId ORDER BY updatedAt DESC, id ASC")
    fun observeForAgent(agentId: String): Flow<List<AgentMemoryEntity>>

    @Query("SELECT * FROM agent_memories WHERE agentId = :agentId ORDER BY updatedAt DESC, id ASC")
    suspend fun listForAgent(agentId: String): List<AgentMemoryEntity>

    @Query("SELECT * FROM agent_memories WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AgentMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AgentMemoryEntity): Long

    @Query(
        """
        UPDATE agent_memories
        SET content = :content,
            sourceConversationId = :sourceConversationId,
            sourceMessageId = :sourceMessageId,
            confidence = :confidence,
            updatedAt = :updatedAt
        WHERE id = :id
          AND agentId = :agentId
          AND kind = :kind
          AND userEdited = 0
        """,
    )
    suspend fun updateAutomatically(
        id: String,
        agentId: String,
        kind: String,
        content: String,
        sourceConversationId: String,
        sourceMessageId: String,
        confidence: Double,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE agent_memories
        SET content = :content,
            confidence = 1.0,
            userEdited = 1,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun markUserEdited(id: String, content: String, updatedAt: Long): Int

    @Query("DELETE FROM agent_memories WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM agent_memories WHERE agentId = :agentId")
    suspend fun clear(agentId: String): Int
}
