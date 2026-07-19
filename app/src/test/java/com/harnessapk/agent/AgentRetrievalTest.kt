package com.harnessapk.agent

import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AgentChunkEntity
import com.harnessapk.storage.AgentChunkFtsEntity
import com.harnessapk.storage.AgentCorpusChunkCrossRef
import com.harnessapk.storage.AgentCorpusEntity
import com.harnessapk.storage.AgentCorpusHierarchyCrossRef
import com.harnessapk.storage.AgentCorpusSourceCrossRef
import com.harnessapk.storage.AgentDao
import com.harnessapk.storage.AgentEntity
import com.harnessapk.storage.AgentHierarchyFtsEntity
import com.harnessapk.storage.AgentHierarchyNodeEntity
import com.harnessapk.storage.AgentSourceFileEntity
import com.harnessapk.storage.AgentVersionCorpusCrossRef
import com.harnessapk.storage.AgentVersionEntity
import com.harnessapk.storage.AgentVersionPackageEntity
import com.harnessapk.storage.AgentVersionSourceCrossRef
import com.harnessapk.storage.ConversationDao
import com.harnessapk.storage.ConversationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
    fun unifiedV2BalancedInstallIsSingleUseAndUsesBoundedChunkBatches() = runTest {
        val fixture = v2InstallFixture(chunkCount = 450)
        val session = fixture.repository.preparePackageImport("balanced.hbundle") {
            "bundle".byteInputStream()
        }

        val result = fixture.repository.installPackage(session, profileId = "balanced")

        assertEquals(AgentStatus.READY, result.agent.status)
        assertEquals(1, result.agent.requiredCorpusCount)
        assertEquals(1, result.agent.installedCorpusCount)
        assertEquals(450, fixture.dao.chunks.size)
        assertTrue(fixture.dao.maxChunkBatchSize <= 200)
        assertEquals(1, fixture.dao.sources.size)
        assertFalse(session.stagedFile.exists())
        val reused = runCatching { fixture.repository.installPackage(session) }.exceptionOrNull()
        assertTrue(reused is AgentBundleException)
    }

    @Test
    fun unifiedImportRejectsExternallyModifiedSessionWithoutInstallingAnything() = runTest {
        val fixture = v2InstallFixture()
        val session = fixture.repository.preparePackageImport("balanced.hbundle") {
            "bundle".byteInputStream()
        }
        session.stagedFile.appendText("tampered")

        val failure = runCatching { fixture.repository.installPackage(session) }.exceptionOrNull()

        assertTrue(failure is AgentBundleException)
        assertTrue(fixture.dao.findAgent("agent-v2") == null)
        assertFalse(session.stagedFile.exists())
    }

    @Test
    fun missingRequiredEvidenceKeepsV2VersionWaiting() = runTest {
        val fixture = v2InstallFixture(requiredEvidenceId = "missing-evidence")
        val session = fixture.repository.preparePackageImport("balanced.hbundle") {
            "bundle".byteInputStream()
        }

        val result = fixture.repository.installPackage(session)

        assertEquals(AgentStatus.WAITING_FOR_CORPUS, result.agent.status)
        assertEquals(AgentStatus.WAITING_FOR_CORPUS.name, fixture.dao.version!!.state)
    }

    @Test
    fun standaloneRequiredCorpusCompletesWaitingVersionWithoutChangingActiveVersion() = runTest {
        val fixture = v2InstallFixture()
        val agentSession = fixture.repository.preparePackageImport("agent.hagent") {
            "agent".byteInputStream()
        }
        val waiting = fixture.repository.installPackage(agentSession)
        val activeVersion = waiting.agent.activeVersion
        val corpusSession = fixture.repository.preparePackageImport("core.hcorpus") {
            "corpus".byteInputStream()
        }

        val completed = fixture.repository.installPackage(corpusSession)

        assertEquals(AgentStatus.READY, completed.agent.status)
        assertEquals(activeVersion, completed.agent.activeVersion)
        assertEquals(1, fixture.dao.versionCorpora.size)
        assertEquals("corpus-core", fixture.dao.versionCorpora.single().corpusId)
    }

    @Test
    fun requiredAndPersistedMessageReferencedCorporaCannotBeRemoved() = runTest {
        val required = optionalRemovalFixture(required = true)
        assertEquals(
            AgentCorpusRemovalOutcome.REQUIRED,
            required.repository.removeOptionalCorpus("agent-remove", 1, "corpus-extra").outcome,
        )

        val referenced = optionalRemovalFixture(required = false)
        referenced.dao.referencedChunkKeys += "source-extra:chunk-extra"
        assertEquals(
            AgentCorpusRemovalOutcome.REFERENCED,
            referenced.repository.removeOptionalCorpus("agent-remove", 1, "corpus-extra").outcome,
        )
        assertEquals(1, referenced.dao.corpusChunkRefs.size)
    }

    @Test
    fun legacyAgentSourcesMetadataConservativelyBlocksOptionalRemoval() = runTest {
        val fixture = optionalRemovalFixture(required = false)
        fixture.dao.hasLegacyAgentSources = true

        val result = fixture.repository.removeOptionalCorpus("agent-remove", 1, "corpus-extra")

        assertEquals(AgentCorpusRemovalOutcome.REFERENCED, result.outcome)
        assertEquals(1, fixture.dao.corpusChunkRefs.size)
    }

    @Test
    fun removingSharedCorpusReferenceKeepsPhysicalEvidenceUntilFinalReference() = runTest {
        val fixture = optionalRemovalFixture(required = false)
        fixture.dao.versionCorpora = fixture.dao.versionCorpora +
            fixture.dao.versionCorpora.single().copy(agentId = "agent-other", version = 9)

        val first = fixture.repository.removeOptionalCorpus("agent-remove", 1, "corpus-extra")

        assertEquals(AgentCorpusRemovalOutcome.REMOVED, first.outcome)
        assertEquals(1, fixture.dao.chunks.size)
        assertEquals(1, fixture.dao.searchRows.size)
        assertEquals(1, fixture.dao.corpusChunkRefs.size)

        val final = fixture.repository.removeOptionalCorpus("agent-other", 9, "corpus-extra")

        assertEquals(AgentCorpusRemovalOutcome.REMOVED, final.outcome)
        assertTrue(fixture.dao.chunks.isEmpty())
        assertTrue(fixture.dao.searchRows.isEmpty())
        assertTrue(fixture.dao.corpusChunkRefs.isEmpty())
    }

    @Test
    fun disabledAgentLeavesFixedVersionRuntimeResolvable() = runTest {
        val fixture = optionalRemovalFixture(required = false)
        fixture.dao.searchResult = listOf("source-extra:chunk-extra")

        fixture.repository.setAgentEnabled("agent-remove", enabled = false)

        assertEquals(AgentStatus.DISABLED, fixture.repository.agent("agent-remove")!!.status)
        assertTrue(fixture.dao.listReadyAgents().none { it.id == "agent-remove" })
        assertEquals(1, fixture.repository.runtimeContext("agent-remove", 1, "证据")!!.evidence.size)
    }

    @Test
    fun historicalCoverageUsesRequestedVersionCrossReferences() = runTest {
        val fixture = optionalRemovalFixture(required = false)
        fixture.dao.version = fixture.dao.version!!.copy(requiredCorpusCount = 2)
        fixture.dao.versionCorpora = fixture.dao.versionCorpora + AgentVersionCorpusCrossRef(
            "agent-remove", 1, "corpus-required", "required-hash", true,
        )

        val coverage = fixture.repository.versionCoverage("agent-remove", 1)

        assertEquals(2, coverage.requiredCorpusCount)
        assertEquals(1, coverage.installedRequiredCorpusCount)
        assertEquals(2, coverage.installedCorpusCount)
    }

    @Test
    fun standaloneSourceDeduplicatesOnDiskWithoutCreatingEvidenceRows() = runTest {
        val fixture = v2InstallFixture()
        fixture.repository.installPackage(
            fixture.repository.preparePackageImport("agent.hagent") { "agent".byteInputStream() },
        )

        val first = fixture.repository.installPackage(
            fixture.repository.preparePackageImport("source.hsource") { "source".byteInputStream() },
        )
        val second = fixture.repository.installPackage(
            fixture.repository.preparePackageImport("source.hsource") { "source".byteInputStream() },
        )

        assertEquals(AgentInstallOutcome.INSTALLED, first.outcome)
        assertEquals(AgentInstallOutcome.ALREADY_INSTALLED, second.outcome)
        val stored = fixture.dao.sources.values.single()
        assertTrue(File(stored.filePath).isFile)
        assertEquals("raw-source", File(stored.filePath).readText())
        assertTrue(fixture.dao.chunks.isEmpty())
        assertTrue(fixture.dao.searchRows.isEmpty())
    }

    @Test
    fun standaloneCorpusRejectsUndeclaredPublisherHashSizeAndVersionMismatches() = runTest {
        val mismatches = listOf<(V2Corpus) -> V2Corpus>(
            { corpus -> corpus.copy(manifest = corpus.manifest.copy(id = "undeclared")) },
            { corpus -> corpus.copy(publisherFingerprint = "wrong-publisher") },
            { corpus -> corpus.copy(packageSha256 = "f".repeat(64)) },
            { corpus -> corpus.copy(compressedSizeBytes = corpus.compressedSizeBytes + 1) },
            { corpus -> corpus.copy(manifest = corpus.manifest.copy(version = 99)) },
        )
        mismatches.forEachIndexed { index, mutate ->
            val fixture = v2InstallFixture(
                standaloneCorpusTransform = mutate,
            )
            fixture.repository.installPackage(
                fixture.repository.preparePackageImport("agent-$index.hagent") { "agent".byteInputStream() },
            )

            val failure = runCatching {
                fixture.repository.installPackage(
                    fixture.repository.preparePackageImport("core.hcorpus") {
                        "corpus".byteInputStream()
                    },
                )
            }.exceptionOrNull()

            assertTrue("mismatch $index", failure is AgentBundleException)
            assertEquals(AgentStatus.WAITING_FOR_CORPUS, fixture.repository.agent("agent-v2")!!.status)
            assertTrue(fixture.dao.versionCorpora.isEmpty())
        }
    }

    @Test
    fun importCopyFailureLeavesNoStagingSessionOrFile() = runTest {
        val fixture = v2InstallFixture()

        val failure = runCatching {
            fixture.repository.preparePackageImport("broken.hbundle") {
                throw java.io.IOException("copy failed")
            }
        }.exceptionOrNull()

        assertTrue(failure is AgentBundleException)
        assertTrue(
            fixture.repositoryRoot.resolve("cache/agent-staging")
                .listFiles().orEmpty().none(File::isFile),
        )
    }

    @Test
    fun batchEvidenceReadyAndCancellationFailuresRollbackDatabaseAndFiles() = runTest {
        val cases = listOf(
            V2InstallFailure.BATCH_INSERT,
            V2InstallFailure.EVIDENCE_CHECK,
            V2InstallFailure.READY_TRANSITION,
            V2InstallFailure.CANCEL_DURING_CHUNKS,
        )
        cases.forEach { failurePoint ->
            val fixture = v2InstallFixture(chunkCount = 450, failure = failurePoint)
            val session = fixture.repository.preparePackageImport("$failurePoint.hbundle") {
                "bundle".byteInputStream()
            }

            val failure = runCatching { fixture.repository.installPackage(session) }.exceptionOrNull()

            if (failurePoint == V2InstallFailure.CANCEL_DURING_CHUNKS) {
                assertTrue(failure is CancellationException)
            } else {
                assertTrue("$failurePoint: $failure", failure is IllegalStateException)
            }
            assertTrue(fixture.dao.findAgent("agent-v2") == null)
            assertTrue(fixture.dao.chunks.isEmpty())
            assertTrue(fixture.dao.corpora.isEmpty())
            assertFalse(session.stagedFile.exists())
            assertTrue(
                fixture.repositoryRoot.resolve("files").walkTopDown().none(File::isFile),
            )
            assertTrue(runCatching { fixture.repository.installPackage(session) }.isFailure)
        }
    }

    @Test
    fun referencedVersionCannotBeDeletedAndLastUnreferencedVersionRemovesAgent() = runTest {
        val fixture = optionalRemovalFixture(required = false)
        val conversationDao = RemovalConversationDao(referenceCount = 1)
        val repository = AgentRepository(
            filesDir = fixture.repositoryRoot.resolve("files"),
            cacheDir = fixture.repositoryRoot.resolve("cache"),
            dao = fixture.dao,
            conversationDao = conversationDao,
            timeProvider = TimeProvider { 20L },
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertEquals(
            AgentVersionRemovalOutcome.REFERENCED,
            repository.removeVersion("agent-remove", 1).outcome,
        )
        conversationDao.referenceCount = 0
        assertEquals(
            AgentVersionRemovalOutcome.REMOVED,
            repository.removeVersion("agent-remove", 1).outcome,
        )
        assertTrue(fixture.dao.findAgent("agent-remove") == null)
    }

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
    fun v1CompatibilityInstallRemainsIdempotent() = runTest {
        val fixture = twoCorpusInstallFixture(conflictingSecondChunk = false)
        val first = fixture.repository.install(fixture.session)
        val secondStaged = requireNotNull(fixture.session.stagedFile.parentFile).resolve("second.hbundle").apply {
            writeText("validated package")
        }
        val secondSession = fixture.session.copy(
            id = "session-v1-second",
            stagedFile = secondStaged,
            parsedBundle = fixture.session.parsedBundle.copy(file = secondStaged),
        )

        val second = fixture.repository.install(secondSession)

        assertEquals(AgentInstallOutcome.INSTALLED, first.outcome)
        assertEquals(AgentInstallOutcome.ALREADY_INSTALLED, second.outcome)
        assertFalse(secondStaged.exists())
        assertEquals(1, fixture.dao.versionCorpora.count { it.corpusId == "corpus-core" })
    }

    @Test
    fun reusesIdenticalPhysicalChunkAcrossCorpora() = runTest {
        val fixture = twoCorpusInstallFixture(conflictingSecondChunk = false)

        fixture.repository.install(fixture.session)

        assertEquals(1, fixture.dao.chunks.size)
        assertEquals(1, fixture.dao.searchRows.size)
        assertEquals(2, fixture.dao.corpusChunkRefs.size)
    }

    @Test
    fun rejectsConflictingPhysicalChunkBeforeAddingSecondCorpusReference() = runTest {
        val fixture = twoCorpusInstallFixture(conflictingSecondChunk = true)

        try {
            fixture.repository.install(fixture.session)
            throw AssertionError("Expected immutable physical evidence conflict")
        } catch (error: AgentBundleException) {
            assertTrue(error.message.orEmpty().contains("immutable evidence"))
        }

        assertEquals(1, fixture.dao.chunks.size)
        assertEquals(1, fixture.dao.corpusChunkRefs.size)
        assertEquals(1, fixture.dao.searchRows.size)
    }

    @Test
    fun buildsNaturalFirstPersonContextFromCurrentVersionEvidence() = runTest {
        val dao = FakeAgentDao().apply {
            version = AgentVersionEntity(
                agentId = "agent-1",
                version = 1,
                schemaVersion = 1,
                bundlePath = "/tmp/agent.hbundle",
                bundleSha256 = "sha",
                manifestJson = "{}",
                persona = "我重视从事实出发。",
                worldviewJsonl = """{"id":"view-investigation","statement":"调查先于结论","evidence":["chunk-secret-42"],"confidence":1.0}""",
                installedAt = 1L,
                state = "READY",
            )
            versionCorpora = listOf(
                AgentVersionCorpusCrossRef("agent-1", 1, "corpus-current", "hash-current", true),
            )
            chunks["hash-current:chunk-investigation"] = AgentChunkEntity(
                chunkKey = "hash-current:chunk-investigation",
                sourceId = "source-current",
                sourceHash = "hash-current",
                chunkId = "chunk-investigation",
                sourceTitle = "调查研究",
                location = "第一章 · 1",
                text = "没有调查，没有发言权。研究问题必须从事实出发。",
                keywordsText = "调查 事实 研究",
            )
            searchResult = listOf("hash-current:chunk-investigation")
        }
        val repository = repository(dao)

        val context = repository.runtimeContext("agent-1", 1, "为什么要先调查事实", 8)!!

        assertEquals(listOf("corpus-current:hash-current"), dao.lastCorpusKeys)
        assertEquals("chunk-investigation", context.evidence.single().chunkId)
        assertTrue(context.systemPrompt.contains("第一人称"))
        assertTrue(context.systemPrompt.contains("基于资料模拟"))
        assertTrue(context.systemPrompt.contains("历史事实、人物经历和核心立场必须由人物资料支持"))
        assertTrue(context.systemPrompt.contains("没有调查，没有发言权。研究问题必须从事实出发。"))
        assertTrue(context.systemPrompt.contains("调查先于结论"))
        assertFalse(context.systemPrompt.contains("[资料 1]"))
        assertFalse(context.systemPrompt.contains("调查研究 · 第一章 · 1"))
        assertFalse(context.systemPrompt.contains("chunk-secret-42"))
        assertFalse(context.systemPrompt.contains("view-investigation"))
    }

    @Test
    fun returnsNaturalConversationContractWhenFtsHasNoEvidence() = runTest {
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
        assertTrue(context.systemPrompt.contains("问候、承接前文和关系互动不要求原文证据"))
        assertFalse(context.systemPrompt.contains("当前资料不足"))
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

    private fun twoCorpusInstallFixture(conflictingSecondChunk: Boolean): TwoCorpusInstallFixture {
        val root = Files.createTempDirectory("agent-physical-reuse-test").toFile().apply { deleteOnExit() }
        val staged = root.resolve("staged.hbundle").apply { writeText("validated package") }
        val corpora = listOf("corpus-core", "corpus-full").map { id ->
            AgentCorpusManifest(
                id = id,
                title = id,
                sourceHash = "corpus-$id",
                sourcesPath = "$id/sources.json",
                chunksPath = "$id/chunks.jsonl",
                required = id == "corpus-core",
            )
        }
        fun chunk(text: String) = AgentCorpusChunk(
            id = "chunk-1",
            sourceTitle = "同一来源",
            sourceHash = "a".repeat(64),
            location = "第一章",
            text = text,
            keywords = listOf("调查"),
            ngrams = listOf("调查"),
        )
        val reader = FakeAgentBundleAccess(
            chunksByCorpus = mapOf(
                "corpus-core" to listOf(chunk("相同证据")),
                "corpus-full" to listOf(chunk(if (conflictingSecondChunk) "冲突证据" else "相同证据")),
            ),
        )
        val parsed = ParsedAgentBundle(
            file = staged,
            packageSha256 = "bundle-sha",
            publisherPublicKey = byteArrayOf(1),
            publisherFingerprint = "publisher-fingerprint",
            manifestJson = "{}",
            agent = AgentPackageManifest(
                id = "agent-physical",
                name = "物理去重代理",
                version = 1,
                summary = "",
                personaPath = "agent/persona.md",
                worldviewPath = "agent/worldview.jsonl",
                conceptsPath = "agent/concepts.json",
                examplesPath = "agent/examples.jsonl",
                evalPath = "agent/eval.jsonl",
                requiredCorpora = listOf("corpus-core"),
            ),
            corpora = corpora,
            persona = "只依据证据",
            worldviewJsonl = "",
            compressedSizeBytes = staged.length(),
            uncompressedSizeBytes = staged.length(),
        )
        val dao = FakeAgentDao()
        val repository = AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = dao,
            reader = reader,
            timeProvider = TimeProvider { 20L },
            ioDispatcher = Dispatchers.Unconfined,
        )
        return TwoCorpusInstallFixture(
            repository,
            AgentImportSession("session-physical", staged, parsed, reader.inspect(staged)),
            dao,
        )
    }

    private suspend fun optionalRemovalFixture(required: Boolean): V2InstallFixture {
        val root = Files.createTempDirectory("agent-removal-test").toFile().apply { deleteOnExit() }
        val dao = FakeAgentDao()
        dao.upsertAgent(
            AgentEntity(
                "agent-remove", "移除测试", "", 1, byteArrayOf(1), "publisher", "LOCAL_FILE",
                AgentStatus.READY.name, 0, 0, 1L, 1L,
            ),
        )
        dao.version = AgentVersionEntity(
            "agent-remove", 1, 2, root.resolve("agent.hagent").absolutePath, "sha", "{}", "人格", "",
            1L, AgentStatus.READY.name,
        )
        dao.corpora["corpus-extra:corpus-hash"] = AgentCorpusEntity(
            "corpus-extra", "corpus-hash", "扩展资料", 1L, 10L,
        )
        dao.versionCorpora = listOf(
            AgentVersionCorpusCrossRef(
                "agent-remove", 1, "corpus-extra", "corpus-hash", required,
                if (required) "required" else "optional", "package-sha", 10L, 1L,
            ),
        )
        dao.chunks["source-extra:chunk-extra"] = AgentChunkEntity(
            "source-extra:chunk-extra", "source-extra", "source-extra", "chunk-extra", "扩展资料",
            location = "第一章", text = "扩展证据", keywordsText = "证据",
        )
        dao.corpusChunkRefs += AgentCorpusChunkCrossRef(
            "corpus-extra", "corpus-hash", "source-extra:chunk-extra",
        )
        dao.searchRows += AgentChunkFtsEntity("source-extra:chunk-extra", "证据")
        return V2InstallFixture(
            AgentRepository(
                filesDir = root.resolve("files"),
                cacheDir = root.resolve("cache"),
                dao = dao,
                timeProvider = TimeProvider { 20L },
                ioDispatcher = Dispatchers.Unconfined,
            ),
            dao,
            root,
        )
    }
}

