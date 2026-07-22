package com.harnessapk.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentBundleReaderTest {
    @Test
    fun readsValidSignedBundleAndStreamsChunks() {
        val bundle = signedBundle()
        val reader = AgentBundleReader()

        val parsed = reader.read(bundle)
        val chunks = mutableListOf<AgentCorpusChunk>()
        reader.forEachChunk(parsed, parsed.corpora.single(), chunks::add)

        assertEquals("agent-1", parsed.agent.id)
        assertEquals("资料研究代理", parsed.agent.name)
        assertEquals(1, parsed.agent.version)
        assertEquals("只根据资料回答。", parsed.persona)
        assertEquals(1, chunks.size)
        assertEquals("chunk-investigation", chunks.single().id)
        assertEquals(listOf("调查", "事实"), chunks.single().keywords)
        assertTrue(parsed.publisherFingerprint.matches(Regex("[0-9a-f]{64}")))
        assertEquals(bundle.length(), parsed.compressedSizeBytes)
        assertTrue(parsed.uncompressedSizeBytes > 0)
    }

    @Test
    fun synchronousV1StreamingKeepsVerifiedBytesWhenOriginalIsOverwritten() {
        val first = v1Chunk("chunk-first", "A".repeat(20_000))
        val original = signedBundle(chunkLines = first + v1Chunk("chunk-second", "B".repeat(20_000)), stored = true)
        val replacement = signedBundle(chunkLines = first + v1Chunk("chunk-mutant", "B".repeat(20_000)), stored = true)
        assertEquals(original.length(), replacement.length())
        val temporaryRoot = Files.createTempDirectory("agent-v1-sync-race").toFile()
        val reader = AgentBundleReader(temporaryDirectory = temporaryRoot)
        val parsed = reader.readBundle(original)
        val ids = mutableListOf<String>()

        reader.forEachChunk(parsed, parsed.corpora.single()) { chunk ->
            ids += chunk.id
            if (ids.size == 1) original.writeBytes(replacement.readBytes())
        }

        assertEquals(listOf("chunk-first", "chunk-second"), ids)
        assertTrue(temporaryRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun suspendingV1StreamingKeepsVerifiedBytesWhenOriginalIsOverwritten() = runBlocking {
        val first = v1Chunk("chunk-first", "A".repeat(20_000))
        val original = signedBundle(chunkLines = first + v1Chunk("chunk-second", "B".repeat(20_000)), stored = true)
        val replacement = signedBundle(chunkLines = first + v1Chunk("chunk-mutant", "B".repeat(20_000)), stored = true)
        assertEquals(original.length(), replacement.length())
        val temporaryRoot = Files.createTempDirectory("agent-v1-suspend-race").toFile()
        val reader = AgentBundleReader(temporaryDirectory = temporaryRoot)
        val parsed = reader.readBundle(original)
        val ids = mutableListOf<String>()

        reader.forEachChunkSuspending(parsed, parsed.corpora.single()) { chunk ->
            ids += chunk.id
            if (ids.size == 1) original.writeBytes(replacement.readBytes())
        }

        assertEquals(listOf("chunk-first", "chunk-second"), ids)
        assertTrue(temporaryRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun v1StreamingCleansStagedBytesOnCallbackFailureAndCancellation() = runBlocking {
        val bundle = signedBundle()
        val temporaryRoot = Files.createTempDirectory("agent-v1-callback-failure").toFile()
        val reader = AgentBundleReader(temporaryDirectory = temporaryRoot)
        val parsed = reader.readBundle(bundle)

        assertBundleFailure("sync callback") {
            reader.forEachChunk(parsed, parsed.corpora.single()) {
                error("sync callback")
            }
        }
        assertTrue(temporaryRoot.listFiles().orEmpty().isEmpty())

        try {
            reader.forEachChunkSuspending(parsed, parsed.corpora.single()) {
                throw CancellationException("cancel callback")
            }
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertEquals("cancel callback", expected.message)
        }
        assertTrue(temporaryRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun v1StreamingUsesDiskIndexRejectsDuplicatesAndCleansTemporaryFiles() {
        val chunkLines = (1..2_000).joinToString(separator = "") { v1Chunk("chunk-$it") } + v1Chunk("chunk-1")
        val temporaryRoot = Files.createTempDirectory("agent-v1-disk-index").toFile()
        val reader = AgentBundleReader(temporaryDirectory = temporaryRoot)
        val parsed = reader.readBundle(signedBundle(chunkLines = chunkLines))
        var sawDiskIndex = false

        assertBundleFailure("重复 id") {
            reader.forEachChunk(parsed, parsed.corpora.single()) {
                if (it.id == "chunk-2000") {
                    sawDiskIndex = temporaryRoot.walkTopDown().any { file ->
                        file.name.startsWith(".corpus-index-") && file.isDirectory && file.listFiles().orEmpty().isNotEmpty()
                    }
                }
            }
        }

        assertTrue(sawDiskIndex)
        assertTrue(temporaryRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun rejectsPathTraversalEntry() {
        val bundle = signedBundle(extraEntries = mapOf("../escape.txt" to "escape".encodeToByteArray()))

        assertBundleFailure("不安全的包内路径") {
            AgentBundleReader().read(bundle)
        }
    }

    @Test
    fun rejectsTamperedChecksummedContent() {
        val bundle = signedBundle(tamperPersonaAfterSigning = true)

        assertBundleFailure("SHA-256") {
            AgentBundleReader().read(bundle)
        }
    }

    @Test
    fun rejectsExecutableEntryEvenWhenSigned() {
        val bundle = signedBundle(extraEntries = mapOf("agent/payload.dex" to byteArrayOf(1, 2, 3)))

        assertBundleFailure("不允许的包内文件") {
            AgentBundleReader().read(bundle)
        }
    }

    @Test
    fun rejectsUnixSymlinkEntry() {
        val bundle = signedBundle()
        markZipEntryAsSymlink(bundle, "agent/manifest.json")

        assertBundleFailure("符号链接") {
            AgentBundleReader().read(bundle)
        }
    }

    @Test
    fun rejectsInvalidSignature() {
        val bundle = signedBundle(corruptSignature = true)

        assertBundleFailure("签名") {
            AgentBundleReader().read(bundle)
        }
    }

    @Test
    fun preservesInjectedSignatureVerifierFailureMapping() {
        val bundle = signedBundle()
        val reader = AgentBundleReader(
            signatureVerifier = AgentSignatureVerifier { _, _, _ -> error("signature verifier failed") },
        )

        assertBundleFailure("人格包读取失败：signature verifier failed") {
            reader.read(bundle)
        }
    }

    private fun signedBundle(
        extraEntries: Map<String, ByteArray> = emptyMap(),
        tamperPersonaAfterSigning: Boolean = false,
        corruptSignature: Boolean = false,
        chunkLines: String = v1Chunk("chunk-investigation", "没有调查，没有发言权。研究问题必须从事实出发。"),
        stored: Boolean = false,
    ): File {
        val directory = Files.createTempDirectory("agent-bundle-test").toFile().apply { deleteOnExit() }
        val target = File(directory, "fixture.hbundle").apply { deleteOnExit() }
        val manifest = """
            {"agent":{"conceptsPath":"agent/concepts.json","evalPath":"agent/eval.jsonl","examplesPath":"agent/examples.jsonl","id":"agent-1","name":"资料研究代理","personaPath":"agent/persona.md","requiredCorpora":["corpus-1"],"summary":"基于资料模拟","version":1,"worldviewPath":"agent/worldview.jsonl"},"corpora":[{"chunksPath":"corpora/corpus-1/chunks.jsonl","id":"corpus-1","required":true,"sourceHash":"source-hash","sourcesPath":"corpora/corpus-1/sources.json","title":"测试资料"}],"schemaVersion":1}
        """.trimIndent().encodeToByteArray()
        val files = linkedMapOf(
            "bundle-manifest.json" to manifest,
            "agent/manifest.json" to "{}".encodeToByteArray(),
            "agent/persona.md" to "只根据资料回答。".encodeToByteArray(),
            "agent/worldview.jsonl" to "{\"id\":\"view-1\",\"evidence\":[\"chunk-investigation\"]}\n".encodeToByteArray(),
            "agent/concepts.json" to "{\"concepts\":[]}".encodeToByteArray(),
            "agent/examples.jsonl" to byteArrayOf(),
            "agent/eval.jsonl" to byteArrayOf(),
            "corpora/corpus-1/manifest.json" to "{}".encodeToByteArray(),
            "corpora/corpus-1/sources.json" to "[{\"title\":\"测试资料\"}]".encodeToByteArray(),
            "corpora/corpus-1/chunks.jsonl" to chunkLines.encodeToByteArray(),
        ).apply { putAll(extraEntries) }
        val checksums = files.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{\"files\":{", postfix = "}}", separator = ",") { (path, bytes) ->
                "\"$path\":\"${bytes.sha256()}\""
            }
            .encodeToByteArray()
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(checksums)
        val signatureBytes = signer.sign().also { if (corruptSignature) it[0] = (it[0].toInt() xor 0x01).toByte() }
        val rawPublicKey = keyPair.public.encoded.takeLast(32).toByteArray()
        val signatureJson = """
            {"algorithm":"Ed25519","publicKey":"${Base64.getEncoder().encodeToString(rawPublicKey)}","signature":"${Base64.getEncoder().encodeToString(signatureBytes)}","signedFile":"checksums.json"}
        """.trimIndent().encodeToByteArray()

        ZipOutputStream(target.outputStream().buffered()).use { output ->
            files.forEach { (path, bytes) ->
                output.putNextEntry(zipEntry(path, bytes, stored))
                output.write(if (tamperPersonaAfterSigning && path == "agent/persona.md") "被篡改".encodeToByteArray() else bytes)
                output.closeEntry()
            }
            output.putNextEntry(zipEntry("checksums.json", checksums, stored))
            output.write(checksums)
            output.closeEntry()
            output.putNextEntry(zipEntry("signature.json", signatureJson, stored))
            output.write(signatureJson)
            output.closeEntry()
        }
        return target
    }

    private fun zipEntry(path: String, bytes: ByteArray, stored: Boolean): ZipEntry =
        ZipEntry(path).apply {
            if (stored) {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = CRC32().apply { update(bytes) }.value
            }
        }

    private fun assertBundleFailure(expectedMessage: String, block: () -> Unit) {
        try {
            block()
            fail("Expected AgentBundleException")
        } catch (error: AgentBundleException) {
            assertTrue("${error.message} should contain $expectedMessage", error.message.orEmpty().contains(expectedMessage))
        }
    }

    private fun markZipEntryAsSymlink(file: File, targetName: String) {
        val bytes = file.readBytes()
        var offset = 0
        while (offset <= bytes.size - 46) {
            if (bytes.readIntLe(offset) != 0x02014b50) {
                offset += 1
                continue
            }
            val nameLength = bytes.readShortLe(offset + 28)
            val extraLength = bytes.readShortLe(offset + 30)
            val commentLength = bytes.readShortLe(offset + 32)
            val name = bytes.copyOfRange(offset + 46, offset + 46 + nameLength).decodeToString()
            if (name == targetName) {
                bytes[offset + 5] = 3
                bytes.writeIntLe(offset + 38, 0xA1FF0000.toInt())
                file.writeBytes(bytes)
                return
            }
            offset += 46 + nameLength + extraLength + commentLength
        }
        fail("Central directory entry not found: $targetName")
    }
}

private fun v1Chunk(id: String, text: String = "保持已验证内容"): String =
    """{"text":"$text","keywords":["调查","事实"],"location":"第一章 · 1","ngrams":["调查","事实"],"sourceHash":"source-hash","sourceTitle":"测试资料","id":"$id"}""" + "\n"

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

private fun ByteArray.readShortLe(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.readIntLe(offset: Int): Int =
    readShortLe(offset) or (readShortLe(offset + 2) shl 16)

private fun ByteArray.writeIntLe(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = (value ushr 16).toByte()
    this[offset + 3] = (value ushr 24).toByte()
}
