package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationWikiDao {
    @Query(
        """
        SELECT * FROM wiki_versions
        WHERE wikiId = :wikiId AND version = :version AND state = 'READY'
        LIMIT 1
        """,
    )
    suspend fun findReadyVersion(wikiId: String, version: Int): WikiVersionEntity?

    @Query(
        """
        SELECT * FROM wiki_versions
        WHERE state = 'READY' AND enabledForNewConversations = 1
        ORDER BY wikiId ASC, version ASC
        """,
    )
    suspend fun listReadyDefaults(): List<WikiVersionEntity>

    @Query(
        """
        SELECT * FROM conversation_wiki_mounts
        WHERE conversationId = :conversationId AND wikiId = :wikiId
        LIMIT 1
        """,
    )
    suspend fun findMount(conversationId: String, wikiId: String): ConversationWikiMountEntity?

    @Query(
        """
        SELECT * FROM conversation_wiki_mounts
        WHERE conversationId = :conversationId
        ORDER BY wikiId ASC, wikiVersion ASC
        """,
    )
    suspend fun listMounts(conversationId: String): List<ConversationWikiMountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMount(entity: ConversationWikiMountEntity)

    @Query("DELETE FROM conversation_wiki_mounts WHERE conversationId = :conversationId AND wikiId = :wikiId")
    suspend fun deleteMount(conversationId: String, wikiId: String): Int

    @Query("DELETE FROM conversation_wiki_mounts WHERE conversationId = :conversationId")
    suspend fun deleteMounts(conversationId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM conversation_wiki_mounts
        WHERE wikiId = :wikiId AND wikiVersion = :version
        """,
    )
    suspend fun countMountReferences(wikiId: String, version: Int): Int

    @Query(
        """
        SELECT COUNT(*) FROM message_wiki_citations
        WHERE wikiId = :wikiId AND wikiVersion = :version
        """,
    )
    suspend fun countCitationReferences(wikiId: String, version: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(entity: WikiRetrievalRunEntity)

    @Query("SELECT * FROM wiki_retrieval_runs WHERE messageId = :messageId LIMIT 1")
    suspend fun findRun(messageId: String): WikiRetrievalRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsage(entity: MessageWikiUsageEntity)

    @Query(
        """
        SELECT * FROM message_wiki_usages
        WHERE messageId = :messageId
        ORDER BY wikiId ASC, wikiVersion ASC
        """,
    )
    suspend fun listUsagesForMessage(messageId: String): List<MessageWikiUsageEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCitation(entity: MessageWikiCitationEntity)

    @Query("DELETE FROM message_wiki_citations WHERE messageId = :messageId")
    suspend fun deleteCitationsForMessage(messageId: String): Int

    @Query("SELECT * FROM message_wiki_citations WHERE id = :id LIMIT 1")
    suspend fun findCitation(id: String): MessageWikiCitationEntity?

    @Query("SELECT * FROM message_wiki_citations WHERE messageId = :messageId ORDER BY displayOrdinal ASC")
    fun observeCitationsForMessage(messageId: String): Flow<List<MessageWikiCitationEntity>>

    @Query("SELECT * FROM message_wiki_citations WHERE messageId = :messageId ORDER BY displayOrdinal ASC")
    suspend fun listCitationsForMessage(messageId: String): List<MessageWikiCitationEntity>
}
