package com.harnessapk.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WikiPackageReaderTest {
    @Test
    fun `verified package stages matching database before invoking inspector`() {
        val fixture = signedWikiPackage()
        val stagingDirectory = Files.createTempDirectory("wiki-package-reader")
        val inspected = mutableListOf<java.nio.file.Path>()
        val reader = WikiPackageReader(
            stagingDirectory = stagingDirectory,
            databaseInspector = WikiDatabaseInspector { path -> inspected.add(path) },
            preExtractionAvailableBytes = { Long.MAX_VALUE },
        )

        val inspection = reader.inspect(fixture.archive.toPath())

        assertEquals(WikiRef("fixture.history", 1), inspection.manifest.ref)
        assertEquals(fixture.content.size.toLong(), inspection.contentSizeBytes)
        assertEquals("ed25519:${fixture.publisherFingerprint}", inspection.publisherFingerprint.keyId)
        assertEquals(listOf(inspection.stagedDatabase), inspected)
        assertTrue(inspection.stagedDatabase.startsWith(stagingDirectory))
        assertTrue(Files.readAllBytes(inspection.stagedDatabase).contentEquals(fixture.content))
    }

    @Test
    fun `content hash mismatch fails before database inspection`() {
        val fixture = signedWikiPackage(contentHash = "0".repeat(64))
        val inspected = mutableListOf<java.nio.file.Path>()
        val reader = WikiPackageReader(
            stagingDirectory = Files.createTempDirectory("wiki-package-mismatch"),
            databaseInspector = WikiDatabaseInspector { path -> inspected.add(path) },
            preExtractionAvailableBytes = { Long.MAX_VALUE },
        )

        try {
            reader.inspect(fixture.archive.toPath())
            fail("Expected WikiPackageException")
        } catch (_: WikiPackageException) {
            // Expected.
        }
        assertTrue(inspected.isEmpty())
    }

    @Test
    fun `publisher key id mismatch fails before database inspection`() {
        val fixture = signedWikiPackage(publisherKeyId = "ed25519:${"0".repeat(64)}")
        val inspected = mutableListOf<java.nio.file.Path>()
        val reader = WikiPackageReader(
            stagingDirectory = Files.createTempDirectory("wiki-package-publisher-key"),
            databaseInspector = WikiDatabaseInspector { path -> inspected.add(path) },
            preExtractionAvailableBytes = { Long.MAX_VALUE },
        )

        try {
            reader.inspect(fixture.archive.toPath())
            fail("Expected WikiPackageException")
        } catch (error: WikiPackageException) {
            assertTrue(error.message.orEmpty().contains("publisher.keyId"))
        }
        assertTrue(inspected.isEmpty())
    }

    @Test
    fun `compression ratio over wiki policy fails before database inspection`() {
        val fixture = signedWikiPackage(content = ByteArray(128 * 1024))
        val inspected = mutableListOf<java.nio.file.Path>()
        val reader = WikiPackageReader(
            stagingDirectory = Files.createTempDirectory("wiki-package-compression-ratio"),
            databaseInspector = WikiDatabaseInspector { path -> inspected.add(path) },
            preExtractionAvailableBytes = { Long.MAX_VALUE },
        )

        try {
            reader.inspect(fixture.archive.toPath())
            fail("Expected WikiPackageException")
        } catch (error: WikiPackageException) {
            assertTrue(error.message.orEmpty().contains("压缩比"))
        }
        assertTrue(inspected.isEmpty())
    }

    @Test
    fun `insufficient space rejects the package before extracting content`() {
        val fixture = signedWikiPackage()
        val stagingDirectory = Files.createTempDirectory("wiki-package-insufficient-space")
        val inspected = mutableListOf<java.nio.file.Path>()
        val reader = WikiPackageReader(
            stagingDirectory = stagingDirectory,
            databaseInspector = WikiDatabaseInspector { path -> inspected.add(path) },
            preExtractionAvailableBytes = { 0L },
        )

        val failure = runCatching { reader.inspect(fixture.archive.toPath()) }.exceptionOrNull()

        assertTrue(failure is WikiInsufficientStorageException)
        assertTrue(inspected.isEmpty())
        assertTrue(stagingDirectory.toFile().walkTopDown().none { it.name == "content.sqlite" })
    }

    private fun signedWikiPackage(
        contentHash: String? = null,
        content: ByteArray = "SQLite fixture payload".encodeToByteArray(),
        publisherKeyId: String? = null,
    ): WikiFixture {
        val root = Files.createTempDirectory("signed-wiki-package").toFile().apply { deleteOnExit() }
        val archive = File(root, "fixture.history-v1.hwiki").apply { deleteOnExit() }
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKey = keyPair.public.encoded.takeLast(32).toByteArray()
        val publisherFingerprint = publicKey.sha256()
        val manifest = manifestJson(
            contentHash = contentHash ?: content.sha256(),
            publisherKeyId = publisherKeyId ?: "ed25519:$publisherFingerprint",
        ).encodeToByteArray()
        val payloads = linkedMapOf(
            "manifest.json" to manifest,
            "content.sqlite" to content,
        )
        val checksums = payloads.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{\"files\":{", postfix = "}}", separator = ",") { (path, bytes) ->
                "\"$path\":\"${bytes.sha256()}\""
            }
            .encodeToByteArray()
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(checksums)
        val signatureJson = """
            {"algorithm":"Ed25519","publicKey":"${Base64.getEncoder().encodeToString(publicKey)}","signature":"${Base64.getEncoder().encodeToString(signer.sign())}","signedFile":"checksums.json"}
        """.trimIndent().encodeToByteArray()

        ZipOutputStream(archive.outputStream().buffered()).use { output ->
            (payloads + mapOf("checksums.json" to checksums, "signature.json" to signatureJson)).forEach { (path, bytes) ->
                output.putNextEntry(ZipEntry(path))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return WikiFixture(archive, content, publisherFingerprint)
    }

    private fun manifestJson(contentHash: String, publisherKeyId: String): String =
        """{"builder":{"name":"harness-wiki-builder","profile":"generic-v1","version":"1"},"capabilities":{"claimGraph":false,"crossWikiLinks":false,"generatedPages":"none","hierarchicalSummaries":true,"sourceAttachments":false,"sourceHierarchy":true,"sourceSearch":true,"temporalAnnotations":false,"termIndex":true,"vectorIndex":false},"conceptNamespace":"fixture-v1","conceptRegistryHash":"${"a".repeat(64)}","publisher":{"keyId":"$publisherKeyId","name":"Fixture Publisher"},"schemaVersion":1,"type":"hwiki","wiki":{"contentHash":"$contentHash","description":"Fixture database","id":"fixture.history","language":["zh-Hant","zh-Hans"],"title":"Fixture History","version":1}}"""

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    private data class WikiFixture(
        val archive: File,
        val content: ByteArray,
        val publisherFingerprint: String,
    )
}
