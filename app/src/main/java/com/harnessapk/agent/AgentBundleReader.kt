package com.harnessapk.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

interface AgentBundleAccess {
    fun inspect(file: File): AgentImportPreview
    fun read(file: File): ParsedAgentBundle
    fun readPackage(file: File): ParsedAgentPackage = V1Bundle(read(file))
    suspend fun forEachChunkSuspending(
        bundle: ParsedAgentBundle,
        corpus: AgentCorpusManifest,
        block: suspend (AgentCorpusChunk) -> Unit,
    )
    suspend fun forEachV2ChunkSuspending(
        file: File,
        corpusId: String? = null,
        block: suspend (V2Chunk) -> Unit,
    ) {
        throw AgentBundleException("当前 reader 不支持 V2 chunk 流式读取")
    }
    suspend fun forEachV2HierarchyNodeSuspending(
        file: File,
        corpusId: String? = null,
        block: suspend (V2HierarchyNode) -> Unit,
    ) {
        throw AgentBundleException("当前 reader 不支持 V2 hierarchy 流式读取")
    }
    suspend fun copyV2SourcePayload(
        file: File,
        packageId: String? = null,
        output: OutputStream,
    ) {
        throw AgentBundleException("当前 reader 不支持 V2 source 读取")
    }
}

class AgentBundleReader(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val signatureVerifier: AgentSignatureVerifier = PortableEd25519Verifier,
    private val temporaryDirectory: File = File(System.getProperty("java.io.tmpdir") ?: "."),
) : AgentBundleAccess {
    override fun inspect(file: File): AgentImportPreview {
        val parsed = readBundle(file)
        return AgentImportPreview(
            agentId = parsed.agent.id,
            name = parsed.agent.name,
            version = parsed.agent.version,
            summary = parsed.agent.summary,
            publisherFingerprint = parsed.publisherFingerprint,
            corpora = parsed.corpora.map(AgentCorpusManifest::title),
            compressedSizeBytes = parsed.compressedSizeBytes,
            includesOriginalSources = ZipFile(file).use { archive ->
                archive.entries().asSequence().any { it.name.startsWith("sources/files/") }
            },
        )
    }

    override fun read(file: File): ParsedAgentBundle = readBundle(file)

    fun readBundle(file: File): ParsedAgentBundle =
        when (val parsed = readPackage(file)) {
            is V1Bundle -> parsed.bundle
            else -> throw AgentBundleException("V2 人格包必须通过 readPackage 读取")
        }

    override fun readPackage(file: File): ParsedAgentPackage {
        return withVerifiedPackage(file) { parsed, _ -> parsed }
    }

    private fun <T> withVerifiedPackage(
        file: File,
        block: (ParsedAgentPackage, Map<String, File>) -> T,
    ): T {
        val staging = stagePackage(file)
        return try {
            val stagedPackages = linkedMapOf<String, File>()
            val parsed = readVerifiedPackage(staging.file, file, nestingDepth = 0, stagedPackages)
            block(parsed, stagedPackages)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AgentBundleException) {
            throw error
        } catch (error: Throwable) {
            throw AgentBundleException("人格包读取失败：${error.message.orEmpty()}", error)
        } finally {
            staging.close()
        }
    }

    private suspend fun <T> withVerifiedPackageSuspending(
        file: File,
        block: suspend (ParsedAgentPackage, Map<String, File>) -> T,
    ): T {
        val staging = stagePackage(file)
        return try {
            val stagedPackages = linkedMapOf<String, File>()
            val parsed = readVerifiedPackage(staging.file, file, nestingDepth = 0, stagedPackages)
            block(parsed, stagedPackages)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AgentBundleException) {
            throw error
        } catch (error: Throwable) {
            throw AgentBundleException("人格包读取失败：${error.message.orEmpty()}", error)
        } finally {
            staging.close()
        }
    }

    private fun stagePackage(file: File): StagedPackage {
        validateInputFile(file)
        temporaryDirectory.mkdirs()
        val privateRoot = Files.createTempDirectory(temporaryDirectory.toPath(), ".harness-agent-reader-").toFile()
        privateRoot.setReadable(false, false)
        privateRoot.setWritable(false, false)
        privateRoot.setExecutable(false, false)
        privateRoot.setReadable(true, true)
        privateRoot.setWritable(true, true)
        privateRoot.setExecutable(true, true)
        val staged = File(privateRoot, "package.zip")
        try {
            file.inputStream().buffered().use { input ->
                staged.outputStream().buffered().use { output ->
                    input.copyBoundedTo(output, MAX_COMPRESSED_BYTES, "人格包")
                }
            }
            return StagedPackage(privateRoot, staged)
        } catch (error: Throwable) {
            privateRoot.deleteRecursively()
            throw error
        }
    }

    fun forEachChunk(
        bundle: ParsedAgentBundle,
        corpus: AgentCorpusManifest,
        block: (AgentCorpusChunk) -> Unit,
    ) {
        if (corpus !in bundle.corpora) throw AgentBundleException("资料包不属于当前智能体")
        withVerifiedPackage(bundle.file) { parsed, stagedPackages ->
            val fresh = (parsed as? V1Bundle)?.bundle
                ?: throw AgentBundleException("V1 流式读取收到非 V1 人格包")
            if (fresh.packageSha256 != bundle.packageSha256) {
                throw AgentBundleException("智能体包在校验后发生变化")
            }
            val freshCorpus = fresh.corpora.singleOrNull { it == corpus }
                ?: throw AgentBundleException("资料包在校验后发生变化")
            streamVerifiedV1Chunks(
                requireNotNull(stagedPackages[fresh.packageSha256]),
                freshCorpus,
                block,
            )
        }
    }

    private fun streamVerifiedV1Chunks(
        stagedFile: File,
        corpus: AgentCorpusManifest,
        block: (AgentCorpusChunk) -> Unit,
    ) {
        ZipFile(stagedFile).use { archive ->
            val entry = archive.getEntry(corpus.chunksPath)
                ?: throw AgentBundleException("缺少资料块文件：${corpus.chunksPath}")
            AgentCorpusValidationIndex(requireNotNull(stagedFile.parentFile)).use { index ->
                archive.getInputStream(entry).use { input ->
                    input.forEachUtf8JsonLine(corpus.chunksPath) { line, lineNumber ->
                        val chunk = parseV1Chunk(line, corpus, lineNumber)
                        if (!index.putUnique(v1ChunkKey(chunk.id))) {
                            throw AgentBundleException("资料块包含重复 id：${chunk.id}")
                        }
                        block(chunk)
                    }
                }
            }
        }
    }

    override suspend fun forEachChunkSuspending(
        bundle: ParsedAgentBundle,
        corpus: AgentCorpusManifest,
        block: suspend (AgentCorpusChunk) -> Unit,
    ) {
        if (corpus !in bundle.corpora) throw AgentBundleException("资料包不属于当前智能体")
        withVerifiedPackageSuspending(bundle.file) { parsed, stagedPackages ->
            val fresh = (parsed as? V1Bundle)?.bundle
                ?: throw AgentBundleException("V1 流式读取收到非 V1 人格包")
            if (fresh.packageSha256 != bundle.packageSha256) {
                throw AgentBundleException("智能体包在校验后发生变化")
            }
            val freshCorpus = fresh.corpora.singleOrNull { it == corpus }
                ?: throw AgentBundleException("资料包在校验后发生变化")
            streamVerifiedV1ChunksSuspending(
                requireNotNull(stagedPackages[fresh.packageSha256]),
                freshCorpus,
                block,
            )
        }
    }

    private suspend fun streamVerifiedV1ChunksSuspending(
        stagedFile: File,
        corpus: AgentCorpusManifest,
        block: suspend (AgentCorpusChunk) -> Unit,
    ) {
        ZipFile(stagedFile).use { archive ->
            val entry = archive.getEntry(corpus.chunksPath)
                ?: throw AgentBundleException("缺少资料块文件：${corpus.chunksPath}")
            AgentCorpusValidationIndex(requireNotNull(stagedFile.parentFile)).use { index ->
                archive.getInputStream(entry).use { input ->
                    input.forEachUtf8JsonLineSuspending(corpus.chunksPath) { line, lineNumber ->
                        currentCoroutineContext().ensureActive()
                        val chunk = parseV1Chunk(line, corpus, lineNumber)
                        if (!index.putUnique(v1ChunkKey(chunk.id))) {
                            throw AgentBundleException("资料块包含重复 id：${chunk.id}")
                        }
                        block(chunk)
                    }
                }
            }
        }
    }

    fun forEachV2Chunk(file: File, block: (V2Chunk) -> Unit) {
        withVerifiedPackage(file) { parsed, stagedPackages ->
            val corpus = parsed as? V2Corpus
                ?: throw AgentBundleException("仅 hcorpus 包支持资料块流式读取")
            streamVerifiedV2Chunks(requireNotNull(stagedPackages[corpus.packageSha256]), block)
        }
    }

    fun forEachV2Chunk(file: File, corpusId: String, block: (V2Chunk) -> Unit) {
        val expectedId = identifier(corpusId)
        withVerifiedPackage(file) { parsed, stagedPackages ->
            val bundle = parsed as? V2Bundle
                ?: throw AgentBundleException("指定 corpus ID 的流式读取仅支持 hbundle")
            val corpus = bundle.corpora.singleOrNull { it.manifest.id == expectedId }
                ?: throw AgentBundleException("bundle 未选择 corpus：$expectedId")
            streamVerifiedV2Chunks(requireNotNull(stagedPackages[corpus.packageSha256]), block)
        }
    }

    override suspend fun forEachV2ChunkSuspending(
        file: File,
        corpusId: String?,
        block: suspend (V2Chunk) -> Unit,
    ) {
        withVerifiedPackageSuspending(file) { parsed, stagedPackages ->
            val corpus = parsed.resolveV2Corpus(corpusId)
            val staged = requireNotNull(stagedPackages[corpus.packageSha256])
            ZipFile(staged).use { archive ->
                val entry = archive.getEntry(V2_CHUNKS_PATH)
                    ?: throw AgentBundleException("缺少包内文件：$V2_CHUNKS_PATH")
                archive.getInputStream(entry).use { input ->
                    input.forEachUtf8JsonLineSuspending(V2_CHUNKS_PATH) { line, lineNumber ->
                        currentCoroutineContext().ensureActive()
                        block(parseV2Chunk(parseObject(line, "$V2_CHUNKS_PATH 第 $lineNumber 行")))
                    }
                }
            }
        }
    }

    override suspend fun forEachV2HierarchyNodeSuspending(
        file: File,
        corpusId: String?,
        block: suspend (V2HierarchyNode) -> Unit,
    ) {
        withVerifiedPackageSuspending(file) { parsed, stagedPackages ->
            val corpus = parsed.resolveV2Corpus(corpusId)
            val staged = requireNotNull(stagedPackages[corpus.packageSha256])
            ZipFile(staged).use { archive ->
                val entry = archive.getEntry(V2_NODES_PATH)
                    ?: throw AgentBundleException("缺少包内文件：$V2_NODES_PATH")
                archive.getInputStream(entry).use { input ->
                    input.forEachUtf8JsonLineSuspending(V2_NODES_PATH) { line, lineNumber ->
                        currentCoroutineContext().ensureActive()
                        block(parseNode(parseObject(line, "$V2_NODES_PATH 第 $lineNumber 行")))
                    }
                }
            }
        }
    }

    private fun ParsedAgentPackage.resolveV2Corpus(corpusId: String?): V2Corpus = when (this) {
        is V2Corpus -> {
            if (corpusId != null && manifest.id != identifier(corpusId)) {
                throw AgentBundleException("hcorpus ID 不匹配：$corpusId")
            }
            this
        }
        is V2Bundle -> {
            val expected = corpusId?.let(::identifier)
                ?: throw AgentBundleException("hbundle 流式读取必须指定 corpus ID")
            corpora.singleOrNull { it.manifest.id == expected }
                ?: throw AgentBundleException("bundle 未选择 corpus：$expected")
        }
        else -> throw AgentBundleException("当前包不包含 V2 corpus")
    }

    override suspend fun copyV2SourcePayload(
        file: File,
        packageId: String?,
        output: OutputStream,
    ) {
        withVerifiedPackageSuspending(file) { parsed, stagedPackages ->
            val source = when (parsed) {
                is V2Source -> {
                    if (packageId != null && sourcePackageId(packageId) != parsed.manifest.id) {
                        throw AgentBundleException("hsource package ID 不匹配：$packageId")
                    }
                    parsed
                }
                is V2Bundle -> {
                    val expected = packageId?.let(::sourcePackageId)
                        ?: throw AgentBundleException("hbundle 读取 source 必须指定 package ID")
                    parsed.sources.singleOrNull { it.manifest.id == expected }
                        ?: throw AgentBundleException("bundle 未选择 source：$expected")
                }
                else -> throw AgentBundleException("当前包不包含 V2 source")
            }
            val staged = requireNotNull(stagedPackages[source.packageSha256])
            ZipFile(staged).use { archive ->
                val path = "files/${source.manifest.storedName}"
                val entry = archive.getEntry(path) ?: throw AgentBundleException("缺少包内文件：$path")
                archive.getInputStream(entry).use { input ->
                    input.copyBoundedTo(output, source.manifest.rawSizeBytes, path)
                }
            }
        }
    }

    private fun sourcePackageId(value: String): String = identifier(value)

    private fun streamVerifiedV2Chunks(stagedFile: File, block: (V2Chunk) -> Unit) {
        ZipFile(stagedFile).use { archive ->
            val entry = archive.getEntry(V2_CHUNKS_PATH)
                ?: throw AgentBundleException("缺少包内文件：$V2_CHUNKS_PATH")
            archive.getInputStream(entry).use { input ->
                input.forEachUtf8JsonLine(V2_CHUNKS_PATH) { line, lineNumber ->
                    block(parseV2Chunk(parseObject(line, "$V2_CHUNKS_PATH 第 $lineNumber 行")))
                }
            }
        }
    }

    private fun readVerifiedPackage(
        stagedFile: File,
        displayFile: File,
        nestingDepth: Int,
        stagedPackages: MutableMap<String, File>,
    ): ParsedAgentPackage {
        if (nestingDepth > MAX_PACKAGE_NESTING) throw AgentBundleException("人格包嵌套层级超过上限")
        rejectUnsafeCentralDirectory(stagedFile)
        return ZipFile(stagedFile).use { archive ->
            val entries = validateBasicEntries(archive)
            val hasBundleManifest = entries.any { it.name == BUNDLE_MANIFEST_PATH }
            val hasPackageManifest = entries.any { it.name == PACKAGE_MANIFEST_PATH }
            if (hasBundleManifest == hasPackageManifest) {
                throw AgentBundleException("人格包必须且只能包含一个顶层 manifest")
            }
            val manifestPath = if (hasBundleManifest) BUNDLE_MANIFEST_PATH else PACKAGE_MANIFEST_PATH
            val runtimeAssetPaths = validateDeclaredV2AgentRuntimeAssets(archive, manifestPath)
            val checksumsBytes = archive.readRequired(CHECKSUMS_PATH, MAX_CHECKSUMS_BYTES)
            val signatureBytes = archive.readRequired(SIGNATURE_PATH, MAX_SIGNATURE_BYTES)
            val checksumMap = parseCanonicalChecksums(checksumsBytes)
            val signature = parseSignature(signatureBytes)
            verifySignature(signature, checksumsBytes)
            verifyChecksums(archive, entries, checksumMap, runtimeAssetPaths)

            val manifestBytes = archive.readRequired(manifestPath, MAX_MANIFEST_BYTES)
            val manifestObject = parseObject(manifestBytes.decodeToString(), manifestPath)
            val schemaVersion = manifestObject.requiredPositiveInt("schemaVersion", manifestPath)
            val type = manifestObject.optionalString("type", manifestPath)
            val verified = VerifiedPackage(
                file = displayFile,
                packageSha256 = stagedFile.sha256(),
                publicKey = signature.publicKey,
                fingerprint = signature.publicKey.sha256(),
                manifestJson = manifestBytes.decodeToString(),
                compressedSize = stagedFile.length(),
                uncompressedSize = entries.sumOf(ZipEntry::getSize),
            )

            val parsed = when (schemaVersion) {
                1 -> {
                    if (!hasBundleManifest || type.isNotEmpty()) {
                        throw AgentBundleException("V1 包顶层 manifest 契约无效")
                    }
                    validateAllowedEntries(entries, PackageKind.V1_BUNDLE)
                    V1Bundle(parseV1Bundle(archive, manifestObject, verified))
                }
                2 -> {
                    val kind = PackageKind.fromWireName(type)
                        ?: throw AgentBundleException("不支持的 V2 type：$type")
                    if ((kind == PackageKind.V2_BUNDLE) != hasBundleManifest) {
                        throw AgentBundleException("V2 type 与顶层 manifest 文件不一致")
                    }
                    validateAllowedEntries(entries, kind)
                    when (kind) {
                        PackageKind.V2_BUNDLE -> parseV2Bundle(
                            archive,
                            entries,
                            manifestObject,
                            verified,
                            requireNotNull(stagedFile.parentFile),
                            nestingDepth,
                            stagedPackages,
                        )
                        PackageKind.V2_AGENT -> parseV2Agent(archive, manifestObject, verified)
                        PackageKind.V2_CORPUS -> parseV2Corpus(
                            archive,
                            manifestObject,
                            verified,
                            requireNotNull(stagedFile.parentFile),
                        )
                        PackageKind.V2_SOURCE -> parseV2Source(archive, entries, manifestObject, verified)
                        PackageKind.V1_BUNDLE -> error("unreachable")
                    }
                }
                else -> throw AgentBundleException("不支持的 schemaVersion：$schemaVersion")
            }
            stagedPackages[verified.packageSha256] = stagedFile
            parsed
        }
    }

    private fun parseV1Bundle(
        archive: ZipFile,
        root: JsonObject,
        verified: VerifiedPackage,
    ): ParsedAgentBundle {
        val agentObject = root.requiredObject("agent", BUNDLE_MANIFEST_PATH)
        val agent = AgentPackageManifest(
            id = agentObject.requiredIdentifier("id", "agent"),
            name = agentObject.requiredString("name", "agent"),
            version = agentObject.requiredPositiveInt("version", "agent"),
            summary = agentObject.optionalString("summary", "agent"),
            personaPath = safePath(agentObject.requiredString("personaPath", "agent")),
            worldviewPath = safePath(agentObject.requiredString("worldviewPath", "agent")),
            conceptsPath = safePath(agentObject.requiredString("conceptsPath", "agent")),
            examplesPath = safePath(agentObject.requiredString("examplesPath", "agent")),
            evalPath = safePath(agentObject.requiredString("evalPath", "agent")),
            requiredCorpora = agentObject.stringList("requiredCorpora", "agent").map(::identifier),
        )
        val corpora = root.optionalArray("corpora", BUNDLE_MANIFEST_PATH).mapIndexed { index, element ->
            val corpus = element.asObject("corpora[$index]")
            AgentCorpusManifest(
                id = corpus.requiredIdentifier("id", "corpora[$index]"),
                title = corpus.requiredString("title", "corpora[$index]"),
                sourceHash = corpus.requiredString("sourceHash", "corpora[$index]"),
                sourcesPath = safePath(corpus.requiredString("sourcesPath", "corpora[$index]")),
                chunksPath = safePath(corpus.requiredString("chunksPath", "corpora[$index]")),
                required = corpus.optionalBoolean("required", "corpora[$index]", false),
            )
        }
        rejectDuplicates(corpora.map(AgentCorpusManifest::id), "V1 corpus id")
        if (!agent.requiredCorpora.all { required -> corpora.any { it.id == required } }) {
            throw AgentBundleException("V1 manifest 缺少 required corpus")
        }
        listOf(
            agent.personaPath,
            agent.worldviewPath,
            agent.conceptsPath,
            agent.examplesPath,
            agent.evalPath,
        ).forEach { path -> archive.requireEntry(path) }
        corpora.forEach { corpus ->
            archive.requireEntry(corpus.sourcesPath)
            archive.requireEntry(corpus.chunksPath)
        }
        val persona = archive.readRequired(agent.personaPath, MAX_PERSONA_BYTES).decodeToString().trim()
        if (persona.isBlank()) throw AgentBundleException("persona.md 不能为空")
        val worldview = archive.readRequired(agent.worldviewPath, MAX_WORLDVIEW_BYTES).decodeToString()
        validateJsonlText(worldview, agent.worldviewPath)
        return ParsedAgentBundle(
            file = verified.file,
            packageSha256 = verified.packageSha256,
            publisherPublicKey = verified.publicKey,
            publisherFingerprint = verified.fingerprint,
            manifestJson = verified.manifestJson,
            agent = agent,
            corpora = corpora,
            persona = persona,
            worldviewJsonl = worldview,
            compressedSizeBytes = verified.compressedSize,
            uncompressedSizeBytes = verified.uncompressedSize,
        )
    }

    private fun parseV2Bundle(
        archive: ZipFile,
        entries: List<ZipEntry>,
        root: JsonObject,
        verified: VerifiedPackage,
        privateRoot: File,
        nestingDepth: Int,
        stagedPackages: MutableMap<String, File>,
    ): V2Bundle {
        val agentObject = root.requiredObject("agent", BUNDLE_MANIFEST_PATH)
        val manifest = V2BundleManifest(
            agent = V2BundleAgentDeclaration(
                id = agentObject.requiredIdentifier("id", "bundle agent"),
                version = agentObject.requiredPositiveInt("version", "bundle agent"),
                fileName = packageFileName(agentObject.requiredString("fileName", "bundle agent")),
                sha256 = agentObject.requiredHash("sha256", "bundle agent"),
                sizeBytes = agentObject.requiredPositiveLong("sizeBytes", "bundle agent"),
            ),
            profileId = root.requiredIdentifier("profile", BUNDLE_MANIFEST_PATH),
            selectedPackageIds = root.stringList("selectedPackageIds", BUNDLE_MANIFEST_PATH).map(::identifier),
        )
        rejectDuplicates(manifest.selectedPackageIds, "bundle selected package ID")

        val packageEntries = entries.filter { it.name.startsWith(PACKAGES_PREFIX) }
        val packageNames = packageEntries.map { it.name.removePrefix(PACKAGES_PREFIX) }
        if (packageNames.any { '/' in it }) throw AgentBundleException("bundle 子包嵌套层级无效")
        rejectDuplicates(packageNames, "bundle 子包文件名")
        val childPackages = linkedMapOf<String, ParsedAgentPackage>()
        packageEntries.forEachIndexed { index, entry ->
            val childName = packageFileName(entry.name.removePrefix(PACKAGES_PREFIX))
            val childFile = File(privateRoot, "child-$nestingDepth-$index.zip")
            archive.getInputStream(entry).use { input ->
                childFile.outputStream().buffered().use { output ->
                    input.copyBoundedTo(output, MAX_COMPRESSED_BYTES, childName)
                }
            }
            childPackages[childName] = readVerifiedPackage(
                childFile,
                verified.file,
                nestingDepth + 1,
                stagedPackages,
            )
        }

        val parsedAgent = childPackages[manifest.agent.fileName]
            ?: throw AgentBundleException("bundle 缺少声明的 hagent：${manifest.agent.fileName}")
        if (parsedAgent !is V2Agent) throw AgentBundleException("bundle agent 子包类型错误")
        if (parsedAgent.packageSha256 != manifest.agent.sha256) {
            throw AgentBundleException("bundle agent SHA-256 不匹配")
        }
        if (parsedAgent.compressedSizeBytes != manifest.agent.sizeBytes) {
            throw AgentBundleException("bundle agent 大小不匹配")
        }
        if (parsedAgent.manifest.id != manifest.agent.id) throw AgentBundleException("bundle agent ID 不匹配")
        if (parsedAgent.manifest.version != manifest.agent.version) throw AgentBundleException("bundle agent 版本不匹配")
        verifyPublisher(verified, parsedAgent, manifest.agent.fileName)

        val profile = parsedAgent.installPlan.profiles.singleOrNull { it.id == manifest.profileId }
            ?: throw AgentBundleException("bundle profile 不存在或重复：${manifest.profileId}")
        if (profile.packageIds != manifest.selectedPackageIds) {
            throw AgentBundleException("bundle selectedPackageIds 与 profile 不一致")
        }
        val selectedDeclarations = profile.packageIds.map { packageId ->
            parsedAgent.installPlan.packages.singleOrNull { it.id == packageId }
                ?: throw AgentBundleException("profile 引用了未声明 package：$packageId")
        }
        val expectedNames = setOf(manifest.agent.fileName) + selectedDeclarations.map(V2InstallPackage::fileName)
        val actualNames = childPackages.keys
        val missing = expectedNames - actualNames
        val extra = actualNames - expectedNames
        if (missing.isNotEmpty()) throw AgentBundleException("bundle 缺少 required/profile 子包：${missing.first()}")
        if (extra.isNotEmpty()) throw AgentBundleException("bundle 包含未声明子包：${extra.first()}")

        val corpora = mutableListOf<V2Corpus>()
        val sources = mutableListOf<V2Source>()
        selectedDeclarations.forEach { declaration ->
            val child = childPackages[declaration.fileName]
                ?: throw AgentBundleException("bundle 缺少子包：${declaration.fileName}")
            if (child.packageSha256 != declaration.sha256) {
                throw AgentBundleException("子包 SHA-256 不匹配：${declaration.fileName}")
            }
            if (child.compressedSizeBytes != declaration.sizeBytes) {
                throw AgentBundleException("子包大小不匹配：${declaration.fileName}")
            }
            verifyPublisher(verified, child, declaration.fileName)
            when (declaration.type) {
                V2PackageType.CORPUS -> {
                    if (child !is V2Corpus) throw AgentBundleException("子包类型不匹配：${declaration.fileName}")
                    if (child.manifest.id != declaration.id) throw AgentBundleException("子包 package ID 不匹配")
                    if (child.manifest.agentId != parsedAgent.manifest.id) throw AgentBundleException("子包 agent ID 不匹配")
                    if (child.manifest.version != parsedAgent.manifest.version) throw AgentBundleException("子包版本不匹配")
                    if (child.manifest.installClass != declaration.installClass) {
                        throw AgentBundleException("子包 installClass 不匹配")
                    }
                    corpora += child
                }
                V2PackageType.SOURCE -> {
                    if (child !is V2Source) throw AgentBundleException("子包类型不匹配：${declaration.fileName}")
                    if (child.manifest.id != declaration.id) throw AgentBundleException("子包 package ID 不匹配")
                    if (child.manifest.agentId != parsedAgent.manifest.id) throw AgentBundleException("子包 agent ID 不匹配")
                    if (child.manifest.version != parsedAgent.manifest.version) throw AgentBundleException("子包版本不匹配")
                    sources += child
                }
                else -> throw AgentBundleException("install plan 子包类型无效：${declaration.type.wireName}")
            }
        }
        val selectedCorpusIds = corpora.map { it.manifest.id }
        val runnable = parsedAgent.manifest.runnableWithoutCorpora ||
            parsedAgent.manifest.requiredCorpora.all(selectedCorpusIds::contains)
        return V2Bundle(
            file = verified.file,
            packageSha256 = verified.packageSha256,
            publisherPublicKey = verified.publicKey,
            publisherFingerprint = verified.fingerprint,
            manifestJson = verified.manifestJson,
            compressedSizeBytes = verified.compressedSize,
            uncompressedSizeBytes = verified.uncompressedSize,
            manifest = manifest,
            profile = profile,
            agent = parsedAgent.copy(isRunnable = runnable),
            corpora = corpora,
            sources = sources,
            selectedCorpusIds = selectedCorpusIds,
        )
    }

    private fun parseV2Agent(
        archive: ZipFile,
        root: JsonObject,
        verified: VerifiedPackage,
    ): V2Agent {
        val agentObject = root.requiredObject("agent", PACKAGE_MANIFEST_PATH)
        val manifest = V2AgentManifest(
            id = agentObject.requiredIdentifier("id", "agent"),
            name = agentObject.requiredString("name", "agent"),
            version = agentObject.requiredPositiveInt("version", "agent"),
            requiredCorpora = root.stringList("requiredCorpora", PACKAGE_MANIFEST_PATH).map(::identifier),
            runnableWithoutCorpora = root.optionalBoolean(
                "runnableWithoutCorpora",
                PACKAGE_MANIFEST_PATH,
                false,
            ),
        )
        rejectDuplicates(manifest.requiredCorpora, "required corpus ID")
        if (manifest.runnableWithoutCorpora && manifest.requiredCorpora.isNotEmpty()) {
            throw AgentBundleException("hagent runnableWithoutCorpora 与 requiredCorpora 冲突")
        }
        val assetBudget = AgentRuntimeAssetBudget()
        val persona = assetBudget.read(archive, V2_PERSONA_PATH).decodeToString().trim()
        if (persona.isBlank()) throw AgentBundleException("$V2_PERSONA_PATH 不能为空")
        val identity = parseIdentity(archive.readAgentJsonObject(V2_IDENTITY_PATH, assetBudget))
        val voice = parseVoice(archive.readAgentJsonObject(V2_VOICE_PATH, assetBudget))
        val worldview = archive.readAgentJsonl(V2_WORLDVIEW_PATH, assetBudget, ::parseWorldview)
        val episodes = archive.readAgentJsonl(V2_EPISODES_PATH, assetBudget, ::parseEpisode)
        val conceptsRoot = archive.readAgentJsonObject(V2_CONCEPTS_PATH, assetBudget)
        val concepts = conceptsRoot.requiredArray("concepts", V2_CONCEPTS_PATH)
            .mapIndexed { index, element -> parseConcept(element.asObject("$V2_CONCEPTS_PATH[$index]")) }
        rejectDuplicates(concepts.map(V2Concept::id), "concept id")
        val examples = archive.readAgentJsonl(V2_EXAMPLES_PATH, assetBudget, ::parseExample)
        val openers = parseOpeners(archive.readAgentJsonObject(V2_OPENERS_PATH, assetBudget))
        val evaluations = archive.readAgentJsonl(V2_EVAL_PATH, assetBudget, ::parseEvaluation)
        var installPlanJson = ""
        val installPlan = parseInstallPlan(
            archive.readAgentJsonObject(V2_INSTALL_PLAN_PATH, assetBudget) { installPlanJson = it },
        )
        if (installPlan.requiredCorpusIds.toSet() != manifest.requiredCorpora.toSet()) {
            throw AgentBundleException("hagent requiredCorpora 与 install plan 不一致")
        }
        return V2Agent(
            file = verified.file,
            packageSha256 = verified.packageSha256,
            publisherPublicKey = verified.publicKey,
            publisherFingerprint = verified.fingerprint,
            manifestJson = verified.manifestJson,
            compressedSizeBytes = verified.compressedSize,
            uncompressedSizeBytes = verified.uncompressedSize,
            manifest = manifest,
            persona = persona,
            identity = identity,
            voice = voice,
            worldview = worldview,
            episodes = episodes,
            concepts = concepts,
            examples = examples,
            openers = openers,
            evaluations = evaluations,
            installPlanJson = installPlanJson,
            installPlan = installPlan,
            isRunnable = manifest.runnableWithoutCorpora || manifest.requiredCorpora.isEmpty(),
        )
    }

    private fun parseV2Corpus(
        archive: ZipFile,
        root: JsonObject,
        verified: VerifiedPackage,
        validationParent: File,
    ): V2Corpus {
        val manifest = V2CorpusManifest(
            id = root.requiredIdentifier("id", PACKAGE_MANIFEST_PATH),
            agentId = root.requiredIdentifier("agentId", PACKAGE_MANIFEST_PATH),
            version = root.requiredPositiveInt("version", PACKAGE_MANIFEST_PATH),
            installClass = root.requiredInstallClass("installClass", PACKAGE_MANIFEST_PATH),
            chunkCount = root.requiredNonNegativeInt("chunkCount", PACKAGE_MANIFEST_PATH),
            sourceIds = root.stringList("sourceIds", PACKAGE_MANIFEST_PATH).map(::identifier),
            sourceHashes = root.stringList("sourceHashes", PACKAGE_MANIFEST_PATH).map(::hash),
            periods = root.stringList("periods", PACKAGE_MANIFEST_PATH),
            genres = root.stringList("genres", PACKAGE_MANIFEST_PATH).map(::sourceGenre),
            authorship = root.stringList("authorship", PACKAGE_MANIFEST_PATH).map(::authorship),
            topLevelIds = root.stringList("topLevelIds", PACKAGE_MANIFEST_PATH).map(::identifier),
            coverage = root.stringList("coverage", PACKAGE_MANIFEST_PATH),
        )
        if (manifest.installClass == V2InstallClass.SOURCE) {
            throw AgentBundleException("hcorpus installClass 不能为 source")
        }
        listOf(
            manifest.sourceIds to "source ID",
            manifest.sourceHashes to "source hash",
            manifest.topLevelIds to "top-level node ID",
            manifest.periods to "periods",
            manifest.genres to "genres",
            manifest.authorship to "authorship",
        ).forEach { (values, label) -> rejectDuplicates(values, label) }
        val sourcesRoot = archive.readRequired(V2_SOURCES_PATH, MAX_CORPUS_METADATA_BYTES)
        val sources = parseArray(sourcesRoot.decodeToString(), V2_SOURCES_PATH)
            .mapIndexed { index, element -> parseSourceRecord(element.asObject("$V2_SOURCES_PATH[$index]")) }
        rejectDuplicates(sources.map(V2SourceRecord::sourceId), "source ID")
        rejectDuplicates(sources.map(V2SourceRecord::sourceHash), "source hash")
        if (sources.map(V2SourceRecord::sourceId) != manifest.sourceIds) {
            throw AgentBundleException("$V2_SOURCES_PATH 与 manifest sourceIds 不一致")
        }
        if (sources.map(V2SourceRecord::sourceHash) != manifest.sourceHashes) {
            throw AgentBundleException("$V2_SOURCES_PATH 与 manifest sourceHashes 不一致")
        }
        val validation = validateCorpusGraph(archive, manifest, sources, validationParent)
        return V2Corpus(
            file = verified.file,
            packageSha256 = verified.packageSha256,
            publisherPublicKey = verified.publicKey,
            publisherFingerprint = verified.fingerprint,
            manifestJson = verified.manifestJson,
            compressedSizeBytes = verified.compressedSize,
            uncompressedSizeBytes = verified.uncompressedSize,
            manifest = manifest,
            sources = sources,
            nodeCount = validation.nodeCount,
            chunkCount = validation.chunkCount,
            duplicateCount = validation.duplicateCount,
            validationDiagnostics = validation.diagnostics,
        )
    }

    private fun validateCorpusGraph(
        archive: ZipFile,
        manifest: V2CorpusManifest,
        sources: List<V2SourceRecord>,
        validationParent: File,
    ): CorpusGraphValidation {
        val sourceById = sources.associateBy(V2SourceRecord::sourceId)
        val topLevelIds = manifest.topLevelIds.toSet()
        val declaredGenres = manifest.genres.map { it.wireName }
        val declaredAuthorship = manifest.authorship.map { it.wireName }
        return AgentCorpusValidationIndex(validationParent).use { index ->
            var indexedRecordCount = 0L
            sources.forEach { source ->
                checkUniqueIndexRecord(
                    index,
                    sourceKey(source.sourceId),
                    encodeIndexFields(
                        source.sourceHash,
                        source.title,
                        source.genre.wireName,
                        source.authorship.wireName,
                        source.period,
                    ),
                    "$V2_SOURCES_PATH source ID",
                    source.sourceId,
                )
                indexedRecordCount += 1
            }

            var nodeCount = 0
            archive.streamJsonl(V2_NODES_PATH) { row, _ ->
                val node = parseNode(row)
                if (node.path.isEmpty() || node.path.size > MAX_HIERARCHY_DEPTH || node.path.any(String::isBlank)) {
                    throw AgentBundleException("$V2_NODES_PATH hierarchy path 无效：${node.id}")
                }
                if (sourceById[node.sourceId] == null) {
                    throw AgentBundleException("$V2_NODES_PATH 引用了未声明来源：${node.id}")
                }
                checkUniqueIndexRecord(
                    index,
                    nodeKey(node.id),
                    encodeIndexFields(node.sourceId, node.parentId.orEmpty(), node.title, *node.path.toTypedArray()),
                    "$V2_NODES_PATH id",
                    node.id,
                )
                indexedRecordCount += 1
                nodeCount += 1
            }
            archive.streamJsonl(V2_NODES_PATH) { row, _ ->
                val node = parseNode(row)
                if (node.path.last() != node.title) {
                    throw AgentBundleException("$V2_NODES_PATH hierarchy path 与 title 不一致：${node.id}")
                }
                val parentId = node.parentId
                if (parentId == null) {
                    if (node.id !in topLevelIds || node.path.size != 1) {
                        throw AgentBundleException("$V2_NODES_PATH topLevelIds 与根节点不闭合：${node.id}")
                    }
                } else {
                    if (node.id in topLevelIds) {
                        throw AgentBundleException("$V2_NODES_PATH topLevelIds 包含非根节点：${node.id}")
                    }
                    val parent = index.get(nodeKey(parentId))?.let(::decodeNodeIndex)
                        ?: throw AgentBundleException("$V2_NODES_PATH parent 不存在：$parentId")
                    if (parent.sourceId != node.sourceId) {
                        throw AgentBundleException("$V2_NODES_PATH parent 跨 source：${node.id}")
                    }
                    if (parent.path != node.path.dropLast(1)) {
                        throw AgentBundleException("$V2_NODES_PATH hierarchy path 未闭合：${node.id}")
                    }
                }
            }
            manifest.topLevelIds.forEach { id ->
                val node = index.get(nodeKey(id))?.let(::decodeNodeIndex)
                    ?: throw AgentBundleException("manifest topLevelIds 引用了不存在的节点：$id")
                if (node.parentId.isNotEmpty()) {
                    throw AgentBundleException("manifest topLevelIds 引用了非根节点：$id")
                }
            }

            var chunkCount = 0
            archive.streamJsonl(V2_CHUNKS_PATH) { row, _ ->
                val chunk = parseV2Chunk(row)
                val source = sourceById[chunk.sourceId]
                    ?: throw AgentBundleException("$V2_CHUNKS_PATH 引用了未声明来源：${chunk.id}")
                if (
                    chunk.sourceHash != source.sourceHash ||
                    chunk.sourceTitle != source.title ||
                    chunk.genre != source.genre ||
                    chunk.authorship != source.authorship ||
                    chunk.period != source.period
                ) {
                    throw AgentBundleException("$V2_CHUNKS_PATH 来源 hash/provenance 不匹配：${chunk.id}")
                }
                observeManifestProvenance(index, "periods", chunk.period, manifest.periods)
                observeManifestProvenance(index, "genres", chunk.genre.wireName, declaredGenres)
                observeManifestProvenance(
                    index,
                    "authorship",
                    chunk.authorship.wireName,
                    declaredAuthorship,
                )
                checkUniqueIndexRecord(
                    index,
                    chunkKeyForValidation(chunk.id),
                    encodeIndexFields(chunk.sourceId, chunk.period, chunk.conflictKey),
                    "$V2_CHUNKS_PATH id",
                    chunk.id,
                )
                validateChunkParents(index, chunk)
                val sourceReferences = listOf(chunk.sourceId) + chunk.sourceAliases
                rejectDuplicates(sourceReferences, "$V2_CHUNKS_PATH source reference")
                sourceReferences.forEach { sourceId ->
                    if (sourceById[sourceId] == null) {
                        throw AgentBundleException("$V2_CHUNKS_PATH sourceAliases 引用了未声明来源：$sourceId")
                    }
                    checkUniqueIndexRecord(
                        index,
                        chunkSourceKey(chunk.id, sourceId),
                        label = "$V2_CHUNKS_PATH source reference",
                        id = "$sourceId:${chunk.id}",
                    )
                }
                indexedRecordCount += 1
                chunkCount += 1
            }
            if (chunkCount != manifest.chunkCount) {
                throw AgentBundleException("$V2_CHUNKS_PATH 数量与 manifest chunkCount 不一致")
            }
            requireManifestProvenanceClosure(index, "periods", manifest.periods)
            requireManifestProvenanceClosure(index, "genres", declaredGenres)
            requireManifestProvenanceClosure(index, "authorship", declaredAuthorship)

            var duplicateCount = 0
            archive.streamJsonl(V2_DUPLICATES_PATH) { row, _ ->
                val duplicate = parseDuplicate(row)
                checkUniqueIndexRecord(
                    index,
                    duplicateKey(duplicate.duplicateChunkId),
                    label = "$V2_DUPLICATES_PATH id",
                    id = duplicate.duplicateChunkId,
                )
                if (index.contains(chunkKeyForValidation(duplicate.duplicateChunkId))) {
                    throw AgentBundleException("$V2_DUPLICATES_PATH duplicateChunkId 与 physical chunk 冲突")
                }
                val physical = index.get(chunkKeyForValidation(duplicate.physicalChunkId))
                    ?.let(::decodeChunkIndex)
                    ?: throw AgentBundleException("$V2_DUPLICATES_PATH 引用了不存在的 physical chunk")
                if (
                    sourceById[duplicate.duplicateSourceId] == null ||
                    sourceById[duplicate.primarySourceId] == null ||
                    duplicate.primarySourceId != physical.sourceId ||
                    duplicate.period != physical.period ||
                    duplicate.conflictKey != physical.conflictKey ||
                    !index.contains(chunkSourceKey(duplicate.physicalChunkId, duplicate.duplicateSourceId))
                ) {
                    throw AgentBundleException("$V2_DUPLICATES_PATH 引用未闭合：${duplicate.duplicateChunkId}")
                }
                indexedRecordCount += 1
                duplicateCount += 1
            }
            CorpusGraphValidation(
                nodeCount = nodeCount,
                chunkCount = chunkCount,
                duplicateCount = duplicateCount,
                diagnostics = V2CorpusValidationDiagnostics(
                    backend = "disk",
                    indexedRecordCount = indexedRecordCount,
                    peakInMemoryRecordCount = 1,
                    diskBytes = index.diskBytes(),
                ),
            )
        }
    }

    private fun validateChunkParents(index: AgentCorpusValidationIndex, chunk: V2Chunk) {
        if (chunk.parentIds.isEmpty()) {
            throw AgentBundleException("$V2_CHUNKS_PATH 缺少 hierarchy parent：${chunk.id}")
        }
        var previousId = ""
        chunk.parentIds.forEach { nodeId ->
            val node = index.get(nodeKey(nodeId))?.let(::decodeNodeIndex)
                ?: throw AgentBundleException("$V2_CHUNKS_PATH 引用了不存在的 hierarchy node：${chunk.id}")
            if (node.sourceId != chunk.sourceId || node.parentId != previousId) {
                throw AgentBundleException("$V2_CHUNKS_PATH hierarchy 跨 source 或未闭合：${chunk.id}")
            }
            previousId = nodeId
        }
    }

    private fun checkUniqueIndexRecord(
        index: AgentCorpusValidationIndex,
        key: String,
        value: ByteArray = byteArrayOf(),
        label: String,
        id: String,
    ) {
        if (!index.putUnique(key, value)) throw AgentBundleException("$label 包含重复 id：$id")
    }

    private fun parseV2Source(
        archive: ZipFile,
        entries: List<ZipEntry>,
        root: JsonObject,
        verified: VerifiedPackage,
    ): V2Source {
        val manifest = V2SourceManifest(
            id = root.requiredIdentifier("id", PACKAGE_MANIFEST_PATH),
            agentId = root.requiredIdentifier("agentId", PACKAGE_MANIFEST_PATH),
            version = root.requiredPositiveInt("version", PACKAGE_MANIFEST_PATH),
            sourceId = root.requiredIdentifier("sourceId", PACKAGE_MANIFEST_PATH),
            sourceHash = root.requiredHash("sourceHash", PACKAGE_MANIFEST_PATH),
            fileName = displayFileName(root.requiredString("fileName", PACKAGE_MANIFEST_PATH)),
            storedName = storedFileName(root.requiredString("storedName", PACKAGE_MANIFEST_PATH)),
            rawSizeBytes = root.requiredNonNegativeLong("rawSizeBytes", PACKAGE_MANIFEST_PATH),
        )
        val expectedPath = "files/${manifest.storedName}"
        val sourceEntries = entries.filter { it.name.startsWith("files/") }
        if (sourceEntries.map(ZipEntry::getName) != listOf(expectedPath)) {
            throw AgentBundleException("hsource 原始文件声明不一致")
        }
        val entry = sourceEntries.single()
        if (entry.size != manifest.rawSizeBytes) throw AgentBundleException("hsource 原始文件大小不匹配")
        val actualHash = archive.getInputStream(entry).use(InputStream::sha256)
        if (actualHash != manifest.sourceHash) throw AgentBundleException("hsource 原始文件 SHA-256 不匹配")
        return V2Source(
            file = verified.file,
            packageSha256 = verified.packageSha256,
            publisherPublicKey = verified.publicKey,
            publisherFingerprint = verified.fingerprint,
            manifestJson = verified.manifestJson,
            compressedSizeBytes = verified.compressedSize,
            uncompressedSizeBytes = verified.uncompressedSize,
            manifest = manifest,
        )
    }

    private fun validateBasicEntries(archive: ZipFile): List<ZipEntry> {
        val entries = archive.entries().asSequence().toList()
        if (entries.isEmpty()) throw AgentBundleException("人格包为空")
        if (entries.size > MAX_ENTRY_COUNT) throw AgentBundleException("包内条目超过 $MAX_ENTRY_COUNT 个")
        val names = mutableSetOf<String>()
        var total = 0L
        entries.forEach { entry ->
            val name = safePath(entry.name)
            if (entry.isDirectory || name.endsWith('/')) throw AgentBundleException("人格包不允许目录条目：$name")
            if (!names.add(name)) throw AgentBundleException("包内存在重复条目：$name")
            if (name.count { it == '/' } + 1 > MAX_PATH_DEPTH) {
                throw AgentBundleException("包内路径嵌套层级超过上限：$name")
            }
            if (entry.size < 0 || entry.compressedSize < 0) throw AgentBundleException("ZIP 条目大小无效：$name")
            if (entry.size > MAX_ENTRY_UNCOMPRESSED_BYTES) {
                throw AgentBundleException("包内文件超过大小上限：$name")
            }
            total = Math.addExact(total, entry.size)
            if (total > MAX_UNCOMPRESSED_BYTES) throw AgentBundleException("声明的解压总量超过 4 GiB")
            if (entry.size > MIN_RATIO_CHECK_BYTES) {
                val compressed = entry.compressedSize.coerceAtLeast(1L)
                if (entry.size / compressed > MAX_COMPRESSION_RATIO) {
                    throw AgentBundleException("ZIP 条目压缩比超过上限：$name")
                }
            }
        }
        setOf(CHECKSUMS_PATH, SIGNATURE_PATH).forEach { required ->
            if (required !in names) throw AgentBundleException("缺少包内文件：$required")
        }
        return entries
    }

    private fun validateAllowedEntries(entries: List<ZipEntry>, kind: PackageKind) {
        val names = entries.map(ZipEntry::getName).toSet()
        val valid = when (kind) {
            PackageKind.V1_BUNDLE -> names.all(::isAllowedV1File)
            PackageKind.V2_AGENT -> names == V2_AGENT_FILES
            PackageKind.V2_CORPUS -> names == V2_CORPUS_FILES
            PackageKind.V2_SOURCE -> names.all { it in COMMON_PACKAGE_FILES || it == PACKAGE_MANIFEST_PATH || it.startsWith("files/") }
            PackageKind.V2_BUNDLE -> names.all {
                it in COMMON_PACKAGE_FILES || it == BUNDLE_MANIFEST_PATH ||
                    (it.startsWith(PACKAGES_PREFIX) && it.removePrefix(PACKAGES_PREFIX).let { child ->
                        '/' !in child && child.substringAfterLast('.', "") in PACKAGE_SUFFIXES
                    })
            }
        }
        if (!valid) {
            val unexpected = names.firstOrNull { name ->
                when (kind) {
                    PackageKind.V1_BUNDLE -> !isAllowedV1File(name)
                    PackageKind.V2_AGENT -> name !in V2_AGENT_FILES
                    PackageKind.V2_CORPUS -> name !in V2_CORPUS_FILES
                    PackageKind.V2_SOURCE -> name !in COMMON_PACKAGE_FILES &&
                        name != PACKAGE_MANIFEST_PATH && !name.startsWith("files/")
                    PackageKind.V2_BUNDLE -> name !in COMMON_PACKAGE_FILES &&
                        name != BUNDLE_MANIFEST_PATH && !name.startsWith(PACKAGES_PREFIX)
                }
            }
            throw AgentBundleException("不允许的包内文件：${unexpected ?: "包文件集合不完整"}")
        }
        val required = when (kind) {
            PackageKind.V1_BUNDLE, PackageKind.V2_BUNDLE -> COMMON_PACKAGE_FILES + BUNDLE_MANIFEST_PATH
            else -> COMMON_PACKAGE_FILES + PACKAGE_MANIFEST_PATH
        }
        val missing = required - names
        if (missing.isNotEmpty()) throw AgentBundleException("缺少包内文件：${missing.first()}")
    }

    private fun rejectUnsafeCentralDirectory(file: File) {
        val centralEntries = try {
            RandomAccessFile(file, "r").use(::readCentralDirectory)
        } catch (error: AgentBundleException) {
            throw error
        } catch (error: Throwable) {
            throw AgentBundleException("ZIP central directory 无效", error)
        }
        ZipFile(file).use { archive ->
            val zipEntries = archive.entries().asSequence().toList()
            if (zipEntries.size != centralEntries.size) {
                throw AgentBundleException("ZIP central directory 与实际 entries 数量不一致")
            }
            centralEntries.zip(zipEntries).forEach { (central, actual) ->
                if (
                    central.name != actual.name ||
                    central.compressedSize != actual.compressedSize ||
                    central.uncompressedSize != actual.size ||
                    central.crc != actual.crc ||
                    central.method != actual.method
                ) {
                    throw AgentBundleException("ZIP central directory 与实际 entries 不一致：${central.name}")
                }
            }
        }
    }

    private fun readCentralDirectory(archive: RandomAccessFile): List<CentralDirectoryEntry> {
        val archiveLength = archive.length()
        val tailSize = minOf(archiveLength, MAX_EOCD_SEARCH_BYTES.toLong()).toInt()
        if (tailSize < END_OF_CENTRAL_DIRECTORY_SIZE) throw AgentBundleException("ZIP central directory 无效")
        val tailStart = archiveLength - tailSize
        val tail = ByteArray(tailSize)
        archive.seek(tailStart)
        archive.readFully(tail)
        val candidates = (0..tail.size - END_OF_CENTRAL_DIRECTORY_SIZE).filter { offset ->
            tail.readIntLe(offset) == END_OF_CENTRAL_DIRECTORY_SIGNATURE &&
                offset + END_OF_CENTRAL_DIRECTORY_SIZE + tail.readUnsignedShortLe(offset + 20) == tail.size
        }
        if (candidates.size != 1) throw AgentBundleException("ZIP central directory EOCD/comment length 无效")
        val eocdOffset = candidates.single()
        val eocdAbsoluteOffset = tailStart + eocdOffset
        val diskNumber = tail.readUnsignedShortLe(eocdOffset + 4)
        val centralDisk = tail.readUnsignedShortLe(eocdOffset + 6)
        val entriesOnDisk = tail.readUnsignedShortLe(eocdOffset + 8)
        val entryCount = tail.readUnsignedShortLe(eocdOffset + 10)
        val centralSize = tail.readUnsignedIntLe(eocdOffset + 12)
        val centralOffset = tail.readUnsignedIntLe(eocdOffset + 16)
        if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != entryCount) {
            throw AgentBundleException("不支持分卷 ZIP 人格包或 entry count 不一致")
        }
        if (
            entryCount == ZIP64_ENTRY_SENTINEL ||
            centralSize == ZIP64_OFFSET_SENTINEL ||
            centralOffset == ZIP64_OFFSET_SENTINEL
        ) {
            throw AgentBundleException("不支持 ZIP64 central directory")
        }
        val centralEnd = centralOffset + centralSize
        if (centralEnd < centralOffset || centralEnd != eocdAbsoluteOffset || centralEnd > archiveLength) {
            throw AgentBundleException("ZIP central directory offset/size/end 无效")
        }
        val entries = ArrayList<CentralDirectoryEntry>(entryCount)
        val localLayouts = ArrayList<LocalEntryLayout>(entryCount)
        archive.seek(centralOffset)
        repeat(entryCount) {
            if (archive.filePointer + CENTRAL_DIRECTORY_HEADER_SIZE > centralEnd) {
                throw AgentBundleException("ZIP central directory header 越界")
            }
            val header = ByteArray(CENTRAL_DIRECTORY_HEADER_SIZE)
            archive.readFully(header)
            if (header.readIntLe(0) != CENTRAL_DIRECTORY_SIGNATURE) {
                throw AgentBundleException("ZIP central directory 条目无效")
            }
            val hostSystem = header.readUnsignedShortLe(4) ushr 8
            val flags = header.readUnsignedShortLe(8)
            val method = header.readUnsignedShortLe(10)
            val crc = header.readUnsignedIntLe(16)
            val compressedSize = header.readUnsignedIntLe(20)
            val uncompressedSize = header.readUnsignedIntLe(24)
            val fileNameLength = header.readUnsignedShortLe(28)
            val extraLength = header.readUnsignedShortLe(30)
            val commentLength = header.readUnsignedShortLe(32)
            val externalAttributes = header.readUnsignedIntLe(38)
            val localHeaderOffset = header.readUnsignedIntLe(42)
            val variableSize = fileNameLength.toLong() + extraLength + commentLength
            if (archive.filePointer + variableSize > centralEnd) {
                throw AgentBundleException("ZIP central directory 条目边界无效")
            }
            if (
                compressedSize == ZIP64_OFFSET_SENTINEL ||
                uncompressedSize == ZIP64_OFFSET_SENTINEL ||
                localHeaderOffset == ZIP64_OFFSET_SENTINEL
            ) {
                throw AgentBundleException("不支持 ZIP64 central directory")
            }
            val fileNameBytes = ByteArray(fileNameLength)
            archive.readFully(fileNameBytes)
            val fileName = safePath(fileNameBytes.decodeToString())
            if (flags and ENCRYPTED_FLAG != 0) throw AgentBundleException("人格包不允许加密条目：$fileName")
            validateUnixMode(hostSystem, externalAttributes, fileName)
            archive.seek(archive.filePointer + extraLength + commentLength)
            val entry = CentralDirectoryEntry(
                name = fileName,
                flags = flags,
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                crc = crc,
                method = method,
                localHeaderOffset = localHeaderOffset,
            )
            localLayouts += validateLocalHeader(archive, entry, centralOffset)
            entries += entry
        }
        if (archive.filePointer != centralEnd) {
            throw AgentBundleException("ZIP central directory size 与 entries 边界不一致")
        }
        validateLocalEntryBoundaries(archive, localLayouts, centralOffset)
        return entries
    }

    private fun validateLocalHeader(
        archive: RandomAccessFile,
        expected: CentralDirectoryEntry,
        centralOffset: Long,
    ): LocalEntryLayout {
        val localHeaderOffset = expected.localHeaderOffset
        if (localHeaderOffset < 0 || localHeaderOffset + LOCAL_FILE_HEADER_SIZE > centralOffset) {
            throw AgentBundleException("ZIP local header offset 无效：${expected.name}")
        }
        val returnOffset = archive.filePointer
        archive.seek(localHeaderOffset)
        val header = ByteArray(LOCAL_FILE_HEADER_SIZE)
        archive.readFully(header)
        if (header.readIntLe(0) != LOCAL_FILE_HEADER_SIGNATURE) {
            throw AgentBundleException("ZIP local header 无效：${expected.name}")
        }
        val flags = header.readUnsignedShortLe(6)
        val method = header.readUnsignedShortLe(8)
        val crc = header.readUnsignedIntLe(14)
        val compressedSize = header.readUnsignedIntLe(18)
        val uncompressedSize = header.readUnsignedIntLe(22)
        val nameLength = header.readUnsignedShortLe(26)
        val extraLength = header.readUnsignedShortLe(28)
        val payloadOffset = localHeaderOffset + LOCAL_FILE_HEADER_SIZE + nameLength + extraLength
        if (payloadOffset > centralOffset) {
            throw AgentBundleException("ZIP local header 边界无效：${expected.name}")
        }
        val name = ByteArray(nameLength).also(archive::readFully).decodeToString()
        if (name != expected.name) throw AgentBundleException("ZIP local/central 文件名不一致：${expected.name}")
        if (flags != expected.flags || method != expected.method) {
            throw AgentBundleException("ZIP local/central flags 或 method 不一致：${expected.name}")
        }
        val usesDescriptor = flags and DATA_DESCRIPTOR_FLAG != 0
        val localValuesAreZero = crc == 0L && compressedSize == 0L && uncompressedSize == 0L
        val localValuesMatch = crc == expected.crc &&
            compressedSize == expected.compressedSize &&
            uncompressedSize == expected.uncompressedSize
        if ((!usesDescriptor && !localValuesMatch) || (usesDescriptor && !localValuesAreZero && !localValuesMatch)) {
            throw AgentBundleException("ZIP local/central CRC 或 size 不一致：${expected.name}")
        }
        val payloadEnd = payloadOffset + expected.compressedSize
        if (payloadEnd < payloadOffset || payloadEnd > centralOffset) {
            throw AgentBundleException("ZIP payload 边界越界：${expected.name}")
        }
        archive.seek(returnOffset)
        return LocalEntryLayout(expected, payloadOffset, payloadEnd)
    }

    private fun validateLocalEntryBoundaries(
        archive: RandomAccessFile,
        layouts: List<LocalEntryLayout>,
        centralOffset: Long,
    ) {
        val sorted = layouts.sortedBy { it.entry.localHeaderOffset }
        sorted.zipWithNext().forEach { (current, next) ->
            if (current.entry.localHeaderOffset == next.entry.localHeaderOffset) {
                throw AgentBundleException("ZIP local header 区域重叠：${current.entry.name}")
            }
        }
        sorted.forEachIndexed { index, layout ->
            val boundary = sorted.getOrNull(index + 1)?.entry?.localHeaderOffset ?: centralOffset
            if (layout.payloadEnd > boundary) {
                throw AgentBundleException("ZIP payload 区域边界重叠：${layout.entry.name}")
            }
            val entryEnd = if (layout.entry.flags and DATA_DESCRIPTOR_FLAG != 0) {
                validateDataDescriptor(archive, layout, boundary)
            } else {
                layout.payloadEnd
            }
            if (entryEnd > boundary) {
                throw AgentBundleException("ZIP data descriptor 边界重叠：${layout.entry.name}")
            }
        }
    }

    private fun validateDataDescriptor(
        archive: RandomAccessFile,
        layout: LocalEntryLayout,
        boundary: Long,
    ): Long {
        val descriptorOffset = layout.payloadEnd
        if (descriptorOffset + DATA_DESCRIPTOR_SIZE_WITHOUT_SIGNATURE > boundary) {
            throw AgentBundleException("ZIP data descriptor 边界无效：${layout.entry.name}")
        }
        val first = archive.readUnsignedIntLeAt(descriptorOffset)
        val hasSignature = first == DATA_DESCRIPTOR_SIGNATURE.toLong()
        val descriptorSize = if (hasSignature) {
            DATA_DESCRIPTOR_SIZE_WITH_SIGNATURE
        } else {
            DATA_DESCRIPTOR_SIZE_WITHOUT_SIGNATURE
        }
        if (descriptorOffset + descriptorSize > boundary) {
            throw AgentBundleException("ZIP data descriptor 边界无效：${layout.entry.name}")
        }
        val valueOffset = descriptorOffset + if (hasSignature) 4 else 0
        val crc = archive.readUnsignedIntLeAt(valueOffset)
        val compressedSize = archive.readUnsignedIntLeAt(valueOffset + 4)
        val uncompressedSize = archive.readUnsignedIntLeAt(valueOffset + 8)
        if (
            crc != layout.entry.crc ||
            compressedSize != layout.entry.compressedSize ||
            uncompressedSize != layout.entry.uncompressedSize
        ) {
            throw AgentBundleException("ZIP data descriptor 与 central header 不一致：${layout.entry.name}")
        }
        return descriptorOffset + descriptorSize
    }

    private fun validateUnixMode(hostSystem: Int, externalAttributes: Long, fileName: String) {
        if (hostSystem != UNIX_HOST_SYSTEM) return
        val mode = (externalAttributes ushr 16).toInt()
        val type = mode and UNIX_FILE_TYPE_MASK
        if (type != 0 && type != UNIX_REGULAR_FILE_TYPE) {
            val label = if (type == UNIX_SYMLINK_TYPE) "符号链接" else "特殊文件"
            throw AgentBundleException("人格包不允许$label：$fileName")
        }
        if (mode and UNIX_EXECUTABLE_MASK != 0) {
            throw AgentBundleException("人格包不允许可执行文件：$fileName")
        }
    }

    private fun verifyChecksums(
        archive: ZipFile,
        entries: List<ZipEntry>,
        checksums: Map<String, String>,
        runtimeAssetPaths: Set<String>,
    ) {
        val contentEntries = entries.filterNot { it.name in COMMON_PACKAGE_FILES }
        val contentNames = contentEntries.mapTo(mutableSetOf(), ZipEntry::getName)
        val undeclared = contentNames - checksums.keys
        val missing = checksums.keys - contentNames
        if (undeclared.isNotEmpty()) throw AgentBundleException("存在未声明 SHA-256 的文件：${undeclared.first()}")
        if (missing.isNotEmpty()) throw AgentBundleException("checksums.json 引用了不存在的文件：${missing.first()}")
        var remainingRuntimeAssetBytes = MAX_AGENT_RUNTIME_ASSET_BYTES.toLong()
        contentEntries.forEach { entry ->
            val actual = if (entry.name in runtimeAssetPaths) {
                val hashed = archive.getInputStream(entry).use { input ->
                    input.sha256Limited(remainingRuntimeAssetBytes, entry.name)
                }
                remainingRuntimeAssetBytes -= hashed.bytes
                hashed.sha256
            } else {
                archive.getInputStream(entry).use(InputStream::sha256)
            }
            if (actual != checksums.getValue(entry.name)) {
                throw AgentBundleException("SHA-256 校验失败：${entry.name}")
            }
        }
    }

    private fun parseCanonicalChecksums(payload: ByteArray): Map<String, String> {
        val root = parseObject(payload.decodeToString(), CHECKSUMS_PATH)
        if (root.keys != setOf("files")) throw AgentBundleException("checksums.json 顶层契约无效")
        val files = root.requiredObject("files", CHECKSUMS_PATH)
        if (files.isEmpty()) throw AgentBundleException("checksums.json files 不能为空")
        val result = sortedMapOf<String, String>()
        files.forEach { (rawPath, value) ->
            val path = safePath(rawPath)
            val digest = value.asString("checksums.json files.$path")
            result[path] = hash(digest)
        }
        if (result.size != files.size) throw AgentBundleException("checksums.json 包含重复规范化路径")
        val canonical = JsonObject(
            mapOf(
                "files" to JsonObject(result.mapValues { JsonPrimitive(it.value) }),
            ),
        ).toString().encodeToByteArray()
        if (!canonical.contentEquals(payload)) throw AgentBundleException("checksums.json 不是规范 JSON")
        return result
    }

    private fun parseSignature(payload: ByteArray): SignatureRecord {
        val root = parseObject(payload.decodeToString(), SIGNATURE_PATH)
        if (root.keys != setOf("algorithm", "publicKey", "signature", "signedFile")) {
            throw AgentBundleException("signature.json 顶层契约无效")
        }
        return try {
            SignatureRecord(
                algorithm = root.requiredString("algorithm", SIGNATURE_PATH),
                publicKey = Base64.getDecoder().decode(root.requiredString("publicKey", SIGNATURE_PATH)),
                signature = Base64.getDecoder().decode(root.requiredString("signature", SIGNATURE_PATH)),
                signedFile = root.requiredString("signedFile", SIGNATURE_PATH),
            )
        } catch (error: IllegalArgumentException) {
            throw AgentBundleException("signature.json 格式无效", error)
        }
    }

    private fun verifySignature(record: SignatureRecord, payload: ByteArray) {
        if (
            record.algorithm != "Ed25519" ||
            record.signedFile != CHECKSUMS_PATH ||
            record.publicKey.size != 32 ||
            record.signature.size != 64
        ) {
            throw AgentBundleException("不支持的签名声明")
        }
        if (!signatureVerifier.verify(record.publicKey, payload, record.signature)) {
            throw AgentBundleException("人格包签名校验失败")
        }
    }

    private fun verifyPublisher(parent: VerifiedPackage, child: ParsedAgentPackage, childName: String) {
        if (parent.fingerprint != child.publisherFingerprint) {
            throw AgentBundleException("子包发布者不匹配：$childName")
        }
    }

    private fun parseInstallPlan(root: JsonObject): V2InstallPlan {
        if (root.requiredPositiveInt("schemaVersion", V2_INSTALL_PLAN_PATH) != 2) {
            throw AgentBundleException("install plan schemaVersion 无效")
        }
        val packages = root.requiredArray("packages", V2_INSTALL_PLAN_PATH).mapIndexed { index, element ->
            val row = element.asObject("install plan packages[$index]")
            V2InstallPackage(
                id = row.requiredIdentifier("id", "install plan package"),
                type = row.requiredPackageType("type", "install plan package"),
                fileName = packageFileName(row.requiredString("fileName", "install plan package")),
                installClass = row.requiredInstallClass("installClass", "install plan package"),
                dependencies = row.stringList("dependencies", "install plan package").map(::identifier),
                sizeBytes = row.requiredPositiveLong("sizeBytes", "install plan package"),
                sha256 = row.requiredHash("sha256", "install plan package"),
            )
        }
        rejectDuplicates(packages.map(V2InstallPackage::id), "install plan package ID")
        rejectDuplicates(packages.map(V2InstallPackage::fileName), "install plan fileName")
        packages.forEach { declaration ->
            if (declaration.type !in setOf(V2PackageType.CORPUS, V2PackageType.SOURCE)) {
                throw AgentBundleException("install plan package type 无效：${declaration.type.wireName}")
            }
            if (declaration.type == V2PackageType.SOURCE && declaration.installClass != V2InstallClass.SOURCE) {
                throw AgentBundleException("source package installClass 无效")
            }
            if (declaration.type == V2PackageType.CORPUS && declaration.installClass == V2InstallClass.SOURCE) {
                throw AgentBundleException("corpus package installClass 无效")
            }
        }
        val profiles = root.requiredArray("profiles", V2_INSTALL_PLAN_PATH).mapIndexed { index, element ->
            val row = element.asObject("install plan profiles[$index]")
            V2InstallProfile(
                id = row.requiredIdentifier("id", "install plan profile"),
                packageIds = row.stringList("packageIds", "install plan profile").map(::identifier),
                recommended = row.optionalBoolean("recommended", "install plan profile", false),
            ).also { profile -> rejectDuplicates(profile.packageIds, "profile ${profile.id} package ID") }
        }
        rejectDuplicates(profiles.map(V2InstallProfile::id), "install plan profile ID")
        val recommended = root.requiredIdentifier("recommendedProfileId", V2_INSTALL_PLAN_PATH)
        if (profiles.count { it.id == recommended && it.recommended } != 1 || profiles.count { it.recommended } != 1) {
            throw AgentBundleException("install plan 推荐 profile 无效")
        }
        val knownIds = packages.mapTo(mutableSetOf(), V2InstallPackage::id)
        profiles.forEach { profile ->
            val unknown = profile.packageIds.firstOrNull { it !in knownIds }
            if (unknown != null) throw AgentBundleException("profile 引用了未声明 package：$unknown")
            profile.packageIds.forEach { selected ->
                val declaration = packages.single { it.id == selected }
                if (!declaration.dependencies.all(profile.packageIds::contains)) {
                    throw AgentBundleException("profile 缺少 package 依赖：$selected")
                }
            }
        }
        val required = root.stringList("requiredCorpusIds", V2_INSTALL_PLAN_PATH).map(::identifier)
        rejectDuplicates(required, "required corpus ID")
        val declaredRequired = packages.filter {
            it.type == V2PackageType.CORPUS && it.installClass == V2InstallClass.REQUIRED
        }.map(V2InstallPackage::id).toSet()
        if (required.toSet() != declaredRequired) {
            throw AgentBundleException("required corpus 集合与 required package 声明不一致")
        }
        required.forEach { id ->
            val declaration = packages.singleOrNull { it.id == id }
                ?: throw AgentBundleException("install plan 缺少 required corpus：$id")
            if (declaration.type != V2PackageType.CORPUS || declaration.installClass != V2InstallClass.REQUIRED) {
                throw AgentBundleException("required corpus 声明无效：$id")
            }
            if (profiles.any { id !in it.packageIds }) throw AgentBundleException("profile 缺少 required corpus：$id")
        }
        return V2InstallPlan(packages, profiles, recommended, required)
    }

    private fun parseIdentity(root: JsonObject): V2Identity = V2Identity(
        selfNames = root.stringList("selfNames", V2_IDENTITY_PATH),
        timeHorizon = root.optionalString("timeHorizon", V2_IDENTITY_PATH),
        roles = root.stringList("roles", V2_IDENTITY_PATH),
        relationships = root.optionalArray("relationships", V2_IDENTITY_PATH).mapIndexed { index, element ->
            val row = element.asObject("$V2_IDENTITY_PATH relationships[$index]")
            V2Relationship(
                subject = row.requiredString("subject", V2_IDENTITY_PATH),
                relation = row.requiredString("relation", V2_IDENTITY_PATH),
                period = row.optionalString("period", V2_IDENTITY_PATH),
                evidence = row.stringList("evidence", V2_IDENTITY_PATH).map(::identifier),
            )
        },
    )

    private fun parseVoice(root: JsonObject): V2Voice = V2Voice(
        defaultForm = root.optionalString("defaultForm", V2_VOICE_PATH),
        sentenceRhythm = root.stringList("sentenceRhythm", V2_VOICE_PATH),
        rhetoricalMoves = root.stringList("rhetoricalMoves", V2_VOICE_PATH),
        preferredTerms = root.stringList("preferredTerms", V2_VOICE_PATH),
        avoidPatterns = root.stringList("avoidPatterns", V2_VOICE_PATH),
        evidence = root.stringList("evidence", V2_VOICE_PATH).map(::identifier),
    )

    private fun parseWorldview(root: JsonObject) = V2Worldview(
        id = root.requiredIdentifier("id", V2_WORLDVIEW_PATH),
        topic = root.requiredString("topic", V2_WORLDVIEW_PATH),
        statement = root.requiredString("statement", V2_WORLDVIEW_PATH),
        conditions = root.stringList("conditions", V2_WORLDVIEW_PATH),
        period = root.optionalString("period", V2_WORLDVIEW_PATH),
        aliases = root.stringList("aliases", V2_WORLDVIEW_PATH),
        confidence = root.optionalDouble("confidence", V2_WORLDVIEW_PATH, 0.0),
        evidence = root.stringList("evidence", V2_WORLDVIEW_PATH).map(::identifier),
    )

    private fun parseEpisode(root: JsonObject) = V2Episode(
        id = root.requiredIdentifier("id", V2_EPISODES_PATH),
        period = root.optionalString("period", V2_EPISODES_PATH),
        location = root.optionalString("location", V2_EPISODES_PATH),
        participants = root.stringList("participants", V2_EPISODES_PATH),
        summary = root.requiredString("summary", V2_EPISODES_PATH),
        meaning = root.optionalString("meaning", V2_EPISODES_PATH),
        evidence = root.stringList("evidence", V2_EPISODES_PATH).map(::identifier),
    )

    private fun parseConcept(root: JsonObject) = V2Concept(
        id = root.requiredIdentifier("id", V2_CONCEPTS_PATH),
        name = root.requiredString("name", V2_CONCEPTS_PATH),
        aliases = root.stringList("aliases", V2_CONCEPTS_PATH),
        keywords = root.stringList("keywords", V2_CONCEPTS_PATH),
        evidence = root.stringList("evidence", V2_CONCEPTS_PATH).map(::identifier),
    )

    private fun parseExample(root: JsonObject) = V2Example(
        id = root.requiredIdentifier("id", V2_EXAMPLES_PATH),
        intent = root.optionalString("intent", V2_EXAMPLES_PATH),
        user = root.requiredString("user", V2_EXAMPLES_PATH),
        assistant = root.requiredString("assistant", V2_EXAMPLES_PATH),
        styleTags = root.stringList("styleTags", V2_EXAMPLES_PATH),
        generationType = root.requiredString("generationType", V2_EXAMPLES_PATH),
        evidence = root.stringList("evidence", V2_EXAMPLES_PATH).map(::identifier),
    )

    private fun parseOpeners(root: JsonObject) = V2Openers(
        default = root.optionalString("default", V2_OPENERS_PATH),
        alternatives = root.stringList("alternatives", V2_OPENERS_PATH),
    )

    private fun parseEvaluation(root: JsonObject) = V2Evaluation(
        id = root.requiredIdentifier("id", V2_EVAL_PATH),
        category = root.requiredString("category", V2_EVAL_PATH),
        question = root.requiredString("question", V2_EVAL_PATH),
        period = root.optionalString("period", V2_EVAL_PATH),
        expectedEvidence = root.stringList("expectedEvidence", V2_EVAL_PATH).map(::identifier),
        corpusId = root.requiredIdentifier("corpusId", V2_EVAL_PATH),
    )

    private fun parseSourceRecord(root: JsonObject) = V2SourceRecord(
        sourceId = root.requiredIdentifier("sourceId", V2_SOURCES_PATH),
        title = root.requiredString("title", V2_SOURCES_PATH),
        fileName = displayFileName(root.requiredString("fileName", V2_SOURCES_PATH)),
        storedName = storedFileName(root.requiredString("storedName", V2_SOURCES_PATH)),
        sourceHash = root.requiredHash("sourceHash", V2_SOURCES_PATH),
        format = root.requiredString("format", V2_SOURCES_PATH),
        genre = root.requiredSourceGenre("genre", V2_SOURCES_PATH),
        authorship = root.requiredAuthorship("authorship", V2_SOURCES_PATH),
        period = root.requiredString("period", V2_SOURCES_PATH),
        rawSizeBytes = root.requiredNonNegativeLong("rawSizeBytes", V2_SOURCES_PATH),
        extractedChars = root.requiredNonNegativeLong("extractedChars", V2_SOURCES_PATH),
    )

    private fun parseNode(root: JsonObject) = V2HierarchyNode(
        id = root.requiredIdentifier("id", V2_NODES_PATH),
        kind = root.requiredString("kind", V2_NODES_PATH),
        title = root.requiredString("title", V2_NODES_PATH),
        sourceId = root.requiredIdentifier("sourceId", V2_NODES_PATH),
        parentId = root.optionalNullableString("parentId", V2_NODES_PATH)?.let(::identifier),
        path = root.stringList("path", V2_NODES_PATH),
        summary = root.optionalString("summary", V2_NODES_PATH),
    )

    private fun parseV2Chunk(root: JsonObject) = V2Chunk(
        id = root.requiredIdentifier("id", V2_CHUNKS_PATH),
        sourceId = root.requiredIdentifier("sourceId", V2_CHUNKS_PATH),
        sourceHash = root.requiredHash("sourceHash", V2_CHUNKS_PATH),
        sourceTitle = root.requiredString("sourceTitle", V2_CHUNKS_PATH),
        genre = root.requiredSourceGenre("genre", V2_CHUNKS_PATH),
        authorship = root.requiredAuthorship("authorship", V2_CHUNKS_PATH),
        period = root.requiredString("period", V2_CHUNKS_PATH),
        location = root.optionalString("location", V2_CHUNKS_PATH),
        parentIds = root.stringList("parentIds", V2_CHUNKS_PATH).map(::identifier),
        context = root.optionalString("context", V2_CHUNKS_PATH),
        text = root.requiredString("text", V2_CHUNKS_PATH),
        keywords = root.stringList("keywords", V2_CHUNKS_PATH),
        ngrams = root.stringList("ngrams", V2_CHUNKS_PATH),
        conflictKey = root.optionalString("conflictKey", V2_CHUNKS_PATH),
        duplicateGroup = root.optionalString("duplicateGroup", V2_CHUNKS_PATH),
        sourceAliases = root.stringList("sourceAliases", V2_CHUNKS_PATH),
        simHash = root.requiredString("simHash", V2_CHUNKS_PATH).also {
            if (!it.matches(Regex("[0-9a-f]{16}"))) throw AgentBundleException("chunk simHash 无效")
        },
    )

    private fun parseDuplicate(root: JsonObject) = V2Duplicate(
        duplicateChunkId = root.requiredIdentifier("duplicateChunkId", V2_DUPLICATES_PATH),
        physicalChunkId = root.requiredIdentifier("physicalChunkId", V2_DUPLICATES_PATH),
        duplicateSourceId = root.requiredIdentifier("duplicateSourceId", V2_DUPLICATES_PATH),
        primarySourceId = root.requiredIdentifier("primarySourceId", V2_DUPLICATES_PATH),
        matchType = root.requiredString("matchType", V2_DUPLICATES_PATH).also {
            if (it !in setOf("exact", "near")) throw AgentBundleException("duplicate matchType 无效")
        },
        period = root.requiredString("period", V2_DUPLICATES_PATH),
        conflictKey = root.optionalString("conflictKey", V2_DUPLICATES_PATH),
    )

    private fun parseV1Chunk(line: String, corpus: AgentCorpusManifest, lineNumber: Int): AgentCorpusChunk =
        try {
            val row = parseObject(line, "资料块第 $lineNumber 行")
            AgentCorpusChunk(
                id = row.requiredIdentifier("id", "资料块第 $lineNumber 行"),
                sourceTitle = row.requiredString("sourceTitle", "资料块第 $lineNumber 行"),
                sourceHash = row.optionalString("sourceHash", "资料块第 $lineNumber 行").ifBlank {
                    corpus.sourceHash
                },
                location = row.optionalString("location", "资料块第 $lineNumber 行"),
                text = row.requiredString("text", "资料块第 $lineNumber 行"),
                keywords = row.stringList("keywords", "资料块第 $lineNumber 行"),
                ngrams = row.stringList("ngrams", "资料块第 $lineNumber 行"),
            )
        } catch (error: AgentBundleException) {
            throw error
        } catch (error: Throwable) {
            throw AgentBundleException("资料块第 $lineNumber 行格式无效", error)
        }

    private fun ZipFile.readAgentJsonObject(
        path: String,
        budget: AgentRuntimeAssetBudget,
        onJsonText: (String) -> Unit = {},
    ): JsonObject {
        val bytes = budget.open(this, path).use { input ->
            AgentRuntimeJsonPreflight(input, path, budget).readJsonBytes()
        }
        val text = bytes.decodeStrictUtf8(path)
        onJsonText(text)
        return parseObject(text, path)
    }

    private fun <T> ZipFile.readAgentJsonl(
        path: String,
        budget: AgentRuntimeAssetBudget,
        parser: (JsonObject) -> T,
    ): List<T> {
        val rows = mutableListOf<T>()
        val ids = mutableSetOf<String>()
        budget.open(this, path).use { input ->
            input.forEachUtf8JsonLine(path) { line, lineNumber ->
                budget.addRecords(path, 1)
                val parsed = parser(parseObject(line, "$path 第 $lineNumber 行"))
                val id = when (parsed) {
                    is V2Worldview -> parsed.id
                    is V2Episode -> parsed.id
                    is V2Example -> parsed.id
                    is V2Evaluation -> parsed.id
                    else -> null
                }
                if (id != null && !ids.add(id)) throw AgentBundleException("$path 包含重复 id：$id")
                rows += parsed
            }
        }
        return rows
    }

    private fun ZipFile.streamJsonl(path: String, block: (JsonObject, Int) -> Unit) {
        val entry = getEntry(path) ?: throw AgentBundleException("缺少包内文件：$path")
        getInputStream(entry).use { input ->
            input.forEachUtf8JsonLine(path) { line, lineNumber ->
                block(parseObject(line, "$path 第 $lineNumber 行"), lineNumber)
            }
        }
    }

    private fun ZipFile.readRequired(name: String, maxBytes: Int): ByteArray {
        val safeName = safePath(name)
        val entry = getEntry(safeName) ?: throw AgentBundleException("缺少包内文件：$safeName")
        if (entry.size > maxBytes) throw AgentBundleException("包内文件超过读取上限：$safeName")
        return getInputStream(entry).use { input -> input.readBytesLimited(maxBytes, safeName) }
    }

    private fun ZipFile.requireEntry(name: String) {
        if (getEntry(safePath(name)) == null) throw AgentBundleException("缺少运行时资源：$name")
    }

    private fun validateDeclaredV2AgentRuntimeAssets(archive: ZipFile, manifestPath: String): Set<String> {
        val manifest = parseObject(archive.readRequired(manifestPath, MAX_MANIFEST_BYTES).decodeToString(), manifestPath)
        if (manifest.optionalString("type", manifestPath) != "hagent" || manifest["schemaVersion"]?.jsonPrimitive?.intOrNull != 2) {
            return emptySet()
        }
        var total = 0L
        V2_AGENT_RUNTIME_ASSET_FILES.forEach { path ->
            val entry = archive.getEntry(path) ?: throw AgentBundleException("缺少包内文件：$path")
            if (entry.size < 0 || entry.size > MAX_AGENT_RUNTIME_ASSET_BYTES) {
                throw AgentBundleException("运行时资产超过大小上限：$path")
            }
            total = Math.addExact(total, entry.size)
            if (total > MAX_AGENT_RUNTIME_ASSET_BYTES) {
                throw AgentBundleException("运行时资产总字节超过大小上限")
            }
        }
        return V2_AGENT_RUNTIME_ASSET_FILES
    }

    private class AgentRuntimeAssetBudget {
        private var consumedBytes = 0L
        private var recordCount = 0L

        fun read(archive: ZipFile, path: String): ByteArray =
            open(archive, path).use { input -> input.readBytesLimited(MAX_AGENT_RUNTIME_ASSET_BYTES, path) }

        fun open(archive: ZipFile, path: String): InputStream {
            val safePath = safePath(path)
            val entry = archive.getEntry(safePath) ?: throw AgentBundleException("缺少包内文件：$safePath")
            if (entry.size < 0 || entry.size > MAX_AGENT_RUNTIME_ASSET_BYTES) {
                throw AgentBundleException("运行时资产超过大小上限：$safePath")
            }
            return object : FilterInputStream(archive.getInputStream(entry)) {
                override fun read(): Int {
                    val value = super.read()
                    if (value >= 0) addBytes(safePath, 1)
                    return value
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    if (count > 0) addBytes(safePath, count)
                    return count
                }
            }
        }

        fun addRecords(path: String, count: Int) {
            recordCount = Math.addExact(recordCount, count.toLong())
            if (recordCount > MAX_AGENT_RUNTIME_ASSET_RECORDS) {
                throw AgentBundleException("运行时资产记录数超过上限：$path")
            }
        }

        private fun addBytes(path: String, count: Int) {
            consumedBytes = Math.addExact(consumedBytes, count.toLong())
            if (consumedBytes > MAX_AGENT_RUNTIME_ASSET_BYTES) {
                throw AgentBundleException("运行时资产超过大小上限：$path")
            }
        }
    }

    /** Counts every JSON array element before kotlinx.serialization creates a JSON DOM. */
    private class AgentRuntimeJsonPreflight(
        private val input: InputStream,
        private val path: String,
        private val budget: AgentRuntimeAssetBudget,
    ) {
        private val bytes = ByteArrayOutputStream()
        private var lookahead = UNSET

        fun readJsonBytes(): ByteArray {
            skipWhitespace()
            parseValue(depth = 0)
            skipWhitespace()
            if (next() != EOF) invalidJson()
            return bytes.toByteArray()
        }

        private fun parseValue(depth: Int) {
            if (depth > MAX_RUNTIME_JSON_NESTING) {
                throw AgentBundleException("$path JSON 嵌套超过上限")
            }
            skipWhitespace()
            when (peek()) {
                '{'.code -> parseObject(depth + 1)
                '['.code -> parseArray(depth + 1)
                '"'.code -> parseString()
                't'.code -> parseLiteral("true")
                'f'.code -> parseLiteral("false")
                'n'.code -> parseLiteral("null")
                '-'.code, in '0'.code..'9'.code -> parseNumber()
                else -> invalidJson()
            }
        }

        private fun parseObject(depth: Int) {
            expect('{'.code)
            skipWhitespace()
            if (peek() == '}'.code) {
                next()
                return
            }
            while (true) {
                parseString()
                skipWhitespace()
                expect(':'.code)
                parseValue(depth)
                skipWhitespace()
                when (next()) {
                    ','.code -> {
                        skipWhitespace()
                        if (peek() == '}'.code) invalidJson()
                    }
                    '}'.code -> return
                    else -> invalidJson()
                }
            }
        }

        private fun parseArray(depth: Int) {
            expect('['.code)
            skipWhitespace()
            if (peek() == ']'.code) {
                next()
                return
            }
            while (true) {
                budget.addRecords(path, 1)
                parseValue(depth)
                skipWhitespace()
                when (next()) {
                    ','.code -> {
                        skipWhitespace()
                        if (peek() == ']'.code) invalidJson()
                    }
                    ']'.code -> return
                    else -> invalidJson()
                }
            }
        }

        private fun parseString() {
            expect('"'.code)
            while (true) {
                when (val value = next()) {
                    EOF -> invalidJson()
                    '"'.code -> return
                    '\\'.code -> parseEscape()
                    in 0..0x1f -> invalidJson()
                    else -> Unit
                }
            }
        }

        private fun parseEscape() {
            when (next()) {
                '"'.code, '\\'.code, '/'.code, 'b'.code, 'f'.code, 'n'.code, 'r'.code, 't'.code -> Unit
                'u'.code -> repeat(4) {
                    if (!isHexDigit(next())) invalidJson()
                }
                else -> invalidJson()
            }
        }

        private fun parseNumber() {
            if (peek() == '-'.code) next()
            when (peek()) {
                '0'.code -> next()
                in '1'.code..'9'.code -> consumeDigits()
                else -> invalidJson()
            }
            if (peek() == '.'.code) {
                next()
                requireDigit()
                consumeDigits()
            }
            if (peek() == 'e'.code || peek() == 'E'.code) {
                next()
                if (peek() == '+'.code || peek() == '-'.code) next()
                requireDigit()
                consumeDigits()
            }
        }

        private fun parseLiteral(literal: String) {
            literal.forEach { expected -> expect(expected.code) }
        }

        private fun consumeDigits() {
            while (peek() in '0'.code..'9'.code) next()
        }

        private fun requireDigit() {
            if (peek() !in '0'.code..'9'.code) invalidJson()
        }

        private fun isHexDigit(value: Int): Boolean =
            value in '0'.code..'9'.code || value in 'a'.code..'f'.code || value in 'A'.code..'F'.code

        private fun skipWhitespace() {
            while (peek() in JSON_WHITESPACE) next()
        }

        private fun expect(expected: Int) {
            if (next() != expected) invalidJson()
        }

        private fun peek(): Int {
            if (lookahead == UNSET) lookahead = readByte()
            return lookahead
        }

        private fun next(): Int {
            val value = if (lookahead == UNSET) readByte() else lookahead
            lookahead = UNSET
            return value
        }

        private fun readByte(): Int = input.read().also { value ->
            if (value != EOF) bytes.write(value)
        }

        private fun invalidJson(): Nothing = throw AgentBundleException("$path JSON 格式无效")

        private companion object {
            const val EOF = -1
            const val UNSET = -2
            val JSON_WHITESPACE = setOf(' '.code, '\t'.code, '\n'.code, '\r'.code)
        }
    }

    private fun validateInputFile(file: File) {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw AgentBundleException("人格包不存在或不是普通文件")
        }
        if (file.length() > MAX_COMPRESSED_BYTES) throw AgentBundleException("人格包超过 2 GiB 上限")
    }

    private fun validateJsonlText(text: String, path: String) {
        val ids = mutableSetOf<String>()
        text.lineSequence().forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed
            if (line.length > MAX_JSONL_LINE_BYTES) throw AgentBundleException("$path 第 ${index + 1} 行超过大小上限")
            val row = parseObject(line, "$path 第 ${index + 1} 行")
            row.optionalString("id", path).takeIf(String::isNotBlank)?.let { id ->
                identifier(id)
                if (!ids.add(id)) throw AgentBundleException("$path 包含重复 id：$id")
            }
        }
    }

    companion object {
        private const val MAX_ENTRY_COUNT = 50_000
        private const val MAX_COMPRESSED_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_UNCOMPRESSED_BYTES = 4L * 1024 * 1024 * 1024
        private const val MAX_ENTRY_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        private const val MAX_CHECKSUMS_BYTES = 16 * 1024 * 1024
        private const val MAX_SIGNATURE_BYTES = 64 * 1024
        private const val MAX_PERSONA_BYTES = 2 * 1024 * 1024
        private const val MAX_WORLDVIEW_BYTES = 32 * 1024 * 1024
        private const val MAX_AGENT_RUNTIME_ASSET_BYTES = 8 * 1024 * 1024
        private const val MAX_AGENT_RUNTIME_ASSET_RECORDS = 10_000L
        private const val MAX_RUNTIME_JSON_NESTING = 256
        private const val MAX_CORPUS_METADATA_BYTES = 16 * 1024 * 1024
        private const val MAX_JSONL_LINE_BYTES = 4 * 1024 * 1024
        private const val MAX_EOCD_SEARCH_BYTES = 65_557
        private const val MAX_PATH_DEPTH = 12
        private const val MAX_HIERARCHY_DEPTH = 128
        private const val MAX_PACKAGE_NESTING = 2
        private const val MAX_COMPRESSION_RATIO = 200L
        private const val MIN_RATIO_CHECK_BYTES = 1024 * 1024
        private const val CENTRAL_DIRECTORY_HEADER_SIZE = 46
        private const val LOCAL_FILE_HEADER_SIZE = 30
        private const val DATA_DESCRIPTOR_SIZE_WITHOUT_SIGNATURE = 12
        private const val DATA_DESCRIPTOR_SIZE_WITH_SIGNATURE = 16
        private const val END_OF_CENTRAL_DIRECTORY_SIZE = 22
        private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50
        private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50
        private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50
        private const val DATA_DESCRIPTOR_SIGNATURE = 0x08074b50
        private const val ZIP64_ENTRY_SENTINEL = 0xffff
        private const val ZIP64_OFFSET_SENTINEL = 0xffffffffL
        private const val UNIX_HOST_SYSTEM = 3
        private const val UNIX_FILE_TYPE_MASK = 0xf000
        private const val UNIX_REGULAR_FILE_TYPE = 0x8000
        private const val UNIX_SYMLINK_TYPE = 0xa000
        private const val UNIX_EXECUTABLE_MASK = 0x49
        private const val ENCRYPTED_FLAG = 0x1
        private const val DATA_DESCRIPTOR_FLAG = 0x8
        private const val CHECKSUMS_PATH = "checksums.json"
        private const val SIGNATURE_PATH = "signature.json"
        private const val BUNDLE_MANIFEST_PATH = "bundle-manifest.json"
        private const val PACKAGE_MANIFEST_PATH = "manifest.json"
        private const val PACKAGES_PREFIX = "packages/"
        private const val V2_PERSONA_PATH = "agent/persona.md"
        private const val V2_IDENTITY_PATH = "agent/identity.json"
        private const val V2_VOICE_PATH = "agent/voice.json"
        private const val V2_WORLDVIEW_PATH = "agent/worldview.jsonl"
        private const val V2_EPISODES_PATH = "agent/episodes.jsonl"
        private const val V2_CONCEPTS_PATH = "agent/concepts.json"
        private const val V2_EXAMPLES_PATH = "agent/examples.jsonl"
        private const val V2_OPENERS_PATH = "agent/openers.json"
        private const val V2_EVAL_PATH = "agent/eval.jsonl"
        private const val V2_INSTALL_PLAN_PATH = "install-plan.json"
        private const val V2_SOURCES_PATH = "sources.json"
        private const val V2_NODES_PATH = "nodes.jsonl"
        private const val V2_CHUNKS_PATH = "chunks.jsonl"
        private const val V2_DUPLICATES_PATH = "duplicates.jsonl"
        private val COMMON_PACKAGE_FILES = setOf(CHECKSUMS_PATH, SIGNATURE_PATH)
        private val PACKAGE_SUFFIXES = setOf("hagent", "hcorpus", "hsource")
        private val V2_AGENT_RUNTIME_ASSET_FILES = setOf(
            V2_PERSONA_PATH,
            V2_IDENTITY_PATH,
            V2_VOICE_PATH,
            V2_WORLDVIEW_PATH,
            V2_EPISODES_PATH,
            V2_CONCEPTS_PATH,
            V2_EXAMPLES_PATH,
            V2_OPENERS_PATH,
            V2_EVAL_PATH,
            V2_INSTALL_PLAN_PATH,
        )
        private val V2_AGENT_FILES = COMMON_PACKAGE_FILES + V2_AGENT_RUNTIME_ASSET_FILES + PACKAGE_MANIFEST_PATH
        private val V2_CORPUS_FILES = COMMON_PACKAGE_FILES + setOf(
            PACKAGE_MANIFEST_PATH,
            V2_SOURCES_PATH,
            V2_NODES_PATH,
            V2_CHUNKS_PATH,
            V2_DUPLICATES_PATH,
        )
    }
}

