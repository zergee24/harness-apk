package com.harnessapk.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message_attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class MessageAttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val type: String,
    val uri: String,
    val mimeType: String,
    val createdAt: Long,
)
