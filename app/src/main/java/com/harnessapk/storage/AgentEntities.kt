package com.harnessapk.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index

@Entity(tableName = "agents")
data class AgentEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val summary: String,
    val activeVersion: Int,
    val publisherPublicKey: ByteArray,
    val publisherFingerprint: String,
    val installSource: String,
    val status: String,
    val requiredCorpusCount: Int,
    val installedCorpusCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "agent_versions",
    primaryKeys = ["agentId", "version"],
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("agentId")],
)
data class AgentVersionEntity(
    val agentId: String,
    val version: Int,
    val schemaVersion: Int,
    val bundlePath: String,
    val bundleSha256: String,
    val manifestJson: String,
    val persona: String,
    val worldviewJsonl: String,
    val installedAt: Long,
    val state: String,
)

@Entity(
    tableName = "agent_corpora",
    primaryKeys = ["corpusId", "sourceHash"],
)
data class AgentCorpusEntity(
    val corpusId: String,
    val sourceHash: String,
    val title: String,
    val indexedAt: Long,
    val sizeBytes: Long,
)

@Entity(
    tableName = "agent_version_corpora",
    primaryKeys = ["agentId", "version", "corpusId", "sourceHash"],
    foreignKeys = [
        ForeignKey(
            entity = AgentVersionEntity::class,
            parentColumns = ["agentId", "version"],
            childColumns = ["agentId", "version"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AgentCorpusEntity::class,
            parentColumns = ["corpusId", "sourceHash"],
            childColumns = ["corpusId", "sourceHash"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("agentId", "version"),
        Index("corpusId", "sourceHash"),
    ],
)
data class AgentVersionCorpusCrossRef(
    val agentId: String,
    val version: Int,
    val corpusId: String,
    val sourceHash: String,
    val required: Boolean,
)

@Entity(
    tableName = "agent_chunks",
    foreignKeys = [
        ForeignKey(
            entity = AgentCorpusEntity::class,
            parentColumns = ["corpusId", "sourceHash"],
            childColumns = ["corpusId", "sourceHash"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("corpusId", "sourceHash")],
)
data class AgentChunkEntity(
    @androidx.room.PrimaryKey val chunkKey: String,
    val corpusId: String,
    val sourceHash: String,
    val chunkId: String,
    val sourceTitle: String,
    val location: String,
    val text: String,
    val keywordsText: String,
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "agent_chunk_fts")
data class AgentChunkFtsEntity(
    val chunkKey: String,
    val corpusKey: String,
    val searchableText: String,
)