private data class CorpusGraphValidation(
    val nodeCount: Int,
    val chunkCount: Int,
    val duplicateCount: Int,
    val diagnostics: V2CorpusValidationDiagnostics,
)

private data class StagedPackage(
    val root: File,
    val file: File,
) : AutoCloseable {
    override fun close() {
        root.deleteRecursively()
    }
}

private data class CentralDirectoryEntry(
    val name: String,
    val flags: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val crc: Long,
    val method: Int,
    val localHeaderOffset: Long,
)

private data class LocalEntryLayout(
    val entry: CentralDirectoryEntry,
    val payloadOffset: Long,
    val payloadEnd: Long,
)

private data class NodeIndexRecord(
    val sourceId: String,
    val parentId: String,
    val path: List<String>,
)

private data class ChunkIndexRecord(
    val sourceId: String,
    val period: String,
    val conflictKey: String,
)

private fun sourceKey(sourceId: String): String = "source\u001f$sourceId"
private fun nodeKey(nodeId: String): String = "node\u001f$nodeId"
private fun chunkKeyForValidation(chunkId: String): String = "chunk\u001f$chunkId"
private fun v1ChunkKey(chunkId: String): String = "v1-chunk\u001f$chunkId"
private fun chunkSourceKey(chunkId: String, sourceId: String): String = "chunk-source\u001f$chunkId\u001f$sourceId"
private fun duplicateKey(chunkId: String): String = "duplicate\u001f$chunkId"
private fun provenanceKey(dimension: String, value: String): String =
    "provenance\u001f$dimension\u001f${value.encodeToByteArray().sha256()}"

