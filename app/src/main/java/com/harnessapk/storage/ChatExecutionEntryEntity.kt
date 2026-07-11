package com.harnessapk.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_execution_entries",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["userMessageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversationId"),
        Index("userMessageId"),
        Index(value = ["conversationId", "sequence"], unique = true),
    ],
)
data class ChatExecutionEntryEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val userMessageId: String,
    val assistantMessageId: String?,
    val targetAssistantMessageId: String?,
    val sequence: Long,
    val type: String,
    val status: String,
    val providerId: String?,
    val model: String?,
    val reasoningEffort: String,
    val requestContextJson: String,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
