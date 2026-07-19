package com.harnessapk.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.ColumnInfo

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
    @ColumnInfo(defaultValue = "''") val identityJson: String = "",
    @ColumnInfo(defaultValue = "''") val voiceJson: String = "",
    @ColumnInfo(defaultValue = "''") val episodesJsonl: String = "",
    @ColumnInfo(defaultValue = "''") val conceptsJson: String = "",
    @ColumnInfo(defaultValue = "''") val examplesJsonl: String = "",
    @ColumnInfo(defaultValue = "''") val openersJson: String = "",
    @ColumnInfo(defaultValue = "''") val evalJsonl: String = "",
    @ColumnInfo(defaultValue = "''") val installPlanJson: String = "",
    @ColumnInfo(defaultValue = "NULL") val lastEvidenceExpandedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val requiredCorpusCount: Int = 0,
    @ColumnInfo(defaultValue = "''") val requiredEvidenceJson: String = "",
    @ColumnInfo(defaultValue = "''") val evaluationCorpusIdsJson: String = "",
)

@Entity(
    tableName = "agent_version_packages",
    primaryKeys = ["agentId", "version", "packageId"],
    foreignKeys = [
        ForeignKey(
            entity = AgentVersionEntity::class,
            parentColumns = ["agentId", "version"],
            childColumns = ["agentId", "version"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("agentId", "version"), Index("packageSha256")],
)
data class AgentVersionPackageEntity(
    val agentId: String,
    val version: Int,
    val packageId: String,
    val type: String,
    val fileName: String,
    val installClass: String,
    val packageSha256: String,
    val packageSizeBytes: Long,
    val installed: Boolean,
    val filePath: String,
    val installedAt: Long?,
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
    @ColumnInfo(defaultValue = "''") val installClass: String = if (required) "required" else "optional",
    @ColumnInfo(defaultValue = "''") val packageSha256: String = "",
    @ColumnInfo(defaultValue = "0") val packageSizeBytes: Long = 0L,
    @ColumnInfo(defaultValue = "0") val installedAt: Long = 0L,
)

@Entity(
    tableName = "agent_chunks",
    indices = [
        Index("sourceId", "sourceHash"),
        Index("sourceHash", "chunkId", unique = true),
    ],
)
data class AgentChunkEntity(
    @androidx.room.PrimaryKey val chunkKey: String,
    val sourceId: String = "",
    val sourceHash: String,
    val chunkId: String,
    val sourceTitle: String,
    val period: String = "",
    val genre: String = "unknown",
    val authorship: String = "unknown",
    val location: String,
    val parentPath: String = "",
    val context: String = "",
    val text: String,
    val keywordsText: String,
    val duplicateGroup: String = "",
    @ColumnInfo(defaultValue = "''") val conflictKey: String = "",
    @ColumnInfo(defaultValue = "'[]'") val sourceAliasesJson: String = "[]",
    @ColumnInfo(defaultValue = "''") val simHash: String = "",
)

@Entity(
    tableName = "agent_corpus_chunks",
    primaryKeys = ["corpusId", "corpusHash", "chunkKey"],
    foreignKeys = [
        ForeignKey(
            entity = AgentCorpusEntity::class,
            parentColumns = ["corpusId", "sourceHash"],
            childColumns = ["corpusId", "corpusHash"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AgentChunkEntity::class,
            parentColumns = ["chunkKey"],
            childColumns = ["chunkKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("corpusId", "corpusHash"),
        Index("chunkKey"),
    ],
)
data class AgentCorpusChunkCrossRef(
    val corpusId: String,
    val corpusHash: String,
    val chunkKey: String,
)

@Entity(
    tableName = "agent_corpus_sources",
    primaryKeys = ["corpusId", "corpusHash", "sourceId", "sourceHash"],
    foreignKeys = [
        ForeignKey(
            entity = AgentCorpusEntity::class,
            parentColumns = ["corpusId", "sourceHash"],
            childColumns = ["corpusId", "corpusHash"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AgentSourceFileEntity::class,
            parentColumns = ["sourceId", "sourceHash"],
            childColumns = ["sourceId", "sourceHash"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("corpusId", "corpusHash"), Index("sourceId", "sourceHash")],
)
data class AgentCorpusSourceCrossRef(
    val corpusId: String,
    val corpusHash: String,
    val sourceId: String,
    val sourceHash: String,
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "agent_chunk_fts")
data class AgentChunkFtsEntity(
    val chunkKey: String,
    val searchableText: String,
)

@Entity(
    tableName = "agent_hierarchy_nodes",
    indices = [
        Index("sourceId", "sourceHash"),
        Index("parentNodeKey"),
    ],
)
data class AgentHierarchyNodeEntity(
    @androidx.room.PrimaryKey val nodeKey: String,
    val sourceId: String,
    val sourceHash: String,
    val nodeId: String,
    val kind: String,
    val title: String,
    val parentNodeKey: String?,
    val path: String,
    val summary: String,
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "agent_hierarchy_fts")
data class AgentHierarchyFtsEntity(
    val nodeKey: String,
    val searchableText: String,
)

@Entity(
    tableName = "agent_corpus_hierarchy",
    primaryKeys = ["corpusId", "corpusHash", "nodeKey"],
    foreignKeys = [
        ForeignKey(
            entity = AgentCorpusEntity::class,
            parentColumns = ["corpusId", "sourceHash"],
            childColumns = ["corpusId", "corpusHash"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AgentHierarchyNodeEntity::class,
            parentColumns = ["nodeKey"],
            childColumns = ["nodeKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("corpusId", "corpusHash"), Index("nodeKey")],
)
data class AgentCorpusHierarchyCrossRef(
    val corpusId: String,
    val corpusHash: String,
    val nodeKey: String,
)

@Entity(
    tableName = "agent_source_files",
    primaryKeys = ["sourceId", "sourceHash"],
)
data class AgentSourceFileEntity(
    val sourceId: String,
    val sourceHash: String,
    val title: String,
    val fileName: String,
    val storedName: String,
    val format: String,
    val genre: String,
    val authorship: String,
    val period: String,
    val rawSizeBytes: Long,
    val filePath: String,
    val packageSha256: String,
    val installedAt: Long,
)

@Entity(
    tableName = "agent_version_sources",
    primaryKeys = ["agentId", "version", "sourceId", "sourceHash"],
    foreignKeys = [
        ForeignKey(
            entity = AgentVersionEntity::class,
            parentColumns = ["agentId", "version"],
            childColumns = ["agentId", "version"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AgentSourceFileEntity::class,
            parentColumns = ["sourceId", "sourceHash"],
            childColumns = ["sourceId", "sourceHash"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("agentId", "version"),
        Index("sourceId", "sourceHash"),
    ],
)
data class AgentVersionSourceCrossRef(
    val agentId: String,
    val version: Int,
    val sourceId: String,
    val sourceHash: String,
)
