package com.harnessapk.agent

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

    private fun signedBundle(
        extraEntries: Map<String, ByteArray> = emptyMap(),
        tamperPersonaAfterSigning: Boolean = false,
        corruptSignature: Boolean = false,
    ): File {
        val directory = Files.createTempDirectory("agent-bundle-test").toFile().apply { deleteOnExit() }
        val target = File(directory, "fixture.hbundle").apply { deleteOnExit() }
        val manifest = """
            {"agent":{"conceptsPath":"agent/concepts.json","evalPath":"agent/eval.jsonl","examplesPath":"agent/examples.jsonl","id":"agent-1","name":"资料研究代理","personaPath":"agent/persona.md","requiredCorpora":["corpus-1"],"summary":"基于资料模拟","version":1,"worldviewPath":"agent/worldview.jsonl"},"corpora":[{"chunksPath":"corpora/corpus-1/chunks.jsonl","id":"corpus-1","required":true,"sourceHash":"source-hash","sourcesPath":"corpora/corpus-1/sources.json","title":"测试资料"}],"schemaVersion":1}
        """.trimIndent().encodeToByteArray()
        val chunk = """
            {"id":"chunk-investigation","keywords":["调查","事实"],"location":"第一章 · 1","ngrams":["调查","事实"],"sourceHash":"source-hash","sourceTitle":"测试资料","text":"没有调查，没有发言权。研究问题必须从事实出发。"}
        """.trimIndent().plus("\n").encodeToByteArray()
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
            "corpora/corpus-1/chunks.jsonl" to chunk,
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
                output.putNextEntry(ZipEntry(path))
                output.write(if (tamperPersonaAfterSigning && path == "agent/persona.md") "被篡改".encodeToByteArray() else bytes)
                output.closeEntry()
            }
            output.putNextEntry(ZipEntry("checksums.json"))
            output.write(checksums)
            output.closeEntry()
            output.putNextEntry(ZipEntry("signature.json"))
            output.write(signatureJson)
            output.closeEntry()
        }
        return target
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