private data class TwoCorpusInstallFixture(
    val repository: AgentRepository,
    val session: AgentImportSession,
    val dao: FakeAgentDao,
)

internal class FakeAgentDao : AgentDao {
    private val agents = MutableStateFlow<List<AgentEntity>>(emptyList())
    var version: AgentVersionEntity? = null
    var versionCorpora: List<AgentVersionCorpusCrossRef> = emptyList()
    val chunks = linkedMapOf<String, AgentChunkEntity>()
    val corpora = linkedMapOf<String, AgentCorpusEntity>()
    val corpusChunkRefs = mutableListOf<AgentCorpusChunkCrossRef>()
    val searchRows = mutableListOf<AgentChunkFtsEntity>()
    val versionPackages = linkedMapOf<String, AgentVersionPackageEntity>()
    val sources = linkedMapOf<String, AgentSourceFileEntity>()
    val versionSources = mutableListOf<AgentVersionSourceCrossRef>()
    val corpusSourceRefs = mutableListOf<AgentCorpusSourceCrossRef>()
    val corpusHierarchyRefs = mutableListOf<AgentCorpusHierarchyCrossRef>()
    val hierarchyNodes = linkedMapOf<String, AgentHierarchyNodeEntity>()
    val hierarchySearchRows = mutableListOf<AgentHierarchyFtsEntity>()
    var searchResult: List<String> = emptyList()
    var lastCorpusKeys: List<String> = emptyList()
    var maxChunkBatchSize: Int = 0
    val referencedChunkKeys = mutableSetOf<String>()
    var hasLegacyAgentSources: Boolean = false
    var failChunkInsertCall: Int? = null
    var failEvidenceCheck: Boolean = false
    var failReadyTransition: Boolean = false
    private var chunkInsertCalls: Int = 0

