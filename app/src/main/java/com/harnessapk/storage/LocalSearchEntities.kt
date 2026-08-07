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
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "local_search_fts")
data class LocalSearchFtsEntity(
    val documentId: String,
    val searchText: String,
)
