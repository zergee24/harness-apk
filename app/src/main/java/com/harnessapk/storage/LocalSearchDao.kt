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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocuments(entities: List<LocalSearchDocumentEntity>)

    @Insert
    suspend fun insertFts(entity: LocalSearchFtsEntity)

    @Insert
    suspend fun insertFts(entities: List<LocalSearchFtsEntity>)

    @Query("DELETE FROM local_search_fts WHERE documentId = :documentId")
    suspend fun deleteFts(documentId: String)

    @Query("DELETE FROM local_search_documents WHERE id = :documentId")
    suspend fun deleteDocument(documentId: String)

    @Query("SELECT id FROM local_search_documents WHERE projectId = :projectId AND sourceKey = :sourceKey")
    suspend fun documentIdsForProjectSource(projectId: String, sourceKey: String): List<String>

    @Query("SELECT DISTINCT sourceKey FROM local_search_documents WHERE projectId = :projectId AND sourceType IN ('CONTEXT','MARKDOWN')")
    suspend fun markdownSourceKeys(projectId: String): List<String>

    @Query("SELECT DISTINCT sourceKey FROM local_search_documents WHERE projectId = :projectId AND sourceType = 'RUN_EVIDENCE' AND sourceKey LIKE :prefix || '%'")
    suspend fun runEvidenceSourceKeys(projectId: String, prefix: String): List<String>

    @Query(
        """
        SELECT documents.* FROM local_search_documents AS documents
        INNER JOIN conversations ON conversations.id = documents.conversationId
        WHERE documents.projectId = :projectId
          AND documents.type = 'MESSAGE'
          AND conversations.isArchived = 0
          AND (documents.dirty = 1 OR TRIM(documents.sourceSha256) = '')
        ORDER BY documents.updatedAt ASC, documents.id ASC
        """,
    )
    suspend fun projectMessageDocumentsNeedingIndex(projectId: String): List<LocalSearchDocumentEntity>

    @Query("UPDATE local_search_documents SET dirty = 1 WHERE projectId = :projectId AND sourceKey = :sourceKey")
    suspend fun markProjectSourceDirty(projectId: String, sourceKey: String)

    @Query("DELETE FROM local_search_documents WHERE projectId = :projectId AND sourceType IN ('CONTEXT','MARKDOWN')")
    suspend fun deleteProjectMarkdownDocuments(projectId: String)

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

    @Transaction
    suspend fun replaceProjectSourceDocuments(
        projectId: String,
        sourceKey: String,
        documents: List<LocalSearchDocumentEntity>,
        searchTexts: List<String>,
    ) {
        require(documents.size == searchTexts.size)
        require(documents.all { it.projectId == projectId && it.sourceKey == sourceKey })
        val previousIds = documentIdsForProjectSource(projectId, sourceKey).toSet()
        documents.zip(searchTexts).forEach { (document, searchText) ->
            replaceDocument(document, searchText)
        }
        (previousIds - documents.mapTo(mutableSetOf(), LocalSearchDocumentEntity::id)).forEach { staleId ->
            deleteFts(staleId)
            deleteDocument(staleId)
        }
    }
}
