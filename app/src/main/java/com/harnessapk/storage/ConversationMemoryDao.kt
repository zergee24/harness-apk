package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationMemoryDao {
    @Query("SELECT * FROM conversation_memory WHERE conversationId = :conversationId LIMIT 1")
    suspend fun findByConversationId(conversationId: String): ConversationMemoryEntity?

    @Query("SELECT * FROM conversation_memory WHERE conversationId = :conversationId LIMIT 1")
    fun observeForConversation(conversationId: String): Flow<ConversationMemoryEntity?>

    @Upsert
    suspend fun upsert(entity: ConversationMemoryEntity)
}
