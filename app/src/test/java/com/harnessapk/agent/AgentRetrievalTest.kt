package com.harnessapk.agent

import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AgentChunkEntity
import com.harnessapk.storage.AgentChunkFtsEntity
import com.harnessapk.storage.AgentCorpusEntity
import com.harnessapk.storage.AgentDao
import com.harnessapk.storage.AgentEntity
import com.harnessapk.storage.AgentVersionCorpusCrossRef
import com.harnessapk.storage.AgentVersionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentRetrievalTest {
    @Test
    fun purgesOnlyExpiredImportStagingFiles() {
        val directory = Files.createTempDirectory("agent-staging-test").toFile().apply { deleteOnExit() }
        val expired = directory.resolve("expired.hbundle").apply {
            writeText("expired")
            setLastModified(1L)
        }
        val current = directory.resolve("current.hbundle").apply {
            writeText("current")
            setLastModified(86_400_001L)
        }

        purgeStaleImportFiles(directory, nowMillis = 86_400_002L)

        assertFalse(expired.exists())
        assertTrue(current.exists())
    }

    @Test
    fun installsValidatedBundleIntoExistingAppStore() = runTest {
        val dao = FakeAgentDao()
        val root = Files.createTempDirectory("agent-install-test").toFile().apply { deleteOnExit() }
        val staged = root.resolve("staged.hbundle").apply { writeText("validated package") }
        val corpus = AgentCorpusManifest(
            id = "corpus-1",
            title = "测试资料",
            sourceHash = "corpus-hash",
            sourcesPath = "corpora/corpus-1/sources.json",
            chunksPath = "corpora/corpus-1/chunks.jsonl",
            required = true,
        )
        val parsed = ParsedAgentBundle(
            file = staged,
            packageSha256 = "bundle-sha",
            publisherPublicKey = byteArrayOf(1, 2, 3),
            publisherFingerprint = "publisher-fingerprint",
            manifestJson = "{}",
            agent = AgentPackageManifest(
                id = "agent-1",
                name = "资料研究代理",
                version = 1,
                summary = "基于资料模拟",
                personaPath = "agent/persona.md",
                worldviewPath = "agent/worldview.jsonl",
                conceptsPath = "agent/concepts.json",
                examplesPath = "agent/examples.jsonl",
                evalPath = "agent/eval.jsonl",
                requiredCorpora = listOf("corpus-1"),
            ),
            corpora = listOf(corpus),
            persona = "我只根据资料回答。",
            worldviewJsonl = "",
            compressedSizeBytes = staged.length(),
            uncompressedSizeBytes = 100L,
        )
        val reader = FakeAgentBundleAccess(
            chunks = listOf(
                AgentCorpusChunk(
                    id = "chunk-1",
                    sourceTitle = "测试资料",
                    sourceHash = "source-hash",
                    location = "第一章",
                    text = "研究问题必须从事实出发",
                    keywords = listOf("研究", "事实"),
                    ngrams = listOf("研究", "事实"),
                ),
            ),
        )
        val repository = AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = dao,
            reader = reader,
            timeProvider = TimeProvider { 20L },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val session = AgentImportSession(
            id = "session-1",
            stagedFile = staged,
            parsedBundle = parsed,
            preview = reader.inspect(staged),
        )

        val result = repository.install(session)

        assertEquals(AgentInstallOutcome.INSTALLED, result.outcome)
        assertEquals(AgentStatus.READY, result.agent.status)
        assertEquals(1, dao.versionCorpora.size)
        assertEquals(1, dao.chunks.size)
        assertEquals(1, dao.searchRows.size)
        assertTrue(dao.version!!.bundlePath.endsWith("agents/agent-1/1/bundle.hbundle"))
        assertTrue(File(dao.version!!.bundlePath).isFile)
        assertTrue(!staged.exists())
    }

    @Test
    fun buildsStrictFirstPersonContextFromCurrentVersionEvidence() = runTest {
        val dao = FakeAgentDao().apply {
            version = AgentVersionEntity(
                agentId = "agent-1",
                version = 2,
                schemaVersion = 1,
                bundlePath = "/tmp/agent.hbundle",
                bundleSha256 = "sha",
                manifestJson = "{}",
                persona = "我重视从事实出发。",
                worldviewJsonl = "{\"statement\":\"调查先于结论\"}",
                installedAt = 1L,
                state = "READY",
            )
            versionCorpora = listOf(
                AgentVersionCorpusCrossRef("agent-1", 2, "corpus-current", "hash-current", true),
            )
            chunks["corpus-current:hash-current:chunk-investigation"] = AgentChunkEntity(
                chunkKey = "corpus-current:hash-current:chunk-investigation",
                corpusId = "corpus-current",
                sourceHash = "hash-current",
                chunkId = "chunk-investigation",
                sourceTitle = "调查研究",
                location = "第一章 · 1",
                text = "没有调查，没有发言权。研究问题必须从事实出发。",
                keywordsText = "调查 事实 研究",
            )
            searchResult = listOf("corpus-current:hash-current:chunk-investigation")
        }
        val repository = repository(dao)

        val context = repository.runtimeContext("agent-1", 2, "为什么要先调查事实", 8)!!

        assertEquals(listOf("corpus-current:hash-current"), dao.lastCorpusKeys)
        assertEquals("chunk-investigation", context.evidence.single().chunkId)
        assertTrue(context.systemPrompt.contains("第一人称"))
        assertTrue(context.systemPrompt.contains("基于资料模拟"))
        assertTrue(context.systemPrompt.contains("不得使用模型通用知识"))
        assertTrue(context.systemPrompt.contains("[资料 1] 调查研究 · 第一章 · 1"))
    }

    @Test
    fun returnsMaterialInsufficientInstructionWhenFtsHasNoEvidence() = runTest {
        val dao = FakeAgentDao().apply {
            version = AgentVersionEntity(
                agentId = "agent-1",
                version = 1,
                schemaVersion = 1,
                bundlePath = "/tmp/agent.hbundle",
                bundleSha256 = "sha",
                manifestJson = "{}",
                persona = "我只依据资料。",
                worldviewJsonl = "",
                installedAt = 1L,
                state = "READY",
            )
            versionCorpora = listOf(AgentVersionCorpusCrossRef("agent-1", 1, "corpus-1", "hash", true))
        }

        val context = repository(dao).runtimeContext("agent-1", 1, "资料中没有的问题", 8)!!

        assertTrue(context.evidence.isEmpty())
        assertTrue(context.systemPrompt.contains("当前资料不足"))
    }

    @Test
    fun chineseQueryUsesQuotedTermsWithoutFtsOperatorsFromUserInput() {
        val query = buildAgentFtsQuery("调查 OR 事实 \"任意引号\"")

        assertTrue(query.contains("\"调查\""))
        assertTrue(query.contains("\"事实\""))
        assertTrue(query.contains(" OR "))
        assertTrue(!query.contains("\"任意引号\""))
    }

    private fun repository(dao: AgentDao): AgentRepository {
        val root = Files.createTempDirectory("agent-repository-test").toFile().apply { deleteOnExit() }
        return AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = dao,
            timeProvider = TimeProvider { 10L },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }
}