    override fun observeAgents(): Flow<List<AgentEntity>> = agents
    override suspend fun findAgent(id: String): AgentEntity? = agents.value.firstOrNull { it.id == id }
    override suspend fun listReadyAgents(): List<AgentEntity> =
        agents.value.filter { it.status == AgentStatus.READY.name }
    override suspend fun findVersion(agentId: String, version: Int): AgentVersionEntity? =
        this.version?.takeIf { it.agentId == agentId && it.version == version }
    override suspend fun listVersions(agentId: String) = listOfNotNull(version).filter { it.agentId == agentId }
    override suspend fun findVersionPackage(agentId: String, version: Int, packageId: String) =
        versionPackages["$agentId:$version:$packageId"]
    override suspend fun listVersionPackages(agentId: String, version: Int) = versionPackages.values
        .filter { it.agentId == agentId && it.version == version }

    override suspend fun findCorpus(corpusId: String, sourceHash: String): AgentCorpusEntity? =
        corpora["$corpusId:$sourceHash"]

    override suspend fun findCorpusById(corpusId: String): AgentCorpusEntity? =
        corpora.values.firstOrNull { it.corpusId == corpusId }
    override suspend fun listVersionCorpora(agentId: String, version: Int): List<AgentVersionCorpusCrossRef> =
        versionCorpora.filter { it.agentId == agentId && it.version == version }
    override suspend fun findVersionCorpus(agentId: String, version: Int, corpusId: String) =
        versionCorpora.firstOrNull { it.agentId == agentId && it.version == version && it.corpusId == corpusId }
    override suspend fun countVersionCorpusReferences(corpusId: String, sourceHash: String) =
        versionCorpora.count { it.corpusId == corpusId && it.sourceHash == sourceHash }
    override suspend fun listCorpusChunkKeys(corpusId: String, corpusHash: String) = corpusChunkRefs
        .filter { it.corpusId == corpusId && it.corpusHash == corpusHash }
        .map(AgentCorpusChunkCrossRef::chunkKey)
    override suspend fun countAgentSourcePartsReferencingChunkKey(chunkKey: String) =
        if (chunkKey in referencedChunkKeys) 1 else 0
    override suspend fun countLegacyAgentSourceParts() = if (hasLegacyAgentSources) 1 else 0
    override suspend fun findSource(sourceId: String, sourceHash: String): AgentSourceFileEntity? =
        sources["$sourceId:$sourceHash"]
    override suspend fun listVersionSources(agentId: String, version: Int): List<AgentSourceFileEntity> =
        versionSources.filter { it.agentId == agentId && it.version == version }
            .mapNotNull { sources["${it.sourceId}:${it.sourceHash}"] }

