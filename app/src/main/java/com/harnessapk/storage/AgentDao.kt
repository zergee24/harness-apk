package com.harnessapk.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents ORDER BY updatedAt DESC")
    fun observeAgents(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE id = :id LIMIT 1")
    suspend fun findAgent(id: String): AgentEntity?

    @Query("SELECT * FROM agents WHERE status = 'READY' ORDER BY updatedAt DESC")
    suspend fun listReadyAgents(): List<AgentEntity>

    @Query("SELECT * FROM agent_versions WHERE agentId = :agentId AND version = :version LIMIT 1")
    suspend fun findVersion(agentId: String, version: Int): AgentVersionEntity?

    @Query("SELECT * FROM agent_corpora WHERE corpusId = :corpusId AND sourceHash = :sourceHash LIMIT 1")
    suspend fun findCorpus(corpusId: String, sourceHash: String): AgentCorpusEntity?

    @Query("SELECT * FROM agent_corpora WHERE corpusId = :corpusId ORDER BY indexedAt DESC LIMIT 1")
    suspend fun findCorpusById(corpusId: String): AgentCorpusEntity?

    @Query("SELECT * FROM agent_version_corpora WHERE agentId = :agentId AND version = :version")
    suspend fun listVersionCorpora(agentId: String, version: Int): List<AgentVersionCorpusCrossRef>

    @Query("SELECT * FROM agent_source_files WHERE sourceId = :sourceId AND sourceHash = :sourceHash LIMIT 1")
    suspend fun findSource(sourceId: String, sourceHash: String): AgentSourceFileEntity?

    @Query(
        """
        SELECT source.* FROM agent_source_files AS source
        INNER JOIN agent_version_sources AS ref
            ON ref.sourceId = source.sourceId AND ref.sourceHash = source.sourceHash
        WHERE ref.agentId = :agentId AND ref.version = :version
        ORDER BY source.title, source.sourceId
        """,
    )
    suspend fun listVersionSources(agentId: String, version: Int): List<AgentSourceFileEntity>

    @Upsert
    suspend fun upsertAgent(entity: AgentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(entity: AgentVersionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCorpus(entity: AgentCorpusEntity): Long

    @Query("UPDATE agent_corpora SET sizeBytes = :sizeBytes WHERE corpusId = :corpusId AND sourceHash = :sourceHash")
    suspend fun updateCorpusSize(corpusId: String, sourceHash: String, sizeBytes: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVersionCorpus(entity: AgentVersionCorpusCrossRef): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChunks(entities: List<AgentChunkEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCorpusChunkRefs(entities: List<AgentCorpusChunkCrossRef>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChunkSearchRows(entities: List<AgentChunkFtsEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHierarchyNodes(entities: List<AgentHierarchyNodeEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHierarchySearchRows(entities: List<AgentHierarchyFtsEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSource(entity: AgentSourceFileEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVersionSource(entity: AgentVersionSourceCrossRef): Long

    @Query(
        """
        SELECT DISTINCT agent_chunk_fts.chunkKey
        FROM agent_chunk_fts
        INNER JOIN agent_corpus_chunks
            ON agent_corpus_chunks.chunkKey = agent_chunk_fts.chunkKey
        WHERE agent_chunk_fts MATCH :ftsQuery
          AND (agent_corpus_chunks.corpusId || ':' || agent_corpus_chunks.corpusHash) IN (:corpusKeys)
        LIMIT :limit
        """,
    )
    suspend fun searchChunkKeys(
        corpusKeys: List<String>,
        ftsQuery: String,
        limit: Int,
    ): List<String>

    @Query(
        """
        SELECT nodeKey FROM agent_hierarchy_fts
        WHERE agent_hierarchy_fts MATCH :ftsQuery
        LIMIT :limit
        """,
    )
    suspend fun searchHierarchyNodeKeys(ftsQuery: String, limit: Int): List<String>

    @Query("SELECT * FROM agent_chunks WHERE chunkKey IN (:chunkKeys)")
    suspend fun listChunks(chunkKeys: List<String>): List<AgentChunkEntity>

    @Query("SELECT * FROM agent_hierarchy_nodes WHERE nodeKey IN (:nodeKeys)")
    suspend fun listHierarchyNodes(nodeKeys: List<String>): List<AgentHierarchyNodeEntity>

    @Query("DELETE FROM agent_chunk_fts WHERE chunkKey NOT IN (SELECT chunkKey FROM agent_chunks)")
    suspend fun deleteOrphanChunkSearchRows(): Int

    @Query("DELETE FROM agent_chunks WHERE chunkKey NOT IN (SELECT chunkKey FROM agent_corpus_chunks)")
    suspend fun deleteOrphanChunks(): Int

    @Query("DELETE FROM agent_hierarchy_fts WHERE nodeKey NOT IN (SELECT nodeKey FROM agent_hierarchy_nodes)")
    suspend fun deleteOrphanHierarchySearchRows(): Int

    @Query(
        """
        DELETE FROM agent_hierarchy_nodes
        WHERE NOT EXISTS (
            SELECT 1 FROM agent_source_files
            WHERE agent_source_files.sourceId = agent_hierarchy_nodes.sourceId
              AND agent_source_files.sourceHash = agent_hierarchy_nodes.sourceHash
        )
        """,
    )
    suspend fun deleteOrphanHierarchyNodes(): Int

    @Query(
        """
        DELETE FROM agent_source_files
        WHERE NOT EXISTS (
            SELECT 1 FROM agent_version_sources
            WHERE agent_version_sources.sourceId = agent_source_files.sourceId
              AND agent_version_sources.sourceHash = agent_source_files.sourceHash
        )
        """,
    )
    suspend fun deleteOrphanSources(): Int

    @Query(
        """
        DELETE FROM agent_corpora
        WHERE NOT EXISTS (
            SELECT 1 FROM agent_version_corpora
            WHERE agent_version_corpora.corpusId = agent_corpora.corpusId
              AND agent_version_corpora.sourceHash = agent_corpora.sourceHash
        )
        """,
    )
    suspend fun deleteOrphanCorpora(): Int
}
