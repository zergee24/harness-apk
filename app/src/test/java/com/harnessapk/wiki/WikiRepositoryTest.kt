package com.harnessapk.wiki

import com.harnessapk.common.TimeProvider
import com.harnessapk.packageformat.PublisherFingerprint
import com.harnessapk.storage.WikiDao
import com.harnessapk.storage.WikiEntity
import com.harnessapk.storage.WikiVersionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class WikiRepositoryTest {
    @Test
    fun `failed install leaves neither metadata nor final directory`() = runTest {
        val root = Files.createTempDirectory("wiki-repository-failure").toFile()
        val dao = FakeWikiDao()
        val fileOps = FailingWikiFileOps().apply { failBeforeAtomicMove = true }
        val repository = WikiRepository(
            filesDir = root.resolve("files"),
            dao = dao,
            transactionRunner = WikiTransactionRunner { block -> block() },
            fileOps = fileOps,
            timeProvider = TimeProvider { 1L },
        )
        val reference = WikiRef("fixture.history", 1)

        try {
            val failure = runCatching { repository.install(confirmedImport(root, reference)) }.exceptionOrNull()

            assertTrue(failure is WikiInstallException)
            assertNull(dao.findVersion(reference.wikiId, reference.version))
            assertFalse(repository.versionDirectory(reference).toFile().exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `move failure after the version directory becomes visible still rolls it back`() = runTest {
        val root = Files.createTempDirectory("wiki-repository-visible-move-failure").toFile()
        val reference = WikiRef("fixture.history", 1)
        val dao = FakeWikiDao()
        val repository = WikiRepository(
            filesDir = root.resolve("files"),
            dao = dao,
            transactionRunner = WikiTransactionRunner { block -> block() },
            fileOps = MoveThenFailWikiFileOps(),
            timeProvider = TimeProvider { 1L },
        )

        try {
            assertTrue(runCatching { repository.install(confirmedImport(root, reference)) }.exceptionOrNull() is WikiInstallException)
            assertNull(dao.findVersion(reference.wikiId, reference.version))
            assertFalse(repository.versionDirectory(reference).toFile().exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `space calculation uses Long and installs exactly at the required bound`() = runTest {
        val root = Files.createTempDirectory("wiki-repository-space").toFile()
        val reference = WikiRef("fixture.history", 1)
        val confirmed = confirmedImport(root, reference)
        val required = WikiRepository.requiredInstallBytes(
            confirmed.inspection.archiveSizeBytes,
            confirmed.inspection.contentSizeBytes,
        )
        var availableBytes = required - 1L
        val dao = FakeWikiDao()
        val repository = WikiRepository(
            filesDir = root.resolve("files"),
            dao = dao,
            transactionRunner = WikiTransactionRunner { block -> block() },
            timeProvider = TimeProvider { 1L },
            privateInstallAvailableBytes = { availableBytes },
        )

        try {
            assertTrue(runCatching { repository.install(confirmed) }.exceptionOrNull() is WikiInsufficientStorageException)

            availableBytes = required
            assertEquals(WikiInstallOutcome.INSTALLED, repository.install(confirmed).outcome)
            assertTrue(WikiRepository.requiredInstallBytes(3_000_000_000L, 2_000_000_000L) > Int.MAX_VALUE)
            assertTrue(
                runCatching { WikiRepository.requiredInstallBytes(Long.MAX_VALUE, 1L) }
                    .exceptionOrNull() is WikiInstallException,
            )
            assertTrue(
                runCatching {
                    repository.install(
                        confirmed.copy(
                            inspection = confirmed.inspection.copy(archiveSizeBytes = -1L),
                        ),
                    )
                }.exceptionOrNull() is WikiInstallException,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sparse source larger than Int max keeps its Long size through installation`() = runTest {
        val root = Files.createTempDirectory("wiki-repository-sparse-long").toFile()
        val reference = WikiRef("fixture.history", 1)
        val sparseSource = root.resolve("staging/sparse-content.sqlite")
        val sourceBytes = Int.MAX_VALUE.toLong() + 1024L
        sparseSource.parentFile?.mkdirs()
        FileChannel.open(
            sparseSource.toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.position(sourceBytes - 1L)
            channel.write(ByteBuffer.wrap(byteArrayOf(1)))
        }
        val baseImport = confirmedImport(root, reference)
        val confirmed = baseImport.copy(
            inspection = baseImport.inspection.copy(
                stagedDatabase = sparseSource.toPath(),
                contentSizeBytes = sourceBytes,
            ),
        )
        val dao = FakeWikiDao()
        val repository = WikiRepository(
            filesDir = root.resolve("files"),
            dao = dao,
            transactionRunner = WikiTransactionRunner { block -> block() },
            fileOps = LongReportingWikiFileOps(),
            timeProvider = TimeProvider { 1L },
            privateInstallAvailableBytes = {
                WikiRepository.requiredInstallBytes(confirmed.inspection.archiveSizeBytes, sourceBytes)
            },
        )

        try {
            assertEquals(WikiInstallOutcome.INSTALLED, repository.install(confirmed).outcome)
            assertEquals(sourceBytes, dao.findVersion(reference.wikiId, reference.version)?.sizeBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `same package is idempotent while a different hash for the same ref is rejected`() = runTest {
        val root = Files.createTempDirectory("wiki-repository-idempotent").toFile()
        val reference = WikiRef("fixture.history", 1)
        val dao = FakeWikiDao()
        val repository = repository(root, dao)
        val confirmed = confirmedImport(root, reference)

        try {
            assertEquals(WikiInstallOutcome.INSTALLED, repository.install(confirmed).outcome)
            assertEquals(WikiInstallOutcome.ALREADY_INSTALLED, repository.install(confirmed).outcome)
            assertTrue(
                runCatching { repository.install(confirmed.copy(packageHash = "e".repeat(64))) }
                    .exceptionOrNull() is WikiInstallException,
            )
            assertEquals("d".repeat(64), dao.findVersion(reference.wikiId, reference.version)?.packageHash)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `enabling a version disables the previous ready default for the same wiki`() = runTest {
        val root = Files.createTempDirectory("wiki-repository-default").toFile()
        val dao = FakeWikiDao()
        val repository = repository(root, dao)
        val first = WikiRef("fixture.history", 1)
        val second = WikiRef("fixture.history", 2)

        try {
            repository.install(confirmedImport(root, first))
            repository.install(confirmedImport(root, second))

            assertFalse(dao.findVersion(first.wikiId, first.version)?.enabledForNewConversations == true)
            assertTrue(dao.findVersion(second.wikiId, second.version)?.enabledForNewConversations == true)
            repository.activate(second)
            assertEquals(second.version, dao.findWiki(second.wikiId)?.activeVersion)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed metadata deletion restores the private version directory`() = runTest {
        val root = Files.createTempDirectory("wiki-repository-delete").toFile()
        val reference = WikiRef("fixture.history", 1)
        val dao = FakeWikiDao()
        var failTransactions = false
        val repository = repository(
            root = root,
            dao = dao,
            transactionRunner = WikiTransactionRunner { block ->
                if (failTransactions) throw WikiInstallException("injected metadata failure")
                block()
            },
        )

        try {
            repository.install(confirmedImport(root, reference))
            failTransactions = true

            assertTrue(runCatching { repository.removeVersion(reference) }.exceptionOrNull() is WikiInstallException)
            assertTrue(repository.versionDirectory(reference).toFile().isDirectory)
            assertTrue(dao.findVersion(reference.wikiId, reference.version) != null)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun confirmedImport(root: File, reference: WikiRef): ConfirmedWikiImport {
        val stagedDatabase = root.resolve("staging/content.sqlite").apply {
            parentFile?.mkdirs()
            writeText("fixture sqlite")
        }
        val manifest = WikiManifest(
            ref = reference,
            title = "Fixture History",
            description = "fixture",
            languages = listOf("zh-Hans"),
            contentHash = "c".repeat(64),
            publisherKeyId = "ed25519:${"b".repeat(64)}",
            publisherName = "Fixture Publisher",
            conceptNamespace = "fixture-v1",
            conceptRegistryHash = "a".repeat(64),
            builderProfile = "generic-v1",
            capabilities = WikiCapabilities(
                sourceHierarchy = true,
                sourceSearch = true,
                hierarchicalSummaries = true,
                termIndex = true,
                temporalAnnotations = false,
                crossWikiLinks = false,
                generatedPages = GeneratedPages.NONE,
                claimGraph = false,
                vectorIndex = false,
                sourceAttachments = false,
            ),
        )
        return ConfirmedWikiImport(
            inspection = WikiImportInspection(
                manifest = manifest,
                publisherFingerprint = PublisherFingerprint("Ed25519", hex = "b".repeat(64)),
                archiveSizeBytes = 64L,
                contentSizeBytes = stagedDatabase.length(),
                stagedDatabase = stagedDatabase.toPath(),
                manifestJson = "{}",
            ),
            packageHash = "d".repeat(64),
            enabledForNewConversations = true,
        )
    }

    private fun repository(
        root: File,
        dao: FakeWikiDao,
        transactionRunner: WikiTransactionRunner = WikiTransactionRunner { block -> block() },
    ): WikiRepository = WikiRepository(
        filesDir = root.resolve("files"),
        dao = dao,
        transactionRunner = transactionRunner,
        timeProvider = TimeProvider { 1L },
    )
}

private class FailingWikiFileOps(
    private val delegate: WikiFileOps = DefaultWikiFileOps(),
) : WikiFileOps by delegate {
    var failBeforeAtomicMove = false

    override suspend fun moveAtomically(source: java.nio.file.Path, destination: java.nio.file.Path) {
        if (failBeforeAtomicMove) throw WikiInstallException("injected atomic move failure")
        delegate.moveAtomically(source, destination)
    }
}

private class MoveThenFailWikiFileOps(
    private val delegate: WikiFileOps = DefaultWikiFileOps(),
) : WikiFileOps by delegate {
    override suspend fun moveAtomically(source: java.nio.file.Path, destination: java.nio.file.Path) {
        delegate.moveAtomically(source, destination)
        throw WikiInstallException("injected failure after atomic move")
    }
}

private class LongReportingWikiFileOps(
    private val delegate: WikiFileOps = DefaultWikiFileOps(),
) : WikiFileOps by delegate {
    override suspend fun copy(source: java.nio.file.Path, target: java.nio.file.Path): Long {
        delegate.createDirectories(requireNotNull(target.parent))
        Files.write(target, byteArrayOf(1), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        return Files.size(source)
    }

    override fun fsync(file: java.nio.file.Path) = Unit
}

private class FakeWikiDao : WikiDao {
    private val wikis = linkedMapOf<String, WikiEntity>()
    private val versions = linkedMapOf<Pair<String, Int>, WikiVersionEntity>()

    override fun observeWikis(): Flow<List<WikiEntity>> = flowOf(wikis.values.toList())

    override suspend fun findWiki(wikiId: String): WikiEntity? = wikis[wikiId]

    override suspend fun findVersion(wikiId: String, version: Int): WikiVersionEntity? = versions[wikiId to version]

    override suspend fun listVersions(wikiId: String): List<WikiVersionEntity> =
        versions.values.filter { it.wikiId == wikiId }.sortedBy(WikiVersionEntity::version)

    override suspend fun insertWiki(entity: WikiEntity) {
        check(wikis.putIfAbsent(entity.id, entity) == null)
    }

    override suspend fun insertVersion(entity: WikiVersionEntity) {
        check(versions.putIfAbsent(entity.wikiId to entity.version, entity) == null)
    }

    override suspend fun updateActiveVersion(wikiId: String, version: Int?, updatedAt: Long): Int {
        val wiki = wikis[wikiId] ?: return 0
        wikis[wikiId] = wiki.copy(activeVersion = version, updatedAt = updatedAt)
        return 1
    }

    override suspend fun disableReadyDefaultVersions(wikiId: String): Int {
        var updated = 0
        versions.entries.forEach { (key, value) ->
            if (value.wikiId == wikiId && value.state == WikiVersionState.READY.name && value.enabledForNewConversations) {
                versions[key] = value.copy(enabledForNewConversations = false)
                updated += 1
            }
        }
        return updated
    }

    override suspend fun setEnabledForNewConversations(wikiId: String, version: Int, enabled: Boolean): Int {
        val key = wikiId to version
        val current = versions[key] ?: return 0
        versions[key] = current.copy(enabledForNewConversations = enabled)
        return 1
    }

    override suspend fun updateVersionState(
        wikiId: String,
        version: Int,
        state: String,
        invalidReason: String?,
    ): Int {
        val key = wikiId to version
        val current = versions[key] ?: return 0
        versions[key] = current.copy(state = state, invalidReason = invalidReason)
        return 1
    }

    override suspend fun deleteVersion(wikiId: String, version: Int): Int =
        if (versions.remove(wikiId to version) == null) 0 else 1

    override suspend fun deleteWikiIfNoVersions(wikiId: String): Int {
        if (versions.values.any { it.wikiId == wikiId }) return 0
        return if (wikis.remove(wikiId) == null) 0 else 1
    }
}