private fun observeManifestProvenance(
    index: AgentCorpusValidationIndex,
    dimension: String,
    value: String,
    declaredValues: List<String>,
) {
    if (value !in declaredValues) {
        throw AgentBundleException("chunks.jsonl 包含 manifest $dimension 未声明值：$value")
    }
    index.putUnique(provenanceKey(dimension, value))
}

private fun requireManifestProvenanceClosure(
    index: AgentCorpusValidationIndex,
    dimension: String,
    declaredValues: List<String>,
) {
    declaredValues.forEach { value ->
        if (!index.contains(provenanceKey(dimension, value))) {
            throw AgentBundleException("manifest $dimension 包含 chunks.jsonl 未使用值：$value")
        }
    }
}

private fun decodeNodeIndex(payload: ByteArray): NodeIndexRecord {
    val fields = decodeIndexFields(payload)
    if (fields.size < 4) throw AgentBundleException("语料 hierarchy 磁盘索引损坏")
    return NodeIndexRecord(fields[0], fields[1], fields.drop(3))
}

private fun decodeChunkIndex(payload: ByteArray): ChunkIndexRecord {
    val fields = decodeIndexFields(payload)
    if (fields.size != 3) throw AgentBundleException("语料 chunk 磁盘索引损坏")
    return ChunkIndexRecord(fields[0], fields[1], fields[2])
}

