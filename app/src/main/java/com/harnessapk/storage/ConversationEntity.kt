package com.harnessapk.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val defaultProviderId: String?,
    val defaultModel: String?,
    val isArchived: Boolean,
    val projectId: String?,
    val promptOriginal: String,
    val promptOptimized: String,
    val promptFinal: String,
    val agentId: String? = null,
    val agentVersion: Int? = null,
)
