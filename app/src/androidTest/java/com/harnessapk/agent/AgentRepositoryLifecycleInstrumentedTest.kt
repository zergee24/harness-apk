package com.harnessapk.agent

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class AgentRepositoryLifecycleInstrumentedTest {
    @Test
    fun fileBackedRoomWalAndFtsPeakGrowthStaysWithinConservativeCorpusBudget() = runBlocking {
        val fixture = FileBackedIntegrationFixture(chunkCount = 4_000)
        val session = fixture.repository.preparePackageImport(fixture.bundle.name) {
            fixture.bundle.inputStream()
        }
        val corpusBytes = (session.parsedPackage as V2Bundle).corpora.single().uncompressedSizeBytes
        val budget = conservativeCorpusInstallWorkspaceBytes(corpusBytes)
        val baseline = fixture.databaseFootprint()
        var peak = baseline
        val sampler = launch(Dispatchers.IO) {
            while (isActive) {
                peak = maxOf(peak, fixture.databaseFootprint())
                delay(2L)
            }
        }

        fixture.repository.installPackage(session, profileId = "source")
        sampler.cancel()
        sampler.join()
        fixture.checkpointWal()
        peak = maxOf(peak, fixture.databaseFootprint())
        val allocatedGrowth = (peak.allocatedBytes - baseline.allocatedBytes).coerceAtLeast(0L)

        assertTrue("allocated growth=$allocatedGrowth budget=$budget", allocatedGrowth <= budget)
        assertEquals(4_000, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_chunks"))
        assertEquals(4_000, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_chunk_fts"))
        println(
            "ROOM_FTS_BUDGET corpus=$corpusBytes budget=$budget " +
                "peakLogical=${peak.logicalBytes} peakAllocated=${peak.allocatedBytes} " +
                "allocatedGrowth=$allocatedGrowth",
        )
        fixture.close()
    }

    @Test
    fun fileBackedQuotaProducesRealSQLiteFullAndRoomInstallKeepsSessionRetryable() = runBlocking {
        val fixture = FileBackedIntegrationFixture(chunkCount = 2_200)
        val session = fixture.repository.preparePackageImport(fixture.bundle.name) {
            fixture.bundle.inputStream()
        }
        val realSQLiteFull = fixture.createRealSQLiteFullFromFileBackedQuota()
        fixture.failNextFtsWrite(realSQLiteFull)

        val failure = runCatching {
            fixture.repository.installPackage(session, profileId = "source")
        }.exceptionOrNull()

        assertTrue(
            "failure chain=" + generateSequence(failure) { it.cause }
                .joinToString(" -> ") { "${it::class.java.name}:${it.message}" },
            failure is AgentInsufficientStorageException,
        )
        assertTrue(generateSequence(failure) { it.cause }.any { it is SQLiteFullException })
        assertEquals(0, fixture.db.scalarInt("SELECT COUNT(*) FROM agents"))
        assertEquals(0, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_chunks"))
        assertTrue(fixture.snapshotFiles().isNotEmpty())

        assertEquals(AgentStatus.READY, fixture.repository.installPackage(session, "source").agent.status)
        assertTrue(fixture.snapshotFiles().isEmpty())
        fixture.close()
    }

    @Test
    fun errnoNormalizationAcceptsOnlyEnospcAndKeepsThatSessionRetryable() = runBlocking {
        val enospcFixture = FileBackedIntegrationFixture(chunkCount = 16)
        val enospcSession = enospcFixture.repository.preparePackageImport(enospcFixture.bundle.name) {
            enospcFixture.bundle.inputStream()
        }
        enospcFixture.failNextFtsWrite(ErrnoException("fts write", OsConstants.ENOSPC))

        val enospcFailure = runCatching {
            enospcFixture.repository.installPackage(enospcSession, profileId = "source")
        }.exceptionOrNull()

        assertTrue(enospcFailure is AgentInsufficientStorageException)
        assertTrue(generateSequence(enospcFailure) { it.cause }.any { it is ErrnoException })
        assertEquals(
            AgentStatus.READY,
            enospcFixture.repository.installPackage(enospcSession, profileId = "source").agent.status,
        )
        enospcFixture.close()

        val eioFixture = FileBackedIntegrationFixture(chunkCount = 16)
        val eioSession = eioFixture.repository.preparePackageImport(eioFixture.bundle.name) {
            eioFixture.bundle.inputStream()
        }
        eioFixture.failNextFtsWrite(ErrnoException("fts write", OsConstants.EIO))

        val eioFailure = runCatching {
            eioFixture.repository.installPackage(eioSession, profileId = "source")
        }.exceptionOrNull()
        val eioChain = generateSequence(eioFailure) { it.cause }
            .joinToString(" -> ") { error ->
                buildString {
                    append(error::class.java.name)
                    append(':')
                    append(error.message)
                    if (error is ErrnoException) append("[errno=${error.errno}]")
                }
            }
        println("EIO_FAILURE_CHAIN $eioChain")

        assertFalse(eioChain, eioFailure is AgentInsufficientStorageException)
        assertTrue(
            eioChain,
            generateSequence(eioFailure) { it.cause }
                .filterIsInstance<ErrnoException>()
                .any { it.errno == OsConstants.EIO },
        )
        assertTrue(
            eioChain,
            runCatching {
                eioFixture.repository.installPackage(eioSession, profileId = "source")
            }.exceptionOrNull() is AgentBundleException,
        )
        eioFixture.close()
    }

    @Test
    fun realSignedBundleInstalls2200ChunksSourceAndReadyStateThroughRoomAndFilesystem() = runBlocking {
        val fixture = IntegrationFixture(chunkCount = 2_200)
        val session = fixture.repository.preparePackageImport(fixture.bundle.name) {
            fixture.bundle.inputStream()
        }

        val result = fixture.repository.installPackage(session, profileId = "source")

        assertEquals(AgentStatus.READY, result.agent.status)
        assertEquals(2_200, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_chunks"))
        assertEquals(2_200, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_chunk_fts"))
        assertEquals(1, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_source_files"))
        assertEquals("conflict-0", fixture.db.scalarString("SELECT conflictKey FROM agent_chunks WHERE chunkId = 'chunk-0'"))
        assertEquals("[]", fixture.db.scalarString("SELECT sourceAliasesJson FROM agent_chunks WHERE chunkId = 'chunk-0'"))
        val sourcePath = fixture.db.scalarString("SELECT filePath FROM agent_source_files LIMIT 1")
        val bundlePath = fixture.db.scalarString("SELECT bundlePath FROM agent_versions LIMIT 1")
        assertTrue(sourcePath.endsWith("/${fixture.sourceHash}/payload"))
        assertEquals("source", File(sourcePath).readText())
        fixture.repository.recoverFileLifecycle()
        assertTrue(File(sourcePath).isFile)
        assertTrue(File(bundlePath).isFile)
        assertFalse(session.stagedFile.exists())
        fixture.close()
    }

    @Test
    fun realRoomRollbackAndCancellationLeaveNoPartialDatabaseOrInstalledFiles() = runBlocking {
        val failures = listOf<Throwable>(
            IllegalStateException("READY transition commit failure"),
            CancellationException("commit boundary cancellation"),
        )
        failures.forEach { injected ->
            val fixture = IntegrationFixture(
                chunkCount = 401,
                transactionFailure = injected,
            )
            val session = fixture.repository.preparePackageImport(fixture.bundle.name) {
                fixture.bundle.inputStream()
            }

            val failure = runCatching {
                fixture.repository.installPackage(session, profileId = "source")
            }.exceptionOrNull()

            assertEquals(injected::class, failure!!::class)
            assertEquals(0, fixture.db.scalarInt("SELECT COUNT(*) FROM agents"))
            assertEquals(0, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_chunks"))
            assertFalse(session.stagedFile.exists())
            assertTrue(fixture.filesRoot.walkTopDown().none(File::isFile))
            fixture.close()
        }
    }

    @Test
    fun realFilesystemMoveCopyAndCleanupFailuresRemainAtomicAndRecoverable() = runBlocking {
        listOf(
            FailureMode.FINAL_MOVE,
            FailureMode.SOURCE_COPY,
        ).forEach { mode ->
            val fixture = IntegrationFixture(chunkCount = 401, fileFailure = mode)
            val session = fixture.repository.preparePackageImport(fixture.bundle.name) {
                fixture.bundle.inputStream()
            }

            assertTrue(runCatching { fixture.repository.installPackage(session, profileId = "source") }.isFailure)
            assertEquals(0, fixture.db.scalarInt("SELECT COUNT(*) FROM agents"))
            assertEquals(0, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_chunks"))
            assertFalse(session.stagedFile.exists())
            assertTrue(fixture.filesRoot.walkTopDown().none(File::isFile))
            fixture.close()
        }

        val cleanup = IntegrationFixture(
            chunkCount = 401,
            transactionFailure = IllegalStateException("commit failure"),
            fileFailure = FailureMode.DELETE,
        )
        val session = cleanup.repository.preparePackageImport(cleanup.bundle.name) {
            cleanup.bundle.inputStream()
        }
        assertTrue(runCatching { cleanup.repository.installPackage(session, profileId = "source") }.isFailure)
        assertEquals(0, cleanup.db.scalarInt("SELECT COUNT(*) FROM agents"))
        assertTrue(cleanup.tombstones().any { it.name.endsWith(".data") })

        cleanup.failureOps.failureMode = FailureMode.NONE
        cleanup.repository.recoverFileLifecycle()
        assertTrue(cleanup.tombstones().isEmpty())
        cleanup.close()

        val committedCleanup = IntegrationFixture(chunkCount = 401)
        val installed = committedCleanup.repository.preparePackageImport(committedCleanup.bundle.name) {
            committedCleanup.bundle.inputStream()
        }
        committedCleanup.repository.installPackage(installed, profileId = "source")
        val installedSourcePath = committedCleanup.db.scalarString("SELECT filePath FROM agent_source_files LIMIT 1")
        assertTrue(File(installedSourcePath).isFile)
        committedCleanup.failureOps.failureMode = FailureMode.DELETE

        val removal = committedCleanup.repository.removeVersion("person.integration", 2)

        assertEquals(AgentVersionRemovalOutcome.REMOVED_CLEANUP_PENDING, removal.outcome)
        assertEquals(0, committedCleanup.db.scalarInt("SELECT COUNT(*) FROM agents"))
        assertEquals(0, committedCleanup.db.scalarInt("SELECT COUNT(*) FROM agent_source_files"))
        assertTrue(committedCleanup.tombstones().any { it.name.endsWith(".data") })
        committedCleanup.failureOps.failureMode = FailureMode.NONE
        committedCleanup.repository.recoverFileLifecycle()
        assertTrue(committedCleanup.tombstones().isEmpty())
        assertTrue(committedCleanup.filesRoot.walkTopDown().none(File::isFile))
        committedCleanup.close()
    }

    @Test
    fun sqliteFullDuringFtsWriteRollsBackAndKeepsTheSameSnapshotSessionRetryable() = runBlocking {
        val fixture = IntegrationFixture(chunkCount = 2_200, sqliteFullAfterFtsWrite = 2)
        val session = fixture.repository.preparePackageImport(fixture.bundle.name) {
            fixture.bundle.inputStream()
        }

        val failure = runCatching {
            fixture.repository.installPackage(session, profileId = "source")
        }.exceptionOrNull()

        assertTrue(failure is AgentInsufficientStorageException)
        assertEquals(0, fixture.db.scalarInt("SELECT COUNT(*) FROM agents"))
        assertEquals(0, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_chunks"))
        assertEquals(0, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_version_packages WHERE installed = 1"))
        assertTrue(fixture.filesRoot.walkTopDown().none(File::isFile))
        assertTrue(fixture.snapshotFiles().isNotEmpty())

        fixture.sqliteFullController.enabled = false
        val installed = fixture.repository.installPackage(session, profileId = "source")

        assertEquals(AgentStatus.READY, installed.agent.status)
        assertTrue(fixture.snapshotFiles().isEmpty())
        fixture.close()
    }
}

private enum class FailureMode {
    NONE,
    FINAL_MOVE,
    SOURCE_COPY,
    DELETE,
}

private class IntegrationFixture(
    chunkCount: Int,
    transactionFailure: Throwable? = null,
    fileFailure: FailureMode = FailureMode.NONE,
    sqliteFullAfterFtsWrite: Int? = null,
) {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = context.cacheDir.resolve("agent-real-integration-${UUID.randomUUID()}").apply { mkdirs() }
    val filesRoot: File = root.resolve("files")
    private val cacheRoot: File = root.resolve("cache")
    private val packageRoot: File = root.resolve("packages").apply { mkdirs() }
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    val failureOps = IntegrationFailureFileOps(fileFailure)
    val sqliteFullController = SQLiteFullController(sqliteFullAfterFtsWrite)
    val sourceHash = "source".encodeToByteArray().sha256()
    private val packages = RealV2Packages(packageRoot, sourceHash)
    val bundle: File = packages.bundle(chunkCount)
    val repository = AgentRepository(
        filesDir = filesRoot,
        cacheDir = cacheRoot,
        dao = sqliteFullController.wrap(db.agentDao()),
        conversationDao = db.conversationDao(),
        reader = AgentBundleReader(temporaryDirectory = root.resolve("reader")),
        transactionRunner = AgentTransactionRunner { block ->
            db.withTransaction {
                block()
                transactionFailure?.let { throw it }
            }
        },
        fileOps = failureOps,
        timeProvider = TimeProvider { 100L },
        ioDispatcher = Dispatchers.IO,
    )

    fun tombstones(): List<File> =
        filesRoot.resolve("agents/.tombstones").listFiles().orEmpty().filter(File::isFile)

    fun snapshotFiles(): List<File> =
        cacheRoot.resolve("agent-install-snapshots").listFiles().orEmpty().filter(File::isFile)

    fun close() {
        db.close()
        root.deleteRecursively()
    }
}

private data class DatabaseFootprint(
    val logicalBytes: Long,
    val allocatedBytes: Long,
) : Comparable<DatabaseFootprint> {
    override fun compareTo(other: DatabaseFootprint): Int =
        allocatedBytes.compareTo(other.allocatedBytes)
}

private class FileBackedIntegrationFixture(
    chunkCount: Int,
    journalMode: RoomDatabase.JournalMode = RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING,
) {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = context.cacheDir.resolve("agent-file-backed-${UUID.randomUUID()}").apply { mkdirs() }
    private val filesRoot = root.resolve("files")
    private val cacheRoot = root.resolve("cache")
    private val packageRoot = root.resolve("packages").apply { mkdirs() }
    private val databaseName = "agent-file-backed-${UUID.randomUUID()}.db"
    private val databasePath = context.getDatabasePath(databaseName)
    private val ftsFailure = OneShotFtsFailureController()
    val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
        .setJournalMode(journalMode)
        .build()
    private val sourceHash = "source".encodeToByteArray().sha256()
    val bundle: File = RealV2Packages(packageRoot, sourceHash).bundle(chunkCount)
    val repository = AgentRepository(
        filesDir = filesRoot,
        cacheDir = cacheRoot,
        dao = ftsFailure.wrap(db.agentDao()),
        conversationDao = db.conversationDao(),
        reader = AgentBundleReader(temporaryDirectory = root.resolve("reader")),
        transactionRunner = AgentTransactionRunner { block -> db.withTransaction { block() } },
        timeProvider = TimeProvider { 100L },
        ioDispatcher = Dispatchers.IO,
    )

    fun databaseFootprint(): DatabaseFootprint {
        val files = listOf(
            databasePath,
            File(databasePath.absolutePath + "-wal"),
            File(databasePath.absolutePath + "-shm"),
            File(databasePath.absolutePath + "-journal"),
        ).filter(File::isFile)
        return DatabaseFootprint(
            logicalBytes = files.sumOf(File::length),
            allocatedBytes = files.sumOf { file ->
                runCatching { Math.multiplyExact(Os.stat(file.absolutePath).st_blocks, 512L) }
                    .getOrDefault(file.length())
            },
        )
    }

    fun checkpointWal() {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            check(cursor.moveToFirst())
        }
    }

    fun failNextFtsWrite(error: Throwable) {
        ftsFailure.failure = error
    }

    fun createRealSQLiteFullFromFileBackedQuota(): SQLiteFullException {
        val quotaPath = root.resolve("sqlite-full-quota.db")
        val quota = SQLiteDatabase.openOrCreateDatabase(quotaPath, null)
        return try {
            quota.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
                check(cursor.moveToFirst())
            }
            quota.execSQL("CREATE TABLE payload(value BLOB NOT NULL)")
            val currentPages = quota.rawQuery("PRAGMA page_count", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
            quota.rawQuery("PRAGMA max_page_count=$currentPages", null).use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getLong(0) == currentPages)
            }
            try {
                quota.execSQL("INSERT INTO payload(value) VALUES(zeroblob(1048576))")
                error("file-backed max_page_count did not raise SQLiteFullException")
            } catch (error: SQLiteFullException) {
                error
            }
        } finally {
            quota.close()
        }
    }

    fun snapshotFiles(): List<File> =
        cacheRoot.resolve("agent-install-snapshots").listFiles().orEmpty().filter(File::isFile)

    fun close() {
        db.close()
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
    }
}

private class OneShotFtsFailureController {
    var failure: Throwable? = null

    fun wrap(delegate: com.harnessapk.storage.AgentDao): com.harnessapk.storage.AgentDao =
        Proxy.newProxyInstance(
            delegate::class.java.classLoader,
            arrayOf(com.harnessapk.storage.AgentDao::class.java),
        ) { _, method, arguments ->
            if (method.name == "insertChunkSearchRows") {
                failure?.let { injected ->
                    failure = null
                    throw injected
                }
            }
            try {
                method.invoke(delegate, *(arguments ?: emptyArray()))
            } catch (error: InvocationTargetException) {
                throw requireNotNull(error.cause)
            }
        } as com.harnessapk.storage.AgentDao
}

private class SQLiteFullController(private val failAfterCall: Int?) {
    var enabled: Boolean = failAfterCall != null
    private var calls = 0

    fun wrap(delegate: com.harnessapk.storage.AgentDao): com.harnessapk.storage.AgentDao {
        if (failAfterCall == null) return delegate
        return Proxy.newProxyInstance(
            delegate::class.java.classLoader,
            arrayOf(com.harnessapk.storage.AgentDao::class.java),
        ) { _, method, arguments ->
            if (enabled && method.name == "insertChunkSearchRows" && ++calls == failAfterCall) {
                throw SQLiteFullException("database or disk is full")
            }
            try {
                method.invoke(delegate, *(arguments ?: emptyArray()))
            } catch (error: InvocationTargetException) {
                throw requireNotNull(error.cause)
            }
        } as com.harnessapk.storage.AgentDao
    }
}

private class IntegrationFailureFileOps(
    var failureMode: FailureMode,
    private val delegate: AgentFileOps = DefaultAgentFileOps(),
) : AgentFileOps by delegate {
    override suspend fun write(target: File, block: suspend (OutputStream) -> Unit) {
        if (
            failureMode == FailureMode.SOURCE_COPY &&
            target.parentFile?.parentFile?.name == "sources"
        ) {
            throw AgentBundleException("injected source copy failure")
        }
        delegate.write(target, block)
    }

    override suspend fun moveAtomically(source: File, destination: File) {
        if (failureMode == FailureMode.FINAL_MOVE && destination.name == "bundle.hbundle") {
            throw AgentBundleException("injected final move failure")
        }
        delegate.moveAtomically(source, destination)
    }

    override fun delete(file: File): Boolean =
        if (
            failureMode == FailureMode.DELETE &&
            file.parentFile?.name == ".tombstones" &&
            file.name.endsWith(".data")
        ) {
            false
        } else {
            delegate.delete(file)
        }
}

private class RealV2Packages(
    private val root: File,
    private val sourceHash: String,
    private val privateKey: Ed25519PrivateKeyParameters = Ed25519PrivateKeyParameters(SecureRandom()),
) {
    fun bundle(chunkCount: Int): File {
        val corpus = corpus(chunkCount)
        val source = source()
        val agent = agent(corpus, source)
        return signed(
            "person.integration-v2-source.hbundle",
            linkedMapOf(
                "bundle-manifest.json" to """
                    {"agent":{"fileName":"${agent.name}","id":"person.integration","sha256":"${agent.sha256()}","sizeBytes":${agent.length()},"version":2},"profile":"source","schemaVersion":2,"selectedPackageIds":["core-evidence","source-source-direct"],"type":"hbundle"}
                """.trimIndent().encodeToByteArray(),
                "packages/${agent.name}" to agent.readBytes(),
                "packages/${corpus.name}" to corpus.readBytes(),
                "packages/${source.name}" to source.readBytes(),
            ),
        )
    }

    private fun corpus(chunkCount: Int): File {
        val chunks = buildString {
            repeat(chunkCount) { index ->
                append(
                    """{"authorship":"direct","conflictKey":"conflict-$index","context":"来源 / 第 $index 段","duplicateGroup":"","genre":"speech","id":"chunk-$index","keywords":["调查"],"location":"第 $index 段","ngrams":[],"parentIds":["node-root"],"period":"1926","sourceAliases":[],"sourceHash":"$sourceHash","sourceId":"source-direct","sourceTitle":"测试来源","text":"调查以后再下结论 $index。","simHash":"${index.toString(16).padStart(16, '0')}"}""",
                )
                append('\n')
            }
        }
        return signed(
            "core-evidence.hcorpus",
            linkedMapOf(
                "manifest.json" to """
                    {"agentId":"person.integration","authorship":["direct"],"chunkCount":$chunkCount,"coverage":["identity:self"],"genres":["speech"],"id":"core-evidence","installClass":"required","periods":["1926"],"schemaVersion":2,"sourceHashes":["$sourceHash"],"sourceIds":["source-direct"],"topLevelIds":["node-root"],"type":"hcorpus","version":2}
                """.trimIndent().encodeToByteArray(),
                "sources.json" to """
                    [{"authorship":"direct","extractedChars":100,"fileName":"source.txt","format":"txt","genre":"speech","period":"1926","rawSizeBytes":6,"sourceHash":"$sourceHash","sourceId":"source-direct","storedName":"source.txt","title":"测试来源"}]
                """.trimIndent().encodeToByteArray(),
                "nodes.jsonl" to (
                    """{"id":"node-root","kind":"source","parentId":null,"path":["测试来源"],"sourceId":"source-direct","summary":"来源","title":"测试来源"}""" + "\n"
                    ).encodeToByteArray(),
                "chunks.jsonl" to chunks.encodeToByteArray(),
                "duplicates.jsonl" to byteArrayOf(),
            ),
        )
    }

    private fun source(): File = signed(
        "source-source-direct.hsource",
        linkedMapOf(
            "manifest.json" to """
                {"agentId":"person.integration","fileName":"source.txt","id":"source-source-direct","rawSizeBytes":6,"schemaVersion":2,"sourceHash":"$sourceHash","sourceId":"source-direct","storedName":"source.txt","type":"hsource","version":2}
            """.trimIndent().encodeToByteArray(),
            "files/source.txt" to "source".encodeToByteArray(),
        ),
    )

    private fun agent(corpus: File, source: File): File {
        val installPlan = """
            {"packages":[{"dependencies":[],"fileName":"${corpus.name}","id":"core-evidence","installClass":"required","sha256":"${corpus.sha256()}","sizeBytes":${corpus.length()},"type":"hcorpus"},{"dependencies":[],"fileName":"${source.name}","id":"source-source-direct","installClass":"source","sha256":"${source.sha256()}","sizeBytes":${source.length()},"type":"hsource"}],"profiles":[{"id":"lite","packageIds":["core-evidence"],"recommended":false},{"id":"balanced","packageIds":["core-evidence"],"recommended":true},{"id":"complete","packageIds":["core-evidence"],"recommended":false},{"id":"source","packageIds":["core-evidence","source-source-direct"],"recommended":false}],"recommendedProfileId":"balanced","requiredCorpusIds":["core-evidence"],"schemaVersion":2}
        """.trimIndent()
        return signed(
            "person.integration-v2.hagent",
            linkedMapOf(
                "manifest.json" to """
                    {"agent":{"id":"person.integration","name":"集成人物","version":2},"requiredCorpora":["core-evidence"],"runnableWithoutCorpora":false,"schemaVersion":2,"type":"hagent"}
                """.trimIndent().encodeToByteArray(),
                "agent/persona.md" to "只依据证据回答。".encodeToByteArray(),
                "agent/identity.json" to """{"relationships":[],"roles":["调查者"],"selfNames":["我"],"timeHorizon":"1926"}""".encodeToByteArray(),
                "agent/voice.json" to """{"avoidPatterns":[],"defaultForm":"直接","evidence":["chunk-0"],"preferredTerms":["调查"],"rhetoricalMoves":[],"sentenceRhythm":["短句"]}""".encodeToByteArray(),
                "agent/worldview.jsonl" to (
                    """{"aliases":[],"conditions":[],"confidence":1.0,"evidence":["chunk-0"],"id":"stance-1","period":"1926","statement":"调查先于结论","topic":"调查"}""" + "\n"
                    ).encodeToByteArray(),
                "agent/episodes.jsonl" to byteArrayOf(),
                "agent/concepts.json" to """{"concepts":[]}""".encodeToByteArray(),
                "agent/examples.jsonl" to byteArrayOf(),
                "agent/openers.json" to """{"alternatives":[],"default":"你好"}""".encodeToByteArray(),
                "agent/eval.jsonl" to (
                    """{"category":"grounding","corpusId":"core-evidence","expectedEvidence":["chunk-0"],"id":"eval-1","period":"1926","question":"如何调查"}""" + "\n"
                    ).encodeToByteArray(),
                "install-plan.json" to installPlan.encodeToByteArray(),
            ),
        )
    }

    private fun signed(name: String, files: Map<String, ByteArray>): File {
        val target = root.resolve(name)
        val checksums = files.entries.sortedBy { it.key }.joinToString(
            prefix = "{\"files\":{",
            postfix = "}}",
            separator = ",",
        ) { (path, bytes) -> "\"$path\":\"${bytes.sha256()}\"" }.encodeToByteArray()
        val signer = Ed25519Signer().apply {
            init(true, privateKey)
            update(checksums, 0, checksums.size)
        }
        val signature = signer.generateSignature()
        val rawPublicKey = privateKey.generatePublicKey().encoded
        val signatureJson = """
            {"algorithm":"Ed25519","publicKey":"${Base64.getEncoder().encodeToString(rawPublicKey)}","signature":"${Base64.getEncoder().encodeToString(signature)}","signedFile":"checksums.json"}
        """.trimIndent().encodeToByteArray()
        ZipOutputStream(target.outputStream().buffered()).use { output ->
            (files + mapOf("checksums.json" to checksums, "signature.json" to signatureJson)).forEach { (path, bytes) ->
                output.putNextEntry(ZipEntry(path))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return target
    }
}

private fun AppDatabase.scalarInt(query: String): Int =
    openHelper.readableDatabase.query(query).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

private fun AppDatabase.scalarString(query: String): String =
    openHelper.readableDatabase.query(query).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

private fun File.sha256(): String = inputStream().buffered().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
