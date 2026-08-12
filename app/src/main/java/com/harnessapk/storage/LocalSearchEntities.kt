package com.harnessapk.storage

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_search_documents",
    indices = [Index("type"), Index("conversationId"), Index("messageId"), Index("projectId")],
)
data class LocalSearchDocumentEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    val body: String,
    val conversationId: String?,
    val messageId: String?,
    val projectId: String?,
    val updatedAt: Long,
    val sourceType: String = "",
    val authority: String = "",
    val sourceKey: String = "",
    val relativePath: String? = null,
    val headingPath: String = "",
    val ordinal: Int = 0,
    val searchableText: String = "",
    val sourceSha256: String = "",
    val gitBlobId: String? = null,
    val sourceUpdatedAt: Long = updatedAt,
    val indexedAt: Long = updatedAt,
    val dirty: Boolean = false,
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "local_search_fts")
data class LocalSearchFtsEntity(
    val documentId: String,
    val searchText: String,
)