fun interface AgentSignatureVerifier {
    fun verify(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean
}

object PortableEd25519Verifier : AgentSignatureVerifier {
    override fun verify(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(payload, 0, payload.size)
                verifySignature(signature)
            }
        } catch (error: Throwable) {
            throw AgentBundleException("当前设备不支持 Ed25519 签名校验", error)
        }
    }
}

private enum class PackageKind {
    V1_BUNDLE,
    V2_BUNDLE,
    V2_AGENT,
    V2_CORPUS,
    V2_SOURCE;

    companion object {
        fun fromWireName(value: String): PackageKind? = when (value) {
            "hbundle" -> V2_BUNDLE
            "hagent" -> V2_AGENT
            "hcorpus" -> V2_CORPUS
            "hsource" -> V2_SOURCE
            else -> null
        }
    }
}

private data class VerifiedPackage(
    val file: File,
    val packageSha256: String,
    val publicKey: ByteArray,
    val fingerprint: String,
    val manifestJson: String,
    val compressedSize: Long,
    val uncompressedSize: Long,
)

private data class SignatureRecord(
    val algorithm: String,
    val publicKey: ByteArray,
    val signature: ByteArray,
    val signedFile: String,
)

private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{1,127}")
private val HASH = Regex("[0-9a-f]{64}")

