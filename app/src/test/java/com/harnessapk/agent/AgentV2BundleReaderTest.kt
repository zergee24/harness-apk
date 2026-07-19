package com.harnessapk.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentV2BundleReaderTest {
    @Test
    fun readsBalancedBundleAndVerifiesEverySelectedChild() {
        val fixture = Fixture()
        val corpus = fixture.corpusPackage()
        val agent = fixture.agentPackage(corpus)
        val bundle = fixture.bundlePackage(agent, listOf(corpus), selectedPackageIds = listOf(CORE_ID))

        val parsed = AgentBundleReader().readPackage(bundle) as V2Bundle

        assertEquals("balanced", parsed.profile.id)
        assertEquals(listOf(CORE_ID), parsed.selectedCorpusIds)
        assertEquals(listOf(CORE_ID), parsed.corpora.map { it.manifest.id })
        assertEquals("person.fixture", parsed.agent.manifest.id)
        assertTrue(parsed.agent.isRunnable)
        assertEquals(fixture.fingerprint, parsed.publisherFingerprint)
    }

    @Test
    fun dispatchesStandaloneV2PackagesAndKeepsV1Wrapper() {
        val fixture = Fixture()
        val corpusFile = fixture.corpusPackage()
        val agentFile = fixture.agentPackage(corpusFile)
        val sourceFile = fixture.sourcePackage()

        val agent = AgentBundleReader().readPackage(agentFile) as V2Agent
        val corpus = AgentBundleReader().readPackage(corpusFile) as V2Corpus
        val source = AgentBundleReader().readPackage(sourceFile) as V2Source
        val v1 = AgentBundleReader().readPackage(v1Bundle()) as V1Bundle

        assertEquals(listOf("我"), agent.identity.selfNames)
        assertEquals("core-evidence", corpus.manifest.id)
        assertEquals("source-direct", source.manifest.sourceId)
        assertFalse(agent.isRunnable)
        assertEquals("agent-1", v1.bundle.agent.id)
        assertEquals(v1.bundle, AgentBundleReader().readBundle(v1.bundle.file))
    }

    @Test
    fun rejectsBundleWhoseSelectedChildrenDoNotMatchInstallPlan() {
        val fixture = Fixture()
        val corpus = fixture.corpusPackage()
        val agent = fixture.agentPackage(corpus)

        assertPackageFailure("profile") {
            AgentBundleReader().readPackage(
                fixture.bundlePackage(agent, emptyList(), selectedPackageIds = emptyList()),
            )
        }
        assertPackageFailure("未声明") {
            AgentBundleReader().readPackage(
                fixture.bundlePackage(
                    agent,
                    listOf(corpus),
                    selectedPackageIds = listOf(CORE_ID),
                    extraPackages = mapOf("extra.hcorpus" to corpus.readBytes()),
                ),
            )
        }
    }

    @Test
    fun rejectsDuplicateProfilePackageAndWrongChildHashOrSize() {
        val fixture = Fixture()
        val corpus = fixture.corpusPackage()

        assertPackageFailure("重复") {
            AgentBundleReader().readPackage(
                fixture.bundlePackage(
                    fixture.agentPackage(corpus, balancedIds = listOf(CORE_ID, CORE_ID)),
                    listOf(corpus),
                    selectedPackageIds = listOf(CORE_ID),
                ),
            )
        }
        val agentWithWrongHash = fixture.agentPackage(corpus, declaredCorpusHash = "0".repeat(64))
        assertPackageFailure("SHA-256") {
            AgentBundleReader().readPackage(
                fixture.bundlePackage(agentWithWrongHash, listOf(corpus), listOf(CORE_ID)),
            )
        }
        val agentWithWrongSize = fixture.agentPackage(corpus, declaredCorpusSize = corpus.length() + 1)
        assertPackageFailure("大小") {
            AgentBundleReader().readPackage(
                fixture.bundlePackage(agentWithWrongSize, listOf(corpus), listOf(CORE_ID)),
            )
        }
    }

    @Test
    fun rejectsWrongChildPublisherTypeAgentOrVersion() {
        val fixture = Fixture()
        val otherPublisher = Fixture()
        val cases = listOf(
            otherPublisher.corpusPackage() to "发布者",
            fixture.corpusPackage(type = "hsource") to "不允许",
            fixture.corpusPackage(agentId = "other.agent") to "agent",
            fixture.corpusPackage(version = 3) to "版本",
        )

        cases.forEach { (corpus, message) ->
            val agent = fixture.agentPackage(corpus)
            assertPackageFailure(message) {
                AgentBundleReader().readPackage(
                    fixture.bundlePackage(agent, listOf(corpus), listOf(CORE_ID)),
                )
            }
        }
    }

    @Test
    fun rejectsMalformedJsonlAndDuplicateSemanticIds() {
        val fixture = Fixture()
        val malformed = fixture.corpusPackage(chunks = "{not-json}\n")
        val duplicate = fixture.corpusPackage(chunks = CHUNK_JSON + CHUNK_JSON)

        assertPackageFailure("chunks.jsonl") {
            AgentBundleReader().readPackage(malformed)
        }
        assertPackageFailure("重复") {
            AgentBundleReader().readPackage(duplicate)
        }
    }

    @Test
    fun rejectsBackslashExecutableSpecialModeRatioAndExcessiveNesting() {
        val fixture = Fixture()

        assertPackageFailure("反斜杠") {
            AgentBundleReader().readPackage(
                fixture.signedPackage(
                    "bad-backslash.hsource",
                    fixture.sourceEntries() + ("files\\bad.txt" to "bad".encodeToByteArray()),
                ),
            )
        }
        assertPackageFailure("可执行") {
            val executable = fixture.sourcePackage()
            patchUnixMode(executable, "files/source.txt", 0x81ED)
            AgentBundleReader().readPackage(executable)
        }
        assertPackageFailure("特殊文件") {
            val special = fixture.sourcePackage()
            patchUnixMode(special, "files/source.txt", 0x21A4)
            AgentBundleReader().readPackage(special)
        }
        assertPackageFailure("压缩比") {
            AgentBundleReader().readPackage(
                fixture.signedPackage(
                    "ratio.hsource",
                    fixture.sourceEntries() + ("files/zeros.txt" to ByteArray(2 * 1024 * 1024)),
                ),
            )
        }
        assertPackageFailure("嵌套") {
            val deep = (1..20).joinToString("/") { "level$it" } + "/payload.txt"
            AgentBundleReader().readPackage(
                fixture.signedPackage("deep.hsource", fixture.sourceEntries() + (deep to byteArrayOf(1))),
            )
        }
    }

    @Test
    fun removesPrivateChildTempsAfterNestedFailure() {
        val fixture = Fixture()
        val tempRoot = Files.createTempDirectory("agent-reader-temp").toFile()
        val corpus = fixture.corpusPackage(corruptSignature = true)
        val agent = fixture.agentPackage(corpus)
        val bundle = fixture.bundlePackage(agent, listOf(corpus), listOf(CORE_ID))

        assertPackageFailure("签名") {
            AgentBundleReader(temporaryDirectory = tempRoot).readPackage(bundle)
        }

        assertTrue(tempRoot.listFiles().orEmpty().isEmpty())
    }

    private class Fixture(
        private val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
    ) {
        val fingerprint: String = rawPublicKey().sha256()

        fun corpusPackage(
            type: String = "hcorpus",
            agentId: String = "person.fixture",
            version: Int = 2,
            chunks: String = CHUNK_JSON,
            corruptSignature: Boolean = false,
        ): File = signedPackage(
            "core-evidence.hcorpus",
            linkedMapOf(
                "manifest.json" to """
                    {"agentId":"$agentId","authorship":["direct"],"chunkCount":1,"coverage":["identity:self"],"genres":["speech"],"id":"$CORE_ID","installClass":"required","periods":["1926"],"schemaVersion":2,"sourceHashes":["${"a".repeat(64)}"],"sourceIds":["source-direct"],"topLevelIds":["node-root"],"type":"$type","version":$version}
                """.trimIndent().encodeToByteArray(),
                "sources.json" to SOURCE_JSON.encodeToByteArray(),
                "nodes.jsonl" to NODE_JSON.encodeToByteArray(),
                "chunks.jsonl" to chunks.encodeToByteArray(),
                "duplicates.jsonl" to byteArrayOf(),
            ),
            corruptSignature,
        )

        fun agentPackage(
            corpus: File,
            balancedIds: List<String> = listOf(CORE_ID),
            declaredCorpusHash: String = corpus.sha256(),
            declaredCorpusSize: Long = corpus.length(),
        ): File {
            val profiles = listOf(
                "lite" to listOf(CORE_ID),
                "balanced" to balancedIds,
                "complete" to listOf(CORE_ID),
                "source" to listOf(CORE_ID),
            ).joinToString(",") { (id, ids) ->
                """{"id":"$id","packageIds":[${ids.joinToString(",") { "\"$it\"" }}],"recommended":${id == "balanced"}}"""
            }
            val plan = """
                {"packages":[{"dependencies":[],"fileName":"${corpus.name}","id":"$CORE_ID","installClass":"required","sha256":"$declaredCorpusHash","sizeBytes":$declaredCorpusSize,"type":"hcorpus"}],"profiles":[$profiles],"recommendedProfileId":"balanced","requiredCorpusIds":["$CORE_ID"],"schemaVersion":2}
            """.trimIndent()
            return signedPackage(
                "person.fixture-v2.hagent",
                linkedMapOf(
                    "manifest.json" to """
                        {"agent":{"id":"person.fixture","name":"测试人物","version":2},"requiredCorpora":["$CORE_ID"],"runnableWithoutCorpora":false,"schemaVersion":2,"type":"hagent"}
                    """.trimIndent().encodeToByteArray(),
                    "agent/persona.md" to "只依据证据回答。".encodeToByteArray(),
                    "agent/identity.json" to """{"relationships":[],"roles":["调查者"],"selfNames":["我"],"timeHorizon":"1926"}""".encodeToByteArray(),
                    "agent/voice.json" to """{"avoidPatterns":[],"defaultForm":"直接","evidence":["chunk-core"],"preferredTerms":["调查"],"rhetoricalMoves":[],"sentenceRhythm":["短句"]}""".encodeToByteArray(),
                    "agent/worldview.jsonl" to ("""{"aliases":[],"conditions":[],"confidence":1.0,"evidence":["chunk-core"],"id":"stance-1","period":"1926","statement":"调查先于结论","topic":"调查"}""" + "\n").encodeToByteArray(),
                    "agent/episodes.jsonl" to ("""{"evidence":["chunk-core"],"id":"episode-1","location":"现场","meaning":"掌握事实","participants":[],"period":"1926","summary":"完成调查"}""" + "\n").encodeToByteArray(),
                    "agent/concepts.json" to """{"concepts":[{"aliases":[],"evidence":["chunk-core"],"id":"concept-1","keywords":["事实"],"name":"调查"}]}""".encodeToByteArray(),
                    "agent/examples.jsonl" to ("""{"assistant":"先调查。","evidence":["chunk-core"],"generationType":"synthesized","id":"example-1","intent":"方法","styleTags":["直接"],"user":"如何判断"}""" + "\n").encodeToByteArray(),
                    "agent/openers.json" to """{"alternatives":[],"default":"你好"}""".encodeToByteArray(),
                    "agent/eval.jsonl" to ("""{"category":"grounding","corpusId":"core-evidence","expectedEvidence":["chunk-core"],"id":"eval-1","period":"1926","question":"如何调查"}""" + "\n").encodeToByteArray(),
                    "install-plan.json" to plan.encodeToByteArray(),
                ),
            )
        }

        fun sourcePackage(): File = signedPackage("source-direct.hsource", sourceEntries())

        fun sourceEntries(): Map<String, ByteArray> = linkedMapOf(
            "manifest.json" to """
                {"agentId":"person.fixture","fileName":"source.txt","id":"source-source-direct","rawSizeBytes":6,"schemaVersion":2,"sourceHash":"${"source".encodeToByteArray().sha256()}","sourceId":"source-direct","storedName":"source.txt","type":"hsource","version":2}
            """.trimIndent().encodeToByteArray(),
            "files/source.txt" to "source".encodeToByteArray(),
        )

        fun bundlePackage(
            agent: File,
            children: List<File>,
            selectedPackageIds: List<String>,
            extraPackages: Map<String, ByteArray> = emptyMap(),
        ): File {
            val entries = linkedMapOf<String, ByteArray>()
            entries["bundle-manifest.json"] = """
                {"agent":{"fileName":"${agent.name}","id":"person.fixture","sha256":"${agent.sha256()}","sizeBytes":${agent.length()},"version":2},"profile":"balanced","schemaVersion":2,"selectedPackageIds":[${selectedPackageIds.joinToString(",") { "\"$it\"" }}],"type":"hbundle"}
            """.trimIndent().encodeToByteArray()
            entries["packages/${agent.name}"] = agent.readBytes()
            children.forEach { entries["packages/${it.name}"] = it.readBytes() }
            extraPackages.forEach { (name, bytes) -> entries["packages/$name"] = bytes }
            return signedPackage("person.fixture-v2-balanced.hbundle", entries)
        }

        fun signedPackage(
            name: String,
            files: Map<String, ByteArray>,
            corruptSignature: Boolean = false,
        ): File {
            val directory = Files.createTempDirectory("agent-v2-fixture").toFile().apply { deleteOnExit() }
            val target = File(directory, name).apply { deleteOnExit() }
            val checksums = files.entries.sortedBy { it.key }.joinToString(
                prefix = "{\"files\":{",
                postfix = "}}",
                separator = ",",
            ) { (path, bytes) -> "\"$path\":\"${bytes.sha256()}\"" }.encodeToByteArray()
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(keyPair.private)
            signer.update(checksums)
            val signature = signer.sign().also {
                if (corruptSignature) it[0] = (it[0].toInt() xor 1).toByte()
            }
            val signatureJson = """
                {"algorithm":"Ed25519","publicKey":"${Base64.getEncoder().encodeToString(rawPublicKey())}","signature":"${Base64.getEncoder().encodeToString(signature)}","signedFile":"checksums.json"}
            """.trimIndent().encodeToByteArray()
            ZipOutputStream(target.outputStream().buffered()).use { output ->
                (files + mapOf("checksums.json" to checksums, "signature.json" to signatureJson))
                    .forEach { (path, bytes) ->
                        output.putNextEntry(ZipEntry(path))
                        output.write(bytes)
                        output.closeEntry()
                    }
            }
            return target
        }

        private fun rawPublicKey(): ByteArray = keyPair.public.encoded.takeLast(32).toByteArray()
    }

    private fun v1Bundle(): File {
        val fixture = Fixture()
        val files = linkedMapOf(
            "bundle-manifest.json" to """
                {"agent":{"conceptsPath":"agent/concepts.json","evalPath":"agent/eval.jsonl","examplesPath":"agent/examples.jsonl","id":"agent-1","name":"V1","personaPath":"agent/persona.md","requiredCorpora":[],"summary":"","version":1,"worldviewPath":"agent/worldview.jsonl"},"corpora":[],"schemaVersion":1}
            """.trimIndent().encodeToByteArray(),
            "agent/persona.md" to "V1 persona".encodeToByteArray(),
            "agent/worldview.jsonl" to byteArrayOf(),
            "agent/concepts.json" to """{"concepts":[]}""".encodeToByteArray(),
            "agent/examples.jsonl" to byteArrayOf(),
            "agent/eval.jsonl" to byteArrayOf(),
        )
        return fixture.signedPackage("fixture.hbundle", files)
    }

    private fun assertPackageFailure(expectedMessage: String, block: () -> Unit) {
        try {
            block()
            fail("Expected AgentBundleException")
        } catch (error: AgentBundleException) {
            assertTrue(
                "${error.message} should contain $expectedMessage",
                error.message.orEmpty().contains(expectedMessage, ignoreCase = true),
            )
        }
    }

    companion object {
        private const val CORE_ID = "core-evidence"
        private val SOURCE_JSON = """
            [{"authorship":"direct","extractedChars":100,"fileName":"source.txt","format":"txt","genre":"speech","period":"1926","rawSizeBytes":6,"sourceHash":"${"a".repeat(64)}","sourceId":"source-direct","storedName":"source.txt","title":"测试来源"}]
        """.trimIndent()
        private val NODE_JSON =
            """{"id":"node-root","kind":"source","parentId":null,"path":["测试来源"],"sourceId":"source-direct","summary":"来源","title":"测试来源"}""" + "\n"
        private val CHUNK_JSON = """
            {"authorship":"direct","conflictKey":"","context":"测试来源 / 第一章","duplicateGroup":"core","genre":"speech","id":"chunk-core","keywords":["调查"],"location":"第一章","ngrams":[],"parentIds":["node-root"],"period":"1926","sourceAliases":[],"sourceHash":"${"a".repeat(64)}","sourceId":"source-direct","sourceTitle":"测试来源","text":"调查以后再下结论。","simHash":"0000000000000001"}
        """.trimIndent() + "\n"
    }
}

private fun File.sha256(): String = inputStream().buffered().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

private fun patchUnixMode(file: File, targetName: String, mode: Int) {
    val bytes = file.readBytes()
    var offset = 0
    while (offset <= bytes.size - 46) {
        if (bytes.readIntLeV2(offset) != 0x02014b50) {
            offset += 1
            continue
        }
        val nameLength = bytes.readShortLeV2(offset + 28)
        val extraLength = bytes.readShortLeV2(offset + 30)
        val commentLength = bytes.readShortLeV2(offset + 32)
        val name = bytes.copyOfRange(offset + 46, offset + 46 + nameLength).decodeToString()
        if (name == targetName) {
            bytes[offset + 5] = 3
            bytes.writeIntLeV2(offset + 38, mode shl 16)
            file.writeBytes(bytes)
            return
        }
        offset += 46 + nameLength + extraLength + commentLength
    }
    fail("Central directory entry not found: $targetName")
}

private fun ByteArray.readShortLeV2(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.readIntLeV2(offset: Int): Int =
    readShortLeV2(offset) or (readShortLeV2(offset + 2) shl 16)

private fun ByteArray.writeIntLeV2(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = (value ushr 16).toByte()
    this[offset + 3] = (value ushr 24).toByte()
}
