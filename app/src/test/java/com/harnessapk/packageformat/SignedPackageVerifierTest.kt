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

    @Test
    fun `rejects signed Unix symlink and executable entries`() {
        val root = Files.createTempDirectory("signed-package-unix-mode").toFile().apply { deleteOnExit() }
        val symlink = signedFixture(
            target = File(root, "symlink.hwiki"),
            payloads = standardPayloads(),
        )
        markZipEntryUnixMode(symlink, "manifest.json", 0xA1FF0000.toInt())
        val executable = signedFixture(
            target = File(root, "executable.hwiki"),
            payloads = standardPayloads(),
        )
        markZipEntryUnixMode(executable, "content.sqlite", 0x81ED0000.toInt())

        assertVerifierFailure(symlink, "符号链接")
        assertVerifierFailure(executable, "可执行文件")
    }

    @Test
    fun `rejects manifest above its declared read bound before payload verification`() {
        val root = Files.createTempDirectory("signed-package-manifest-bound").toFile().apply { deleteOnExit() }
        val archive = signedFixture(
            target = File(root, "manifest-too-large.hwiki"),
            payloads = linkedMapOf(
                "manifest.json" to "x".repeat(17).encodeToByteArray(),
                "content.sqlite" to byteArrayOf(1, 2, 3),
            ),
        )

        assertVerifierFailure(
            archive = archive,
            expectedMessage = "manifest超过大小上限",
            policy = WIKI_FIXTURE_POLICY.copy(maxManifestBytes = 16),
        )
    }

    @Test
    fun `accepts a valid Zip64 signed archive`() {
        val root = Files.createTempDirectory("signed-package-zip64").toFile().apply { deleteOnExit() }
        val archive = signedFixture(
            target = File(root, "zip64.hwiki"),
            payloads = standardPayloads(),
        )
        rewriteAsZip64(archive)

        val verified = SignedPackageVerifier().verify(archive.toPath(), WIKI_FIXTURE_POLICY)

        assertEquals(setOf("manifest.json", "content.sqlite"), verified.payloads.keys)
    }

    private fun assertVerifierFailure(
        archive: File,
        expectedMessage: String? = null,
        policy: SignedPackagePolicy = WIKI_FIXTURE_POLICY,
    ) {
        try {
            SignedPackageVerifier().verify(archive.toPath(), policy)
            fail("Expected SignedPackageException for ${archive.name}")
        } catch (error: SignedPackageException) {
            if (expectedMessage != null) {
                assertTrue(
                    "${error.message} should contain $expectedMessage",
                    error.message.orEmpty().contains(expectedMessage),
                )
            }
            // Expected.
        }
    }

    private fun standardPayloads() = linkedMapOf(
        "manifest.json" to "{\"schemaVersion\":1}".encodeToByteArray(),
        "content.sqlite" to byteArrayOf(1, 2, 3),
    )

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

    private fun markZipEntryUnixMode(file: File, targetName: String, externalAttributes: Int) {
        val bytes = file.readBytes()
        var offset = 0
        while (offset <= bytes.size - CENTRAL_DIRECTORY_HEADER_SIZE) {
            if (bytes.readIntLe(offset) != CENTRAL_DIRECTORY_SIGNATURE.toInt()) {
                offset += 1
                continue
            }
            val nameLength = bytes.readShortLe(offset + 28)
            val extraLength = bytes.readShortLe(offset + 30)
            val commentLength = bytes.readShortLe(offset + 32)
            val name = bytes.copyOfRange(offset + CENTRAL_DIRECTORY_HEADER_SIZE, offset + CENTRAL_DIRECTORY_HEADER_SIZE + nameLength)
                .decodeToString()
            if (name == targetName) {
                bytes[offset + 5] = UNIX_HOST_SYSTEM.toByte()
                bytes.writeIntLe(offset + 38, externalAttributes)
                file.writeBytes(bytes)
                return
            }
            offset += CENTRAL_DIRECTORY_HEADER_SIZE + nameLength + extraLength + commentLength
        }
        fail("Central directory entry not found: $targetName")
    }

    private fun rewriteAsZip64(file: File) {
        val original = file.readBytes()
        val eocdOffset = original.size - END_OF_CENTRAL_DIRECTORY_SIZE
        require(original.readIntLe(eocdOffset) == END_OF_CENTRAL_DIRECTORY_SIGNATURE.toInt())
        val entryCount = original.readShortLe(eocdOffset + 10).toLong()
        val centralDirectorySize = original.readUnsignedIntLe(eocdOffset + 12)
        val centralDirectoryOffset = original.readUnsignedIntLe(eocdOffset + 16)
        val output = ByteArrayOutputStream().apply {
            write(original, 0, eocdOffset)
            val zip64EocdOffset = size().toLong()
            writeIntLe(ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE)
            writeLongLe(ZIP64_END_OF_CENTRAL_DIRECTORY_RECORD_SIZE)
            writeShortLe(45)
            writeShortLe(45)
            writeIntLe(0)
            writeIntLe(0)
            writeLongLe(entryCount)
            writeLongLe(entryCount)
            writeLongLe(centralDirectorySize)
            writeLongLe(centralDirectoryOffset)
            writeIntLe(ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE)
            writeIntLe(0)
            writeLongLe(zip64EocdOffset)
            writeIntLe(1)
            writeIntLe(END_OF_CENTRAL_DIRECTORY_SIGNATURE)
            writeShortLe(0)
            writeShortLe(0)
            writeShortLe(ZIP64_ENTRY_SENTINEL)
            writeShortLe(ZIP64_ENTRY_SENTINEL)
            writeIntLe(ZIP64_OFFSET_SENTINEL)
            writeIntLe(ZIP64_OFFSET_SENTINEL)
            writeShortLe(0)
        }
        file.writeBytes(output.toByteArray())
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

    private fun ByteArrayOutputStream.writeLongLe(value: Long) {
        repeat(Long.SIZE_BYTES) { index ->
            write(((value ushr (index * Byte.SIZE_BITS)) and 0xff).toInt())
        }
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    private fun ByteArray.readShortLe(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.readIntLe(offset: Int): Int =
        readShortLe(offset) or (readShortLe(offset + 2) shl 16)

    private fun ByteArray.readUnsignedIntLe(offset: Int): Long =
        readShortLe(offset).toLong() or (readShortLe(offset + 2).toLong() shl 16)

    private fun ByteArray.writeIntLe(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }

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
        const val ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06064b50L
        const val ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE = 0x07064b50L
        const val CENTRAL_DIRECTORY_HEADER_SIZE = 46
        const val END_OF_CENTRAL_DIRECTORY_SIZE = 22
        const val ZIP64_END_OF_CENTRAL_DIRECTORY_RECORD_SIZE = 44L
        const val ZIP64_ENTRY_SENTINEL = 0xffff
        const val ZIP64_OFFSET_SENTINEL = 0xffffffffL
        const val UNIX_HOST_SYSTEM = 3
    }
}