private fun safePath(value: String): String {
    if ('\\' in value) throw AgentBundleException("包内路径不允许反斜杠：$value")
    val parts = value.split('/')
    if (
        value.isBlank() ||
        value.startsWith('/') ||
        Regex("^[A-Za-z]:/").containsMatchIn(value) ||
        parts.any { it.isBlank() || it == "." || it == ".." }
    ) {
        throw AgentBundleException("不安全的包内路径：$value")
    }
    return value
}

private fun identifier(value: String): String =
    value.trim().takeIf(IDENTIFIER::matches) ?: throw AgentBundleException("标识符无效：$value")

private fun hash(value: String): String =
    value.takeIf(HASH::matches) ?: throw AgentBundleException("SHA-256 格式无效：$value")

private fun sourceGenre(value: String): V2SourceGenre =
    V2SourceGenre.entries.singleOrNull { it.wireName == value }
        ?: throw AgentBundleException("source genre 无效：$value")

private fun authorship(value: String): V2Authorship =
    V2Authorship.entries.singleOrNull { it.wireName == value }
        ?: throw AgentBundleException("authorship 无效：$value")

private fun packageFileName(value: String): String {
    val safe = safePath(value)
    if ('/' in safe || safe.substringAfterLast('.', "") !in setOf("hagent", "hcorpus", "hsource")) {
        throw AgentBundleException("子包 fileName 无效：$value")
    }
    return safe
}

