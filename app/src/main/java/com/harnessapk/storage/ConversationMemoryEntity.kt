package com.harnessapk.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_memory",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ConversationMemoryEntity(
    @PrimaryKey val conversationId: String,
    val summary: String,
    val coveredThroughMessageId: String?,
    val coveredThroughCreatedAt: Long,
    val compressedMessageCount: Int,
    val updatedAt: Long,
)
