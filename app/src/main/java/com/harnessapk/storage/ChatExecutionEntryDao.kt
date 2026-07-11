package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatExecutionEntryDao {
    @Query("SELECT * FROM chat_execution_entries WHERE conversationId = :conversationId ORDER BY sequence ASC")
    fun observeForConversation(conversationId: String): Flow<List<ChatExecutionEntryEntity>>

    @Query("SELECT * FROM chat_execution_entries WHERE conversationId = :conversationId ORDER BY sequence ASC")
    suspend fun listForConversation(conversationId: String): List<ChatExecutionEntryEntity>

    @Query("SELECT * FROM chat_execution_entries WHERE status = :status ORDER BY createdAt ASC")
    suspend fun listByStatus(status: String): List<ChatExecutionEntryEntity>

    @Query("SELECT * FROM chat_execution_entries WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ChatExecutionEntryEntity?

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM chat_execution_entries WHERE conversationId = :conversationId")
    suspend fun maxSequence(conversationId: String): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ChatExecutionEntryEntity)

    @Update
    suspend fun update(entity: ChatExecutionEntryEntity)

    @Query("DELETE FROM chat_execution_entries WHERE id = :id")
    suspend fun deleteById(id: String)
}
