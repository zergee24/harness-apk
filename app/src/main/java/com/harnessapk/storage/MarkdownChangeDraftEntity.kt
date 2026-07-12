package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "markdown_change_drafts",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index("projectId"), Index("sourceUserMessageId")],
)
data class MarkdownChangeDraftEntity(
    @androidx.room.PrimaryKey val id: String,
    val conversationId: String,
    val projectId: String,
    val sourceUserMessageId: String,
    val assistantMessageId: String?,
    val status: String,
    val summary: String,
    val rawResponse: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "markdown_change_draft_items",
    foreignKeys = [
        ForeignKey(
            entity = MarkdownChangeDraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("draftId"), Index(value = ["draftId", "itemIndex"], unique = true)],
)
data class MarkdownChangeDraftItemEntity(
    @androidx.room.PrimaryKey val id: String,
    val draftId: String,
    val itemIndex: Int,
    val operation: String,
    val relativePath: String,
    val title: String,
    val reason: String,
    val proposedMarkdown: String,
    val retained: Boolean,
    val baselineSha256: String?,
    val expectedAbsent: Boolean,
    val applyStatus: String?,
    val applyErrorMessage: String?,
)

data class MarkdownChangeDraftRecord(
    val draft: MarkdownChangeDraftEntity,
    val items: List<MarkdownChangeDraftItemEntity>,
)

@Dao
interface MarkdownChangeDraftDao {
    @Query("SELECT * FROM markdown_change_drafts WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeForConversation(conversationId: String): Flow<List<MarkdownChangeDraftEntity>>

    @Query("SELECT * FROM markdown_change_drafts WHERE id = :draftId LIMIT 1")
    suspend fun findDraft(draftId: String): MarkdownChangeDraftEntity?

    @Query("SELECT * FROM markdown_change_draft_items WHERE draftId = :draftId ORDER BY itemIndex ASC")
    suspend fun listItems(draftId: String): List<MarkdownChangeDraftItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDraft(draft: MarkdownChangeDraftEntity)

    @Update
    suspend fun updateDraft(draft: MarkdownChangeDraftEntity)

    @Query("DELETE FROM markdown_change_draft_items WHERE draftId = :draftId")
    suspend fun deleteItems(draftId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<MarkdownChangeDraftItemEntity>)

    @Transaction
    suspend fun replaceItems(draftId: String, items: List<MarkdownChangeDraftItemEntity>) {
        deleteItems(draftId)
        if (items.isNotEmpty()) insertItems(items)
    }
}
