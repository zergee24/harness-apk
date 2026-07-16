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

    @Query("SELECT * FROM agent_versions WHERE agentId = :agentId AND version = :version LIMIT 1")
    suspend fun findVersion(agentId: String, version: Int): AgentVersionEntity?

    @Query("SELECT * FROM agent_corpora WHERE corpusId = :corpusId AND sourceHash = :sourceHash LIMIT 1")
    suspend fun findCorpus(corpusId: String, sourceHash: String): AgentCorpusEntity?

    @Query("SELECT * FROM agent_corpora WHERE corpusId = :corpusId ORDER BY indexedAt DESC LIMIT 1")
    suspend fun findCorpusById(corpusId: String): AgentCorpusEntity?

    @Query("SELECT * FROM agent_version_corpora WHERE agentId = :agentId AND version = :version")
    suspend fun listVersionCorpora(agentId: String, version: Int): List<AgentVersionCorpusCrossRef>

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
    suspend fun insertChunkSearchRows(entities: List<AgentChunkFtsEntity>): List<Long>

    @Query(
        """
        SELECT chunkKey FROM agent_chunk_fts
        WHERE agent_chunk_fts MATCH :ftsQuery AND corpusKey IN (:corpusKeys)
        LIMIT :limit
        """,
    )
    suspend fun searchChunkKeys(
        corpusKeys: List<String>,
        ftsQuery: String,
        limit: Int,
    ): List<String>

    @Query("SELECT * FROM agent_chunks WHERE chunkKey IN (:chunkKeys)")
    suspend fun listChunks(chunkKeys: List<String>): List<AgentChunkEntity>
}