    override suspend fun upsertAgent(entity: AgentEntity) {
        agents.value = agents.value.filterNot { it.id == entity.id } + entity
    }

    override suspend fun insertVersion(entity: AgentVersionEntity) {
        version = entity
    }
    override suspend fun upsertVersionPackages(entities: List<AgentVersionPackageEntity>) {
        entities.forEach { versionPackages["${it.agentId}:${it.version}:${it.packageId}"] = it }
    }
    override suspend fun markVersionPackageInstalled(
        agentId: String,
        version: Int,
        packageId: String,
        filePath: String,
        installedAt: Long,
    ): Int {
        val key = "$agentId:$version:$packageId"
        val row = versionPackages[key] ?: return 0
        versionPackages[key] = row.copy(installed = true, filePath = filePath, installedAt = installedAt)
        return 1
    }
    override suspend fun markVersionPackageRemoved(agentId: String, version: Int, packageId: String): Int {
        val key = "$agentId:$version:$packageId"
        val row = versionPackages[key] ?: return 0
        versionPackages[key] = row.copy(installed = false, filePath = "", installedAt = null)
        return 1
    }
    override suspend fun countInstalledPackagePathReferences(filePath: String) =
        versionPackages.values.count { it.installed && it.filePath == filePath }
    override suspend fun updateVersionState(agentId: String, version: Int, state: String, expandedAt: Long?): Int {
        if (failReadyTransition) throw IllegalStateException("ready transition failed")
        val row = this.version?.takeIf { it.agentId == agentId && it.version == version } ?: return 0
        this.version = row.copy(state = state, lastEvidenceExpandedAt = expandedAt)
        return 1
    }
    override suspend fun updateAgentInstallState(
        agentId: String,
        status: String,
        requiredCount: Int,
        installedCount: Int,
        updatedAt: Long,
    ): Int {
        val row = agents.value.firstOrNull { it.id == agentId } ?: return 0
        upsertAgent(
            row.copy(
                status = status,
                requiredCorpusCount = requiredCount,
                installedCorpusCount = installedCount,
                updatedAt = updatedAt,
            ),
        )
        return 1
    }
    override suspend fun updateAgentStatus(agentId: String, status: String, updatedAt: Long): Int {
        val row = agents.value.firstOrNull { it.id == agentId } ?: return 0
        upsertAgent(row.copy(status = status, updatedAt = updatedAt))
        return 1
    }
    override suspend fun deleteVersionCorpus(agentId: String, version: Int, corpusId: String): Int {
        val before = versionCorpora.size
        versionCorpora = versionCorpora.filterNot {
            it.agentId == agentId && it.version == version && it.corpusId == corpusId
        }
        return before - versionCorpora.size
    }
    override suspend fun deleteCorpus(corpusId: String, sourceHash: String): Int {
        val removed = corpora.remove("$corpusId:$sourceHash") ?: return 0
        corpusChunkRefs.removeIf { it.corpusId == corpusId && it.corpusHash == sourceHash }
        corpusSourceRefs.removeIf { it.corpusId == corpusId && it.corpusHash == sourceHash }
        corpusHierarchyRefs.removeIf { it.corpusId == corpusId && it.corpusHash == sourceHash }
        return if (removed.corpusId == corpusId) 1 else 0
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
        chunkInsertCalls += 1
        if (chunkInsertCalls == failChunkInsertCall) throw IllegalStateException("batch insert failed")
        maxChunkBatchSize = maxOf(maxChunkBatchSize, entities.size)
        return entities.map { entity ->
            if (chunks.containsKey(entity.chunkKey)) {
                -1L
            } else {
                chunks[entity.chunkKey] = entity
                1L
            }
        }
    }
    override suspend fun insertCorpusChunkRefs(entities: List<AgentCorpusChunkCrossRef>): List<Long> {
        corpusChunkRefs += entities
        return entities.map { 1L }
    }
    override suspend fun insertCorpusSourceRefs(entities: List<AgentCorpusSourceCrossRef>): List<Long> {
        corpusSourceRefs += entities.filterNot(corpusSourceRefs::contains)
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
    override suspend fun countRequiredEvidenceChunk(agentId: String, version: Int, chunkId: String): Int {
        if (failEvidenceCheck) throw IllegalStateException("evidence check failed")
        val requiredCorpora = versionCorpora.filter {
            it.agentId == agentId && it.version == version && it.required
        }.map { "${it.corpusId}:${it.sourceHash}" }.toSet()
        val keys = corpusChunkRefs.filter { "${it.corpusId}:${it.corpusHash}" in requiredCorpora }
            .map(AgentCorpusChunkCrossRef::chunkKey)
            .toSet()
        return chunks.values.count { it.chunkKey in keys && it.chunkId == chunkId }
    }
    override suspend fun insertHierarchyNodes(entities: List<AgentHierarchyNodeEntity>): List<Long> = entities.map {
        if (hierarchyNodes.putIfAbsent(it.nodeKey, it) == null) 1L else -1L
    }
    override suspend fun insertHierarchySearchRows(entities: List<AgentHierarchyFtsEntity>): List<Long> {
        hierarchySearchRows += entities
        return entities.map { 1L }
    }
    override suspend fun insertCorpusHierarchyRefs(entities: List<AgentCorpusHierarchyCrossRef>): List<Long> {
        corpusHierarchyRefs += entities.filterNot(corpusHierarchyRefs::contains)
        return entities.map { 1L }
    }
    override suspend fun insertSource(entity: AgentSourceFileEntity): Long {
        val key = "${entity.sourceId}:${entity.sourceHash}"
        if (key in sources) return -1L
        sources[key] = entity
        return 1L
    }
    override suspend fun upsertSource(entity: AgentSourceFileEntity) {
        sources["${entity.sourceId}:${entity.sourceHash}"] = entity
    }
    override suspend fun insertVersionSource(entity: AgentVersionSourceCrossRef): Long {
        if (entity !in versionSources) versionSources += entity
        return 1L
    }
    override suspend fun deleteVersion(agentId: String, version: Int): Int {
        val row = this.version?.takeIf { it.agentId == agentId && it.version == version } ?: return 0
        this.version = null
        versionCorpora = versionCorpora.filterNot { it.agentId == agentId && it.version == version }
        versionPackages.entries.removeIf { it.value.agentId == agentId && it.value.version == version }
        versionSources.removeIf { it.agentId == agentId && it.version == version }
        return if (row.agentId == agentId) 1 else 0
    }
    override suspend fun deleteAgent(agentId: String): Int {
        val before = agents.value.size
        agents.value = agents.value.filterNot { it.id == agentId }
        deleteVersion(agentId, version?.version ?: -1)
        return before - agents.value.size
    }
    override suspend fun searchHierarchyNodeKeys(ftsQuery: String, limit: Int): List<String> = emptyList()
    override suspend fun listHierarchyNodes(nodeKeys: List<String>): List<AgentHierarchyNodeEntity> =
        nodeKeys.mapNotNull(hierarchyNodes::get)
    override suspend fun deleteOrphanChunkSearchRows(): Int {
        val before = searchRows.size
        searchRows.removeIf { it.chunkKey !in chunks }
        return before - searchRows.size
    }
    override suspend fun deleteOrphanChunks(): Int {
        val referenced = corpusChunkRefs.map(AgentCorpusChunkCrossRef::chunkKey).toSet()
        val before = chunks.size
        chunks.keys.removeIf { it !in referenced }
        return before - chunks.size
    }
    override suspend fun deleteOrphanHierarchySearchRows(): Int {
        val before = hierarchySearchRows.size
        hierarchySearchRows.removeIf { it.nodeKey !in hierarchyNodes }
        return before - hierarchySearchRows.size
    }
    override suspend fun deleteOrphanHierarchyNodes(): Int {
        val referenced = corpusHierarchyRefs.map(AgentCorpusHierarchyCrossRef::nodeKey).toSet()
        val before = hierarchyNodes.size
        hierarchyNodes.keys.removeIf { it !in referenced }
        return before - hierarchyNodes.size
    }
    override suspend fun deleteOrphanSources(): Int = 0
    override suspend fun deleteOrphanCorpora(): Int = 0

    fun clearInstalledState() {
        agents.value = emptyList()
        version = null
        versionCorpora = emptyList()
        chunks.clear()
        corpora.clear()
        corpusChunkRefs.clear()
        searchRows.clear()
        versionPackages.clear()
        sources.clear()
        versionSources.clear()
        corpusSourceRefs.clear()
        corpusHierarchyRefs.clear()
        hierarchyNodes.clear()
        hierarchySearchRows.clear()
    }
}

private enum class V2InstallFailure {
    NONE,
    BATCH_INSERT,
    EVIDENCE_CHECK,
    READY_TRANSITION,
    CANCEL_DURING_CHUNKS,
}

private data class V2InstallFixture(
    val repository: AgentRepository,
    val dao: FakeAgentDao,
    val repositoryRoot: File,
)

private fun v2InstallFixture(
    chunkCount: Int = 1,
    requiredEvidenceId: String = "chunk-0",
    standaloneCorpusTransform: (V2Corpus) -> V2Corpus = { it },
    failure: V2InstallFailure = V2InstallFailure.NONE,
): V2InstallFixture {
    val root = Files.createTempDirectory("agent-v2-install-test").toFile().apply { deleteOnExit() }
    val packageFile = root.resolve("template.zip").apply { writeText("template") }
    val hash = "5fec1a8b0ded0b8aa27e7afd9caddbf49b1d7dd530efc0515b85c073cdf5a0f0"
    val installPackage = V2InstallPackage(
        id = "corpus-core",
        type = V2PackageType.CORPUS,
        fileName = "core.hcorpus",
        installClass = V2InstallClass.REQUIRED,
        dependencies = emptyList(),
        sizeBytes = 6L,
        sha256 = "b".repeat(64),
    )
    val sourceInstallPackage = V2InstallPackage(
        id = "source-package",
        type = V2PackageType.SOURCE,
        fileName = "source.hsource",
        installClass = V2InstallClass.SOURCE,
        dependencies = emptyList(),
        sizeBytes = 6L,
        sha256 = "e".repeat(64),
    )
    val plan = V2InstallPlan(
        packages = listOf(installPackage, sourceInstallPackage),
        profiles = listOf(V2InstallProfile("balanced", listOf("corpus-core", "source-package"), true)),
        recommendedProfileId = "balanced",
        requiredCorpusIds = listOf("corpus-core"),
    )
    val agent = V2Agent(
        file = packageFile,
        packageSha256 = "c".repeat(64),
        publisherPublicKey = byteArrayOf(1),
        publisherFingerprint = "publisher-v2",
        manifestJson = "{}",
        compressedSizeBytes = 5L,
        uncompressedSizeBytes = 5L,
        manifest = V2AgentManifest("agent-v2", "V2 人格", 2, listOf("corpus-core"), false),
        persona = "基于资料模拟。",
        identity = V2Identity(emptyList(), "", emptyList(), emptyList()),
        voice = V2Voice("", emptyList(), emptyList(), emptyList(), emptyList(), listOf(requiredEvidenceId)),
        worldview = emptyList(),
        episodes = emptyList(),
        concepts = emptyList(),
        examples = emptyList(),
        openers = V2Openers("", emptyList()),
        evaluations = listOf(
            V2Evaluation("eval-1", "grounding", "依据？", "", listOf(requiredEvidenceId), "corpus-core"),
        ),
        installPlanJson = "{}",
        installPlan = plan,
        isRunnable = false,
    )
    val source = V2SourceRecord(
        sourceId = "source-1",
        title = "测试来源",
        fileName = "source.txt",
        storedName = "source.txt",
        sourceHash = hash,
        format = "txt",
        genre = V2SourceGenre.ESSAY,
        authorship = V2Authorship.DIRECT,
        period = "test",
        rawSizeBytes = 0L,
        extractedChars = 0L,
    )
    val corpus = V2Corpus(
        file = packageFile,
        packageSha256 = installPackage.sha256,
        publisherPublicKey = byteArrayOf(1),
        publisherFingerprint = "publisher-v2",
        manifestJson = "{}",
        compressedSizeBytes = installPackage.sizeBytes,
        uncompressedSizeBytes = installPackage.sizeBytes,
        manifest = V2CorpusManifest(
            "corpus-core", "agent-v2", 2, V2InstallClass.REQUIRED, chunkCount,
            listOf(source.sourceId), listOf(source.sourceHash), listOf("test"),
            listOf(V2SourceGenre.ESSAY), listOf(V2Authorship.DIRECT), listOf("root"), listOf("identity:self"),
        ),
        sources = listOf(source),
        nodeCount = 1,
        chunkCount = chunkCount,
        duplicateCount = 0,
        validationDiagnostics = V2CorpusValidationDiagnostics("disk", chunkCount.toLong(), 1, 1L),
    )
    val sourcePackage = V2Source(
        file = packageFile,
        packageSha256 = sourceInstallPackage.sha256,
        publisherPublicKey = byteArrayOf(1),
        publisherFingerprint = "publisher-v2",
        manifestJson = "{}",
        compressedSizeBytes = sourceInstallPackage.sizeBytes,
        uncompressedSizeBytes = sourceInstallPackage.sizeBytes,
        manifest = V2SourceManifest(
            "source-package", "agent-v2", 2, source.sourceId, source.sourceHash,
            source.fileName, source.storedName, "raw-source".encodeToByteArray().size.toLong(),
        ),
    )
    val bundle = V2Bundle(
        file = packageFile,
        packageSha256 = "d".repeat(64),
        publisherPublicKey = byteArrayOf(1),
        publisherFingerprint = "publisher-v2",
        manifestJson = "{}",
        compressedSizeBytes = 6L,
        uncompressedSizeBytes = 6L,
        manifest = V2BundleManifest(
            V2BundleAgentDeclaration("agent-v2", 2, "agent.hagent", agent.packageSha256, agent.compressedSizeBytes),
            "balanced",
            listOf("corpus-core", "source-package"),
        ),
        profile = plan.profiles.single(),
        agent = agent,
        corpora = listOf(corpus),
        sources = listOf(sourcePackage),
        selectedCorpusIds = listOf("corpus-core"),
    )
    val chunks = List(chunkCount) { index ->
        V2Chunk(
            id = "chunk-$index",
            sourceId = source.sourceId,
            sourceHash = source.sourceHash,
            sourceTitle = source.title,
            genre = source.genre,
            authorship = source.authorship,
            period = source.period,
            location = "第 ${index + 1} 段",
            parentIds = listOf("root"),
            context = "",
            text = "证据 $index",
            keywords = listOf("证据"),
            ngrams = emptyList(),
            conflictKey = "",
            duplicateGroup = "",
            sourceAliases = emptyList(),
            simHash = "0".repeat(16),
        )
    }
    val reader = FakeV2PackageAccess(
        packages = mapOf(
            "bundle" to bundle,
            "agent" to agent,
            "corpus" to standaloneCorpusTransform(corpus),
            "source" to sourcePackage,
        ),
        chunks = chunks,
        nodes = listOf(V2HierarchyNode("root", "document", "测试来源", source.sourceId, null, listOf("测试来源"), "")),
        cancelDuringChunksAt = if (failure == V2InstallFailure.CANCEL_DURING_CHUNKS) 201 else null,
    )
    val dao = FakeAgentDao().apply {
        failChunkInsertCall = if (failure == V2InstallFailure.BATCH_INSERT) 2 else null
        failEvidenceCheck = failure == V2InstallFailure.EVIDENCE_CHECK
        failReadyTransition = failure == V2InstallFailure.READY_TRANSITION
    }
    return V2InstallFixture(
        repository = AgentRepository(
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            dao = dao,
            reader = reader,
            transactionRunner = AgentTransactionRunner { block ->
                try {
                    block()
                } catch (error: Throwable) {
                    dao.clearInstalledState()
                    throw error
                }
            },
            timeProvider = TimeProvider { 20L },
            ioDispatcher = Dispatchers.Unconfined,
        ),
        dao = dao,
        repositoryRoot = root,
    )
}

private class FakeV2PackageAccess(
    private val packages: Map<String, ParsedAgentPackage>,
    private val chunks: List<V2Chunk>,
    private val nodes: List<V2HierarchyNode>,
    private val cancelDuringChunksAt: Int? = null,
) : AgentBundleAccess {
    override fun inspect(file: File): AgentImportPreview = error("V2 preview uses readPackage")
    override fun read(file: File): ParsedAgentBundle = error("Not a V1 package")
    override fun readPackage(file: File): ParsedAgentPackage {
        val marker = file.readText()
        return requireNotNull(packages[marker]).withFile(file)
    }
    override suspend fun forEachChunkSuspending(
        bundle: ParsedAgentBundle,
        corpus: AgentCorpusManifest,
        block: suspend (AgentCorpusChunk) -> Unit,
    ) = error("Not a V1 package")
    override suspend fun forEachV2ChunkSuspending(
        file: File,
        corpusId: String?,
        block: suspend (V2Chunk) -> Unit,
    ) {
        chunks.forEachIndexed { index, chunk ->
            if (index == cancelDuringChunksAt) throw CancellationException("cancelled during chunks")
            block(chunk)
        }
    }
    override suspend fun forEachV2HierarchyNodeSuspending(
        file: File,
        corpusId: String?,
        block: suspend (V2HierarchyNode) -> Unit,
    ) {
        nodes.forEach { block(it) }
    }
    override suspend fun copyV2SourcePayload(
        file: File,
        packageId: String?,
        output: java.io.OutputStream,
    ) {
        output.write("raw-source".encodeToByteArray())
    }
}

private class RemovalConversationDao(var referenceCount: Int) : ConversationDao {
    override fun observeActive(): Flow<List<ConversationEntity>> = MutableStateFlow(emptyList())
    override suspend fun findById(id: String): ConversationEntity? = null
    override suspend fun findLatestActive(): ConversationEntity? = null
    override suspend fun findLatestActiveInProject(projectId: String): ConversationEntity? = null
    override suspend fun insert(entity: ConversationEntity) = Unit
    override suspend fun update(entity: ConversationEntity) = Unit
    override suspend fun updateIdentityIfNoUserMessages(id: String, agentId: String?, agentVersion: Int?, updatedAt: Long) = 0
    override suspend fun clearProject(projectId: String) = Unit
    override suspend fun archive(id: String, updatedAt: Long) = Unit
    override suspend fun countByAgentVersion(agentId: String, version: Int) = referenceCount
}

private fun ParsedAgentPackage.withFile(file: File): ParsedAgentPackage = when (this) {
    is V1Bundle -> V1Bundle(bundle.copy(file = file))
    is V2Agent -> copy(file = file)
    is V2Corpus -> copy(file = file)
    is V2Source -> copy(file = file)
    is V2Bundle -> copy(
        file = file,
        agent = agent.copy(file = file),
        corpora = corpora.map { it.copy(file = file) },
        sources = sources.map { it.copy(file = file) },
    )
}

private class FakeAgentBundleAccess(
    private val chunks: List<AgentCorpusChunk> = emptyList(),
    private val chunksByCorpus: Map<String, List<AgentCorpusChunk>> = emptyMap(),
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
        (chunksByCorpus[corpus.id] ?: chunks).forEach { block(it) }
    }
}
