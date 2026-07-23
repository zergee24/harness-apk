package com.harnessapk.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MessageAttachmentEntity::class,
        MessagePartEntity::class,
        ProviderProfileEntity::class,
        ConversationMemoryEntity::class,
        ChatExecutionEntryEntity::class,
        ConversationMarkdownLinkEntity::class,
        MarkdownChangeDraftEntity::class,
        MarkdownChangeDraftItemEntity::class,
        AgentEntity::class,
        AgentVersionEntity::class,
        AgentVersionPackageEntity::class,
        AgentCorpusEntity::class,
        AgentVersionCorpusCrossRef::class,
        AgentChunkEntity::class,
        AgentCorpusChunkCrossRef::class,
        AgentCorpusSourceCrossRef::class,
        AgentChunkFtsEntity::class,
        AgentHierarchyNodeEntity::class,
        AgentHierarchyFtsEntity::class,
        AgentCorpusHierarchyCrossRef::class,
        AgentSourceFileEntity::class,
        AgentVersionSourceCrossRef::class,
        AgentMemoryEntity::class,
        WikiEntity::class,
        WikiVersionEntity::class,
        ConversationWikiMountEntity::class,
        WikiRetrievalRunEntity::class,
        MessageWikiUsageEntity::class,
        MessageWikiCitationEntity::class,
    ],
    version = 19,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun messageAttachmentDao(): MessageAttachmentDao
    abstract fun messagePartDao(): MessagePartDao
    abstract fun providerProfileDao(): ProviderProfileDao
    abstract fun conversationMemoryDao(): ConversationMemoryDao
    abstract fun chatExecutionEntryDao(): ChatExecutionEntryDao
    abstract fun conversationMarkdownLinkDao(): ConversationMarkdownLinkDao
    abstract fun markdownChangeDraftDao(): MarkdownChangeDraftDao
    abstract fun agentDao(): AgentDao
    abstract fun agentMemoryDao(): AgentMemoryDao
    abstract fun wikiDao(): WikiDao
    abstract fun conversationWikiDao(): ConversationWikiDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS conversation_memory (
                        conversationId TEXT NOT NULL PRIMARY KEY,
                        summary TEXT NOT NULL,
                        coveredThroughMessageId TEXT,
                        coveredThroughCreatedAt INTEGER NOT NULL,
                        compressedMessageCount INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE provider_profiles ADD COLUMN availableModels TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN promptOriginal TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN promptOptimized TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN promptFinal TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE provider_profiles ADD COLUMN nativeWebSearchMode TEXT NOT NULL DEFAULT 'DISABLED'",
                )
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN projectId TEXT")
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS message_parts (
                        id TEXT NOT NULL PRIMARY KEY,
                        messageId TEXT NOT NULL,
                        partIndex INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        content TEXT NOT NULL,
                        metadataJson TEXT NOT NULL,
                        stable INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_message_parts_messageId ON message_parts(messageId)",
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_message_parts_messageId_partIndex
                    ON message_parts(messageId, partIndex)
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE provider_profiles ADD COLUMN customHeadersJson TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE provider_profiles ADD COLUMN customBodyJson TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_execution_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        conversationId TEXT NOT NULL,
                        userMessageId TEXT NOT NULL,
                        assistantMessageId TEXT,
                        targetAssistantMessageId TEXT,
                        sequence INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        providerId TEXT,
                        model TEXT,
                        reasoningEffort TEXT NOT NULL,
                        requestContextJson TEXT NOT NULL,
                        errorMessage TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE,
                        FOREIGN KEY(userMessageId) REFERENCES messages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chat_execution_entries_conversationId ON chat_execution_entries(conversationId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chat_execution_entries_userMessageId ON chat_execution_entries(userMessageId)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_chat_execution_entries_conversationId_sequence ON chat_execution_entries(conversationId, sequence)",
                )
            }
        }

        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS conversation_markdown_links (
                        conversationId TEXT NOT NULL,
                        projectId TEXT NOT NULL,
                        relativePath TEXT NOT NULL,
                        linkedAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(conversationId, projectId, relativePath),
                        FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_markdown_links_conversationId ON conversation_markdown_links(conversationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_markdown_links_projectId ON conversation_markdown_links(projectId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS markdown_change_drafts (
                        id TEXT NOT NULL PRIMARY KEY,
                        conversationId TEXT NOT NULL,
                        projectId TEXT NOT NULL,
                        sourceUserMessageId TEXT NOT NULL,
                        assistantMessageId TEXT,
                        status TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        rawResponse TEXT,
                        errorMessage TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_markdown_change_drafts_conversationId ON markdown_change_drafts(conversationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_markdown_change_drafts_projectId ON markdown_change_drafts(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_markdown_change_drafts_sourceUserMessageId ON markdown_change_drafts(sourceUserMessageId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS markdown_change_draft_items (
                        id TEXT NOT NULL PRIMARY KEY,
                        draftId TEXT NOT NULL,
                        itemIndex INTEGER NOT NULL,
                        operation TEXT NOT NULL,
                        relativePath TEXT NOT NULL,
                        title TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        proposedMarkdown TEXT NOT NULL,
                        retained INTEGER NOT NULL,
                        baselineSha256 TEXT,
                        expectedAbsent INTEGER NOT NULL,
                        applyStatus TEXT,
                        applyErrorMessage TEXT,
                        FOREIGN KEY(draftId) REFERENCES markdown_change_drafts(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_markdown_change_draft_items_draftId ON markdown_change_draft_items(draftId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_markdown_change_draft_items_draftId_itemIndex ON markdown_change_draft_items(draftId, itemIndex)")
            }
        }

        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN agentId TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN agentVersion INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agents (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        activeVersion INTEGER NOT NULL,
                        publisherPublicKey BLOB NOT NULL,
                        publisherFingerprint TEXT NOT NULL,
                        installSource TEXT NOT NULL,
                        status TEXT NOT NULL,
                        requiredCorpusCount INTEGER NOT NULL,
                        installedCorpusCount INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_versions (
                        agentId TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        schemaVersion INTEGER NOT NULL,
                        bundlePath TEXT NOT NULL,
                        bundleSha256 TEXT NOT NULL,
                        manifestJson TEXT NOT NULL,
                        persona TEXT NOT NULL,
                        worldviewJsonl TEXT NOT NULL,
                        installedAt INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        PRIMARY KEY(agentId, version),
                        FOREIGN KEY(agentId) REFERENCES agents(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_versions_agentId ON agent_versions(agentId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_corpora (
                        corpusId TEXT NOT NULL,
                        sourceHash TEXT NOT NULL,
                        title TEXT NOT NULL,
                        indexedAt INTEGER NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        PRIMARY KEY(corpusId, sourceHash)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_version_corpora (
                        agentId TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        corpusId TEXT NOT NULL,
                        sourceHash TEXT NOT NULL,
                        required INTEGER NOT NULL,
                        PRIMARY KEY(agentId, version, corpusId, sourceHash),
                        FOREIGN KEY(agentId, version) REFERENCES agent_versions(agentId, version) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(corpusId, sourceHash) REFERENCES agent_corpora(corpusId, sourceHash) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_version_corpora_agentId_version ON agent_version_corpora(agentId, version)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_version_corpora_corpusId_sourceHash ON agent_version_corpora(corpusId, sourceHash)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_chunks (
                        chunkKey TEXT NOT NULL PRIMARY KEY,
                        corpusId TEXT NOT NULL,
                        sourceHash TEXT NOT NULL,
                        chunkId TEXT NOT NULL,
                        sourceTitle TEXT NOT NULL,
                        location TEXT NOT NULL,
                        text TEXT NOT NULL,
                        keywordsText TEXT NOT NULL,
                        FOREIGN KEY(corpusId, sourceHash) REFERENCES agent_corpora(corpusId, sourceHash) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_chunks_corpusId_sourceHash ON agent_chunks(corpusId, sourceHash)")
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS agent_chunk_fts
                    USING FTS4(chunkKey TEXT NOT NULL, corpusKey TEXT NOT NULL, searchableText TEXT NOT NULL, tokenize=unicode61)
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_versions ADD COLUMN identityJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agent_versions ADD COLUMN voiceJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agent_versions ADD COLUMN episodesJsonl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agent_versions ADD COLUMN conceptsJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agent_versions ADD COLUMN examplesJsonl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agent_versions ADD COLUMN openersJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agent_versions ADD COLUMN installPlanJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agent_versions ADD COLUMN lastEvidenceExpandedAt INTEGER DEFAULT NULL")

                db.execSQL("ALTER TABLE agent_version_corpora ADD COLUMN installClass TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agent_version_corpora ADD COLUMN packageSha256 TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agent_version_corpora ADD COLUMN packageSizeBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE agent_version_corpora ADD COLUMN installedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE agent_version_corpora
                    SET installClass = CASE WHEN required = 1 THEN 'required' ELSE 'optional' END,
                        installedAt = COALESCE((
                            SELECT agent_versions.installedAt
                            FROM agent_versions
                            WHERE agent_versions.agentId = agent_version_corpora.agentId
                              AND agent_versions.version = agent_version_corpora.version
                        ), 0)
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE agent_chunk_fts_v11_physical (
                        chunkKey TEXT NOT NULL PRIMARY KEY,
                        searchableText TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE agent_chunk_fts_v11_entries (
                        chunkKey TEXT NOT NULL,
                        searchableText TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO agent_chunk_fts_v11_entries (chunkKey, searchableText)
                    SELECT chunkKey, searchableText
                    FROM agent_chunk_fts
                    ORDER BY rowid
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX index_agent_chunk_fts_v11_entries_chunkKey
                    ON agent_chunk_fts_v11_entries(chunkKey)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO agent_chunk_fts_v11_physical (chunkKey, searchableText)
                    SELECT physicalKey, GROUP_CONCAT(searchableText, ' ')
                    FROM (
                        SELECT chunks.sourceHash || ':' || chunks.chunkId AS physicalKey,
                            COALESCE(fts.searchableText, chunks.keywordsText || ' ' || chunks.text)
                                AS searchableText
                        FROM agent_chunks AS chunks
                        LEFT JOIN agent_chunk_fts_v11_entries AS fts ON fts.chunkKey = chunks.chunkKey
                        ORDER BY physicalKey, chunks.chunkKey, fts.rowid
                    )
                    GROUP BY physicalKey
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE agent_chunk_fts_v11_entries")
                db.execSQL("ALTER TABLE agent_chunks RENAME TO agent_chunks_v11")
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_agent_chunks_v11_sourceHash_chunkId_chunkKey
                    ON agent_chunks_v11(sourceHash, chunkId, chunkKey)
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE agent_chunk_fts")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_chunks (
                        chunkKey TEXT NOT NULL PRIMARY KEY,
                        sourceId TEXT NOT NULL,
                        sourceHash TEXT NOT NULL,
                        chunkId TEXT NOT NULL,
                        sourceTitle TEXT NOT NULL,
                        period TEXT NOT NULL,
                        genre TEXT NOT NULL,
                        authorship TEXT NOT NULL,
                        location TEXT NOT NULL,
                        parentPath TEXT NOT NULL,
                        context TEXT NOT NULL,
                        text TEXT NOT NULL,
                        keywordsText TEXT NOT NULL,
                        duplicateGroup TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO agent_chunks (
                        chunkKey, sourceId, sourceHash, chunkId, sourceTitle, period,
                        genre, authorship, location, parentPath, context, text,
                        keywordsText, duplicateGroup
                    )
                    SELECT legacy.sourceHash || ':' || legacy.chunkId,
                        legacy.sourceHash, legacy.sourceHash, legacy.chunkId,
                        legacy.sourceTitle, '', 'unknown', 'unknown', legacy.location, '',
                        '', legacy.text, legacy.keywordsText, ''
                    FROM agent_chunks_v11 AS legacy
                    INNER JOIN (
                        SELECT sourceHash, chunkId, MIN(chunkKey) AS canonicalChunkKey
                        FROM agent_chunks_v11
                        GROUP BY sourceHash, chunkId
                    ) AS canonical ON canonical.canonicalChunkKey = legacy.chunkKey
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_corpus_chunks (
                        corpusId TEXT NOT NULL,
                        corpusHash TEXT NOT NULL,
                        chunkKey TEXT NOT NULL,
                        PRIMARY KEY(corpusId, corpusHash, chunkKey),
                        FOREIGN KEY(corpusId, corpusHash)
                            REFERENCES agent_corpora(corpusId, sourceHash)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(chunkKey) REFERENCES agent_chunks(chunkKey)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO agent_corpus_chunks (corpusId, corpusHash, chunkKey)
                    SELECT DISTINCT corpusId, sourceHash, sourceHash || ':' || chunkId
                    FROM agent_chunks_v11
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE agent_chunks_v11")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_agent_chunks_sourceId_sourceHash ON agent_chunks(sourceId, sourceHash)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_agent_chunks_sourceHash_chunkId ON agent_chunks(sourceHash, chunkId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_agent_corpus_chunks_corpusId_corpusHash ON agent_corpus_chunks(corpusId, corpusHash)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_agent_corpus_chunks_chunkKey ON agent_corpus_chunks(chunkKey)",
                )
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS agent_chunk_fts
                    USING FTS4(chunkKey TEXT NOT NULL, searchableText TEXT NOT NULL, tokenize=unicode61)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO agent_chunk_fts (chunkKey, searchableText)
                    SELECT chunkKey, searchableText
                    FROM agent_chunk_fts_v11_physical
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE agent_chunk_fts_v11_physical")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_hierarchy_nodes (
                        nodeKey TEXT NOT NULL PRIMARY KEY,
                        sourceId TEXT NOT NULL,
                        sourceHash TEXT NOT NULL,
                        nodeId TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        title TEXT NOT NULL,
                        parentNodeKey TEXT,
                        path TEXT NOT NULL,
                        summary TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_agent_hierarchy_nodes_sourceId_sourceHash ON agent_hierarchy_nodes(sourceId, sourceHash)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_agent_hierarchy_nodes_parentNodeKey ON agent_hierarchy_nodes(parentNodeKey)",
                )
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS agent_hierarchy_fts
                    USING FTS4(nodeKey TEXT NOT NULL, searchableText TEXT NOT NULL, tokenize=unicode61)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_source_files (
                        sourceId TEXT NOT NULL,
                        sourceHash TEXT NOT NULL,
                        title TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        storedName TEXT NOT NULL,
                        format TEXT NOT NULL,
                        genre TEXT NOT NULL,
                        authorship TEXT NOT NULL,
                        period TEXT NOT NULL,
                        rawSizeBytes INTEGER NOT NULL,
                        filePath TEXT NOT NULL,
                        packageSha256 TEXT NOT NULL,
                        installedAt INTEGER NOT NULL,
                        PRIMARY KEY(sourceId, sourceHash)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_version_sources (
                        agentId TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        sourceId TEXT NOT NULL,
                        sourceHash TEXT NOT NULL,
                        PRIMARY KEY(agentId, version, sourceId, sourceHash),
                        FOREIGN KEY(agentId, version)
                            REFERENCES agent_versions(agentId, version)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(sourceId, sourceHash)
                            REFERENCES agent_source_files(sourceId, sourceHash)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_agent_version_sources_agentId_version ON agent_version_sources(agentId, version)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_agent_version_sources_sourceId_sourceHash ON agent_version_sources(sourceId, sourceHash)",
                )

                db.query("PRAGMA foreign_key_check").use { cursor ->
                    if (cursor.moveToFirst()) {
                        throw IllegalStateException(
                            "foreign_key_check failed: ${cursor.getString(0)} / row ${cursor.getLong(1)}",
                        )
                    }
                }
            }
        }

        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_versions ADD COLUMN requiredCorpusCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE agent_versions ADD COLUMN evalJsonl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agent_versions ADD COLUMN requiredEvidenceJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agent_versions ADD COLUMN evaluationCorpusIdsJson TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    UPDATE agent_versions
                    SET requiredCorpusCount = CASE
                        WHEN version = (SELECT activeVersion FROM agents WHERE agents.id = agent_versions.agentId)
                            THEN COALESCE((SELECT requiredCorpusCount FROM agents WHERE agents.id = agent_versions.agentId), 0)
                        ELSE (SELECT COUNT(*) FROM agent_version_corpora
                              WHERE agent_version_corpora.agentId = agent_versions.agentId
                                AND agent_version_corpora.version = agent_versions.version
                                AND agent_version_corpora.required = 1)
                    END
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_version_packages (
                        agentId TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        packageId TEXT NOT NULL,
                        type TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        installClass TEXT NOT NULL,
                        packageSha256 TEXT NOT NULL,
                        packageSizeBytes INTEGER NOT NULL,
                        installed INTEGER NOT NULL,
                        filePath TEXT NOT NULL,
                        installedAt INTEGER,
                        PRIMARY KEY(agentId, version, packageId),
                        FOREIGN KEY(agentId, version) REFERENCES agent_versions(agentId, version)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_version_packages_agentId_version ON agent_version_packages(agentId, version)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_version_packages_packageSha256 ON agent_version_packages(packageSha256)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_corpus_sources (
                        corpusId TEXT NOT NULL,
                        corpusHash TEXT NOT NULL,
                        sourceId TEXT NOT NULL,
                        sourceHash TEXT NOT NULL,
                        PRIMARY KEY(corpusId, corpusHash, sourceId, sourceHash),
                        FOREIGN KEY(corpusId, corpusHash) REFERENCES agent_corpora(corpusId, sourceHash)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(sourceId, sourceHash) REFERENCES agent_source_files(sourceId, sourceHash)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_corpus_sources_corpusId_corpusHash ON agent_corpus_sources(corpusId, corpusHash)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_corpus_sources_sourceId_sourceHash ON agent_corpus_sources(sourceId, sourceHash)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_corpus_hierarchy (
                        corpusId TEXT NOT NULL,
                        corpusHash TEXT NOT NULL,
                        nodeKey TEXT NOT NULL,
                        PRIMARY KEY(corpusId, corpusHash, nodeKey),
                        FOREIGN KEY(corpusId, corpusHash) REFERENCES agent_corpora(corpusId, sourceHash)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(nodeKey) REFERENCES agent_hierarchy_nodes(nodeKey)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_corpus_hierarchy_corpusId_corpusHash ON agent_corpus_hierarchy(corpusId, corpusHash)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_corpus_hierarchy_nodeKey ON agent_corpus_hierarchy(nodeKey)")
                db.query("PRAGMA foreign_key_check").use { cursor ->
                    if (cursor.moveToFirst()) {
                        throw IllegalStateException(
                            "foreign_key_check failed: ${cursor.getString(0)} / row ${cursor.getLong(1)}",
                        )
                    }
                }
            }
        }

        val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_chunks ADD COLUMN conflictKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agent_chunks ADD COLUMN sourceAliasesJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE agent_chunks ADD COLUMN simHash TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_14_15: Migration = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE agent_versions ADD COLUMN agentPackageSizeBytes INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE agent_versions ADD COLUMN selectedProfileId TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        val MIGRATION_15_16: Migration = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_memories (
                        id TEXT NOT NULL,
                        agentId TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        content TEXT NOT NULL,
                        sourceConversationId TEXT NOT NULL,
                        sourceMessageId TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        userEdited INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_agent_memories_agentId_updatedAt
                    ON agent_memories(agentId, updatedAt)
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_16_17: Migration = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS wikis (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        activeVersion INTEGER,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wikis_activeVersion ON wikis(activeVersion)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS wiki_versions (
                        wikiId TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        contentPath TEXT NOT NULL,
                        schemaVersion INTEGER NOT NULL,
                        contentHash TEXT NOT NULL,
                        packageHash TEXT NOT NULL,
                        publisherKeyId TEXT NOT NULL,
                        publisherFingerprint TEXT NOT NULL,
                        manifestJson TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        enabledForNewConversations INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        installedAt INTEGER NOT NULL,
                        invalidReason TEXT,
                        PRIMARY KEY(wikiId, version),
                        FOREIGN KEY(wikiId) REFERENCES wikis(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_wiki_versions_wikiId_version ON wiki_versions(wikiId, version)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_wiki_versions_publisherFingerprint ON wiki_versions(publisherFingerprint)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_wiki_versions_wikiId_enabledForNewConversations ON wiki_versions(wikiId, enabledForNewConversations)",
                )
            }
        }

        val MIGRATION_17_18: Migration = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS conversation_wiki_mounts (
                        conversationId TEXT NOT NULL,
                        wikiId TEXT NOT NULL,
                        wikiVersion INTEGER NOT NULL,
                        enabled INTEGER NOT NULL,
                        mountedAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(conversationId, wikiId),
                        FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE,
                        FOREIGN KEY(wikiId, wikiVersion) REFERENCES wiki_versions(wikiId, version) ON DELETE NO ACTION
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_conversation_wiki_mounts_wikiId_wikiVersion ON conversation_wiki_mounts(wikiId, wikiVersion)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS wiki_retrieval_runs (
                        messageId TEXT NOT NULL,
                        allowedScopeJson TEXT NOT NULL,
                        explicitOverrideJson TEXT,
                        routerVersion TEXT NOT NULL,
                        retrieverVersion TEXT NOT NULL,
                        status TEXT NOT NULL,
                        candidateCount INTEGER NOT NULL,
                        evidenceCount INTEGER NOT NULL,
                        elapsedMillis INTEGER NOT NULL,
                        errorCode TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(messageId),
                        FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS message_wiki_usages (
                        messageId TEXT NOT NULL,
                        wikiId TEXT NOT NULL,
                        wikiVersion INTEGER NOT NULL,
                        scoutRank INTEGER,
                        deepHitCount INTEGER NOT NULL,
                        selectedEvidenceCount INTEGER NOT NULL,
                        enteredContext INTEGER NOT NULL,
                        PRIMARY KEY(messageId, wikiId, wikiVersion),
                        FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_message_wiki_usages_wikiId_wikiVersion ON message_wiki_usages(wikiId, wikiVersion)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS message_wiki_citations (
                        id TEXT NOT NULL,
                        messageId TEXT NOT NULL,
                        displayOrdinal INTEGER NOT NULL,
                        wikiId TEXT NOT NULL,
                        wikiVersion INTEGER NOT NULL,
                        wikiTitle TEXT NOT NULL,
                        documentId TEXT NOT NULL,
                        sectionId TEXT NOT NULL,
                        chunkId TEXT NOT NULL,
                        sourceTitle TEXT NOT NULL,
                        sectionPath TEXT NOT NULL,
                        locatorLabel TEXT NOT NULL,
                        originalTextSnapshot TEXT NOT NULL,
                        originalTextSha256 TEXT NOT NULL,
                        answerRangesJson TEXT NOT NULL,
                        verificationState TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE,
                        FOREIGN KEY(wikiId, wikiVersion) REFERENCES wiki_versions(wikiId, version) ON DELETE NO ACTION
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_message_wiki_citations_messageId ON message_wiki_citations(messageId)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_message_wiki_citations_messageId_displayOrdinal ON message_wiki_citations(messageId, displayOrdinal)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_message_wiki_citations_wikiId_wikiVersion ON message_wiki_citations(wikiId, wikiVersion)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_message_wiki_citations_chunkId ON message_wiki_citations(chunkId)",
                )
                db.query("PRAGMA foreign_key_check").use { cursor ->
                    if (cursor.moveToFirst()) {
                        throw IllegalStateException("foreign_key_check failed after Wiki conversation migration")
                    }
                }
            }
        }

        /**
         * Citation rows are immutable message snapshots. They must remain writable when the
         * exact Wiki package disappears between retrieval and terminal-message persistence.
         */
        val MIGRATION_18_19: Migration = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS message_wiki_citations_new (
                        id TEXT NOT NULL,
                        messageId TEXT NOT NULL,
                        displayOrdinal INTEGER NOT NULL,
                        wikiId TEXT NOT NULL,
                        wikiVersion INTEGER NOT NULL,
                        wikiTitle TEXT NOT NULL,
                        documentId TEXT NOT NULL,
                        sectionId TEXT NOT NULL,
                        chunkId TEXT NOT NULL,
                        sourceTitle TEXT NOT NULL,
                        sectionPath TEXT NOT NULL,
                        locatorLabel TEXT NOT NULL,
                        originalTextSnapshot TEXT NOT NULL,
                        originalTextSha256 TEXT NOT NULL,
                        answerRangesJson TEXT NOT NULL,
                        verificationState TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO message_wiki_citations_new (
                        id, messageId, displayOrdinal, wikiId, wikiVersion, wikiTitle,
                        documentId, sectionId, chunkId, sourceTitle, sectionPath, locatorLabel,
                        originalTextSnapshot, originalTextSha256, answerRangesJson,
                        verificationState, createdAt
                    )
                    SELECT
                        id, messageId, displayOrdinal, wikiId, wikiVersion, wikiTitle,
                        documentId, sectionId, chunkId, sourceTitle, sectionPath, locatorLabel,
                        originalTextSnapshot, originalTextSha256, answerRangesJson,
                        verificationState, createdAt
                    FROM message_wiki_citations
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE message_wiki_citations")
                db.execSQL("ALTER TABLE message_wiki_citations_new RENAME TO message_wiki_citations")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_message_wiki_citations_messageId ON message_wiki_citations(messageId)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_message_wiki_citations_messageId_displayOrdinal ON message_wiki_citations(messageId, displayOrdinal)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_message_wiki_citations_wikiId_wikiVersion ON message_wiki_citations(wikiId, wikiVersion)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_message_wiki_citations_chunkId ON message_wiki_citations(chunkId)",
                )
                db.query("PRAGMA foreign_key_check").use { cursor ->
                    if (cursor.moveToFirst()) {
                        throw IllegalStateException("foreign_key_check failed after immutable Wiki citation migration")
                    }
                }
            }
        }
    }
}
