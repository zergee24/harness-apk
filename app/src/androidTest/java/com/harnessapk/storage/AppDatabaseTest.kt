package com.harnessapk.storage

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
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
import com.harnessapk.agent.ParsedAgentBundle
import com.harnessapk.common.TimeProvider
import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.chat.MessageRole
import com.harnessapk.chat.MessageStatus
import com.harnessapk.chat.UiMessagePartType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
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
            limit = 8,
        )

        assertEquals(listOf("source-hash:chunk-1"), keys)
        assertEquals(1, db.scalarInt("SELECT COUNT(*) FROM agent_chunks"))
        assertEquals(1, db.scalarInt("SELECT COUNT(*) FROM agent_chunk_fts"))
        assertEquals(2, db.scalarInt("SELECT COUNT(*) FROM agent_corpus_chunks"))
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
    fun migratesRealVersion11FixtureAndPreservesV1DataAndSearch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-11-12-${System.nanoTime()}.db"
        createVersion11Fixture(context, name, conflictingChunk = false)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
            )
            .build()
        val sqlite = db.openHelper.writableDatabase

        assertEquals(14, sqlite.version)
        assertEquals(1, sqlite.scalarInt("SELECT requiredCorpusCount FROM agent_versions WHERE agentId = 'agent-1' AND version = 1"))
        assertEquals(0, sqlite.scalarInt("SELECT COUNT(*) FROM agent_version_packages"))
        assertEquals(0, sqlite.scalarInt("SELECT COUNT(*) FROM agent_corpus_sources"))
        assertEquals(0, sqlite.scalarInt("SELECT COUNT(*) FROM agent_corpus_hierarchy"))
        assertEquals("", sqlite.string("SELECT conflictKey FROM agent_chunks LIMIT 1"))
        assertEquals("[]", sqlite.string("SELECT sourceAliasesJson FROM agent_chunks LIMIT 1"))
        assertEquals("", sqlite.string("SELECT simHash FROM agent_chunks LIMIT 1"))
        assertEquals("conversation-1", sqlite.string("SELECT id FROM conversations"))
        assertEquals("message-part-1", sqlite.string("SELECT id FROM message_parts"))
        assertEquals("notes/fixture.md", sqlite.string("SELECT relativePath FROM conversation_markdown_links"))
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
                8,
            ),
        )
        listOf("legacy_core_only", "legacy_full_only").forEach { legacyNgram ->
            assertEquals(
                listOf("source-hash:chunk-1"),
                db.agentDao().searchChunkKeys(
                    listOf("corpus-core:source-hash", "corpus-full:source-hash"),
                    legacyNgram,
                    8,
                ),
            )
        }
        assertEquals(0, sqlite.scalarInt("SELECT COUNT(*) FROM pragma_foreign_key_check"))
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
            INSERT INTO messages (
                id, conversationId, role, content, status, providerId, model,
                createdAt, updatedAt, errorCode, errorMessage
            ) VALUES ('message-1', 'conversation-1', 'ASSISTANT', '保留消息', 'SUCCEEDED',
                'provider-1', 'model-1', 2, 3, NULL, NULL)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO message_parts (
                id, messageId, partIndex, type, content, metadataJson, stable, createdAt, updatedAt
            ) VALUES ('message-part-1', 'message-1', 0, 'TEXT', '保留分片', '{}', 1, 2, 3)
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
