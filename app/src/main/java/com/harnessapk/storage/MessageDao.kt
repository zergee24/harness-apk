package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun listForConversation(conversationId: String): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
          AND status = 'SUCCEEDED'
          AND role IN ('USER', 'ASSISTANT')
          AND TRIM(content) != ''
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun listRecentSuccessfulText(conversationId: String, limit: Int): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
          AND status = 'SUCCEEDED'
          AND role = 'ASSISTANT'
        ORDER BY createdAt DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun findLastSuccessfulAssistant(conversationId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND role = 'USER'")
    suspend fun countUserMessages(conversationId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: MessageEntity)

    @Update
    suspend fun update(entity: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)
}
