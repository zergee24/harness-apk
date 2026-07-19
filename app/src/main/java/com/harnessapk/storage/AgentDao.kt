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

    @Query(
        """
        SELECT agents.* FROM agents
        INNER JOIN agent_versions
            ON agent_versions.agentId = agents.id
           AND agent_versions.version = agents.activeVersion
        WHERE agents.status = 'READY' AND agent_versions.state = 'READY'
        ORDER BY agents.updatedAt DESC
        """,
    )
    suspend fun listReadyAgents(): List<AgentEntity>

    @Query("SELECT * FROM agent_versions WHERE agentId = :agentId AND version = :version LIMIT 1")
    suspend fun findVersion(agentId: String, version: Int): AgentVersionEntity?

    @Query("SELECT * FROM agent_versions WHERE agentId = :agentId ORDER BY version")
    suspend fun listVersions(agentId: String): List<AgentVersionEntity>

    @Query(
        "SELECT * FROM agent_version_packages WHERE agentId = :agentId AND version = :version AND packageId = :packageId LIMIT 1",
    )
    suspend fun findVersionPackage(agentId: String, version: Int, packageId: String): AgentVersionPackageEntity?

    @Query("SELECT * FROM agent_version_packages WHERE agentId = :agentId AND version = :version")
    suspend fun listVersionPackages(agentId: String, version: Int): List<AgentVersionPackageEntity>

    @Query("SELECT * FROM agent_corpora WHERE corpusId = :corpusId AND sourceHash = :sourceHash LIMIT 1")
    suspend fun findCorpus(corpusId: String, sourceHash: String): AgentCorpusEntity?

    @Query("SELECT * FROM agent_corpora WHERE corpusId = :corpusId ORDER BY indexedAt DESC LIMIT 1")
    suspend fun findCorpusById(corpusId: String): AgentCorpusEntity?

    @Query("SELECT * FROM agent_version_corpora WHERE agentId = :agentId AND version = :version")
    suspend fun listVersionCorpora(agentId: String, version: Int): List<AgentVersionCorpusCrossRef>

    @Query(
        "SELECT * FROM agent_version_corpora WHERE agentId = :agentId AND version = :version AND corpusId = :corpusId LIMIT 1",
    )
    suspend fun findVersionCorpus(agentId: String, version: Int, corpusId: String): AgentVersionCorpusCrossRef?

    @Query("SELECT COUNT(*) FROM agent_version_corpora WHERE corpusId = :corpusId AND sourceHash = :sourceHash")
    suspend fun countVersionCorpusReferences(corpusId: String, sourceHash: String): Int

    @Query("SELECT chunkKey FROM agent_corpus_chunks WHERE corpusId = :corpusId AND corpusHash = :corpusHash")
    suspend fun listCorpusChunkKeys(corpusId: String, corpusHash: String): List<String>

    @Query(
        """
        SELECT COUNT(*) FROM message_parts
        WHERE type = 'AGENT_SOURCES'
          AND metadataJson LIKE '%"' || :chunkKey || '"%'
        """,
    )
    suspend fun countAgentSourcePartsReferencingChunkKey(chunkKey: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM message_parts AS part
        INNER JOIN messages AS message ON message.id = part.messageId
        INNER JOIN conversations AS conversation ON conversation.id = message.conversationId
        WHERE part.type = 'AGENT_SOURCES'
          AND conversation.agentId = :agentId
          AND conversation.agentVersion = :version
          AND (part.metadataJson = '' OR part.metadataJson NOT LIKE '%chunkKeys%')
        """,
    )
    suspend fun countLegacyAgentSourceParts(agentId: String, version: Int): Int

    @Query(
        """
        SELECT DISTINCT chunkRef.chunkKey FROM agent_version_corpora AS versionCorpus
        INNER JOIN agent_corpus_chunks AS chunkRef
            ON chunkRef.corpusId = versionCorpus.corpusId
           AND chunkRef.corpusHash = versionCorpus.sourceHash
        WHERE versionCorpus.agentId = :agentId
          AND versionCorpus.version = :version
          AND chunkRef.chunkKey IN (:chunkKeys)
        """,
    )
    suspend fun listInstalledVersionChunkKeys(
        agentId: String,
        version: Int,
        chunkKeys: List<String>,
    ): List<String>

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

    @Upsert
    suspend fun upsertVersionPackages(entities: List<AgentVersionPackageEntity>)

    @Query(
        """
        UPDATE agent_version_packages
        SET installed = 1, filePath = :filePath, installedAt = :installedAt
        WHERE agentId = :agentId AND version = :version AND packageId = :packageId
        """,
    )
    suspend fun markVersionPackageInstalled(
        agentId: String,
        version: Int,
        packageId: String,
        filePath: String,
        installedAt: Long,
    ): Int

    @Query(
        """
        UPDATE agent_version_packages
        SET installed = 0, filePath = '', installedAt = NULL
        WHERE agentId = :agentId AND version = :version AND packageId = :packageId
        """,
    )
    suspend fun markVersionPackageRemoved(agentId: String, version: Int, packageId: String): Int

    @Query("SELECT COUNT(*) FROM agent_version_packages WHERE installed = 1 AND filePath = :filePath")
    suspend fun countInstalledPackagePathReferences(filePath: String): Int

    @Query("SELECT COUNT(*) FROM agent_versions WHERE bundlePath = :filePath")
    suspend fun countVersionBundlePathReferences(filePath: String): Int

    @Query("SELECT COUNT(*) FROM agent_source_files WHERE filePath = :filePath")
    suspend fun countSourceFilePathReferences(filePath: String): Int

    @Query(
        """
        SELECT (
            (SELECT COUNT(*) FROM agent_version_sources WHERE sourceId = :sourceId AND sourceHash = :sourceHash) +
            (SELECT COUNT(*) FROM agent_corpus_sources WHERE sourceId = :sourceId AND sourceHash = :sourceHash)
        )
        """,
    )
    suspend fun countSourceReferences(sourceId: String, sourceHash: String): Int

    @Query(
        """
        SELECT source.* FROM agent_source_files AS source
        INNER JOIN agent_corpus_sources AS ref
            ON ref.sourceId = source.sourceId AND ref.sourceHash = source.sourceHash
        WHERE ref.corpusId = :corpusId AND ref.corpusHash = :corpusHash
        """,
    )
    suspend fun listCorpusSources(corpusId: String, corpusHash: String): List<AgentSourceFileEntity>

    @Query("SELECT * FROM agent_source_files WHERE filePath != '' ORDER BY sourceHash, sourceId")
    suspend fun listInstalledSources(): List<AgentSourceFileEntity>

    @Query("UPDATE agent_source_files SET filePath = :filePath WHERE sourceHash = :sourceHash")
    suspend fun updateSourcePathByHash(sourceHash: String, filePath: String): Int

    @Query("UPDATE agent_versions SET state = :state, lastEvidenceExpandedAt = :expandedAt WHERE agentId = :agentId AND version = :version")
    suspend fun updateVersionState(agentId: String, version: Int, state: String, expandedAt: Long?): Int

    @Query(
        """
        UPDATE agents
        SET status = :status, requiredCorpusCount = :requiredCount,
            installedCorpusCount = :installedCount, updatedAt = :updatedAt
        WHERE id = :agentId
        """,
    )
    suspend fun updateAgentInstallState(
        agentId: String,
        status: String,
        requiredCount: Int,
        installedCount: Int,
        updatedAt: Long,
    ): Int

    @Query("UPDATE agents SET status = :status, updatedAt = :updatedAt WHERE id = :agentId")
    suspend fun updateAgentStatus(agentId: String, status: String, updatedAt: Long): Int

    @Query("DELETE FROM agent_version_corpora WHERE agentId = :agentId AND version = :version AND corpusId = :corpusId")
    suspend fun deleteVersionCorpus(agentId: String, version: Int, corpusId: String): Int

    @Query("DELETE FROM agent_corpora WHERE corpusId = :corpusId AND sourceHash = :sourceHash")
    suspend fun deleteCorpus(corpusId: String, sourceHash: String): Int

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
    suspend fun insertCorpusSourceRefs(entities: List<AgentCorpusSourceCrossRef>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChunkSearchRows(entities: List<AgentChunkFtsEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHierarchyNodes(entities: List<AgentHierarchyNodeEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHierarchySearchRows(entities: List<AgentHierarchyFtsEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCorpusHierarchyRefs(entities: List<AgentCorpusHierarchyCrossRef>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSource(entity: AgentSourceFileEntity): Long

    @Upsert
    suspend fun upsertSource(entity: AgentSourceFileEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVersionSource(entity: AgentVersionSourceCrossRef): Long

    @Query("DELETE FROM agent_versions WHERE agentId = :agentId AND version = :version")
    suspend fun deleteVersion(agentId: String, version: Int): Int

    @Query("DELETE FROM agents WHERE id = :agentId")
    suspend fun deleteAgent(agentId: String): Int

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

    @Query(
        """
        SELECT COUNT(DISTINCT chunks.chunkKey)
        FROM agent_chunks AS chunks
        INNER JOIN agent_corpus_chunks AS corpusChunks ON corpusChunks.chunkKey = chunks.chunkKey
        INNER JOIN agent_version_corpora AS versionCorpora
            ON versionCorpora.corpusId = corpusChunks.corpusId
           AND versionCorpora.sourceHash = corpusChunks.corpusHash
        WHERE versionCorpora.agentId = :agentId
          AND versionCorpora.version = :version
          AND versionCorpora.required = 1
          AND chunks.chunkId = :chunkId
        """,
    )
    suspend fun countRequiredEvidenceChunk(agentId: String, version: Int, chunkId: String): Int

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
            SELECT 1 FROM agent_corpus_hierarchy
            WHERE agent_corpus_hierarchy.nodeKey = agent_hierarchy_nodes.nodeKey
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
          AND NOT EXISTS (
            SELECT 1 FROM agent_corpus_sources
            WHERE agent_corpus_sources.sourceId = agent_source_files.sourceId
              AND agent_corpus_sources.sourceHash = agent_source_files.sourceHash
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
