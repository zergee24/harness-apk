package com.harnessapk.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "conversation_markdown_links",
    primaryKeys = ["conversationId", "projectId", "relativePath"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index("projectId")],
)
data class ConversationMarkdownLinkEntity(
    val conversationId: String,
    val projectId: String,
    val relativePath: String,
    val linkedAt: Long,
    val updatedAt: Long,
)
