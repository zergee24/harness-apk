package com.harnessapk.storage

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.agent.AgentBundleAccess
import com.harnessapk.agent.AgentBundleException
import com.harnessapk.agent.AgentCorpusChunk
import com.harnessapk.agent.AgentCorpusManifest
import com.harnessapk.agent.AgentImportPreview
import com.harnessapk.agent.AgentImportSession
import com.harnessapk.agent.AgentPackageManifest
import com.harnessapk.agent.AgentRepository
import com.harnessapk.agent.AgentRuntimeContext
import com.harnessapk.agent.AgentEvidence
import com.harnessapk.agent.AgentLifecycleCoordinator
import com.harnessapk.agent.AgentTransactionRunner
import com.harnessapk.agent.ParsedAgentBundle
import com.harnessapk.agentmemory.AgentMemoryCandidate
import com.harnessapk.agentmemory.AgentMemoryExtractionInput
import com.harnessapk.agentmemory.AgentMemoryKind
import com.harnessapk.agentmemory.AgentMemoryMessageSnapshot
import com.harnessapk.agentmemory.AgentMemoryPolicy
import com.harnessapk.agentmemory.AgentMemoryRepository
import com.harnessapk.agentmemory.AgentMemoryTransactionRunner
import com.harnessapk.common.TimeProvider
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.chat.AgentSourcePartWriter
import com.harnessapk.chat.ChatRepository
import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import com.harnessapk.chat.StreamingMessageSnapshot
import com.harnessapk.chat.UiMessagePartDraft
import com.harnessapk.chat.UiMessagePartType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    @Test
    fun agentMemoryRoomTransactionPreservesCompletedUserEdit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        var now = 10L
        val repository = AgentMemoryRepository(
            dao = db.agentMemoryDao(),
            transactionRunner = AgentMemoryTransactionRunner { block ->
                db.withTransaction { block() }
            },
            timeProvider = TimeProvider { now },
        )
        fun candidate(content: String, messageId: String): AgentMemoryCandidate {
            val verifiedContent = "$content；以后默认用中文回答"
            return AgentMemoryCandidate(
                kind = AgentMemoryKind.USER_PREFERENCE,
                dedupeKey = "language",
                content = verifiedContent,
                sourceMessageId = messageId,
                sourceQuote = verifiedContent,
                confidence = 0.8,
            )
        }
        fun acceptedBatch(
            conversationId: String,
            candidates: List<AgentMemoryCandidate>,
        ): AgentMemoryPolicy.AcceptedBatch {
            val input = AgentMemoryExtractionInput(
                agentId = "agent-1",
                conversationId = conversationId,
                projectId = null,
                conversationSummary = "",
                recentMessages = candidates.mapIndexed { index, candidate ->
                    AgentMemoryMessageSnapshot(
                        id = candidate.sourceMessageId,
                        conversationId = conversationId,
                        role = MessageRole.USER,
                        status = MessageStatus.SUCCEEDED,
                        content = candidate.sourceQuote,
                        order = index.toLong(),
                    )
                },
                projectFacts = emptyList(),
            )
            return checkNotNull(AgentMemoryPolicy().evaluate(input, candidates).acceptedBatch)
        }
        repository.merge(acceptedBatch("conversation-1", listOf(candidate("默认英文", "message-1"))))
        val id = repository.list("agent-1").single().id
        now = 20L
        assertTrue(repository.edit(id, "默认中文"))
        now = 30L

        val result = repository.merge(
            acceptedBatch("conversation-2", listOf(candidate("自动改写", "message-2"))),
        )

        val memory = repository.list("agent-1").single()
        assertEquals(1, result.protectedCount)
        assertEquals("默认中文", memory.content)
        assertEquals("conversation-1", memory.sourceConversationId)
        assertEquals("message-1", memory.sourceMessageId)
        assertEquals(10L, memory.createdAt)
        assertEquals(20L, memory.updatedAt)
        assertTrue(memory.userEdited)
        db.close()
    }

    @Test
    fun storesOnePhysicalChunkForTwoCorporaAndFiltersFtsThroughCrossRefs() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dao = db.agentDao()
        dao.upsertAgent(
            AgentEntity(
                id = "agent-1",
                name = "资料研究代理",
                summary = "基于资料模拟",
                activeVersion = 1,
                publisherPublicKey = byteArrayOf(1, 2, 3),
                publisherFingerprint = "fingerprint",
                installSource = "LOCAL_FILE",
                status = "READY",
                requiredCorpusCount = 1,
                installedCorpusCount = 1,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        dao.insertVersion(
            AgentVersionEntity(
                agentId = "agent-1",
                version = 1,
                schemaVersion = 1,
                bundlePath = "/tmp/agent.hbundle",
                bundleSha256 = "bundle-sha",
                manifestJson = "{}",
                persona = "只根据资料回答",
                worldviewJsonl = "",
                installedAt = 1L,
                state = "READY",
            ),
        )
        dao.insertCorpus(
            AgentCorpusEntity(
                corpusId = "corpus-1",
                sourceHash = "source-hash",
                title = "测试资料",
                indexedAt = 1L,
                sizeBytes = 100L,
            ),
        )
        dao.insertVersionCorpus(
            AgentVersionCorpusCrossRef(
                agentId = "agent-1",
                version = 1,
                corpusId = "corpus-1",
                sourceHash = "source-hash",
                required = true,
                installClass = "required",
                packageSha256 = "a".repeat(64),
                packageSizeBytes = 100L,
                installedAt = 1L,
            ),
        )
        dao.insertCorpus(
            AgentCorpusEntity(
                corpusId = "corpus-full",
                sourceHash = "source-hash",
                title = "完整资料",
                indexedAt = 1L,
                sizeBytes = 100L,
            ),
        )
        dao.insertVersionCorpus(
            AgentVersionCorpusCrossRef(
                agentId = "agent-1",
                version = 1,
                corpusId = "corpus-full",
                sourceHash = "source-hash",
                required = false,
                installClass = "optional",
                packageSha256 = "b".repeat(64),
                packageSizeBytes = 200L,
                installedAt = 1L,
            ),
        )
        dao.insertChunks(
            listOf(
                AgentChunkEntity(
                    chunkKey = "source-hash:chunk-1",
                    sourceId = "source-1",
                    sourceHash = "source-hash",
                    chunkId = "chunk-1",
                    sourceTitle = "测试资料",
                    period = "1926",
                    genre = "speech",
                    authorship = "direct",
                    location = "第一章",
                    parentPath = "测试资料 / 第一章",
                    context = "测试资料 / 第一章 / 1926",
                    text = "研究问题必须从事实出发",
                    keywordsText = "调查 事实",
                    duplicateGroup = "core",
                ),
            ),
        )
        dao.insertCorpusChunkRefs(
            listOf(
                AgentCorpusChunkCrossRef("corpus-1", "source-hash", "source-hash:chunk-1"),
                AgentCorpusChunkCrossRef("corpus-full", "source-hash", "source-hash:chunk-1"),
            ),
        )
        dao.insertChunkSearchRows(
            listOf(
                AgentChunkFtsEntity(
                    chunkKey = "source-hash:chunk-1",
                    searchableText = "调查 事实 调查研究",
                ),
            ),
        )

        val keys = dao.searchChunkKeys(
            corpusKeys = listOf("corpus-1:source-hash"),
            ftsQuery = "调查 OR 事实",
            afterChunkKey = "",
            limit = 8,
        )

        assertEquals(listOf("source-hash:chunk-1"), keys)
        assertEquals(1, db.scalarInt("SELECT COUNT(*) FROM agent_chunks"))
        assertEquals(1, db.scalarInt("SELECT COUNT(*) FROM agent_chunk_fts"))
        assertEquals(2, db.scalarInt("SELECT COUNT(*) FROM agent_corpus_chunks"))
        assertEquals(
            listOf("source-hash:chunk-1"),
            dao.listInstalledVersionChunkKeys(
                agentId = "agent-1",
                version = 1,
                chunkKeys = listOf("source-hash:chunk-1", "missing:chunk"),
            ),
        )
        db.close()
    }

    @Test
    fun legacyAgentSourcesAreScopedByMessageConversationAgentAndVersion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val conversationDao = db.conversationDao()
        val messageDao = db.messageDao()
        val partDao = db.messagePartDao()
        listOf(
            ConversationEntity(
                "target-conversation", "target", 1L, 1L, null, null, false, null,
                "", "", "", "agent-target", 2,
            ),
            ConversationEntity(
                "other-conversation", "other", 1L, 1L, null, null, false, null,
                "", "", "", "agent-other", 9,
            ),
        ).forEach { conversationDao.insert(it) }
        listOf(
            MessageEntity(
                "target-message", "target-conversation", MessageRole.ASSISTANT.name, "answer",
                MessageStatus.SUCCEEDED.name, null, null, 1L, 1L, null, null,
            ),
            MessageEntity(
                "other-message", "other-conversation", MessageRole.ASSISTANT.name, "answer",
                MessageStatus.SUCCEEDED.name, null, null, 1L, 1L, null, null,
            ),
        ).forEach { messageDao.insert(it) }
        partDao.insertAll(
            listOf(
                MessagePartEntity(
                    "target-part", "target-message", 0, UiMessagePartType.AGENT_SOURCES.name,
                    "legacy", "", true, 1L, 1L,
                ),
                MessagePartEntity(
                    "other-part", "other-message", 0, UiMessagePartType.AGENT_SOURCES.name,
                    "legacy", "{}", true, 1L, 1L,
                ),
            ),
        )

        assertEquals(1, db.agentDao().countLegacyAgentSourceParts("agent-target", 2))
        assertEquals(0, db.agentDao().countLegacyAgentSourceParts("agent-target", 1))
        assertEquals(1, db.agentDao().countLegacyAgentSourceParts("agent-other", 9))
        db.close()
    }

    @Test
    fun agentSourceWriterRevalidatesFixedVersionAfterRetrievalAndPersistsInRoomTransaction() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dao = db.agentDao()
        dao.upsertAgent(
            AgentEntity(
                "agent-1", "agent", "", 1, byteArrayOf(1), "publisher", "LOCAL_FILE",
                "READY", 0, 1, 1L, 1L,
            ),
        )
        dao.insertVersion(
            AgentVersionEntity(
                "agent-1", 1, 2, "/tmp/agent.hagent", "sha", "{}", "persona", "",
                1L, "READY",
            ),
        )
        dao.insertCorpus(AgentCorpusEntity("corpus-1", "corpus-hash", "corpus", 1L, 1L))
        dao.insertVersionCorpus(
            AgentVersionCorpusCrossRef("agent-1", 1, "corpus-1", "corpus-hash", false),
        )
        dao.insertChunks(
            listOf(
                AgentChunkEntity(
                    "source-hash:chunk-1", "source-1", "source-hash", "chunk-1", "source",
                    location = "section", text = "evidence", keywordsText = "evidence",
                ),
            ),
        )
        dao.insertCorpusChunkRefs(
            listOf(AgentCorpusChunkCrossRef("corpus-1", "corpus-hash", "source-hash:chunk-1")),
        )
        val repository = ChatRepository(
            db.conversationDao(),
            db.messageDao(),
            db.messagePartDao(),
            db.messageAttachmentDao(),
            db.conversationMemoryDao(),
            TimeProvider { 1L },
        )
        val conversationId = repository.createConversation(agentId = "agent-1", agentVersion = 1)
        val messageId = repository.insertAssistantPending(conversationId, "provider", "model")
        val selected = AgentRuntimeContext(
            "agent-1",
            1,
            "prompt",
            listOf(
                AgentEvidence(
                    "chunk-1", "source", "section", "evidence", 1,
                    chunkKey = "source-hash:chunk-1",
                ),
            ),
        )
        dao.deleteVersionCorpus("agent-1", 1, "corpus-1")
        val writer = AgentSourcePartWriter(
            dao = dao,
            chatRepository = repository,
            transactionRunner = AgentTransactionRunner { block -> db.withTransaction { block() } },
            lifecycleCoordinator = AgentLifecycleCoordinator(),
        )

        writer.persist(
            messageId,
            StreamingMessageSnapshot(
                MessageStatus.PENDING,
                listOf(UiMessagePartDraft(0, UiMessagePartType.TEXT, "answer[资料1]", stable = true)),
            ),
            selected,
        )

        assertEquals(0, db.scalarInt("SELECT COUNT(*) FROM message_parts WHERE type = 'AGENT_SOURCES'"))
        assertEquals(
            0,
            db.scalarInt(
                "SELECT COUNT(*) FROM message_parts WHERE metadataJson LIKE '%source-hash:chunk-1%'",
            ),
        )
        db.close()
    }

    @Test
    fun repositoryReusesIdenticalPhysicalChunkAfterInsertIgnore() = runBlocking {
        val fixture = repositoryChunkConflictFixture(conflicting = false)

        fixture.repository.install(fixture.session)

        assertEquals(1, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_chunks"))
        assertEquals(1, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_chunk_fts"))
        assertEquals(1, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_corpus_chunks"))
        fixture.close()
        Unit
    }

    @Test
    fun repositoryRollsBackCorpusInstallWhenPhysicalEvidenceConflicts() = runBlocking {
        val fixture = repositoryChunkConflictFixture(conflicting = true)

        try {
            fixture.repository.install(fixture.session)
            fail("Expected immutable physical evidence conflict")
        } catch (error: AgentBundleException) {
            assertTrue(error.message.orEmpty().contains("immutable evidence"))
        }

        assertEquals(1, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_chunks"))
        assertEquals(1, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_chunk_fts"))
        assertEquals(0, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_corpus_chunks"))
        assertEquals(0, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_corpora"))
        assertEquals(0, fixture.db.scalarInt("SELECT COUNT(*) FROM agents"))
        fixture.close()
        Unit
    }

    private suspend fun repositoryChunkConflictFixture(conflicting: Boolean): RepositoryConflictFixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val sourceHash = "a".repeat(64)
        val existing = AgentChunkEntity(
            chunkKey = "$sourceHash:chunk-1",
            sourceId = sourceHash,
            sourceHash = sourceHash,
            chunkId = "chunk-1",
            sourceTitle = "同一来源",
            location = "第一章",
            text = "原始证据",
            keywordsText = "调查",
        )
        db.agentDao().insertChunks(listOf(existing))
        db.agentDao().insertChunkSearchRows(
            listOf(AgentChunkFtsEntity(existing.chunkKey, "调查 原始证据")),
        )
        val root = context.cacheDir.resolve("repository-conflict-${System.nanoTime()}").apply { mkdirs() }
        return repositoryFixture(
            db = db,
            root = root,
            chunk = AgentCorpusChunk(
                id = "chunk-1",
                sourceTitle = "同一来源",
                sourceHash = sourceHash,
                location = "第一章",
                text = if (conflicting) "冲突证据" else "原始证据",
                keywords = listOf("调查"),
                ngrams = listOf("调查"),
            ),
        )
    }

    private suspend fun repositoryFixture(
        db: AppDatabase,
        root: File,
        chunk: AgentCorpusChunk,
    ): RepositoryConflictFixture {
        val staged = root.resolve("staged.hbundle").apply { writeText("validated") }
        val corpus = AgentCorpusManifest(
            id = "corpus-new",
            title = "新语料",
            sourceHash = "corpus-hash",
            sourcesPath = "sources.json",
            chunksPath = "chunks.jsonl",
            required = true,
        )
        val parsed = ParsedAgentBundle(
            file = staged,
            packageSha256 = "bundle-sha",
            publisherPublicKey = byteArrayOf(1),
            publisherFingerprint = "publisher",
            manifestJson = "{}",
            agent = AgentPackageManifest(
                id = "agent-room-conflict",
                name = "事务测试代理",
                version = 1,
                summary = "",
                personaPath = "persona.md",
                worldviewPath = "worldview.jsonl",
                conceptsPath = "concepts.json",
                examplesPath = "examples.jsonl",
                evalPath = "eval.jsonl",
                requiredCorpora = listOf(corpus.id),
            ),
            corpora = listOf(corpus),
            persona = "只依据证据",
            worldviewJsonl = "",
            compressedSizeBytes = staged.length(),
            uncompressedSizeBytes = staged.length(),
        )
        val reader = RepositoryConflictReader(chunk, parsed)
        val repository = AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = db.agentDao(),
            reader = reader,
            transactionRunner = { block -> db.withTransaction { block() } },
            timeProvider = TimeProvider { 1L },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val session = repository.prepareImport("staged.hbundle") { staged.inputStream() }
        return RepositoryConflictFixture(
            db = db,
            root = root,
            repository = repository,
            session = session,
        )
    }

    @Test
    fun storesHierarchyAndOriginalSourceWithoutCreatingSourceChunkRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dao = db.agentDao()
        dao.insertSource(
            AgentSourceFileEntity(
                sourceId = "source-1",
                sourceHash = "c".repeat(64),
                title = "原始资料",
                fileName = "source.pdf",
                storedName = "source.pdf",
                format = "pdf",
                genre = "speech",
                authorship = "direct",
                period = "1926",
                rawSizeBytes = 1024L,
                filePath = "/tmp/source.pdf",
                packageSha256 = "d".repeat(64),
                installedAt = 2L,
            ),
        )
        dao.insertHierarchyNodes(
            listOf(
                AgentHierarchyNodeEntity(
                    nodeKey = "${"c".repeat(64)}:node-1",
                    sourceId = "source-1",
                    sourceHash = "c".repeat(64),
                    nodeId = "node-1",
                    kind = "section",
                    title = "第一章",
                    parentNodeKey = null,
                    path = "原始资料 / 第一章",
                    summary = "层级摘要",
                ),
            ),
        )
        dao.insertHierarchySearchRows(
            listOf(AgentHierarchyFtsEntity(nodeKey = "${"c".repeat(64)}:node-1", searchableText = "第一章 层级摘要")),
        )

        assertNotNull(dao.findSource("source-1", "c".repeat(64)))
        assertEquals(
            listOf("${"c".repeat(64)}:node-1"),
            dao.searchHierarchyNodeKeys("第一章", 8),
        )
        assertEquals(0, db.scalarInt("SELECT COUNT(*) FROM agent_chunks"))
        assertEquals(0, db.scalarInt("SELECT COUNT(*) FROM agent_chunk_fts"))
        db.close()
    }

    @Test
    fun ftsCandidateLimitsAreStableAcrossDifferentInsertionOrders() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        suspend fun search(order: List<String>): List<List<String>> {
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            val dao = db.agentDao()
            val corpusHash = "f".repeat(64)
            dao.insertCorpus(AgentCorpusEntity("corpus", corpusHash, "corpus", 1L, 1L))
            val chunks = order.map { key ->
                AgentChunkEntity(
                    chunkKey = key,
                    sourceHash = corpusHash,
                    chunkId = key,
                    sourceTitle = "source",
                    location = "location",
                    text = "shared evidence",
                    keywordsText = "shared",
                )
            }
            dao.insertChunks(chunks)
            dao.insertCorpusChunkRefs(chunks.map { AgentCorpusChunkCrossRef("corpus", corpusHash, it.chunkKey) })
            dao.insertChunkSearchRows(chunks.map { AgentChunkFtsEntity(it.chunkKey, "shared evidence") })

            val nodes = order.map { key ->
                AgentHierarchyNodeEntity(
                    nodeKey = key,
                    sourceId = "source",
                    sourceHash = corpusHash,
                    nodeId = key,
                    kind = "section",
                    title = "shared",
                    parentNodeKey = null,
                    path = "shared",
                    summary = "evidence",
                )
            }
            dao.insertHierarchyNodes(nodes)
            dao.insertHierarchySearchRows(nodes.map { AgentHierarchyFtsEntity(it.nodeKey, "shared evidence") })
            dao.insertCorpusHierarchyRefs(
                nodes.map { AgentCorpusHierarchyCrossRef("corpus", corpusHash, it.nodeKey) },
            )

            val result = listOf(
                dao.searchChunkKeys(listOf("corpus:$corpusHash"), "shared", "", 2),
                dao.searchChunkKeys(listOf("corpus:$corpusHash"), "shared", "m-key", 2),
                dao.searchHierarchyNodeKeysForCorpora(listOf("corpus:$corpusHash"), "shared", "", 2),
                dao.searchHierarchyNodeKeysForCorpora(listOf("corpus:$corpusHash"), "shared", "m-key", 2),
            )
            db.close()
            return result
        }

        val first = search(listOf("z-key", "a-key", "m-key"))
        val second = search(listOf("m-key", "z-key", "a-key"))

        assertEquals(first, second)
        assertEquals(
            listOf(
                listOf("a-key", "m-key"),
                listOf("z-key"),
                listOf("a-key", "m-key"),
                listOf("z-key"),
            ),
            first,
        )
    }

    @Test
    fun migration15To16PreservesRealHistoricalSchemaAndV2Data() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-15-16-real-${System.nanoTime()}.db"
        createVersion15Fixture(context, name)

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_15_16)
            .build()
        val sqlite = migrated.openHelper.writableDatabase

        assertEquals(16, sqlite.version)
        assertEquals(6, sqlite.scalarInt("SELECT COUNT(*) FROM conversations"))
        assertEquals(
            "daily-conversation|USER|默认使用中文",
            sqlite.string(
                """
                SELECT conversationId || '|' || role || '|' || content
                FROM messages WHERE id = 'daily-message'
                """.trimIndent(),
            ),
        )
        assertEquals(
            "project-1|notes/project.md",
            sqlite.string(
                """
                SELECT projectId || '|' || relativePath
                FROM conversation_markdown_links
                WHERE conversationId = 'project-conversation'
                """.trimIndent(),
            ),
        )
        assertEquals(
            "balanced",
            sqlite.string(
                "SELECT selectedProfileId FROM agent_versions WHERE agentId = 'agent-v2'",
            ),
        )
        assertEquals(
            1,
            sqlite.scalarInt(
                "SELECT COUNT(*) FROM agent_version_packages WHERE agentId = 'agent-v2'",
            ),
        )
        assertEquals(
            "required|1",
            sqlite.string(
                """
                SELECT installClass || '|' || installed
                FROM agent_version_packages
                WHERE agentId = 'agent-v2' AND version = 2 AND packageId = 'core'
                """.trimIndent(),
            ),
        )
        assertEquals(
            "1|required",
            sqlite.string(
                """
                SELECT required || '|' || installClass
                FROM agent_version_corpora
                WHERE agentId = 'agent-v2' AND version = 2 AND corpusId = 'core'
                """.trimIndent(),
            ),
        )
        assertEquals(
            1,
            sqlite.scalarInt("SELECT COUNT(*) FROM agent_chunks WHERE sourceId = 'source-v2'"),
        )
        assertEquals(1, sqlite.scalarInt("SELECT COUNT(*) FROM agent_source_files"))
        assertEquals(1, sqlite.scalarInt("SELECT COUNT(*) FROM agent_hierarchy_nodes"))
        val v2SourceHash = sqlite.string(
            "SELECT sourceHash FROM agent_corpora WHERE corpusId = 'core'",
        )
        val v2CorpusKeys = listOf("core:$v2SourceHash")
        assertEquals(
            listOf("source-v2:chunk-1"),
            migrated.agentDao().searchChunkKeys(v2CorpusKeys, "默认", "", 8),
        )
        assertEquals(
            listOf("source-v2:root"),
            migrated.agentDao().searchHierarchyNodeKeysForCorpora(v2CorpusKeys, "摘要", "", 8),
        )
        assertEquals(0, sqlite.scalarInt("SELECT COUNT(*) FROM agent_memories"))
        assertEquals(
            0,
            sqlite.scalarInt("SELECT COUNT(*) FROM pragma_foreign_key_list('agent_memories')"),
        )
        sqlite.assertNoForeignKeyViolations()

        migrated.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test
    fun agentMemoryWeakAssociationSurvivesSourceDeletion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-15-16-${System.nanoTime()}.db"
        val version15 = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val agentDao = version15.agentDao()
        val sourceHash = "a".repeat(64)
        version15.conversationDao().insert(
            conversation("daily-conversation", updatedAt = 20L, agentId = "agent-v2"),
        )
        version15.conversationDao().insert(
            conversation(
                id = "project-conversation",
                updatedAt = 10L,
                projectId = "project-1",
                agentId = "agent-v2",
            ),
        )
        version15.messageDao().insert(
            MessageEntity(
                id = "daily-message",
                conversationId = "daily-conversation",
                role = "USER",
                content = "默认使用中文",
                status = "SUCCEEDED",
                providerId = "provider-1",
                model = "model-1",
                createdAt = 1L,
                updatedAt = 1L,
                errorCode = null,
                errorMessage = null,
            ),
        )
        version15.conversationMarkdownLinkDao().insert(
            ConversationMarkdownLinkEntity(
                conversationId = "project-conversation",
                projectId = "project-1",
                relativePath = "notes/project.md",
                linkedAt = 2L,
                updatedAt = 2L,
            ),
        )
        agentDao.upsertAgent(
            AgentEntity(
                id = "agent-v2",
                name = "测试人物",
                summary = "",
                activeVersion = 2,
                publisherPublicKey = byteArrayOf(1),
                publisherFingerprint = "publisher",
                installSource = "LOCAL_FILE",
                status = "READY",
                requiredCorpusCount = 1,
                installedCorpusCount = 1,
                createdAt = 1L,
                updatedAt = 2L,
            ),
        )
        agentDao.insertVersion(
            AgentVersionEntity(
                agentId = "agent-v2",
                version = 2,
                schemaVersion = 2,
                bundlePath = "/tmp/agent.hagent",
                bundleSha256 = "b".repeat(64),
                manifestJson = "{}",
                persona = "第一人称",
                worldviewJsonl = "",
                installedAt = 2L,
                state = "READY",
                identityJson = "{}",
                voiceJson = "{}",
                installPlanJson = "{}",
                requiredCorpusCount = 1,
                agentPackageSizeBytes = 100L,
                selectedProfileId = "balanced",
            ),
        )
        agentDao.upsertVersionPackages(
            listOf(
                AgentVersionPackageEntity(
                    agentId = "agent-v2",
                    version = 2,
                    packageId = "core",
                    type = "corpus",
                    fileName = "core.hcorpus",
                    installClass = "required",
                    packageSha256 = "c".repeat(64),
                    packageSizeBytes = 50L,
                    installed = true,
                    filePath = "/tmp/core.hcorpus",
                    installedAt = 2L,
                ),
            ),
        )
        agentDao.insertCorpus(AgentCorpusEntity("core", sourceHash, "核心资料", 2L, 50L))
        agentDao.insertVersionCorpus(
            AgentVersionCorpusCrossRef(
                "agent-v2",
                2,
                "core",
                sourceHash,
                true,
                "required",
                "c".repeat(64),
                50L,
                2L,
            ),
        )
        agentDao.insertSource(
            AgentSourceFileEntity(
                sourceId = "source-1",
                sourceHash = sourceHash,
                title = "来源",
                fileName = "source.md",
                storedName = "source.md",
                format = "md",
                genre = "conversation",
                authorship = "direct",
                period = "2026",
                rawSizeBytes = 20L,
                filePath = "/tmp/source.md",
                packageSha256 = "d".repeat(64),
                installedAt = 2L,
            ),
        )
        agentDao.insertVersionSource(
            AgentVersionSourceCrossRef("agent-v2", 2, "source-1", sourceHash),
        )
        agentDao.insertCorpusSourceRefs(
            listOf(AgentCorpusSourceCrossRef("core", sourceHash, "source-1", sourceHash)),
        )
        agentDao.insertChunks(
            listOf(
                AgentChunkEntity(
                    chunkKey = "source-1:chunk-1",
                    sourceId = "source-1",
                    sourceHash = sourceHash,
                    chunkId = "chunk-1",
                    sourceTitle = "来源",
                    period = "2026",
                    genre = "conversation",
                    authorship = "direct",
                    location = "第一段",
                    text = "默认使用中文",
                    keywordsText = "默认 中文",
                ),
            ),
        )
        agentDao.insertCorpusChunkRefs(
            listOf(AgentCorpusChunkCrossRef("core", sourceHash, "source-1:chunk-1")),
        )
        agentDao.insertChunkSearchRows(
            listOf(AgentChunkFtsEntity("source-1:chunk-1", "默认 中文")),
        )
        agentDao.insertHierarchyNodes(
            listOf(
                AgentHierarchyNodeEntity(
                    nodeKey = "source-1:root",
                    sourceId = "source-1",
                    sourceHash = sourceHash,
                    nodeId = "root",
                    kind = "document",
                    title = "来源",
                    parentNodeKey = null,
                    path = "来源",
                    summary = "摘要",
                ),
            ),
        )
        agentDao.insertHierarchySearchRows(
            listOf(AgentHierarchyFtsEntity("source-1:root", "来源 摘要")),
        )
        agentDao.insertCorpusHierarchyRefs(
            listOf(AgentCorpusHierarchyCrossRef("core", sourceHash, "source-1:root")),
        )

        val migrated = version15
        val sqlite = migrated.openHelper.writableDatabase

        assertEquals(16, sqlite.version)
        assertEquals(2, sqlite.scalarInt("SELECT COUNT(*) FROM conversations"))
        assertEquals("默认使用中文", sqlite.string("SELECT content FROM messages WHERE id = 'daily-message'"))
        assertEquals("notes/project.md", sqlite.string("SELECT relativePath FROM conversation_markdown_links"))
        assertEquals("balanced", sqlite.string("SELECT selectedProfileId FROM agent_versions"))
        assertEquals(1, sqlite.scalarInt("SELECT COUNT(*) FROM agent_version_packages"))
        assertEquals(1, sqlite.scalarInt("SELECT COUNT(*) FROM agent_chunks"))
        assertEquals(1, sqlite.scalarInt("SELECT COUNT(*) FROM agent_chunk_fts"))
        assertEquals(1, sqlite.scalarInt("SELECT COUNT(*) FROM agent_source_files"))
        assertEquals(1, sqlite.scalarInt("SELECT COUNT(*) FROM agent_hierarchy_nodes"))
        assertEquals(0, sqlite.scalarInt("SELECT COUNT(*) FROM agent_memories"))
        assertEquals(0, sqlite.scalarInt("SELECT COUNT(*) FROM pragma_foreign_key_list('agent_memories')"))
        sqlite.assertNoForeignKeyViolations()

        migrated.agentMemoryDao().insert(
            AgentMemoryEntity(
                id = "memory-1",
                agentId = "agent-v2",
                kind = "USER_PREFERENCE",
                content = "默认使用中文",
                sourceConversationId = "daily-conversation",
                sourceMessageId = "daily-message",
                confidence = 0.9,
                userEdited = false,
                createdAt = 3L,
                updatedAt = 3L,
            ),
        )
        sqlite.execSQL("DELETE FROM conversations WHERE id = 'daily-conversation'")
        sqlite.execSQL("DELETE FROM agents WHERE id = 'agent-v2'")

        assertEquals(1, migrated.agentMemoryDao().listForAgent("agent-v2").size)
        sqlite.assertNoForeignKeyViolations()
        migrated.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test
    fun migratesRealVersion11FixtureAndPreservesV1DataAndSearch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-11-12-${System.nanoTime()}.db"
        createVersion11Fixture(context, name, conflictingChunk = false)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
            )
            .build()
        val sqlite = db.openHelper.writableDatabase

        assertEquals(16, sqlite.version)
        assertEquals(
            1,
            sqlite.scalarInt(
                """
                SELECT COUNT(*) FROM conversations
                WHERE id = 'ordinary-daily' AND agentId IS NULL AND agentVersion IS NULL
                    AND projectId IS NULL
                """.trimIndent(),
            ),
        )
        assertEquals(
            1,
            sqlite.scalarInt(
                """
                SELECT COUNT(*) FROM conversations
                WHERE id = 'persona-daily' AND agentId = 'agent-1' AND agentVersion = 1
                    AND projectId IS NULL
                """.trimIndent(),
            ),
        )
        assertEquals(
            1,
            sqlite.scalarInt(
                """
                SELECT COUNT(*) FROM conversations
                WHERE id = 'ordinary-project' AND agentId IS NULL AND agentVersion IS NULL
                    AND projectId = 'project-1'
                """.trimIndent(),
            ),
        )
        assertEquals(
            1,
            sqlite.scalarInt(
                """
                SELECT COUNT(*) FROM conversations
                WHERE id = 'conversation-1' AND agentId = 'agent-1' AND agentVersion = 1
                    AND projectId = 'project-1'
                """.trimIndent(),
            ),
        )
        assertEquals(4, sqlite.scalarInt("SELECT COUNT(*) FROM conversations"))
        assertEquals(4, sqlite.scalarInt("SELECT COUNT(*) FROM messages"))
        assertEquals(5, sqlite.scalarInt("SELECT COUNT(*) FROM message_parts"))
        mapOf(
            "message-1" to "conversation-1|ASSISTANT|保留消息",
            "message-ordinary-daily" to "ordinary-daily|USER|普通日常消息",
            "message-persona-daily" to "persona-daily|USER|人物日常消息",
            "message-ordinary-project" to "ordinary-project|ASSISTANT|普通项目消息",
        ).forEach { (messageId, expected) ->
            assertEquals(
                expected,
                sqlite.string(
                    """
                    SELECT conversationId || '|' || role || '|' || content
                    FROM messages WHERE id = '$messageId'
                    """.trimIndent(),
                ),
            )
        }
        mapOf(
            "message-part-1" to "message-1|0|TEXT|保留分片",
            "part-ordinary-daily" to "message-ordinary-daily|0|TEXT|普通日常分片",
            "part-persona-daily" to "message-persona-daily|0|TEXT|人物日常分片",
            "part-ordinary-project" to "message-ordinary-project|0|TEXT|普通项目分片",
        ).forEach { (partId, expected) ->
            assertEquals(
                expected,
                sqlite.string(
                    """
                    SELECT messageId || '|' || partIndex || '|' || type || '|' || content
                    FROM message_parts WHERE id = '$partId'
                    """.trimIndent(),
                ),
            )
        }
        assertEquals(
            "message-1|1|AGENT_SOURCES",
            sqlite.string(
                """
                SELECT messageId || '|' || partIndex || '|' || type
                FROM message_parts WHERE id = 'message-part-legacy-source'
                """.trimIndent(),
            ),
        )
        mapOf(
            "conversation-1" to "project-1|notes/fixture.md",
            "ordinary-project" to "project-1|notes/ordinary-project.md",
        ).forEach { (conversationId, expected) ->
            assertEquals(
                expected,
                sqlite.string(
                    """
                    SELECT projectId || '|' || relativePath FROM conversation_markdown_links
                    WHERE conversationId = '$conversationId'
                    """.trimIndent(),
                ),
            )
        }
        assertEquals(2, sqlite.scalarInt("SELECT COUNT(*) FROM conversation_markdown_links"))
        assertEquals(1, sqlite.scalarInt("SELECT requiredCorpusCount FROM agent_versions WHERE agentId = 'agent-1' AND version = 1"))
        assertEquals(0, sqlite.scalarInt("SELECT agentPackageSizeBytes FROM agent_versions WHERE agentId = 'agent-1' AND version = 1"))
        assertEquals("", sqlite.string("SELECT selectedProfileId FROM agent_versions WHERE agentId = 'agent-1' AND version = 1"))
        assertEquals(0, sqlite.scalarInt("SELECT COUNT(*) FROM agent_version_packages"))
        assertEquals(0, sqlite.scalarInt("SELECT COUNT(*) FROM agent_corpus_sources"))
        assertEquals(0, sqlite.scalarInt("SELECT COUNT(*) FROM agent_corpus_hierarchy"))
        assertEquals(0, sqlite.scalarInt("SELECT COUNT(*) FROM agent_memories"))
        assertEquals(
            "1|required",
            sqlite.string(
                """
                SELECT required || '|' || installClass FROM agent_version_corpora
                WHERE agentId = 'agent-1' AND version = 1 AND corpusId = 'corpus-core'
                """.trimIndent(),
            ),
        )
        assertEquals(
            "0|optional",
            sqlite.string(
                """
                SELECT required || '|' || installClass FROM agent_version_corpora
                WHERE agentId = 'agent-1' AND version = 1 AND corpusId = 'corpus-full'
                """.trimIndent(),
            ),
        )
        assertEquals("", sqlite.string("SELECT conflictKey FROM agent_chunks LIMIT 1"))
        assertEquals("[]", sqlite.string("SELECT sourceAliasesJson FROM agent_chunks LIMIT 1"))
        assertEquals("", sqlite.string("SELECT simHash FROM agent_chunks LIMIT 1"))
        assertEquals(1, db.agentDao().countLegacyAgentSourceParts("agent-1", 1))
        assertEquals("provider-1", sqlite.string("SELECT id FROM provider_profiles"))
        assertEquals("V1 persona", db.agentDao().findVersion("agent-1", 1)?.persona)
        assertEquals("", db.agentDao().findVersion("agent-1", 1)?.identityJson)
        assertEquals(1, sqlite.scalarInt("SELECT COUNT(*) FROM agent_chunks"))
        assertEquals(1, sqlite.scalarInt("SELECT COUNT(*) FROM agent_chunk_fts"))
        assertEquals(2, sqlite.scalarInt("SELECT COUNT(*) FROM agent_corpus_chunks"))
        assertEquals("source-hash", sqlite.string("SELECT sourceId FROM agent_chunks"))
        assertEquals(
            "调查 legacy_core_only legacy_shared 事实 legacy_full_only legacy_shared",
            sqlite.string("SELECT searchableText FROM agent_chunk_fts"),
        )
        assertEquals(
            listOf("source-hash:chunk-1"),
            db.agentDao().searchChunkKeys(
                listOf("corpus-core:source-hash", "corpus-full:source-hash"),
                "调查",
                "",
                8,
            ),
        )
        listOf("legacy_core_only", "legacy_full_only").forEach { legacyNgram ->
            assertEquals(
                listOf("source-hash:chunk-1"),
                db.agentDao().searchChunkKeys(
                    listOf("corpus-core:source-hash", "corpus-full:source-hash"),
                    legacyNgram,
                    "",
                    8,
                ),
            )
        }
        sqlite.assertNoForeignKeyViolations()
        db.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test
    fun migratedV1PhysicalChunkIsReusedByNormalInstallTransaction() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-11-12-reinstall-${System.nanoTime()}.db"
        createVersion11Fixture(context, name, conflictingChunk = false)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
            )
            .build()
        db.openHelper.writableDatabase
        val root = context.cacheDir.resolve("migration-reinstall-${System.nanoTime()}").apply { mkdirs() }
        val fixture = repositoryFixture(
            db = db,
            root = root,
            chunk = AgentCorpusChunk(
                id = "chunk-1",
                sourceTitle = "测试资料",
                sourceHash = "source-hash",
                location = "第一章",
                text = "调查以后再下结论",
                keywords = listOf("调查", "事实"),
                ngrams = listOf("legacy_core_only"),
            ),
        )

        fixture.repository.install(fixture.session)

        assertEquals(1, db.scalarInt("SELECT COUNT(*) FROM agent_chunks"))
        assertEquals(1, db.scalarInt("SELECT COUNT(*) FROM agent_chunk_fts"))
        assertEquals(3, db.scalarInt("SELECT COUNT(*) FROM agent_corpus_chunks"))
        assertEquals(1, db.scalarInt("SELECT COUNT(*) FROM agent_corpus_chunks WHERE corpusId = 'corpus-new'"))
        assertEquals("source-hash", db.openHelper.readableDatabase.string("SELECT sourceId FROM agent_chunks"))
        fixture.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test
    fun migrationFailsWhenCollapsedPhysicalEvidenceConflicts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-11-12-conflict-${System.nanoTime()}.db"
        createVersion11Fixture(context, name, conflictingChunk = true)

        try {
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(
                    AppDatabase.MIGRATION_11_12,
                    AppDatabase.MIGRATION_12_13,
                    AppDatabase.MIGRATION_13_14,
                    AppDatabase.MIGRATION_14_15,
                    AppDatabase.MIGRATION_15_16,
                )
                .build()
                .openHelper
                .writableDatabase
            fail("Expected conflicting physical evidence to fail migration")
        } catch (expected: Throwable) {
            assertTrue(expected.stackTraceToString().contains("conflicting physical chunk"))
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun storesConversation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val entity = ConversationEntity(
            id = "conversation-1",
            title = "测试会话",
            createdAt = 1L,
            updatedAt = 1L,
            defaultProviderId = null,
            defaultModel = null,
            isArchived = false,
            projectId = null,
            promptOriginal = "",
            promptOptimized = "",
            promptFinal = "",
        )

        db.conversationDao().insert(entity)

        assertEquals(listOf(entity), db.conversationDao().observeActive().first())
        db.close()
    }

    @Test
    fun latestActiveConversationIncludesAssistantAndKeepsProjectScope() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dao = db.conversationDao()

        dao.insert(conversation(id = "older-person", updatedAt = 10L, agentId = "agent-1"))
        dao.insert(conversation(id = "recent-assistant", updatedAt = 20L))
        assertEquals("recent-assistant", dao.findLatestActive()!!.id)

        dao.insert(conversation(id = "project-old-person", updatedAt = 30L, projectId = "project-1", agentId = "agent-1"))
        dao.insert(conversation(id = "project-recent-assistant", updatedAt = 40L, projectId = "project-1"))
        dao.insert(conversation(id = "other-project-person", updatedAt = 50L, projectId = "project-2", agentId = "agent-2"))

        assertEquals("other-project-person", dao.findLatestActive()!!.id)
        assertEquals("project-recent-assistant", dao.findLatestActiveInProject("project-1")!!.id)

        dao.insert(conversation(id = "tie-a", updatedAt = 60L))
        dao.insert(conversation(id = "tie-z", updatedAt = 60L, agentId = "agent-3"))

        assertEquals("tie-z", dao.findLatestActive()!!.id)
        db.close()
    }

    @Test
    fun storesAllLegalProjectAndAgentIdentityCombinations() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val combinations = listOf(
            Triple<String?, String?, Int?>(null, null, null),
            Triple("p1", null, null),
            Triple(null, "a1", 1),
            Triple("p1", "a1", 1),
        )

        combinations.forEachIndexed { index, (projectId, agentId, agentVersion) ->
            db.conversationDao().insert(
                ConversationEntity(
                    id = "combination-$index",
                    title = "组合测试 $index",
                    createdAt = index.toLong(),
                    updatedAt = index.toLong(),
                    defaultProviderId = null,
                    defaultModel = null,
                    isArchived = false,
                    projectId = projectId,
                    promptOriginal = "",
                    promptOptimized = "",
                    promptFinal = "",
                    agentId = agentId,
                    agentVersion = agentVersion,
                ),
            )
        }

        val stored = db.conversationDao().observeActive().first().associateBy { it.id }
        assertEquals(4, stored.size)
        combinations.forEachIndexed { index, (projectId, agentId, agentVersion) ->
            val conversation = requireNotNull(stored["combination-$index"])
            assertEquals(projectId, conversation.projectId)
            assertEquals(agentId, conversation.agentId)
            assertEquals(agentVersion, conversation.agentVersion)
        }
        db.close()
    }

    @Test
    fun rejectsIdentityUpdateWhenConversationAlreadyHasUserMessage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val conversation = ConversationEntity(
            id = "identity-locked",
            title = "身份锁定测试",
            createdAt = 1L,
            updatedAt = 1L,
            defaultProviderId = null,
            defaultModel = null,
            isArchived = false,
            projectId = null,
            promptOriginal = "",
            promptOptimized = "",
            promptFinal = "",
            agentId = "agent-1",
            agentVersion = 1,
        )
        db.conversationDao().insert(conversation)
        db.messageDao().insert(
            MessageEntity(
                id = "user-1",
                conversationId = conversation.id,
                role = MessageRole.USER.name,
                content = "你好",
                status = MessageStatus.SUCCEEDED.name,
                providerId = null,
                model = null,
                createdAt = 2L,
                updatedAt = 2L,
                errorCode = null,
                errorMessage = null,
            ),
        )

        val updated = db.conversationDao().updateIdentityIfNoUserMessages(
            id = conversation.id,
            agentId = "agent-2",
            agentVersion = 2,
            updatedAt = 3L,
        )

        assertEquals(0, updated)
        assertEquals("agent-1", db.conversationDao().findById(conversation.id)!!.agentId)
        assertEquals(1, db.conversationDao().findById(conversation.id)!!.agentVersion)
        db.close()
    }

    @Test
    fun storesConversationMemory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        db.conversationDao().insert(
            ConversationEntity(
                id = "conversation-memory",
                title = "记忆测试",
                createdAt = 1L,
                updatedAt = 1L,
                defaultProviderId = null,
                defaultModel = null,
                isArchived = false,
                projectId = null,
                promptOriginal = "",
                promptOptimized = "",
                promptFinal = "",
            ),
        )
        val memory = ConversationMemoryEntity(
            conversationId = "conversation-memory",
            summary = "- 用户：需要简洁回答",
            coveredThroughMessageId = "m1",
            coveredThroughCreatedAt = 10L,
            compressedMessageCount = 3,
            updatedAt = 20L,
        )

        db.conversationMemoryDao().upsert(memory)

        assertEquals(memory, db.conversationMemoryDao().findByConversationId("conversation-memory"))
        db.close()
    }

    @Test
    fun storesProviderAvailableModels() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val provider = ProviderProfileEntity(
            id = "provider",
            name = "OpenAI",
            baseUrl = "https://happycode.vip/v1",
            apiKeyAlias = "provider:provider",
            encryptedApiKey = "key".encodeToByteArray(),
            apiKeyIv = "iv".encodeToByteArray(),
            defaultModel = "gpt-5.5",
            availableModels = "gpt-5.5\ngpt-5.5-pro",
            defaultVisionModel = "gpt-5.5",
            supportsVision = true,
            nativeWebSearchMode = NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS.name,
            enabled = true,
            createdAt = 1L,
            updatedAt = 1L,
        )

        db.providerProfileDao().insert(provider)

        val stored = db.providerProfileDao().findById("provider")!!
        assertEquals("OpenAI", stored.name)
        assertEquals("gpt-5.5\ngpt-5.5-pro", stored.availableModels)
        db.close()
    }

    @Test
    fun storesMessageParts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        db.conversationDao().insert(
            ConversationEntity(
                id = "conversation-parts",
                title = "分片测试",
                createdAt = 1L,
                updatedAt = 1L,
                defaultProviderId = null,
                defaultModel = null,
                isArchived = false,
                projectId = null,
                promptOriginal = "",
                promptOptimized = "",
                promptFinal = "",
            ),
        )
        db.messageDao().insert(
            MessageEntity(
                id = "message-parts",
                conversationId = "conversation-parts",
                role = MessageRole.ASSISTANT.name,
                content = "可见答案",
                status = MessageStatus.STREAMING.name,
                providerId = "openai",
                model = "gpt-5.5",
                createdAt = 2L,
                updatedAt = 2L,
                errorCode = null,
                errorMessage = null,
            ),
        )
        val parts = listOf(
            MessagePartEntity(
                id = "part-1",
                messageId = "message-parts",
                partIndex = 0,
                type = UiMessagePartType.REASONING.name,
                content = "内部推理",
                metadataJson = "",
                stable = true,
                createdAt = 2L,
                updatedAt = 3L,
            ),
            MessagePartEntity(
                id = "part-2",
                messageId = "message-parts",
                partIndex = 1,
                type = UiMessagePartType.TEXT.name,
                content = "可见答案",
                metadataJson = "",
                stable = false,
                createdAt = 2L,
                updatedAt = 3L,
            ),
        )

        db.messagePartDao().replaceForMessage("message-parts", parts)

        assertEquals(parts, db.messagePartDao().listForMessage("message-parts"))
        db.close()
    }

    @Test
    fun storesChatExecutionEntry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        db.conversationDao().insert(
            ConversationEntity(
                id = "conversation-queue",
                title = "队列测试",
                createdAt = 1L,
                updatedAt = 1L,
                defaultProviderId = null,
                defaultModel = null,
                isArchived = false,
                projectId = null,
                promptOriginal = "",
                promptOptimized = "",
                promptFinal = "",
            ),
        )
        db.messageDao().insert(
            MessageEntity(
                id = "message-queue",
                conversationId = "conversation-queue",
                role = MessageRole.USER.name,
                content = "排队消息",
                status = MessageStatus.SUCCEEDED.name,
                providerId = null,
                model = null,
                createdAt = 2L,
                updatedAt = 2L,
                errorCode = null,
                errorMessage = null,
            ),
        )
        val entry = ChatExecutionEntryEntity(
            id = "entry-queue",
            conversationId = "conversation-queue",
            userMessageId = "message-queue",
            assistantMessageId = null,
            targetAssistantMessageId = null,
            sequence = 1L,
            type = "NORMAL",
            status = "QUEUED",
            providerId = "provider",
            model = "model",
            reasoningEffort = "HIGH",
            requestContextJson = "{}",
            errorMessage = null,
            createdAt = 2L,
            updatedAt = 2L,
        )

        db.chatExecutionEntryDao().insert(entry)

        assertEquals(listOf(entry), db.chatExecutionEntryDao().listForConversation("conversation-queue"))
        db.close()
    }

    private fun createVersion15Fixture(
        context: Context,
        name: String,
    ) {
        createVersion11Fixture(context, name, conflictingChunk = false)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(15) {
                        override fun onConfigure(db: SupportSQLiteDatabase) {
                            db.setForeignKeyConstraintsEnabled(true)
                        }

                        override fun onCreate(db: SupportSQLiteDatabase) {
                            error("version 11 fixture must already exist")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            check(oldVersion == 11 && newVersion == 15)
                            AppDatabase.MIGRATION_11_12.migrate(db)
                            AppDatabase.MIGRATION_12_13.migrate(db)
                            AppDatabase.MIGRATION_13_14.migrate(db)
                            AppDatabase.MIGRATION_14_15.migrate(db)
                        }
                    },
                )
                .build(),
        )
        val db = helper.writableDatabase
        check(db.version == 15)
        val sourceHash = "a".repeat(64)

        db.execSQL(
            """
            INSERT INTO conversations (
                id, title, createdAt, updatedAt, defaultProviderId, defaultModel, isArchived,
                projectId, promptOriginal, promptOptimized, promptFinal, agentId, agentVersion
            ) VALUES ('daily-conversation', '日常会话', 1, 20, NULL, NULL, 0,
                NULL, '', '', '', 'agent-v2', 2)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO conversations (
                id, title, createdAt, updatedAt, defaultProviderId, defaultModel, isArchived,
                projectId, promptOriginal, promptOptimized, promptFinal, agentId, agentVersion
            ) VALUES ('project-conversation', '项目会话', 1, 10, NULL, NULL, 0,
                'project-1', '', '', '', 'agent-v2', 2)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO messages (
                id, conversationId, role, content, status, providerId, model,
                createdAt, updatedAt, errorCode, errorMessage
            ) VALUES ('daily-message', 'daily-conversation', 'USER', '默认使用中文',
                'SUCCEEDED', 'provider-1', 'model-1', 1, 1, NULL, NULL)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO conversation_markdown_links (
                conversationId, projectId, relativePath, linkedAt, updatedAt
            ) VALUES ('project-conversation', 'project-1', 'notes/project.md', 2, 2)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agents (
                id, name, summary, activeVersion, publisherPublicKey, publisherFingerprint,
                installSource, status, requiredCorpusCount, installedCorpusCount, createdAt, updatedAt
            ) VALUES ('agent-v2', '测试人物', '', 2, X'01', 'publisher',
                'LOCAL_FILE', 'READY', 1, 1, 1, 2)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_versions (
                agentId, version, schemaVersion, bundlePath, bundleSha256, manifestJson,
                persona, worldviewJsonl, installedAt, state, identityJson, voiceJson,
                installPlanJson, requiredCorpusCount, agentPackageSizeBytes, selectedProfileId
            ) VALUES ('agent-v2', 2, 2, '/tmp/agent.hagent', '${"b".repeat(64)}', '{}',
                '第一人称', '', 2, 'READY', '{}', '{}', '{}', 1, 100, 'balanced')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_version_packages (
                agentId, version, packageId, type, fileName, installClass, packageSha256,
                packageSizeBytes, installed, filePath, installedAt
            ) VALUES ('agent-v2', 2, 'core', 'corpus', 'core.hcorpus', 'required',
                '${"c".repeat(64)}', 50, 1, '/tmp/core.hcorpus', 2)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_corpora (corpusId, sourceHash, title, indexedAt, sizeBytes)
            VALUES ('core', '$sourceHash', '核心资料', 2, 50)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_version_corpora (
                agentId, version, corpusId, sourceHash, required, installClass,
                packageSha256, packageSizeBytes, installedAt
            ) VALUES ('agent-v2', 2, 'core', '$sourceHash', 1, 'required',
                '${"c".repeat(64)}', 50, 2)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_source_files (
                sourceId, sourceHash, title, fileName, storedName, format, genre,
                authorship, period, rawSizeBytes, filePath, packageSha256, installedAt
            ) VALUES ('source-v2', '$sourceHash', '来源', 'source.md', 'source.md', 'md',
                'conversation', 'direct', '2026', 20, '/tmp/source.md',
                '${"d".repeat(64)}', 2)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_version_sources (agentId, version, sourceId, sourceHash)
            VALUES ('agent-v2', 2, 'source-v2', '$sourceHash')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_corpus_sources (corpusId, corpusHash, sourceId, sourceHash)
            VALUES ('core', '$sourceHash', 'source-v2', '$sourceHash')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_chunks (
                chunkKey, sourceId, sourceHash, chunkId, sourceTitle, period, genre,
                authorship, location, parentPath, context, text, keywordsText,
                duplicateGroup, conflictKey, sourceAliasesJson, simHash
            ) VALUES ('source-v2:chunk-1', 'source-v2', '$sourceHash', 'chunk-1', '来源',
                '2026', 'conversation', 'direct', '第一段', '', '', '默认使用中文',
                '默认 中文', '', '', '[]', '')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_corpus_chunks (corpusId, corpusHash, chunkKey)
            VALUES ('core', '$sourceHash', 'source-v2:chunk-1')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_chunk_fts (chunkKey, searchableText)
            VALUES ('source-v2:chunk-1', '默认 中文')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_hierarchy_nodes (
                nodeKey, sourceId, sourceHash, nodeId, kind, title, parentNodeKey, path, summary
            ) VALUES ('source-v2:root', 'source-v2', '$sourceHash', 'root', 'document',
                '来源', NULL, '来源', '摘要')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_hierarchy_fts (nodeKey, searchableText)
            VALUES ('source-v2:root', '来源 摘要')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_corpus_hierarchy (corpusId, corpusHash, nodeKey)
            VALUES ('core', '$sourceHash', 'source-v2:root')
            """.trimIndent(),
        )
        db.query("PRAGMA foreign_key_check").use { cursor ->
            check(!cursor.moveToFirst())
        }
        helper.close()
    }

    private fun createVersion11Fixture(
        context: Context,
        name: String,
        conflictingChunk: Boolean,
    ) {
        context.deleteDatabase(name)
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        db.execSQL("PRAGMA foreign_keys=ON")
        VERSION_11_SCHEMA.forEach(db::execSQL)
        db.execSQL(
            """
            INSERT INTO conversations (
                id, title, createdAt, updatedAt, defaultProviderId, defaultModel, isArchived,
                projectId, promptOriginal, promptOptimized, promptFinal, agentId, agentVersion
            ) VALUES ('conversation-1', '迁移会话', 1, 2, 'provider-1', 'model-1', 0,
                'project-1', '原始', '优化', '最终', 'agent-1', 1)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO conversations (
                id, title, createdAt, updatedAt, defaultProviderId, defaultModel, isArchived,
                projectId, promptOriginal, promptOptimized, promptFinal, agentId, agentVersion
            ) VALUES ('ordinary-daily', '普通日常', 1, 2, NULL, NULL, 0,
                NULL, '', '', '', NULL, NULL)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO conversations (
                id, title, createdAt, updatedAt, defaultProviderId, defaultModel, isArchived,
                projectId, promptOriginal, promptOptimized, promptFinal, agentId, agentVersion
            ) VALUES ('persona-daily', '人物日常', 1, 2, 'provider-1', 'model-1', 0,
                NULL, '', '', '', 'agent-1', 1)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO conversations (
                id, title, createdAt, updatedAt, defaultProviderId, defaultModel, isArchived,
                projectId, promptOriginal, promptOptimized, promptFinal, agentId, agentVersion
            ) VALUES ('ordinary-project', '普通项目', 1, 2, NULL, NULL, 0,
                'project-1', '', '', '', NULL, NULL)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO messages (
                id, conversationId, role, content, status, providerId, model,
                createdAt, updatedAt, errorCode, errorMessage
            ) VALUES ('message-1', 'conversation-1', 'ASSISTANT', '保留消息', 'SUCCEEDED',
                'provider-1', 'model-1', 2, 3, NULL, NULL)
            """.trimIndent(),
        )
        listOf(
            Triple("ordinary-daily", "普通日常消息", "USER"),
            Triple("persona-daily", "人物日常消息", "USER"),
            Triple("ordinary-project", "普通项目消息", "ASSISTANT"),
        ).forEach { (conversationId, content, role) ->
            db.execSQL(
                """
                INSERT INTO messages (
                    id, conversationId, role, content, status, providerId, model,
                    createdAt, updatedAt, errorCode, errorMessage
                ) VALUES ('message-$conversationId', '$conversationId', '$role', '$content',
                    'SUCCEEDED', NULL, NULL, 2, 3, NULL, NULL)
                """.trimIndent(),
            )
        }
        db.execSQL(
            """
            INSERT INTO message_parts (
                id, messageId, partIndex, type, content, metadataJson, stable, createdAt, updatedAt
            ) VALUES ('message-part-1', 'message-1', 0, 'TEXT', '保留分片', '{}', 1, 2, 3)
            """.trimIndent(),
        )
        listOf(
            "ordinary-daily" to "普通日常分片",
            "persona-daily" to "人物日常分片",
            "ordinary-project" to "普通项目分片",
        ).forEach { (conversationId, content) ->
            db.execSQL(
                """
                INSERT INTO message_parts (
                    id, messageId, partIndex, type, content, metadataJson, stable,
                    createdAt, updatedAt
                ) VALUES ('part-$conversationId', 'message-$conversationId', 0, 'TEXT',
                    '$content', '{}', 1, 2, 3)
                """.trimIndent(),
            )
        }
        db.execSQL(
            """
            INSERT INTO message_parts (
                id, messageId, partIndex, type, content, metadataJson, stable, createdAt, updatedAt
            ) VALUES ('message-part-legacy-source', 'message-1', 1, 'AGENT_SOURCES', '旧资料', '', 1, 2, 3)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO provider_profiles (
                id, name, baseUrl, apiKeyAlias, encryptedApiKey, apiKeyIv, defaultModel,
                availableModels, defaultVisionModel, supportsVision, nativeWebSearchMode,
                enabled, createdAt, updatedAt, customHeadersJson, customBodyJson
            ) VALUES ('provider-1', 'Provider', 'https://example.com', 'key', NULL, NULL,
                'model-1', 'model-1', NULL, 0, 'DISABLED', 1, 1, 1, '', '')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO conversation_markdown_links (
                conversationId, projectId, relativePath, linkedAt, updatedAt
            ) VALUES ('conversation-1', 'project-1', 'notes/fixture.md', 2, 3)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO conversation_markdown_links (
                conversationId, projectId, relativePath, linkedAt, updatedAt
            ) VALUES ('ordinary-project', 'project-1', 'notes/ordinary-project.md', 2, 3)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agents (
                id, name, summary, activeVersion, publisherPublicKey, publisherFingerprint,
                installSource, status, requiredCorpusCount, installedCorpusCount, createdAt, updatedAt
            ) VALUES ('agent-1', 'V1 人格', '迁移人格', 1, X'010203', 'fingerprint',
                'LOCAL_FILE', 'READY', 1, 2, 1, 2)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_versions (
                agentId, version, schemaVersion, bundlePath, bundleSha256, manifestJson,
                persona, worldviewJsonl, installedAt, state
            ) VALUES ('agent-1', 1, 1, '/tmp/v1.hbundle', 'bundle-sha', '{}',
                'V1 persona', '', 2, 'READY')
            """.trimIndent(),
        )
        listOf("corpus-core", "corpus-full").forEach { corpusId ->
            db.execSQL(
                """
                INSERT INTO agent_corpora (corpusId, sourceHash, title, indexedAt, sizeBytes)
                VALUES ('$corpusId', 'source-hash', '$corpusId', 2, 100)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO agent_version_corpora (
                    agentId, version, corpusId, sourceHash, required
                ) VALUES ('agent-1', 1, '$corpusId', 'source-hash', ${if (corpusId == "corpus-core") 1 else 0})
                """.trimIndent(),
            )
        }
        db.execSQL(
            """
            INSERT INTO agent_chunks (
                chunkKey, corpusId, sourceHash, chunkId, sourceTitle, location, text, keywordsText
            ) VALUES ('corpus-core:source-hash:chunk-1', 'corpus-core', 'source-hash',
                'chunk-1', '测试资料', '第一章', '调查以后再下结论', '调查 事实')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_chunks (
                chunkKey, corpusId, sourceHash, chunkId, sourceTitle, location, text, keywordsText
            ) VALUES ('corpus-full:source-hash:chunk-1', 'corpus-full', 'source-hash',
                'chunk-1', '测试资料', '第一章',
                '${if (conflictingChunk) "冲突文本" else "调查以后再下结论"}', '调查 事实')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_chunk_fts (chunkKey, corpusKey, searchableText)
            VALUES ('corpus-core:source-hash:chunk-1', 'corpus-core:source-hash',
                '调查 legacy_core_only legacy_shared')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO agent_chunk_fts (chunkKey, corpusKey, searchableText)
            VALUES ('corpus-full:source-hash:chunk-1', 'corpus-full:source-hash',
                '事实 legacy_full_only legacy_shared')
            """.trimIndent(),
        )
        db.version = 11
        db.close()
    }

    private fun conversation(
        id: String,
        updatedAt: Long,
        projectId: String? = null,
        agentId: String? = null,
    ) = ConversationEntity(
        id = id,
        title = id,
        createdAt = 1L,
        updatedAt = updatedAt,
        defaultProviderId = null,
        defaultModel = null,
        isArchived = false,
        projectId = projectId,
        promptOriginal = "",
        promptOptimized = "",
        promptFinal = "",
        agentId = agentId,
        agentVersion = agentId?.let { 1 },
    )

    companion object {
        private val VERSION_11_SCHEMA = listOf(
            "CREATE TABLE conversations (id TEXT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, defaultProviderId TEXT, defaultModel TEXT, isArchived INTEGER NOT NULL, projectId TEXT, promptOriginal TEXT NOT NULL, promptOptimized TEXT NOT NULL, promptFinal TEXT NOT NULL, agentId TEXT, agentVersion INTEGER, PRIMARY KEY(id))",
            "CREATE TABLE messages (id TEXT NOT NULL, conversationId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL, providerId TEXT, model TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, errorCode TEXT, errorMessage TEXT, PRIMARY KEY(id), FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)",
            "CREATE INDEX index_messages_conversationId ON messages(conversationId)",
            "CREATE TABLE message_attachments (id TEXT NOT NULL, messageId TEXT NOT NULL, type TEXT NOT NULL, uri TEXT NOT NULL, mimeType TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE)",
            "CREATE INDEX index_message_attachments_messageId ON message_attachments(messageId)",
            "CREATE TABLE message_parts (id TEXT NOT NULL, messageId TEXT NOT NULL, partIndex INTEGER NOT NULL, type TEXT NOT NULL, content TEXT NOT NULL, metadataJson TEXT NOT NULL, stable INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE)",
            "CREATE INDEX index_message_parts_messageId ON message_parts(messageId)",
            "CREATE UNIQUE INDEX index_message_parts_messageId_partIndex ON message_parts(messageId, partIndex)",
            "CREATE TABLE provider_profiles (id TEXT NOT NULL, name TEXT NOT NULL, baseUrl TEXT NOT NULL, apiKeyAlias TEXT NOT NULL, encryptedApiKey BLOB, apiKeyIv BLOB, defaultModel TEXT NOT NULL, availableModels TEXT NOT NULL, defaultVisionModel TEXT, supportsVision INTEGER NOT NULL, nativeWebSearchMode TEXT NOT NULL, enabled INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, customHeadersJson TEXT NOT NULL, customBodyJson TEXT NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE conversation_memory (conversationId TEXT NOT NULL, summary TEXT NOT NULL, coveredThroughMessageId TEXT, coveredThroughCreatedAt INTEGER NOT NULL, compressedMessageCount INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(conversationId), FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)",
            "CREATE TABLE chat_execution_entries (id TEXT NOT NULL, conversationId TEXT NOT NULL, userMessageId TEXT NOT NULL, assistantMessageId TEXT, targetAssistantMessageId TEXT, sequence INTEGER NOT NULL, type TEXT NOT NULL, status TEXT NOT NULL, providerId TEXT, model TEXT, reasoningEffort TEXT NOT NULL, requestContextJson TEXT NOT NULL, errorMessage TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE, FOREIGN KEY(userMessageId) REFERENCES messages(id) ON DELETE CASCADE)",
            "CREATE INDEX index_chat_execution_entries_conversationId ON chat_execution_entries(conversationId)",
            "CREATE INDEX index_chat_execution_entries_userMessageId ON chat_execution_entries(userMessageId)",
            "CREATE UNIQUE INDEX index_chat_execution_entries_conversationId_sequence ON chat_execution_entries(conversationId, sequence)",
            "CREATE TABLE conversation_markdown_links (conversationId TEXT NOT NULL, projectId TEXT NOT NULL, relativePath TEXT NOT NULL, linkedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(conversationId, projectId, relativePath), FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)",
            "CREATE INDEX index_conversation_markdown_links_conversationId ON conversation_markdown_links(conversationId)",
            "CREATE INDEX index_conversation_markdown_links_projectId ON conversation_markdown_links(projectId)",
            "CREATE TABLE markdown_change_drafts (id TEXT NOT NULL, conversationId TEXT NOT NULL, projectId TEXT NOT NULL, sourceUserMessageId TEXT NOT NULL, assistantMessageId TEXT, status TEXT NOT NULL, summary TEXT NOT NULL, rawResponse TEXT, errorMessage TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)",
            "CREATE INDEX index_markdown_change_drafts_conversationId ON markdown_change_drafts(conversationId)",
            "CREATE INDEX index_markdown_change_drafts_projectId ON markdown_change_drafts(projectId)",
            "CREATE INDEX index_markdown_change_drafts_sourceUserMessageId ON markdown_change_drafts(sourceUserMessageId)",
            "CREATE TABLE markdown_change_draft_items (id TEXT NOT NULL, draftId TEXT NOT NULL, itemIndex INTEGER NOT NULL, operation TEXT NOT NULL, relativePath TEXT NOT NULL, title TEXT NOT NULL, reason TEXT NOT NULL, proposedMarkdown TEXT NOT NULL, retained INTEGER NOT NULL, baselineSha256 TEXT, expectedAbsent INTEGER NOT NULL, applyStatus TEXT, applyErrorMessage TEXT, PRIMARY KEY(id), FOREIGN KEY(draftId) REFERENCES markdown_change_drafts(id) ON DELETE CASCADE)",
            "CREATE INDEX index_markdown_change_draft_items_draftId ON markdown_change_draft_items(draftId)",
            "CREATE UNIQUE INDEX index_markdown_change_draft_items_draftId_itemIndex ON markdown_change_draft_items(draftId, itemIndex)",
            "CREATE TABLE agents (id TEXT NOT NULL, name TEXT NOT NULL, summary TEXT NOT NULL, activeVersion INTEGER NOT NULL, publisherPublicKey BLOB NOT NULL, publisherFingerprint TEXT NOT NULL, installSource TEXT NOT NULL, status TEXT NOT NULL, requiredCorpusCount INTEGER NOT NULL, installedCorpusCount INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE agent_versions (agentId TEXT NOT NULL, version INTEGER NOT NULL, schemaVersion INTEGER NOT NULL, bundlePath TEXT NOT NULL, bundleSha256 TEXT NOT NULL, manifestJson TEXT NOT NULL, persona TEXT NOT NULL, worldviewJsonl TEXT NOT NULL, installedAt INTEGER NOT NULL, state TEXT NOT NULL, PRIMARY KEY(agentId, version), FOREIGN KEY(agentId) REFERENCES agents(id) ON DELETE CASCADE)",
            "CREATE INDEX index_agent_versions_agentId ON agent_versions(agentId)",
            "CREATE TABLE agent_corpora (corpusId TEXT NOT NULL, sourceHash TEXT NOT NULL, title TEXT NOT NULL, indexedAt INTEGER NOT NULL, sizeBytes INTEGER NOT NULL, PRIMARY KEY(corpusId, sourceHash))",
            "CREATE TABLE agent_version_corpora (agentId TEXT NOT NULL, version INTEGER NOT NULL, corpusId TEXT NOT NULL, sourceHash TEXT NOT NULL, required INTEGER NOT NULL, PRIMARY KEY(agentId, version, corpusId, sourceHash), FOREIGN KEY(agentId, version) REFERENCES agent_versions(agentId, version) ON DELETE CASCADE, FOREIGN KEY(corpusId, sourceHash) REFERENCES agent_corpora(corpusId, sourceHash) ON DELETE CASCADE)",
            "CREATE INDEX index_agent_version_corpora_agentId_version ON agent_version_corpora(agentId, version)",
            "CREATE INDEX index_agent_version_corpora_corpusId_sourceHash ON agent_version_corpora(corpusId, sourceHash)",
            "CREATE TABLE agent_chunks (chunkKey TEXT NOT NULL, corpusId TEXT NOT NULL, sourceHash TEXT NOT NULL, chunkId TEXT NOT NULL, sourceTitle TEXT NOT NULL, location TEXT NOT NULL, text TEXT NOT NULL, keywordsText TEXT NOT NULL, PRIMARY KEY(chunkKey), FOREIGN KEY(corpusId, sourceHash) REFERENCES agent_corpora(corpusId, sourceHash) ON DELETE CASCADE)",
            "CREATE INDEX index_agent_chunks_corpusId_sourceHash ON agent_chunks(corpusId, sourceHash)",
            "CREATE VIRTUAL TABLE agent_chunk_fts USING FTS4(chunkKey TEXT NOT NULL, corpusKey TEXT NOT NULL, searchableText TEXT NOT NULL, tokenize=unicode61)",
        )
    }
}

