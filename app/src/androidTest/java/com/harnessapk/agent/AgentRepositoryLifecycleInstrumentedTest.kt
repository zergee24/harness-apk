package com.harnessapk.agent

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class AgentRepositoryLifecycleInstrumentedTest {
    @Test
    fun realSignedBundleInstalls2200ChunksSourceAndReadyStateThroughRoomAndFilesystem() = runBlocking {
        val fixture = IntegrationFixture(chunkCount = 2_200)
        val session = fixture.repository.preparePackageImport(fixture.bundle.name) {
            fixture.bundle.inputStream()
        }

        val result = fixture.repository.installPackage(session)

        assertEquals(AgentStatus.READY, result.agent.status)
        assertEquals(2_200, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_chunks"))
        assertEquals(2_200, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_chunk_fts"))
        assertEquals(1, fixture.db.scalarInt("SELECT COUNT(*) FROM agent_source_files"))
        assertEquals("conflict-0", fixture.db.scalarString("SELECT conflictKey FROM agent_chunks WHERE chunkId = 'chunk-0'"))
        assertEquals("[]", fixture.db.scalarString("SELECT sourceAliasesJson FROM agent_chunks WHERE chunkId = 'chunk-0'"))
        val sourcePath = fixture.db.scalarString("SELECT filePath FROM agent_source_files LIMIT 1")
        assertTrue(sourcePath.endsWith("/${fixture.sourceHash}/payload"))
        assertEquals("source", File(sourcePath).readText())
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

            val failure = runCatching { fixture.repository.installPackage(session) }.exceptionOrNull()

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

            assertTrue(runCatching { fixture.repository.installPackage(session) }.isFailure)
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
        assertTrue(runCatching { cleanup.repository.installPackage(session) }.isFailure)
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
        committedCleanup.repository.installPackage(installed)
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
) {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = context.cacheDir.resolve("agent-real-integration-${UUID.randomUUID()}").apply { mkdirs() }
    val filesRoot: File = root.resolve("files")
    private val cacheRoot: File = root.resolve("cache")
    private val packageRoot: File = root.resolve("packages").apply { mkdirs() }
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    val failureOps = IntegrationFailureFileOps(fileFailure)
    val sourceHash = "source".encodeToByteArray().sha256()
    private val packages = RealV2Packages(packageRoot, sourceHash)
    val bundle: File = packages.bundle(chunkCount)
    val repository = AgentRepository(
        filesDir = filesRoot,
        cacheDir = cacheRoot,
        dao = db.agentDao(),
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

    fun close() {
        db.close()
        root.deleteRecursively()
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
            "person.integration-v2-balanced.hbundle",
            linkedMapOf(
                "bundle-manifest.json" to """
                    {"agent":{"fileName":"${agent.name}","id":"person.integration","sha256":"${agent.sha256()}","sizeBytes":${agent.length()},"version":2},"profile":"balanced","schemaVersion":2,"selectedPackageIds":["core-evidence","source-source-direct"],"type":"hbundle"}
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
            {"packages":[{"dependencies":[],"fileName":"${corpus.name}","id":"core-evidence","installClass":"required","sha256":"${corpus.sha256()}","sizeBytes":${corpus.length()},"type":"hcorpus"},{"dependencies":[],"fileName":"${source.name}","id":"source-source-direct","installClass":"source","sha256":"${source.sha256()}","sizeBytes":${source.length()},"type":"hsource"}],"profiles":[{"id":"balanced","packageIds":["core-evidence","source-source-direct"],"recommended":true}],"recommendedProfileId":"balanced","requiredCorpusIds":["core-evidence"],"schemaVersion":2}
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
