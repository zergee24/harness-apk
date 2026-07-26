package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageAttachmentDao {
    @Query("SELECT * FROM message_attachments WHERE messageId = :messageId ORDER BY createdAt ASC")
    fun observeForMessage(messageId: String): Flow<List<MessageAttachmentEntity>>

    @Query(
        """
        SELECT message_attachments.* FROM message_attachments
        INNER JOIN messages ON messages.id = message_attachments.messageId
        WHERE messages.conversationId = :conversationId
        ORDER BY messages.createdAt ASC, message_attachments.createdAt ASC
        """,
    )
    fun observeForConversation(conversationId: String): Flow<List<MessageAttachmentEntity>>

    @Query("SELECT * FROM message_attachments WHERE messageId = :messageId ORDER BY createdAt ASC")
    suspend fun listForMessage(messageId: String): List<MessageAttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: MessageAttachmentEntity)
}
