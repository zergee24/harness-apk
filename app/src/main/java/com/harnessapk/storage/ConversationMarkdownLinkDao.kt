package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationMarkdownLinkDao {
    @Query("SELECT * FROM conversation_markdown_links WHERE conversationId = :conversationId ORDER BY updatedAt DESC")
    fun observeForConversation(conversationId: String): Flow<List<ConversationMarkdownLinkEntity>>

    @Query("SELECT * FROM conversation_markdown_links WHERE conversationId = :conversationId ORDER BY updatedAt DESC")
    suspend fun listForConversation(conversationId: String): List<ConversationMarkdownLinkEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ConversationMarkdownLinkEntity): Long

    @Query("DELETE FROM conversation_markdown_links WHERE conversationId = :conversationId AND projectId = :projectId AND relativePath = :relativePath")
    suspend fun delete(conversationId: String, projectId: String, relativePath: String)

    @Query("DELETE FROM conversation_markdown_links WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)
}