private fun storedFileName(value: String): String {
    val safe = safePath(value)
    if ('/' in safe || safe.toByteArray().size > 200) throw AgentBundleException("storedName 无效：$value")
    return safe
}

private fun displayFileName(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || '/' in trimmed || '\\' in trimmed || trimmed.toByteArray().size > 512) {
        throw AgentBundleException("fileName 无效：$value")
    }
    return trimmed
}

private fun ByteArray.decodeStrictUtf8(path: String): String =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    } catch (error: CharacterCodingException) {
        throw AgentBundleException("$path JSON 格式无效", error)
    }

private fun parseObject(text: String, label: String): JsonObject =
    try {
        Json.parseToJsonElement(text) as? JsonObject
            ?: throw AgentBundleException("$label 必须是 JSON 对象")
    } catch (error: AgentBundleException) {
        throw error
    } catch (error: Throwable) {
        throw AgentBundleException("$label JSON 格式无效", error)
    }

private fun parseArray(text: String, label: String): JsonArray =
    try {
        Json.parseToJsonElement(text) as? JsonArray
            ?: throw AgentBundleException("$label 必须是 JSON 数组")
    } catch (error: AgentBundleException) {
        throw error
    } catch (error: Throwable) {
        throw AgentBundleException("$label JSON 格式无效", error)
    }

