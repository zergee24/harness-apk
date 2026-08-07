package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface LocalSearchDao {
    @Query("SELECT * FROM local_search_documents ORDER BY updatedAt DESC, id ASC")
    suspend fun listDocuments(): List<LocalSearchDocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(entity: LocalSearchDocumentEntity)

    @Insert
    suspend fun insertFts(entity: LocalSearchFtsEntity)

    @Query("DELETE FROM local_search_fts WHERE documentId = :documentId")
    suspend fun deleteFts(documentId: String)

    @Query("DELETE FROM local_search_documents WHERE id = :documentId")
    suspend fun deleteDocument(documentId: String)

    @Query("DELETE FROM local_search_documents WHERE type = 'PROJECT_NAME'")
    suspend fun deleteProjectDocuments()

    @Query("DELETE FROM local_search_fts WHERE documentId LIKE 'project:%'")
    suspend fun deleteProjectFts()

    @Query(
        """
        SELECT documents.* FROM local_search_documents AS documents
        INNER JOIN local_search_fts AS searchIndex ON searchIndex.documentId = documents.id
        WHERE local_search_fts MATCH :match
        ORDER BY documents.updatedAt DESC, documents.id ASC
        LIMIT :limit
        """,
    )
    suspend fun searchFts(match: String, limit: Int): List<LocalSearchDocumentEntity>

    @Query(
        """
        SELECT * FROM local_search_documents
        WHERE title LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%'
        ORDER BY updatedAt DESC, id ASC
        LIMIT :limit
        """,
    )
    suspend fun searchContains(query: String, limit: Int): List<LocalSearchDocumentEntity>

    @Transaction
    suspend fun replaceFts(documentId: String, searchText: String) {
        deleteFts(documentId)
        insertFts(LocalSearchFtsEntity(documentId, searchText))
    }

    @Transaction
    suspend fun replaceDocument(entity: LocalSearchDocumentEntity, searchText: String) {
        upsertDocument(entity)
        replaceFts(entity.id, searchText)
    }
}
