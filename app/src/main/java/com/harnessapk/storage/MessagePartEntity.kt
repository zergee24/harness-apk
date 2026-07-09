package com.harnessapk.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message_parts",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("messageId"),
        Index(value = ["messageId", "partIndex"], unique = true),
    ],
)
data class MessagePartEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val partIndex: Int,
    val type: String,
    val content: String,
    val metadataJson: String,
    val stable: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
