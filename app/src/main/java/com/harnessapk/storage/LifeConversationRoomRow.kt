package com.harnessapk.storage

import androidx.room.ColumnInfo

/**
 * One SQL projection for the life overview.
 *
 * The overview must not issue one message or execution query per row. Room
 * returns the durable conversation fields together with the small set of
 * message/execution facts needed for title, summary and status rendering.
 */
data class LifeConversationRoomRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long,
    @ColumnInfo(name = "isArchived") val isArchived: Boolean,
    @ColumnInfo(name = "projectId") val projectId: String?,
    @ColumnInfo(name = "agentId") val agentId: String?,
    @ColumnInfo(name = "agentVersion") val agentVersion: Int?,
    @ColumnInfo(name = "defaultProviderId") val defaultProviderId: String?,
    @ColumnInfo(name = "defaultModel") val defaultModel: String?,
    @ColumnInfo(name = "promptOriginal") val promptOriginal: String,
    @ColumnInfo(name = "promptOptimized") val promptOptimized: String,
    @ColumnInfo(name = "promptFinal") val promptFinal: String,
    @ColumnInfo(name = "messageCount") val messageCount: Long,
    @ColumnInfo(name = "lastMessageAt") val lastMessageAt: Long?,
    @ColumnInfo(name = "lastUserText") val lastUserText: String?,
    @ColumnInfo(name = "lastAssistantText") val lastAssistantText: String?,
    @ColumnInfo(name = "latestExecutionStatus") val latestExecutionStatus: String?,
    @ColumnInfo(name = "latestRequestContextJson") val latestRequestContextJson: String?,
    @ColumnInfo(name = "openExecutionCount") val openExecutionCount: Long,
    @ColumnInfo(name = "firstUserText") val firstUserText: String? = null,
    @ColumnInfo(name = "firstUserRequestContextJson") val firstUserRequestContextJson: String? = null,
)