private class FakeAgentDao : AgentDao {
    private val agents = MutableStateFlow<List<AgentEntity>>(emptyList())
    var version: AgentVersionEntity? = null
    var versionCorpora: List<AgentVersionCorpusCrossRef> = emptyList()
    val chunks = linkedMapOf<String, AgentChunkEntity>()
    val corpora = linkedMapOf<String, AgentCorpusEntity>()
    val searchRows = mutableListOf<AgentChunkFtsEntity>()
    var searchResult: List<String> = emptyList()
    var lastCorpusKeys: List<String> = emptyList()

    override fun observeAgents(): Flow<List<AgentEntity>> = agents
    override suspend fun findAgent(id: String): AgentEntity? = agents.value.firstOrNull { it.id == id }
    override suspend fun listReadyAgents(): List<AgentEntity> =
        agents.value.filter { it.status == AgentStatus.READY.name }
    override suspend fun findVersion(agentId: String, version: Int): AgentVersionEntity? =
        this.version?.takeIf { it.agentId == agentId && it.version == version }

    override suspend fun findCorpus(corpusId: String, sourceHash: String): AgentCorpusEntity? =
        corpora["$corpusId:$sourceHash"]

    override suspend fun findCorpusById(corpusId: String): AgentCorpusEntity? =
        corpora.values.firstOrNull { it.corpusId == corpusId }
    override suspend fun listVersionCorpora(agentId: String, version: Int): List<AgentVersionCorpusCrossRef> =
        versionCorpora.filter { it.agentId == agentId && it.version == version }

    override suspend fun upsertAgent(entity: AgentEntity) {
        agents.value = agents.value.filterNot { it.id == entity.id } + entity
    }

    override suspend fun insertVersion(entity: AgentVersionEntity) {
        version = entity
    }

    override suspend fun insertCorpus(entity: AgentCorpusEntity): Long {
        corpora.putIfAbsent("${entity.corpusId}:${entity.sourceHash}", entity)
        return 1L
    }

    override suspend fun updateCorpusSize(corpusId: String, sourceHash: String, sizeBytes: Long) {
        val key = "$corpusId:$sourceHash"
        corpora[key] = corpora.getValue(key).copy(sizeBytes = sizeBytes)
    }

    override suspend fun insertVersionCorpus(entity: AgentVersionCorpusCrossRef): Long {
        versionCorpora = versionCorpora + entity
        return 1L
    }
    override suspend fun insertChunks(entities: List<AgentChunkEntity>): List<Long> {
        entities.forEach { chunks[it.chunkKey] = it }
        return entities.map { 1L }
    }

    override suspend fun insertChunkSearchRows(entities: List<AgentChunkFtsEntity>): List<Long> {
        searchRows += entities
        return entities.map { 1L }
    }

    override suspend fun searchChunkKeys(corpusKeys: List<String>, ftsQuery: String, limit: Int): List<String> {
        lastCorpusKeys = corpusKeys
        return searchResult.take(limit)
    }

    override suspend fun listChunks(chunkKeys: List<String>): List<AgentChunkEntity> =
        chunkKeys.mapNotNull(chunks::get)
}

private class FakeAgentBundleAccess(
    private val chunks: List<AgentCorpusChunk>,
) : AgentBundleAccess {
    override fun read(file: File): ParsedAgentBundle = error("Not used")

    override fun inspect(file: File): AgentImportPreview = AgentImportPreview(
        agentId = "agent-1",
        name = "资料研究代理",
        version = 1,
        summary = "基于资料模拟",
        publisherFingerprint = "publisher-fingerprint",
        corpora = listOf("测试资料"),
        compressedSizeBytes = file.length(),
        includesOriginalSources = false,
    )

    override suspend fun forEachChunkSuspending(
        bundle: ParsedAgentBundle,
        corpus: AgentCorpusManifest,
        block: suspend (AgentCorpusChunk) -> Unit,
    ) {
        check(bundle.file.isFile) { "bundle file must remain readable while indexing" }
        chunks.forEach { block(it) }
    }
}
