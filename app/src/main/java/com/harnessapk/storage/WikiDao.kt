package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WikiDao {
    @Query("SELECT * FROM wikis ORDER BY updatedAt DESC, id ASC")
    fun observeWikis(): Flow<List<WikiEntity>>

    @Query("SELECT * FROM wikis WHERE id = :wikiId LIMIT 1")
    suspend fun findWiki(wikiId: String): WikiEntity?

    @Query("SELECT * FROM wiki_versions WHERE wikiId = :wikiId AND version = :version LIMIT 1")
    suspend fun findVersion(wikiId: String, version: Int): WikiVersionEntity?

    @Query("SELECT * FROM wiki_versions WHERE wikiId = :wikiId ORDER BY version ASC")
    suspend fun listVersions(wikiId: String): List<WikiVersionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWiki(entity: WikiEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(entity: WikiVersionEntity)

    @Query("UPDATE wikis SET activeVersion = :version, updatedAt = :updatedAt WHERE id = :wikiId")
    suspend fun updateActiveVersion(wikiId: String, version: Int?, updatedAt: Long): Int

    @Query(
        """
        UPDATE wiki_versions
        SET enabledForNewConversations = 0
        WHERE wikiId = :wikiId
          AND state = 'READY'
          AND enabledForNewConversations = 1
        """,
    )
    suspend fun disableReadyDefaultVersions(wikiId: String): Int

    @Query(
        """
        UPDATE wiki_versions
        SET enabledForNewConversations = :enabled
        WHERE wikiId = :wikiId AND version = :version AND state = 'READY'
        """,
    )
    suspend fun setEnabledForNewConversations(wikiId: String, version: Int, enabled: Boolean): Int

    @Query(
        """
        UPDATE wiki_versions
        SET state = :state, invalidReason = :invalidReason
        WHERE wikiId = :wikiId AND version = :version
        """,
    )
    suspend fun updateVersionState(wikiId: String, version: Int, state: String, invalidReason: String?): Int

    @Query("DELETE FROM wiki_versions WHERE wikiId = :wikiId AND version = :version")
    suspend fun deleteVersion(wikiId: String, version: Int): Int

    @Query(
        """
        DELETE FROM wikis
        WHERE id = :wikiId
          AND NOT EXISTS (SELECT 1 FROM wiki_versions WHERE wikiId = :wikiId)
        """,
    )
    suspend fun deleteWikiIfNoVersions(wikiId: String): Int
}