private fun JsonElement.asObject(label: String): JsonObject =
    this as? JsonObject ?: throw AgentBundleException("$label 必须是对象")

private fun JsonElement.asString(label: String): String {
    val primitive = this as? JsonPrimitive
    if (primitive == null || !primitive.isString) throw AgentBundleException("$label 必须是字符串")
    return primitive.content
}

private fun JsonObject.requiredObject(key: String, label: String): JsonObject =
    this[key] as? JsonObject ?: throw AgentBundleException("$label 缺少对象 $key")

private fun JsonObject.requiredArray(key: String, label: String): JsonArray =
    this[key] as? JsonArray ?: throw AgentBundleException("$label 缺少数组 $key")

private fun JsonObject.optionalArray(key: String, label: String): JsonArray =
    when (val value = this[key]) {
        null -> JsonArray(emptyList())
        is JsonArray -> value
        else -> throw AgentBundleException("$label 的 $key 必须是数组")
    }

private fun JsonObject.requiredString(key: String, label: String): String =
    optionalNullableString(key, label)?.trim()?.takeIf(String::isNotEmpty)
        ?: throw AgentBundleException("$label 缺少 $key")

private fun JsonObject.optionalString(key: String, label: String): String =
    optionalNullableString(key, label)?.trim().orEmpty()

