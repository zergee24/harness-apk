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
    fun streamsStandaloneAndNestedCorpusFromVerifiedStagedBytes() {
        val fixture = Fixture()
        val secondChunk = CHUNK_JSON.replace("chunk-core", "chunk-second")
        val corpus = fixture.corpusPackage(chunks = CHUNK_JSON + secondChunk, chunkCount = 2)
        val agent = fixture.agentPackage(corpus)
        val bundle = fixture.bundlePackage(agent, listOf(corpus), selectedPackageIds = listOf(CORE_ID))
        val standaloneIds = mutableListOf<String>()

        AgentBundleReader().forEachV2Chunk(corpus) { chunk ->
            standaloneIds += chunk.id
            if (standaloneIds.size == 1) corpus.writeText("replaced after validation")
        }
        val nestedIds = mutableListOf<String>()
        AgentBundleReader().forEachV2Chunk(bundle, CORE_ID) { nestedIds += it.id }

        assertEquals(listOf("chunk-core", "chunk-second"), standaloneIds)
        assertEquals(listOf("chunk-core", "chunk-second"), nestedIds)
    }

    @Test
    fun requiredDeclarationsExactlyMatchRequiredIdsAndEveryProfile() {
        val fixture = Fixture()
        val corpus = fixture.corpusPackage()

        assertPackageFailure("required") {
            AgentBundleReader().readPackage(
                fixture.agentPackage(
                    corpus = corpus,
                    requiredCorpusIds = emptyList(),
                    manifestRequiredCorpora = emptyList(),
                ),
            )
        }
        assertPackageFailure("profile") {
            AgentBundleReader().readPackage(
                fixture.agentPackage(
                    corpus = corpus,
                    balancedIds = emptyList(),
                ),
            )
        }
    }

    @Test
    fun corpusValidationUsesBoundedDiskIndex() {
        val fixture = Fixture()
        val chunks = (1..2_000).joinToString("") { index ->
            CHUNK_JSON.replace("chunk-core", "chunk-$index")
        }

        val parsed = AgentBundleReader().readPackage(
            fixture.corpusPackage(chunks = chunks, chunkCount = 2_000),
        ) as V2Corpus

        assertEquals("disk", parsed.validationDiagnostics.backend)
        assertEquals(2_002L, parsed.validationDiagnostics.indexedRecordCount)
        assertTrue(parsed.validationDiagnostics.peakInMemoryRecordCount <= 1)
        assertTrue(parsed.validationDiagnostics.diskBytes > 0L)
    }

    @Test
    fun rejectsAgentRuntimeJsonlEntryAboveSmallAssetLimitBeforeParsing() {
        val fixture = Fixture()
        val corpus = fixture.corpusPackage()
        val oversizedWorldview = worldviewRecords(count = 9_000, statementLength = 1_024)

        assertPackageFailure("运行时资产") {
            AgentBundleReader().readPackage(
                fixture.agentPackage(
                    corpus,
                    runtimeAssets = mapOf("agent/worldview.jsonl" to oversizedWorldview.encodeToByteArray()),
                ),
            )
        }
    }

    @Test
    fun rejectsDeclaredOversizedAgentAssetBeforeChecksumOrSignatureVerification() {
        val fixture = Fixture()
        val corpus = fixture.corpusPackage()

        assertPackageFailure("运行时资产") {
            AgentBundleReader().readPackage(
                fixture.agentPackage(
                    corpus,
                    runtimeAssets = mapOf(
                        "agent/worldview.jsonl" to worldviewRecords(count = 9_000, statementLength = 1_024).encodeToByteArray(),
                    ),
                    corruptSignature = true,
                ),
            )
        }
    }

    @Test
    fun rejectsAgentRuntimeJsonlWhenCumulativeRecordCountExceedsLimit() {
        val fixture = Fixture()
        val corpus = fixture.corpusPackage()
        val worldview = (1..10_001).joinToString(separator = "") { index ->
            worldviewJson("stance-$index", "调查先于结论")
        }

        assertPackageFailure("记录数") {
            AgentBundleReader().readPackage(
                fixture.agentPackage(
                    corpus,
                    runtimeAssets = mapOf("agent/worldview.jsonl" to worldview.encodeToByteArray()),
                ),
            )
        }
    }

    @Test
    fun rejectsAgentRuntimeJsonlWhenZipSizeMetadataUnderstatesStream() {
        val fixture = Fixture()
        val corpus = fixture.corpusPackage()
        val agent = fixture.agentPackage(
            corpus,
            runtimeAssets = mapOf(
                "agent/worldview.jsonl" to worldviewRecords(count = 9_000, statementLength = 1_024).encodeToByteArray(),
            ),
        )
        patchCentralUncompressedSizeAndDescriptor(agent, "agent/worldview.jsonl", 1)

        assertPackageFailure("运行时资产") { AgentBundleReader().readPackage(agent) }
    }

    @Test
    fun rejectsUnderstatedAgentAssetWhileHashingBeforeChecksumMismatch() {
        val fixture = Fixture()
        val corpus = fixture.corpusPackage()
        val agent = fixture.agentPackage(
            corpus,
            runtimeAssets = mapOf(
                "agent/worldview.jsonl" to worldviewRecords(count = 9_000, statementLength = 1_024).encodeToByteArray(),
            ),
            corruptChecksums = true,
        )
        patchCentralUncompressedSizeAndDescriptor(agent, "agent/worldview.jsonl", 1)

        assertPackageFailure("运行时资产") { AgentBundleReader().readPackage(agent) }
    }

    @Test
    fun rejectsCrossSourceAndOpenCorpusGraphs() {
        val fixture = Fixture()
        val sources = sourceJson(
            sourceRecord("source-a", "来源 A", "a".repeat(64), "speech", "direct", "1926"),
            sourceRecord("source-b", "来源 B", "b".repeat(64), "letter", "secondary", "1930"),
        )
        val rootA = nodeJson("root-a", "source-a")
        val rootB = nodeJson("root-b", "source-b")
        val validChunk = chunkJson("chunk-a", "source-a", "a".repeat(64), "来源 A", "speech", "direct", "1926", listOf("root-a"))
        val cases = listOf(
            fixture.corpusPackage(
                sources = sources,
                nodes = rootA,
                chunks = validChunk.replace("\"sourceHash\":\"${"a".repeat(64)}\"", "\"sourceHash\":\"${"b".repeat(64)}\""),
                sourceIds = listOf("source-a", "source-b"),
                sourceHashes = listOf("a".repeat(64), "b".repeat(64)),
                topLevelIds = listOf("root-a"),
            ) to "来源",
            fixture.corpusPackage(
                sources = sources,
                nodes = rootA + nodeJson("child-b", "source-b", "root-a"),
                chunks = validChunk,
                sourceIds = listOf("source-a", "source-b"),
                sourceHashes = listOf("a".repeat(64), "b".repeat(64)),
                topLevelIds = listOf("root-a"),
            ) to "parent",
            fixture.corpusPackage(
                sources = sources,
                nodes = rootA + rootB,
                chunks = validChunk.replace("[\"root-a\"]", "[\"root-b\"]"),
                sourceIds = listOf("source-a", "source-b"),
                sourceHashes = listOf("a".repeat(64), "b".repeat(64)),
                topLevelIds = listOf("root-a", "root-b"),
            ) to "hierarchy",
            fixture.corpusPackage(
                sources = sources,
                nodes = rootA + rootB,
                chunks = validChunk,
                duplicates = duplicateJson("duplicate-b", "chunk-a", "source-b", "source-a", "1926"),
                sourceIds = listOf("source-a", "source-b"),
                sourceHashes = listOf("a".repeat(64), "b".repeat(64)),
                topLevelIds = listOf("root-a", "root-b"),
            ) to "duplicates.jsonl",
            fixture.corpusPackage(
                sources = sources,
                nodes = rootA + rootB,
                chunks = validChunk,
                sourceIds = listOf("source-a", "source-b"),
                sourceHashes = listOf("a".repeat(64), "b".repeat(64)),
                topLevelIds = listOf("missing-root"),
            ) to "topLevelIds",
        )

        cases.forEach { (file, message) ->
            assertPackageFailure(message) { AgentBundleReader().readPackage(file) }
        }
    }

    @Test
    fun rejectsMissingExtraAndEmptyManifestProvenanceDimensions() {
        val fixture = Fixture()
        val letterSource = sourceRecord(
            "source-direct",
            "测试来源",
            "a".repeat(64),
            "letter",
            "direct",
            "1926",
        )
        val letterChunk = chunkJson(
            "chunk-core",
            "source-direct",
            "a".repeat(64),
            "测试来源",
            "letter",
            "direct",
            "1926",
            listOf("node-root"),
        )
        val cases = listOf(
            fixture.corpusPackage(periods = listOf("1926", "1930")) to "periods",
            fixture.corpusPackage(
                sources = sourceJson(letterSource),
                chunks = letterChunk,
                genres = listOf("speech"),
            ) to "genres",
            fixture.corpusPackage(authorshipValues = emptyList()) to "authorship",
        )

        cases.forEach { (file, dimension) ->
            assertPackageFailure(dimension) { AgentBundleReader().readPackage(file) }
        }
    }

    @Test
    fun rejectsFakeEocdBypassAndCentralDirectoryMismatches() {
        val fixture = Fixture()
        val fakeEocd = fixture.sourcePackage()
        patchUnixMode(fakeEocd, "files/source.txt", 0x81ED)
        appendFakeCentralDirectoryComment(fakeEocd)
        assertPackageFailure("central directory") { AgentBundleReader().readPackage(fakeEocd) }

        listOf(EocdField.ENTRY_COUNT, EocdField.CENTRAL_OFFSET, EocdField.CENTRAL_SIZE).forEach { field ->
            val malformed = fixture.sourcePackage()
            corruptEocdField(malformed, field)
            assertPackageFailure("central directory") { AgentBundleReader().readPackage(malformed) }
        }
    }

    @Test
    fun rejectsLocalOnlyFlagsMethodCrcAndSizeTampering() {
        LocalHeaderField.entries.forEach { field ->
            val fixture = Fixture().sourcePackage()
            corruptLocalHeaderField(fixture, "files/source.txt", field)
            assertPackageFailure("local/central") { AgentBundleReader().readPackage(fixture) }
        }
    }

    @Test
    fun rejectsPayloadOverlapOutOfRangeAndDescriptorBoundaryTampering() {
        val overlap = Fixture().sourcePackage()
        extendPayloadIntoNextLocalHeader(overlap, "manifest.json")
        assertPackageFailure("边界") { AgentBundleReader().readPackage(overlap) }

        val outOfRange = Fixture().sourcePackage()
        extendPayloadIntoCentralDirectory(outOfRange, "files/source.txt")
        assertPackageFailure("边界") { AgentBundleReader().readPackage(outOfRange) }

        val descriptor = Fixture().sourcePackage()
        corruptDataDescriptorSignature(descriptor, "files/source.txt")
        assertPackageFailure("descriptor") { AgentBundleReader().readPackage(descriptor) }
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
            chunkCount: Int = 1,
            sources: String = SOURCE_JSON,
            nodes: String = NODE_JSON,
            duplicates: String = "",
            sourceIds: List<String> = listOf("source-direct"),
            sourceHashes: List<String> = listOf("a".repeat(64)),
            topLevelIds: List<String> = listOf("node-root"),
            periods: List<String> = listOf("1926"),
            genres: List<String> = listOf("speech"),
            authorshipValues: List<String> = listOf("direct"),
            corruptSignature: Boolean = false,
        ): File = signedPackage(
            "core-evidence.hcorpus",
            linkedMapOf(
                "manifest.json" to """
                    {"agentId":"$agentId","authorship":${authorshipValues.jsonArray()},"chunkCount":$chunkCount,"coverage":["identity:self"],"genres":${genres.jsonArray()},"id":"$CORE_ID","installClass":"required","periods":${periods.jsonArray()},"schemaVersion":2,"sourceHashes":${sourceHashes.jsonArray()},"sourceIds":${sourceIds.jsonArray()},"topLevelIds":${topLevelIds.jsonArray()},"type":"$type","version":$version}
                """.trimIndent().encodeToByteArray(),
                "sources.json" to sources.encodeToByteArray(),
                "nodes.jsonl" to nodes.encodeToByteArray(),
                "chunks.jsonl" to chunks.encodeToByteArray(),
                "duplicates.jsonl" to duplicates.encodeToByteArray(),
            ),
            corruptSignature,
        )

        fun agentPackage(
            corpus: File,
            balancedIds: List<String> = listOf(CORE_ID),
            declaredCorpusHash: String = corpus.sha256(),
            declaredCorpusSize: Long = corpus.length(),
            requiredCorpusIds: List<String> = listOf(CORE_ID),
            manifestRequiredCorpora: List<String> = requiredCorpusIds,
            runtimeAssets: Map<String, ByteArray> = emptyMap(),
            corruptSignature: Boolean = false,
            corruptChecksums: Boolean = false,
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
                {"packages":[{"dependencies":[],"fileName":"${corpus.name}","id":"$CORE_ID","installClass":"required","sha256":"$declaredCorpusHash","sizeBytes":$declaredCorpusSize,"type":"hcorpus"}],"profiles":[$profiles],"recommendedProfileId":"balanced","requiredCorpusIds":${requiredCorpusIds.jsonArray()},"schemaVersion":2}
            """.trimIndent()
            val assets = linkedMapOf(
                "manifest.json" to """
                    {"agent":{"id":"person.fixture","name":"测试人物","version":2},"requiredCorpora":${manifestRequiredCorpora.jsonArray()},"runnableWithoutCorpora":false,"schemaVersion":2,"type":"hagent"}
                """.trimIndent().encodeToByteArray(),
                "agent/persona.md" to "只依据证据回答。".encodeToByteArray(),
                "agent/identity.json" to """{"relationships":[],"roles":["调查者"],"selfNames":["我"],"timeHorizon":"1926"}""".encodeToByteArray(),
                "agent/voice.json" to """{"avoidPatterns":[],"defaultForm":"直接","evidence":["chunk-core"],"preferredTerms":["调查"],"rhetoricalMoves":[],"sentenceRhythm":["短句"]}""".encodeToByteArray(),
                "agent/worldview.jsonl" to worldviewJson("stance-1", "调查先于结论").encodeToByteArray(),
                "agent/episodes.jsonl" to ("""{"evidence":["chunk-core"],"id":"episode-1","location":"现场","meaning":"掌握事实","participants":[],"period":"1926","summary":"完成调查"}""" + "\n").encodeToByteArray(),
                "agent/concepts.json" to """{"concepts":[{"aliases":[],"evidence":["chunk-core"],"id":"concept-1","keywords":["事实"],"name":"调查"}]}""".encodeToByteArray(),
                "agent/examples.jsonl" to ("""{"assistant":"先调查。","evidence":["chunk-core"],"generationType":"synthesized","id":"example-1","intent":"方法","styleTags":["直接"],"user":"如何判断"}""" + "\n").encodeToByteArray(),
                "agent/openers.json" to """{"alternatives":[],"default":"你好"}""".encodeToByteArray(),
                "agent/eval.jsonl" to ("""{"category":"grounding","corpusId":"core-evidence","expectedEvidence":["chunk-core"],"id":"eval-1","period":"1926","question":"如何调查"}""" + "\n").encodeToByteArray(),
                "install-plan.json" to plan.encodeToByteArray(),
            )
            assets.putAll(runtimeAssets)
            return signedPackage(
                "person.fixture-v2.hagent",
                assets,
                corruptSignature,
                corruptChecksums,
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
            corruptChecksums: Boolean = false,
        ): File {
            val directory = Files.createTempDirectory("agent-v2-fixture").toFile().apply { deleteOnExit() }
            val target = File(directory, name).apply { deleteOnExit() }
            val checksums = files.entries.sortedBy { it.key }.joinToString(
                prefix = "{\"files\":{",
                postfix = "}}",
                separator = ",",
            ) { (path, bytes) -> "\"$path\":\"${bytes.sha256()}\"" }
                .let { canonical ->
                    if (corruptChecksums) canonical.replaceFirst(Regex("[0-9a-f]{64}"), "0".repeat(64)) else canonical
                }
                .encodeToByteArray()
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

private fun List<String>.jsonArray(): String = joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

private fun worldviewJson(id: String, statement: String): String =
    """{"aliases":[],"conditions":[],"confidence":1.0,"evidence":["chunk-core"],"id":"$id","period":"1926","statement":"$statement","topic":"调查"}""" + "\n"

private fun worldviewRecords(count: Int, statementLength: Int): String =
    buildString(count * (statementLength + 160)) {
        repeat(count) { index -> append(worldviewJson("asset-$index", randomAscii(statementLength, index))) }
    }

private fun randomAscii(length: Int, seed: Int): String = buildString(length) {
    var state = seed.toLong() + 1
    repeat(length) {
        state = (state * 1_103_515_245 + 12_345) and 0x7fff_ffff
        append(('a'.code + (state % 26).toInt()).toChar())
    }
}

private fun sourceJson(vararg records: String): String = records.joinToString(prefix = "[", postfix = "]")

private fun sourceRecord(
    id: String,
    title: String,
    hash: String,
    genre: String,
    authorship: String,
    period: String,
): String =
    """{"authorship":"$authorship","extractedChars":100,"fileName":"$id.txt","format":"txt","genre":"$genre","period":"$period","rawSizeBytes":6,"sourceHash":"$hash","sourceId":"$id","storedName":"$id.txt","title":"$title"}"""

private fun nodeJson(id: String, sourceId: String, parentId: String? = null): String =
    """{"id":"$id","kind":"source","parentId":${parentId?.let { "\"$it\"" } ?: "null"},"path":["$id"],"sourceId":"$sourceId","summary":"来源","title":"$id"}
    """.trimIndent() + "\n"

private fun chunkJson(
    id: String,
    sourceId: String,
    sourceHash: String,
    sourceTitle: String,
    genre: String,
    authorship: String,
    period: String,
    parentIds: List<String>,
): String =
    """{"authorship":"$authorship","conflictKey":"","context":"$sourceTitle","duplicateGroup":"core","genre":"$genre","id":"$id","keywords":["调查"],"location":"第一章","ngrams":[],"parentIds":${parentIds.jsonArray()},"period":"$period","sourceAliases":[],"sourceHash":"$sourceHash","sourceId":"$sourceId","sourceTitle":"$sourceTitle","text":"调查以后再下结论。","simHash":"0000000000000001"}
    """.trimIndent()

private fun duplicateJson(
    duplicateId: String,
    physicalId: String,
    duplicateSourceId: String,
    primarySourceId: String,
    period: String,
): String =
    """{"conflictKey":"","duplicateChunkId":"$duplicateId","duplicateSourceId":"$duplicateSourceId","matchType":"exact","period":"$period","physicalChunkId":"$physicalId","primarySourceId":"$primarySourceId"}
    """.trimIndent()

private enum class EocdField { ENTRY_COUNT, CENTRAL_OFFSET, CENTRAL_SIZE }

private enum class LocalHeaderField {
    FLAGS,
    METHOD,
    CRC,
    COMPRESSED_SIZE,
    UNCOMPRESSED_SIZE,
}

private data class TestZipLayout(
    val name: String,
    val centralHeaderOffset: Int,
    val localHeaderOffset: Int,
    val payloadOffset: Int,
    val compressedSize: Int,
)

private fun appendFakeCentralDirectoryComment(file: File) {
    val bytes = file.readBytes()
    val eocdOffset = bytes.findEocdOffset()
    val fakeName = "safe.txt".encodeToByteArray()
    val fakeCentralOffset = bytes.size
    val fakeCentral = ByteArray(46 + fakeName.size).apply {
        writeIntLeV2(0, 0x02014b50)
        writeShortLeV2(4, 20)
        writeShortLeV2(6, 20)
        writeShortLeV2(28, fakeName.size)
        fakeName.copyInto(this, 46)
    }
    val fakeEocd = ByteArray(22).apply {
        writeIntLeV2(0, 0x06054b50)
        writeShortLeV2(8, 1)
        writeShortLeV2(10, 1)
        writeIntLeV2(12, fakeCentral.size)
        writeIntLeV2(16, fakeCentralOffset)
    }
    val comment = fakeCentral + fakeEocd
    bytes.writeShortLeV2(eocdOffset + 20, comment.size)
    file.writeBytes(bytes + comment)
}

private fun corruptEocdField(file: File, field: EocdField) {
    val bytes = file.readBytes()
    val offset = bytes.findEocdOffset()
    when (field) {
        EocdField.ENTRY_COUNT -> {
            val count = bytes.readShortLeV2(offset + 10)
            bytes.writeShortLeV2(offset + 8, count - 1)
            bytes.writeShortLeV2(offset + 10, count - 1)
        }
        EocdField.CENTRAL_OFFSET -> bytes.writeIntLeV2(offset + 16, bytes.readIntLeV2(offset + 16) + 1)
        EocdField.CENTRAL_SIZE -> bytes.writeIntLeV2(offset + 12, bytes.readIntLeV2(offset + 12) + 1)
    }
    file.writeBytes(bytes)
}

private fun corruptLocalHeaderField(file: File, targetName: String, field: LocalHeaderField) {
    val bytes = file.readBytes()
    val layout = bytes.zipLayouts().single { it.name == targetName }
    val offset = layout.localHeaderOffset
    when (field) {
        LocalHeaderField.FLAGS ->
            bytes.writeShortLeV2(offset + 6, bytes.readShortLeV2(offset + 6) xor 0x0008)
        LocalHeaderField.METHOD ->
            bytes.writeShortLeV2(offset + 8, bytes.readShortLeV2(offset + 8) xor 0x0008)
        LocalHeaderField.CRC ->
            bytes.writeIntLeV2(offset + 14, bytes.readIntLeV2(offset + 14) + 1)
        LocalHeaderField.COMPRESSED_SIZE ->
            bytes.writeIntLeV2(offset + 18, bytes.readIntLeV2(offset + 18) + 1)
        LocalHeaderField.UNCOMPRESSED_SIZE ->
            bytes.writeIntLeV2(offset + 22, bytes.readIntLeV2(offset + 22) + 1)
    }
    file.writeBytes(bytes)
}

private fun extendPayloadIntoNextLocalHeader(file: File, targetName: String) {
    val bytes = file.readBytes()
    val layouts = bytes.zipLayouts()
    val target = layouts.single { it.name == targetName }
    val nextOffset = layouts.map(TestZipLayout::localHeaderOffset)
        .filter { it > target.localHeaderOffset }
        .minOrNull()
        ?: error("No following local header")
    bytes.writeIntLeV2(target.centralHeaderOffset + 20, nextOffset - target.payloadOffset + 1)
    file.writeBytes(bytes)
}

private fun extendPayloadIntoCentralDirectory(file: File, targetName: String) {
    val bytes = file.readBytes()
    val target = bytes.zipLayouts().single { it.name == targetName }
    val centralOffset = bytes.readIntLeV2(bytes.findEocdOffset() + 16)
    bytes.writeIntLeV2(target.centralHeaderOffset + 20, centralOffset - target.payloadOffset + 1)
    file.writeBytes(bytes)
}

private fun corruptDataDescriptorSignature(file: File, targetName: String) {
    val bytes = file.readBytes()
    val target = bytes.zipLayouts().single { it.name == targetName }
    val descriptorOffset = target.payloadOffset + target.compressedSize
    assertEquals(0x08074b50, bytes.readIntLeV2(descriptorOffset))
    bytes[descriptorOffset] = (bytes[descriptorOffset].toInt() xor 1).toByte()
    file.writeBytes(bytes)
}

private fun ByteArray.zipLayouts(): List<TestZipLayout> {
    val eocdOffset = findEocdOffset()
    val entryCount = readShortLeV2(eocdOffset + 10)
    var centralOffset = readIntLeV2(eocdOffset + 16)
    return List(entryCount) {
        assertEquals(0x02014b50, readIntLeV2(centralOffset))
        val nameLength = readShortLeV2(centralOffset + 28)
        val extraLength = readShortLeV2(centralOffset + 30)
        val commentLength = readShortLeV2(centralOffset + 32)
        val localHeaderOffset = readIntLeV2(centralOffset + 42)
        val localNameLength = readShortLeV2(localHeaderOffset + 26)
        val localExtraLength = readShortLeV2(localHeaderOffset + 28)
        val layout = TestZipLayout(
            name = copyOfRange(centralOffset + 46, centralOffset + 46 + nameLength).decodeToString(),
            centralHeaderOffset = centralOffset,
            localHeaderOffset = localHeaderOffset,
            payloadOffset = localHeaderOffset + 30 + localNameLength + localExtraLength,
            compressedSize = readIntLeV2(centralOffset + 20),
        )
        centralOffset += 46 + nameLength + extraLength + commentLength
        layout
    }
}

private fun ByteArray.findEocdOffset(): Int {
    for (offset in size - 22 downTo 0) {
        if (readIntLeV2(offset) == 0x06054b50) return offset
    }
    fail("EOCD not found")
    return -1
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

private fun ByteArray.writeShortLeV2(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}

private fun patchCentralUncompressedSizeAndDescriptor(file: File, targetName: String, size: Int) {
    val bytes = file.readBytes()
    val layout = bytes.zipLayouts().single { it.name == targetName }
    bytes.writeIntLeV2(layout.centralHeaderOffset + 24, size)
    val descriptorOffset = layout.payloadOffset + layout.compressedSize
    assertEquals(0x08074b50, bytes.readIntLeV2(descriptorOffset))
    bytes.writeIntLeV2(descriptorOffset + 12, size)
    file.writeBytes(bytes)
}
