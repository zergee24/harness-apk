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

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ConversationEntity?

    @Query(
        """
        SELECT * FROM conversations
        WHERE isArchived = 0 AND agentId IS NOT NULL
        ORDER BY updatedAt DESC LIMIT 1
        """,
    )
    suspend fun findLatestWithAgent(): ConversationEntity?

    @Query(
        """
        SELECT * FROM conversations
        WHERE isArchived = 0 AND projectId = :projectId AND agentId IS NOT NULL
        ORDER BY updatedAt DESC LIMIT 1
        """,
    )
    suspend fun findLatestWithAgentInProject(projectId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ConversationEntity)

    @Update
    suspend fun update(entity: ConversationEntity)

    @Query("UPDATE conversations SET isArchived = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun archive(id: String, updatedAt: Long)
}
