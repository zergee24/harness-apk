package com.harnessapk.wiki

import com.harnessapk.packageformat.PublisherFingerprint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class WikiPackageImportCoordinatorTest {
    @Test
    fun `prepares private session confirms unknown publisher and cleans files after install`() = runBlocking {
        val root = Files.createTempDirectory("wiki-import-coordinator")
        try {
            var receivedImport: ConfirmedWikiImport? = null
            val coordinator = WikiPackageImportCoordinator(
                cacheDir = root.toFile(),
                inspectPackage = { archive, inspectionDirectory -> inspectionFor(archive, inspectionDirectory) },
                install = { confirmed ->
                    receivedImport = confirmed
                    WikiInstallResult(WikiInstallOutcome.INSTALLED, confirmed.inspection.manifest.ref)
                },
                isKnownPublisher = { false },
                hasReadyVersion = { false },
            )

            val session = coordinator.prepareImport(
                sourceName = "history.hwiki",
                openInputStream = { ByteArrayInputStream("signed archive".encodeToByteArray()) },
                sourceBytes = "signed archive".length.toLong(),
            )

            assertFalse(session.isKnownPublisher)
            assertTrue(session.defaultEnabledForNewConversations)
            assertTrue(session.stagedArchive.isFile)

            coordinator.install(session, enabledForNewConversations = true)

            assertEquals(session.packageHash, receivedImport?.packageHash)
            assertTrue(receivedImport?.enabledForNewConversations == true)
            assertFalse(session.stagingDirectory.exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `known publisher skips confirmation and cancellation removes the staged package`() = runBlocking {
        val root = Files.createTempDirectory("wiki-import-coordinator")
        try {
            val coordinator = WikiPackageImportCoordinator(
                cacheDir = root.toFile(),
                inspectPackage = { archive, inspectionDirectory -> inspectionFor(archive, inspectionDirectory) },
                install = { error("install must not run") },
                isKnownPublisher = { true },
                hasReadyVersion = { true },
            )

            val session = coordinator.prepareImport(
                sourceName = "history.hwiki",
                openInputStream = { ByteArrayInputStream("signed archive".encodeToByteArray()) },
            )

            assertTrue(session.isKnownPublisher)
            assertFalse(session.defaultEnabledForNewConversations)
            coordinator.discard(session)
            assertFalse(session.stagingDirectory.exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun inspectionFor(archive: Path, inspectionDirectory: Path): WikiImportInspection {
        Files.createDirectories(inspectionDirectory)
        val database = inspectionDirectory.resolve("content.sqlite")
        Files.writeString(database, "sqlite fixture")
        val archiveBytes = Files.readAllBytes(archive)
        return WikiImportInspection(
            manifest = WikiManifest(
                ref = WikiRef("fixture.history", 1),
                title = "史料测试库",
                description = "",
                languages = listOf("zh-Hant"),
                contentHash = database.sha256(),
                publisherKeyId = "ed25519:publisher",
                publisherName = "测试发布者",
                conceptNamespace = "fixture-v1",
                conceptRegistryHash = "0".repeat(64),
                builderProfile = "fixture",
                capabilities = WikiCapabilities(
                    sourceHierarchy = true,
                    sourceSearch = true,
                    hierarchicalSummaries = false,
                    termIndex = false,
                    temporalAnnotations = false,
                    crossWikiLinks = false,
                    generatedPages = GeneratedPages.NONE,
                    claimGraph = false,
                    vectorIndex = false,
                    sourceAttachments = false,
                ),
            ),
            publisherFingerprint = PublisherFingerprint("ed25519", "publisher", "a".repeat(64)),
            archiveSizeBytes = archiveBytes.size.toLong(),
            contentSizeBytes = Files.size(database),
            stagedDatabase = database,
            manifestJson = "{}",
        )
    }
}

private fun Path.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(Files.readAllBytes(this))
    .joinToString("") { "%02x".format(it) }
