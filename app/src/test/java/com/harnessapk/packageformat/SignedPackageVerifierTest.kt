package com.harnessapk.packageformat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SignedPackageVerifierTest {
    @Test
    fun `verifies checksums signature and exact entry allowlist`() {
        val verified = SignedPackageVerifier().verify(
            stagedArchive = desktopFixturePath(),
            policy = WIKI_FIXTURE_POLICY,
        )

        assertEquals(setOf("manifest.json", "content.sqlite"), verified.payloads.keys)
        assertEquals(64, verified.publisherFingerprint.hex.length)
        assertEquals("Ed25519", verified.publisherFingerprint.algorithm)
        assertTrue(verified.payloads.getValue("content.sqlite").uncompressedSizeBytes > 0)
    }

    @Test
    fun `rejects duplicate traversal and undeclared entries`() {
        val root = Files.createTempDirectory("signed-package-invalid").toFile().apply { deleteOnExit() }
        val duplicate = File(root, "duplicate-entry.hwiki")
        writeStoredZip(
            target = duplicate,
            entries = listOf(
                RawZipEntry("manifest.json", "first".encodeToByteArray()),
                RawZipEntry("manifest.json", "second".encodeToByteArray()),
            ),
        )
        val traversal = File(root, "parent-traversal.hwiki")
        writeStoredZip(
            target = traversal,
            entries = listOf(RawZipEntry("../escape", "unsafe".encodeToByteArray())),
        )
        val extra = signedFixture(
            target = File(root, "extra-entry.hwiki"),
            payloads = linkedMapOf(
                "manifest.json" to "{}".encodeToByteArray(),
                "content.sqlite" to byteArrayOf(1, 2, 3),
                "unexpected.txt" to "not allowed".encodeToByteArray(),
            ),
        )

        listOf(duplicate, traversal, extra).forEach { archive ->
            assertVerifierFailure(archive)
        }
    }

    private fun assertVerifierFailure(archive: File) {
        try {
            SignedPackageVerifier().verify(archive.toPath(), WIKI_FIXTURE_POLICY)
            fail("Expected SignedPackageException for ${archive.name}")
        } catch (_: SignedPackageException) {
            // Expected.
        }
    }

    private fun desktopFixturePath() = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
        .map { File(it, "build/wiki-fixture/fixture.history-v1.hwiki") }
        .firstOrNull(File::isFile)
        ?.toPath()
        ?: signedFixture(
            target = File(Files.createTempDirectory("wiki-fixture-fallback").toFile(), "fixture.history-v1.hwiki"),
            payloads = linkedMapOf(
                "manifest.json" to "{\"schemaVersion\":1}".encodeToByteArray(),
                "content.sqlite" to byteArrayOf(1, 2, 3),
            ),
        ).toPath()

    private fun signedFixture(target: File, payloads: LinkedHashMap<String, ByteArray>): File {
        val checksums = payloads.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{\"files\":{", postfix = "}}", separator = ",") { (path, bytes) ->
                "\"$path\":\"${bytes.sha256()}\""
            }
            .encodeToByteArray()
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(checksums)
        val signature = signer.sign()
        val publicKey = keyPair.public.encoded.takeLast(32).toByteArray()
        val signatureJson = """
            {"algorithm":"Ed25519","publicKey":"${Base64.getEncoder().encodeToString(publicKey)}","signature":"${Base64.getEncoder().encodeToString(signature)}","signedFile":"checksums.json"}
        """.trimIndent().encodeToByteArray()

        ZipOutputStream(target.outputStream().buffered()).use { output ->
            (payloads + mapOf("checksums.json" to checksums, "signature.json" to signatureJson)).forEach { (path, bytes) ->
                output.putNextEntry(ZipEntry(path))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return target
    }

    private fun writeStoredZip(target: File, entries: List<RawZipEntry>) {
        val output = ByteArrayOutputStream()
        val layouts = entries.map { entry ->
            val name = entry.name.encodeToByteArray()
            val crc = CRC32().apply { update(entry.bytes) }.value
            val offset = output.size()
            output.writeIntLe(LOCAL_FILE_HEADER_SIGNATURE)
            output.writeShortLe(20)
            output.writeShortLe(0)
            output.writeShortLe(0)
            output.writeShortLe(0)
            output.writeShortLe(0)
            output.writeIntLe(crc)
            output.writeIntLe(entry.bytes.size.toLong())
            output.writeIntLe(entry.bytes.size.toLong())
            output.writeShortLe(name.size)
            output.writeShortLe(0)
            output.write(name)
            output.write(entry.bytes)
            RawZipLayout(entry, name, crc, offset)
        }
        val centralDirectoryOffset = output.size()
        layouts.forEach { layout ->
            output.writeIntLe(CENTRAL_DIRECTORY_SIGNATURE)
            output.writeShortLe(20)
            output.writeShortLe(20)
            output.writeShortLe(0)
            output.writeShortLe(0)
            output.writeShortLe(0)
            output.writeShortLe(0)
            output.writeIntLe(layout.crc)
            output.writeIntLe(layout.entry.bytes.size.toLong())
            output.writeIntLe(layout.entry.bytes.size.toLong())
            output.writeShortLe(layout.name.size)
            output.writeShortLe(0)
            output.writeShortLe(0)
            output.writeShortLe(0)
            output.writeShortLe(0)
            output.writeIntLe(0)
            output.writeIntLe(layout.localHeaderOffset.toLong())
            output.write(layout.name)
        }
        val centralDirectorySize = output.size() - centralDirectoryOffset
        output.writeIntLe(END_OF_CENTRAL_DIRECTORY_SIGNATURE)
        output.writeShortLe(0)
        output.writeShortLe(0)
        output.writeShortLe(entries.size)
        output.writeShortLe(entries.size)
        output.writeIntLe(centralDirectorySize.toLong())
        output.writeIntLe(centralDirectoryOffset.toLong())
        output.writeShortLe(0)
        target.writeBytes(output.toByteArray())
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Long) {
        write((value and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 24) and 0xff).toInt())
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    private data class RawZipEntry(val name: String, val bytes: ByteArray)

    private data class RawZipLayout(
        val entry: RawZipEntry,
        val name: ByteArray,
        val crc: Long,
        val localHeaderOffset: Int,
    )

    private companion object {
        val WIKI_FIXTURE_POLICY = SignedPackagePolicy(
            allowedPayloads = setOf("manifest.json", "content.sqlite"),
            maxArchiveBytes = 256L * 1024 * 1024,
            maxExpandedBytes = 768L * 1024 * 1024,
            maxEntryCount = 4,
        )

        const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50L
        const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50L
        const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50L
    }
}
