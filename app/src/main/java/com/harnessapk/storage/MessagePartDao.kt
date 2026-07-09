package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface MessagePartDao {
    @Query("SELECT * FROM message_parts WHERE messageId = :messageId ORDER BY partIndex ASC")
    suspend fun listForMessage(messageId: String): List<MessagePartEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(parts: List<MessagePartEntity>)

    @Query("DELETE FROM message_parts WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)

    @Transaction
    suspend fun replaceForMessage(messageId: String, parts: List<MessagePartEntity>) {
        deleteForMessage(messageId)
        if (parts.isNotEmpty()) insertAll(parts)
    }
}