private fun JsonObject.optionalNullableString(key: String, label: String): String? =
    when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else throw AgentBundleException("$label 的 $key 必须是字符串")
        else -> throw AgentBundleException("$label 的 $key 必须是字符串")
    }

private fun JsonObject.requiredIdentifier(key: String, label: String): String =
    identifier(requiredString(key, label))

private fun JsonObject.requiredHash(key: String, label: String): String =
    hash(requiredString(key, label))

private fun JsonObject.requiredPositiveInt(key: String, label: String): Int =
    integerValue(key, label).takeIf { it > 0 }?.toInt()
        ?: throw AgentBundleException("$label 的 $key 必须大于 0")

private fun JsonObject.requiredNonNegativeInt(key: String, label: String): Int =
    integerValue(key, label).takeIf { it in 0..Int.MAX_VALUE }?.toInt()
        ?: throw AgentBundleException("$label 的 $key 必须是非负整数")

private fun JsonObject.requiredPositiveLong(key: String, label: String): Long =
    integerValue(key, label).takeIf { it > 0 }
        ?: throw AgentBundleException("$label 的 $key 必须大于 0")

private fun JsonObject.requiredNonNegativeLong(key: String, label: String): Long =
    integerValue(key, label).takeIf { it >= 0 }
        ?: throw AgentBundleException("$label 的 $key 必须是非负整数")

private fun JsonObject.integerValue(key: String, label: String): Long {
    val primitive = this[key] as? JsonPrimitive
        ?: throw AgentBundleException("$label 缺少整数 $key")
    if (primitive.isString || primitive.booleanOrNull != null || primitive.content.contains('.')) {
        throw AgentBundleException("$label 的 $key 必须是整数")
    }
    return primitive.longOrNull ?: throw AgentBundleException("$label 的 $key 必须是整数")
}

private fun JsonObject.optionalBoolean(key: String, label: String, default: Boolean): Boolean =
    when (val value = this[key]) {
        null -> default
        is JsonPrimitive -> value.booleanOrNull
            ?: throw AgentBundleException("$label 的 $key 必须是布尔值")
        else -> throw AgentBundleException("$label 的 $key 必须是布尔值")
    }

private fun JsonObject.optionalDouble(key: String, label: String, default: Double): Double =
    when (val value = this[key]) {
        null -> default
        is JsonPrimitive -> value.doubleOrNull?.takeIf(Double::isFinite)
            ?: throw AgentBundleException("$label 的 $key 必须是有限数值")
        else -> throw AgentBundleException("$label 的 $key 必须是有限数值")
    }

private fun JsonObject.stringList(key: String, label: String): List<String> =
    optionalArray(key, label).mapIndexed { index, element ->
        element.asString("$label 的 $key[$index]").trim().takeIf(String::isNotEmpty)
            ?: throw AgentBundleException("$label 的 $key[$index] 不能为空")
    }

private fun JsonObject.requiredPackageType(key: String, label: String): V2PackageType {
    val value = requiredString(key, label)
    return V2PackageType.entries.singleOrNull { it.wireName == value }
        ?: throw AgentBundleException("$label 的 type 无效：$value")
}

private fun JsonObject.requiredInstallClass(key: String, label: String): V2InstallClass {
    val value = requiredString(key, label)
    return V2InstallClass.entries.singleOrNull { it.wireName == value }
        ?: throw AgentBundleException("$label 的 installClass 无效：$value")
}

private fun JsonObject.requiredSourceGenre(key: String, label: String): V2SourceGenre =
    sourceGenre(requiredString(key, label))

private fun JsonObject.requiredAuthorship(key: String, label: String): V2Authorship =
    authorship(requiredString(key, label))

private fun <T> rejectDuplicates(values: List<T>, label: String) {
    val seen = mutableSetOf<T>()
    values.forEach { value ->
        if (!seen.add(value)) throw AgentBundleException("$label 重复：$value")
    }
}

private fun isAllowedV1File(path: String): Boolean {
    if (path in setOf("bundle-manifest.json", "checksums.json", "signature.json")) return true
    val suffix = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when {
        path.startsWith("agent/") -> suffix in setOf("json", "jsonl", "md", "txt")
        path.startsWith("corpora/") -> suffix in setOf("json", "jsonl", "md", "txt")
        path.startsWith("sources/") -> suffix in setOf("json", "jsonl", "md", "txt", "pdf", "epub")
        else -> false
    }
}

private fun InputStream.readBytesLimited(maxBytes: Int, name: String): ByteArray {
    val output = ByteArrayOutputStream()
    copyBoundedTo(output, maxBytes.toLong(), name)
    return output.toByteArray()
}

private fun InputStream.copyBoundedTo(output: OutputStream, maxBytes: Long, name: String): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw AgentBundleException("$name 超过读取上限")
        output.write(buffer, 0, read)
    }
    return total
}

private inline fun InputStream.forEachUtf8JsonLine(
    path: String,
    block: (String, Int) -> Unit,
) {
    val line = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var lineNumber = 1
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        for (index in 0 until count) {
            val byte = buffer[index]
            if (byte == '\n'.code.toByte()) {
                val text = line.toByteArray()
                    .decodeStrictUtf8("$path 第 $lineNumber 行")
                    .trimEnd('\r')
                if (text.isNotBlank()) block(text, lineNumber)
                line.reset()
                lineNumber += 1
            } else {
                if (line.size() >= 4 * 1024 * 1024) {
                    throw AgentBundleException("$path 第 $lineNumber 行超过大小上限")
                }
                line.write(byte.toInt())
            }
        }
    }
    if (line.size() > 0) {
        val text = line.toByteArray()
            .decodeStrictUtf8("$path 第 $lineNumber 行")
            .trimEnd('\r')
        if (text.isNotBlank()) block(text, lineNumber)
    }
}

private suspend fun InputStream.forEachUtf8JsonLineSuspending(
    path: String,
    block: suspend (String, Int) -> Unit,
) {
    val line = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var lineNumber = 1
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        for (index in 0 until count) {
            val byte = buffer[index]
            if (byte == '\n'.code.toByte()) {
                val text = line.toByteArray()
                    .decodeStrictUtf8("$path 第 $lineNumber 行")
                    .trimEnd('\r')
                if (text.isNotBlank()) block(text, lineNumber)
                line.reset()
                lineNumber += 1
            } else {
                if (line.size() >= 4 * 1024 * 1024) {
                    throw AgentBundleException("$path 第 $lineNumber 行超过大小上限")
                }
                line.write(byte.toInt())
            }
        }
    }
    if (line.size() > 0) {
        val text = line.toByteArray()
            .decodeStrictUtf8("$path 第 $lineNumber 行")
            .trimEnd('\r')
        if (text.isNotBlank()) block(text, lineNumber)
    }
}

private fun InputStream.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().toHex()
}

private fun InputStream.sha256Limited(maxBytes: Long, name: String): HashedBytes {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw AgentBundleException("运行时资产超过大小上限：$name")
        digest.update(buffer, 0, read)
    }
    return HashedBytes(digest.digest().toHex(), total)
}

private data class HashedBytes(
    val sha256: String,
    val bytes: Long,
)

private fun File.sha256(): String = inputStream().buffered().use(InputStream::sha256)

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun ByteArray.findSignatureBackwards(signature: Int): Int {
    for (offset in size - 4 downTo 0) {
        if (readIntLe(offset) == signature) return offset
    }
    return -1
}

private fun ByteArray.readUnsignedShortLe(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.readIntLe(offset: Int): Int =
    readUnsignedShortLe(offset) or (readUnsignedShortLe(offset + 2) shl 16)

private fun ByteArray.readUnsignedIntLe(offset: Int): Long = readIntLe(offset).toLong() and 0xffffffffL

private fun RandomAccessFile.readUnsignedIntLeAt(offset: Long): Long {
    seek(offset)
    val bytes = ByteArray(4)
    readFully(bytes)
    return bytes.readUnsignedIntLe(0)
}