private data class RepositoryConflictFixture(
    val db: AppDatabase,
    val root: File,
    val repository: AgentRepository,
    val session: AgentImportSession,
) {
    fun close() {
        db.close()
        root.deleteRecursively()
    }
}

private class RepositoryConflictReader(
    private val chunk: AgentCorpusChunk,
    private val bundle: ParsedAgentBundle,
) : AgentBundleAccess {
    override fun inspect(file: File): AgentImportPreview = AgentImportPreview(
        agentId = "agent-room-conflict",
        name = "事务测试代理",
        version = 1,
        summary = "",
        publisherFingerprint = "publisher",
        corpora = listOf("新语料"),
        compressedSizeBytes = file.length(),
        includesOriginalSources = false,
    )

    override fun read(file: File): ParsedAgentBundle = bundle.copy(file = file)

    override suspend fun forEachChunkSuspending(
        bundle: ParsedAgentBundle,
        corpus: AgentCorpusManifest,
        block: suspend (AgentCorpusChunk) -> Unit,
    ) {
        block(chunk)
    }
}

private fun AppDatabase.scalarInt(query: String): Int =
    openHelper.readableDatabase.query(query).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

private fun androidx.sqlite.db.SupportSQLiteDatabase.scalarInt(query: String): Int =
    query(query).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

private fun androidx.sqlite.db.SupportSQLiteDatabase.string(query: String): String =
    query(query).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

private fun androidx.sqlite.db.SupportSQLiteDatabase.assertNoForeignKeyViolations() {
    query("PRAGMA foreign_key_check").use { cursor ->
        assertFalse(cursor.moveToFirst())
    }
}
