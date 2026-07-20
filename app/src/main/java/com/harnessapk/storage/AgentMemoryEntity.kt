package com.harnessapk.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_memories",
    indices = [Index(value = ["agentId", "updatedAt"])],
)
data class AgentMemoryEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val kind: String,
    val content: String,
    val sourceConversationId: String,
    val sourceMessageId: String,
    val confidence: Double,
    @ColumnInfo(defaultValue = "0") val userEdited: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)
