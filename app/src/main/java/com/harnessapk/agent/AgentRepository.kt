package com.harnessapk.agent

import com.harnessapk.common.SystemTimeProvider
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AgentChunkEntity
import com.harnessapk.storage.AgentChunkFtsEntity
import com.harnessapk.storage.AgentCorpusChunkCrossRef
import com.harnessapk.storage.AgentCorpusHierarchyCrossRef
import com.harnessapk.storage.AgentCorpusSourceCrossRef
import com.harnessapk.storage.AgentCorpusEntity
import com.harnessapk.storage.AgentDao
import com.harnessapk.storage.AgentEntity
import com.harnessapk.storage.AgentHierarchyFtsEntity
import com.harnessapk.storage.AgentHierarchyNodeEntity
import com.harnessapk.storage.AgentVersionCorpusCrossRef
import com.harnessapk.storage.AgentVersionEntity
import com.harnessapk.storage.AgentVersionPackageEntity
import com.harnessapk.storage.AgentSourceFileEntity
import com.harnessapk.storage.AgentVersionSourceCrossRef
import com.harnessapk.storage.ConversationDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun interface AgentTransactionRunner {
    suspend fun run(block: suspend () -> Unit)
}

class AgentRepository(
    private val filesDir: File,
    private val cacheDir: File,
    private val dao: AgentDao,
    private val conversationDao: ConversationDao? = null,
    private val reader: AgentBundleAccess = AgentBundleReader(),
    private val transactionRunner: AgentTransactionRunner = AgentTransactionRunner { block -> block() },
    private val timeProvider: TimeProvider = SystemTimeProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val packageSessions = ConcurrentHashMap<String, OwnedPackageSession>()
    private val storageJson = Json { encodeDefaults = true }

    fun observeAgents(): Flow<List<Agent>> = dao.observeAgents().map { rows -> rows.map(AgentEntity::toDomain) }

    suspend fun agent(id: String): Agent? = withContext(ioDispatcher) { dao.findAgent(id)?.toDomain() }

    suspend fun prepareImport(
        sourceName: String,
        openInputStream: () -> InputStream,
    ): AgentImportSession {
        val session = preparePackageImport(sourceName, openInputStream)
        val bundle = (session.parsedPackage as? V1Bundle)?.bundle
            ?: run {
                discardPackageImport(session)
                throw AgentBundleException("V2 人格包必须通过统一安装 API 导入")
            }
        return AgentImportSession(session.id, session.stagedFile, bundle, session.preview)
    }

    suspend fun preparePackageImport(
        sourceName: String,
        openInputStream: () -> InputStream,
    ): AgentPackageImportSession = withContext(ioDispatcher) {
        val stagingDirectory = File(cacheDir, "agent-staging").apply { mkdirs() }
        val now = timeProvider.nowMillis()
        purgeExpiredOwnedSessions(now)
        purgeStaleImportFiles(stagingDirectory, now)
        val stagedFile = File(stagingDirectory, "${UUID.randomUUID()}.package")
        try {
            openInputStream().use { input -> copyBundleWithLimit(input, stagedFile) }
            val parsed = reader.readPackage(stagedFile)
            val id = UUID.randomUUID().toString()
            val session = AgentPackageImportSession(
                id = id,
                sourceName = sourceName,
                stagedFile = stagedFile,
                parsedPackage = parsed,
                publisherFingerprint = parsed.publisherFingerprint,
                packageSha256 = stagedFile.sha256Hex(),
                packageBytes = stagedFile.length(),
                preview = if (parsed is V1Bundle) reader.inspect(stagedFile) else parsed.toPreview(),
            )
            packageSessions[id] = OwnedPackageSession(session, now)
            session
        } catch (error: Throwable) {
            stagedFile.delete()
            throw if (error is AgentBundleException) error else AgentBundleException("无法导入 $sourceName", error)
        }
    }

    suspend fun install(session: AgentImportSession): AgentInstallResult {
        packageSessions[session.id]?.let { return installPackage(it.session) }
        return installV1(session)
    }

    suspend fun installPackage(
        session: AgentPackageImportSession,
        profileId: String = "balanced",
    ): AgentInstallResult = withContext(ioDispatcher) {
        val owned = packageSessions.remove(session.id)
            ?: throw AgentBundleException("导入会话已经失效或已使用")
        try {
            validateOwnedSession(session, owned)
            when (val parsed = owned.session.parsedPackage) {
                is V1Bundle -> installV1(
                    AgentImportSession(session.id, session.stagedFile, parsed.bundle, session.preview),
                )
                is V2Bundle -> installV2Bundle(parsed, session.stagedFile, profileId)
                is V2Agent -> installV2Agent(parsed, session.stagedFile)
                is V2Corpus -> installStandaloneV2Corpus(parsed, session.stagedFile, session.sourceName)
                is V2Source -> installStandaloneV2Source(parsed, session.stagedFile, session.sourceName)
            }
        } finally {
            session.stagedFile.delete()
        }
    }

    private suspend fun installV1(session: AgentImportSession): AgentInstallResult = withContext(ioDispatcher) {
        val bundle = session.parsedBundle
        require(session.stagedFile == bundle.file && session.stagedFile.isFile) { "导入会话已经失效" }
        val existingVersion = dao.findVersion(bundle.agent.id, bundle.agent.version)
        if (existingVersion != null) {
            if (existingVersion.bundleSha256 != bundle.packageSha256) {
                throw AgentBundleException("同一版本的智能体内容不同，请发布新版本")
            }
            session.stagedFile.delete()
            val existingAgent = requireNotNull(dao.findAgent(bundle.agent.id))
            return@withContext AgentInstallResult(AgentInstallOutcome.ALREADY_INSTALLED, existingAgent.toDomain())
        }
        val existingAgent = dao.findAgent(bundle.agent.id)
        if (existingAgent != null && existingAgent.publisherFingerprint != bundle.publisherFingerprint) {
            throw AgentBundleException("发布者指纹发生变化，拒绝覆盖现有智能体")
        }

        val versionDirectory = File(filesDir, "agents/${safeFileSegment(bundle.agent.id)}/${bundle.agent.version}")
        val finalBundle = File(versionDirectory, "bundle.hbundle")
        versionDirectory.mkdirs()
        moveAtomically(session.stagedFile, finalBundle)
        val installedBundle = bundle.copy(file = finalBundle)
        try {
            val now = timeProvider.nowMillis()
            var resultAgent: AgentEntity? = null
            transactionRunner.run {
                val availableCorpora = linkedMapOf<String, AgentCorpusEntity>()
                bundle.corpora.forEach { corpus ->
                    val existingCorpus = dao.findCorpus(corpus.id, corpus.sourceHash)
                    val storedCorpus = existingCorpus ?: installCorpus(installedBundle, corpus, now)
                    availableCorpora[corpus.id] = storedCorpus
                }
                bundle.agent.requiredCorpora.filterNot(availableCorpora::containsKey).forEach { corpusId ->
                    dao.findCorpusById(corpusId)?.let { availableCorpora[corpusId] = it }
                }
                val installedRequired = bundle.agent.requiredCorpora.count(availableCorpora::containsKey)
                val status = if (installedRequired == bundle.agent.requiredCorpora.size) {
                    AgentStatus.READY
                } else {
                    AgentStatus.WAITING_FOR_CORPUS
                }
                val agentEntity = AgentEntity(
                    id = bundle.agent.id,
                    name = bundle.agent.name,
                    summary = bundle.agent.summary,
                    activeVersion = bundle.agent.version,
                    publisherPublicKey = bundle.publisherPublicKey,
                    publisherFingerprint = bundle.publisherFingerprint,
                    installSource = "LOCAL_FILE",
                    status = status.name,
                    requiredCorpusCount = bundle.agent.requiredCorpora.size,
                    installedCorpusCount = installedRequired,
                    createdAt = existingAgent?.createdAt ?: now,
                    updatedAt = now,
                )
                dao.upsertAgent(agentEntity)
                dao.insertVersion(
                    AgentVersionEntity(
                        agentId = bundle.agent.id,
                        version = bundle.agent.version,
                        schemaVersion = 1,
                        bundlePath = finalBundle.absolutePath,
                        bundleSha256 = bundle.packageSha256,
                        manifestJson = bundle.manifestJson,
                        persona = bundle.persona,
                        worldviewJsonl = bundle.worldviewJsonl,
                        installedAt = now,
                        state = status.name,
                        requiredCorpusCount = bundle.agent.requiredCorpora.size,
                    ),
                )
                availableCorpora.values.forEach { corpus ->
                    dao.insertVersionCorpus(
                        AgentVersionCorpusCrossRef(
                            agentId = bundle.agent.id,
                            version = bundle.agent.version,
                            corpusId = corpus.corpusId,
                            sourceHash = corpus.sourceHash,
                            required = corpus.corpusId in bundle.agent.requiredCorpora,
                        ),
                    )
                }
                resultAgent = agentEntity
            }
            AgentInstallResult(AgentInstallOutcome.INSTALLED, requireNotNull(resultAgent).toDomain())
        } catch (error: Throwable) {
            finalBundle.delete()
            versionDirectory.delete()
            throw error
        }
    }

    fun discardImport(session: AgentImportSession) {
        packageSessions.remove(session.id)
        session.stagedFile.delete()
    }

    fun discardPackageImport(session: AgentPackageImportSession) {
        packageSessions.remove(session.id)
        session.stagedFile.delete()
    }

    private suspend fun installV2Bundle(
        bundle: V2Bundle,
        stagedFile: File,
        profileId: String,
    ): AgentInstallResult {
        if (bundle.profile.id != profileId) {
            throw AgentBundleException("bundle profile 与安装选择不一致：$profileId")
        }
        return installV2AgentContent(bundle.agent, bundle.corpora, bundle.sources, stagedFile, "bundle.hbundle")
    }

    private suspend fun installV2Agent(agent: V2Agent, stagedFile: File): AgentInstallResult =
        installV2AgentContent(agent, emptyList(), emptyList(), stagedFile, "agent.hagent")

    private suspend fun installV2AgentContent(
        agent: V2Agent,
        corpora: List<V2Corpus>,
        sources: List<V2Source>,
        stagedFile: File,
        installedName: String,
    ): AgentInstallResult {
        val existingAgent = dao.findAgent(agent.manifest.id)
        if (existingAgent != null && existingAgent.publisherFingerprint != agent.publisherFingerprint) {
            throw AgentBundleException("发布者指纹发生变化，拒绝覆盖现有智能体")
        }
        val existingVersion = dao.findVersion(agent.manifest.id, agent.manifest.version)
        if (existingVersion != null && existingVersion.bundleSha256 != agent.packageSha256) {
            throw AgentBundleException("同一版本的智能体内容不同，请发布新版本")
        }
        val versionDirectory = File(
            filesDir,
            "agents/${safeFileSegment(agent.manifest.id)}/${agent.manifest.version}",
        )
        val finalPackage = if (existingVersion == null) File(versionDirectory, installedName) else null
        if (finalPackage != null) {
            versionDirectory.mkdirs()
            moveAtomically(stagedFile, finalPackage)
        }
        val readablePackage = finalPackage ?: stagedFile
        val preparedSources = mutableListOf<PreparedSourceFile>()
        try {
            sources.forEach { source -> preparedSources += prepareV2SourceFile(readablePackage, source) }
            val now = timeProvider.nowMillis()
            var result: AgentEntity? = null
            transactionRunner.run {
                if (existingVersion == null) {
                    val initial = AgentEntity(
                        id = agent.manifest.id,
                        name = agent.manifest.name,
                        summary = "",
                        activeVersion = agent.manifest.version,
                        publisherPublicKey = agent.publisherPublicKey,
                        publisherFingerprint = agent.publisherFingerprint,
                        installSource = "LOCAL_FILE",
                        status = AgentStatus.WAITING_FOR_CORPUS.name,
                        requiredCorpusCount = agent.manifest.requiredCorpora.size,
                        installedCorpusCount = 0,
                        createdAt = existingAgent?.createdAt ?: now,
                        updatedAt = now,
                    )
                    dao.upsertAgent(initial)
                    dao.insertVersion(
                        AgentVersionEntity(
                            agentId = agent.manifest.id,
                            version = agent.manifest.version,
                            schemaVersion = 2,
                            bundlePath = requireNotNull(finalPackage).absolutePath,
                            bundleSha256 = agent.packageSha256,
                            manifestJson = agent.manifestJson,
                            persona = agent.persona,
                            worldviewJsonl = agent.worldview.joinToString("\n") { it.toStorageJson().toString() },
                            installedAt = now,
                            state = AgentStatus.WAITING_FOR_CORPUS.name,
                            identityJson = agent.identity.toStorageJson().toString(),
                            voiceJson = agent.voice.toStorageJson().toString(),
                            episodesJsonl = agent.episodes.joinToString("\n") { it.toStorageJson().toString() },
                            conceptsJson = JsonArray(agent.concepts.map(V2Concept::toStorageJson)).toString(),
                            examplesJsonl = agent.examples.joinToString("\n") { it.toStorageJson().toString() },
                            openersJson = agent.openers.toStorageJson().toString(),
                            evalJsonl = agent.evaluations.joinToString("\n") { it.toStorageJson().toString() },
                            installPlanJson = agent.installPlanJson,
                            requiredCorpusCount = agent.manifest.requiredCorpora.size,
                            requiredEvidenceJson = storageJson.encodeToString(agent.requiredEvidenceIds().sorted()),
                            evaluationCorpusIdsJson = storageJson.encodeToString(
                                agent.evaluations.map(V2Evaluation::corpusId).distinct().sorted(),
                            ),
                        ),
                    )
                    dao.upsertVersionPackages(
                        agent.installPlan.packages.map { declaration ->
                            AgentVersionPackageEntity(
                                agentId = agent.manifest.id,
                                version = agent.manifest.version,
                                packageId = declaration.id,
                                type = declaration.type.wireName,
                                fileName = declaration.fileName,
                                installClass = declaration.installClass.wireName,
                                packageSha256 = declaration.sha256,
                                packageSizeBytes = declaration.sizeBytes,
                                installed = false,
                                filePath = "",
                                installedAt = null,
                            )
                        },
                    )
                }
                corpora.forEach { corpus ->
                    installV2Corpus(readablePackage, corpus, now)
                    dao.markVersionPackageInstalled(
                        agent.manifest.id,
                        agent.manifest.version,
                        corpus.manifest.id,
                        "",
                        now,
                    )
                }
                preparedSources.forEach { prepared ->
                    persistV2Source(prepared.source, prepared.file, now)
                    dao.markVersionPackageInstalled(
                        agent.manifest.id,
                        agent.manifest.version,
                        prepared.source.manifest.id,
                        prepared.file.absolutePath,
                        now,
                    )
                }
                result = refreshV2InstallState(agent, now)
            }
            val outcome = if (existingVersion == null || corpora.isNotEmpty()) {
                AgentInstallOutcome.INSTALLED
            } else {
                AgentInstallOutcome.ALREADY_INSTALLED
            }
            return AgentInstallResult(outcome, requireNotNull(result).toDomain())
        } catch (error: Throwable) {
            if (finalPackage != null) {
                finalPackage.delete()
                versionDirectory.delete()
            }
            preparedSources.filter(PreparedSourceFile::created).forEach { prepared ->
                prepared.file.delete()
                prepared.file.parentFile?.delete()
            }
            throw error
        }
    }

    private suspend fun prepareV2SourceFile(packageFile: File, source: V2Source): PreparedSourceFile {
        val directory = File(filesDir, "agents/sources/${source.manifest.sourceHash}").apply { mkdirs() }
        val destination = File(directory, source.manifest.storedName)
        if (destination.isFile) {
            if (
                destination.length() != source.manifest.rawSizeBytes ||
                destination.sha256Hex() != source.manifest.sourceHash
            ) {
                throw AgentBundleException("已安装 hsource 原始文件被修改")
            }
            return PreparedSourceFile(source, destination, false)
        }
        val part = File(directory, ".${source.manifest.storedName}.${UUID.randomUUID()}.part")
        try {
            part.outputStream().buffered().use { output ->
                reader.copyV2SourcePayload(packageFile, source.manifest.id, output)
            }
            if (
                part.length() != source.manifest.rawSizeBytes ||
                part.sha256Hex() != source.manifest.sourceHash
            ) {
                throw AgentBundleException("hsource 原始文件复制 hash/bytes 不匹配")
            }
            moveAtomically(part, destination)
            return PreparedSourceFile(source, destination, true)
        } catch (error: Throwable) {
            part.delete()
            directory.delete()
            throw error
        }
    }

    private suspend fun persistV2Source(source: V2Source, file: File, now: Long) {
        val existing = dao.findSource(source.manifest.sourceId, source.manifest.sourceHash)
        dao.upsertSource(
            AgentSourceFileEntity(
                sourceId = source.manifest.sourceId,
                sourceHash = source.manifest.sourceHash,
                title = existing?.title ?: source.manifest.fileName,
                fileName = source.manifest.fileName,
                storedName = source.manifest.storedName,
                format = existing?.format ?: source.manifest.fileName.substringAfterLast('.', "unknown"),
                genre = existing?.genre ?: V2SourceGenre.UNKNOWN.wireName,
                authorship = existing?.authorship ?: V2Authorship.UNKNOWN.wireName,
                period = existing?.period.orEmpty(),
                rawSizeBytes = source.manifest.rawSizeBytes,
                filePath = file.absolutePath,
                packageSha256 = source.packageSha256,
                installedAt = now,
            ),
        )
        dao.insertVersionSource(
            AgentVersionSourceCrossRef(
                source.manifest.agentId,
                source.manifest.version,
                source.manifest.sourceId,
                source.manifest.sourceHash,
            ),
        )
    }

    private suspend fun installStandaloneV2Corpus(
        corpus: V2Corpus,
        stagedFile: File,
        sourceName: String,
    ): AgentInstallResult {
        val agent = dao.findAgent(corpus.manifest.agentId)
            ?: throw AgentBundleException("hcorpus 对应的人格版本尚未安装")
        val version = dao.findVersion(corpus.manifest.agentId, corpus.manifest.version)
            ?: throw AgentBundleException("hcorpus 对应的人格版本尚未安装")
        if (agent.publisherFingerprint != corpus.publisherFingerprint) {
            throw AgentBundleException("hcorpus 发布者指纹不匹配")
        }
        val declaration = dao.findVersionPackage(
            corpus.manifest.agentId,
            corpus.manifest.version,
            corpus.manifest.id,
        ) ?: throw AgentBundleException("hcorpus 未在 install plan 中声明")
        if (
            declaration.type != V2PackageType.CORPUS.wireName ||
            declaration.installClass != corpus.manifest.installClass.wireName ||
            declaration.packageSha256 != corpus.packageSha256 ||
            declaration.packageSizeBytes != corpus.compressedSizeBytes ||
            sourceName.packageBaseName() != declaration.fileName
        ) {
            throw AgentBundleException("hcorpus 与 install plan 的文件/hash/bytes 不匹配")
        }
        if (declaration.installed) {
            if (declaration.filePath.isNotBlank()) {
                val installed = File(declaration.filePath)
                if (
                    !installed.isFile || installed.length() != declaration.packageSizeBytes ||
                    installed.sha256Hex() != declaration.packageSha256
                ) {
                    throw AgentBundleException("已安装 hcorpus 文件缺失或被修改")
                }
            }
            return AgentInstallResult(AgentInstallOutcome.ALREADY_INSTALLED, agent.toDomain())
        }
        val packageDirectory = File(filesDir, "agents/packages").apply { mkdirs() }
        val finalPackage = File(packageDirectory, "${corpus.packageSha256}.hcorpus")
        val createdFile = !finalPackage.exists()
        if (createdFile) moveAtomically(stagedFile, finalPackage)
        try {
            val now = timeProvider.nowMillis()
            var updated: AgentEntity? = null
            transactionRunner.run {
                installV2Corpus(finalPackage, corpus.copy(file = finalPackage), now)
                dao.markVersionPackageInstalled(
                    corpus.manifest.agentId,
                    corpus.manifest.version,
                    corpus.manifest.id,
                    finalPackage.absolutePath,
                    now,
                )
                val installedAgent = V2Agent(
                    file = File(version.bundlePath),
                    packageSha256 = version.bundleSha256,
                    publisherPublicKey = agent.publisherPublicKey,
                    publisherFingerprint = agent.publisherFingerprint,
                    manifestJson = version.manifestJson,
                    compressedSizeBytes = File(version.bundlePath).length(),
                    uncompressedSizeBytes = File(version.bundlePath).length(),
                    manifest = V2AgentManifest(
                        agent.id,
                        agent.name,
                        version.version,
                        dao.listVersionPackages(agent.id, version.version)
                            .filter { it.type == V2PackageType.CORPUS.wireName && it.installClass == V2InstallClass.REQUIRED.wireName }
                            .map(AgentVersionPackageEntity::packageId),
                        version.requiredCorpusCount == 0,
                    ),
                    persona = version.persona,
                    identity = V2Identity(emptyList(), "", emptyList(), emptyList()),
                    voice = V2Voice("", emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
                    worldview = emptyList(),
                    episodes = emptyList(),
                    concepts = emptyList(),
                    examples = emptyList(),
                    openers = V2Openers("", emptyList()),
                    evaluations = emptyList(),
                    installPlanJson = version.installPlanJson,
                    installPlan = V2InstallPlan(emptyList(), emptyList(), "", emptyList()),
                    isRunnable = false,
                )
                updated = refreshV2InstallState(
                    installedAgent,
                    now,
                    evidenceIds = version.storedRequiredEvidenceIds(),
                    evaluationCorpusIds = version.storedEvaluationCorpusIds(),
                )
            }
            return AgentInstallResult(AgentInstallOutcome.INSTALLED, requireNotNull(updated).toDomain())
        } catch (error: Throwable) {
            if (createdFile) finalPackage.delete()
            throw error
        }
    }

    private suspend fun installStandaloneV2Source(
        source: V2Source,
        stagedFile: File,
        sourceName: String,
    ): AgentInstallResult {
        val agent = dao.findAgent(source.manifest.agentId)
            ?: throw AgentBundleException("hsource 对应的人格版本尚未安装")
        dao.findVersion(source.manifest.agentId, source.manifest.version)
            ?: throw AgentBundleException("hsource 对应的人格版本尚未安装")
        if (agent.publisherFingerprint != source.publisherFingerprint) {
            throw AgentBundleException("hsource 发布者指纹不匹配")
        }
        val declaration = dao.findVersionPackage(
            source.manifest.agentId,
            source.manifest.version,
            source.manifest.id,
        ) ?: throw AgentBundleException("hsource 未在 install plan 中声明")
        if (
            declaration.type != V2PackageType.SOURCE.wireName ||
            declaration.installClass != V2InstallClass.SOURCE.wireName ||
            declaration.packageSha256 != source.packageSha256 ||
            declaration.packageSizeBytes != source.compressedSizeBytes ||
            sourceName.packageBaseName() != declaration.fileName
        ) {
            throw AgentBundleException("hsource 与 install plan 的文件/hash/bytes 不匹配")
        }
        if (declaration.installed) {
            val stored = dao.findSource(source.manifest.sourceId, source.manifest.sourceHash)
            val installed = stored?.filePath?.takeIf(String::isNotBlank)?.let(::File)
            if (
                installed == null || !installed.isFile || installed.length() != source.manifest.rawSizeBytes ||
                installed.sha256Hex() != source.manifest.sourceHash
            ) {
                throw AgentBundleException("已安装 hsource 原始文件缺失或被修改")
            }
            return AgentInstallResult(AgentInstallOutcome.ALREADY_INSTALLED, agent.toDomain())
        }
        val sourceDirectory = File(filesDir, "agents/sources/${source.manifest.sourceHash}").apply { mkdirs() }
        val finalSource = File(sourceDirectory, source.manifest.storedName)
        val createdFile = !finalSource.exists()
        if (createdFile) {
            val part = File(sourceDirectory, ".${source.manifest.storedName}.${UUID.randomUUID()}.part")
            try {
                part.outputStream().buffered().use { output ->
                    reader.copyV2SourcePayload(stagedFile, source.manifest.id, output)
                }
                if (part.length() != source.manifest.rawSizeBytes) {
                    throw AgentBundleException("hsource 原始文件复制大小不匹配")
                }
                if (part.sha256Hex() != source.manifest.sourceHash) {
                    throw AgentBundleException("hsource 原始文件复制 hash 不匹配")
                }
                moveAtomically(part, finalSource)
            } catch (error: Throwable) {
                part.delete()
                sourceDirectory.delete()
                throw error
            }
        } else if (
            finalSource.length() != source.manifest.rawSizeBytes ||
            finalSource.sha256Hex() != source.manifest.sourceHash
        ) {
            throw AgentBundleException("已安装 hsource 原始文件被修改")
        }
        try {
            val now = timeProvider.nowMillis()
            transactionRunner.run {
                val existing = dao.findSource(source.manifest.sourceId, source.manifest.sourceHash)
                dao.upsertSource(
                    AgentSourceFileEntity(
                        sourceId = source.manifest.sourceId,
                        sourceHash = source.manifest.sourceHash,
                        title = existing?.title ?: source.manifest.fileName,
                        fileName = source.manifest.fileName,
                        storedName = source.manifest.storedName,
                        format = existing?.format ?: source.manifest.fileName.substringAfterLast('.', "unknown"),
                        genre = existing?.genre ?: V2SourceGenre.UNKNOWN.wireName,
                        authorship = existing?.authorship ?: V2Authorship.UNKNOWN.wireName,
                        period = existing?.period.orEmpty(),
                        rawSizeBytes = source.manifest.rawSizeBytes,
                        filePath = finalSource.absolutePath,
                        packageSha256 = source.packageSha256,
                        installedAt = now,
                    ),
                )
                dao.insertVersionSource(
                    AgentVersionSourceCrossRef(
                        source.manifest.agentId,
                        source.manifest.version,
                        source.manifest.sourceId,
                        source.manifest.sourceHash,
                    ),
                )
                dao.markVersionPackageInstalled(
                    source.manifest.agentId,
                    source.manifest.version,
                    source.manifest.id,
                    finalSource.absolutePath,
                    now,
                )
            }
            return AgentInstallResult(AgentInstallOutcome.INSTALLED, requireNotNull(dao.findAgent(agent.id)).toDomain())
        } catch (error: Throwable) {
            if (createdFile) {
                finalSource.delete()
                sourceDirectory.delete()
            }
            throw error
        }
    }

    private suspend fun installV2Corpus(packageFile: File, corpus: V2Corpus, now: Long) {
        val corpusHash = corpus.packageSha256
        if (dao.findCorpus(corpus.manifest.id, corpusHash) == null) {
            dao.insertCorpus(
                AgentCorpusEntity(corpus.manifest.id, corpusHash, corpus.manifest.id, now, 0L),
            )
            var sizeBytes = 0L
            val chunkBatch = ArrayList<AgentChunkEntity>(CHUNK_BATCH_SIZE)
            val searchBatch = ArrayList<AgentChunkFtsEntity>(CHUNK_BATCH_SIZE)
            suspend fun flushChunks() {
                if (chunkBatch.isEmpty()) return
                val candidates = chunkBatch.toList()
                val results = dao.insertChunks(candidates)
                val ignored = candidates.zip(results).filter { it.second == -1L }.map { it.first }
                if (ignored.isNotEmpty()) {
                    val existing = dao.listChunks(ignored.map(AgentChunkEntity::chunkKey))
                        .associateBy(AgentChunkEntity::chunkKey)
                    ignored.forEach { candidate ->
                        if (existing[candidate.chunkKey]?.hasSameImmutableEvidence(candidate) != true) {
                            throw AgentBundleException("physical chunk immutable evidence 冲突：${candidate.chunkKey}")
                        }
                    }
                }
                dao.insertCorpusChunkRefs(
                    candidates.map { AgentCorpusChunkCrossRef(corpus.manifest.id, corpusHash, it.chunkKey) },
                )
                dao.insertChunkSearchRows(searchBatch.zip(results).filter { it.second != -1L }.map { it.first })
                chunkBatch.clear()
                searchBatch.clear()
            }
            reader.forEachV2ChunkSuspending(packageFile, corpus.manifest.id) { chunk ->
                val key = chunkKey(chunk.sourceHash, chunk.id)
                sizeBytes += chunk.text.encodeToByteArray().size
                chunkBatch += AgentChunkEntity(
                    chunkKey = key,
                    sourceId = chunk.sourceId,
                    sourceHash = chunk.sourceHash,
                    chunkId = chunk.id,
                    sourceTitle = chunk.sourceTitle,
                    period = chunk.period,
                    genre = chunk.genre.wireName,
                    authorship = chunk.authorship.wireName,
                    location = chunk.location,
                    parentPath = chunk.parentIds.joinToString("/"),
                    context = chunk.context,
                    text = chunk.text,
                    keywordsText = (chunk.keywords + chunk.ngrams).distinct().joinToString(" "),
                    duplicateGroup = chunk.duplicateGroup,
                )
                searchBatch += AgentChunkFtsEntity(key, (chunk.keywords + chunk.ngrams).distinct().joinToString(" "))
                if (chunkBatch.size >= CHUNK_BATCH_SIZE) flushChunks()
            }
            flushChunks()
            val sourcesById = corpus.sources.associateBy(V2SourceRecord::sourceId)
            corpus.sources.forEach { source ->
                val existing = dao.findSource(source.sourceId, source.sourceHash)
                dao.upsertSource(
                    AgentSourceFileEntity(
                        sourceId = source.sourceId,
                        sourceHash = source.sourceHash,
                        title = source.title,
                        fileName = source.fileName,
                        storedName = source.storedName,
                        format = source.format,
                        genre = source.genre.wireName,
                        authorship = source.authorship.wireName,
                        period = source.period,
                        rawSizeBytes = source.rawSizeBytes,
                        filePath = existing?.filePath.orEmpty(),
                        packageSha256 = existing?.packageSha256.orEmpty(),
                        installedAt = existing?.installedAt ?: now,
                    ),
                )
            }
            dao.insertCorpusSourceRefs(
                corpus.sources.map { source ->
                    AgentCorpusSourceCrossRef(
                        corpus.manifest.id,
                        corpusHash,
                        source.sourceId,
                        source.sourceHash,
                    )
                },
            )
            val nodeBatch = ArrayList<AgentHierarchyNodeEntity>(CHUNK_BATCH_SIZE)
            val nodeSearchBatch = ArrayList<AgentHierarchyFtsEntity>(CHUNK_BATCH_SIZE)
            suspend fun flushNodes() {
                if (nodeBatch.isEmpty()) return
                val candidates = nodeBatch.toList()
                val results = dao.insertHierarchyNodes(candidates)
                val ignored = candidates.zip(results).filter { it.second == -1L }.map { it.first }
                if (ignored.isNotEmpty()) {
                    val existing = dao.listHierarchyNodes(ignored.map(AgentHierarchyNodeEntity::nodeKey))
                        .associateBy(AgentHierarchyNodeEntity::nodeKey)
                    ignored.forEach { candidate ->
                        if (existing[candidate.nodeKey] != candidate) {
                            throw AgentBundleException("hierarchy immutable metadata 冲突：${candidate.nodeKey}")
                        }
                    }
                }
                dao.insertCorpusHierarchyRefs(
                    candidates.map { AgentCorpusHierarchyCrossRef(corpus.manifest.id, corpusHash, it.nodeKey) },
                )
                dao.insertHierarchySearchRows(nodeSearchBatch.zip(results).filter { it.second != -1L }.map { it.first })
                nodeBatch.clear()
                nodeSearchBatch.clear()
            }
            reader.forEachV2HierarchyNodeSuspending(packageFile, corpus.manifest.id) { node ->
                val source = requireNotNull(sourcesById[node.sourceId])
                val key = chunkKey(source.sourceHash, node.id)
                nodeBatch += AgentHierarchyNodeEntity(
                    nodeKey = key,
                    sourceId = node.sourceId,
                    sourceHash = source.sourceHash,
                    nodeId = node.id,
                    kind = node.kind,
                    title = node.title,
                    parentNodeKey = node.parentId?.let { chunkKey(source.sourceHash, it) },
                    path = node.path.joinToString("/"),
                    summary = node.summary,
                )
                nodeSearchBatch += AgentHierarchyFtsEntity(key, listOf(node.title, node.summary).joinToString(" "))
                if (nodeBatch.size >= CHUNK_BATCH_SIZE) flushNodes()
            }
            flushNodes()
            dao.updateCorpusSize(corpus.manifest.id, corpusHash, sizeBytes)
        }
        dao.insertVersionCorpus(
            AgentVersionCorpusCrossRef(
                agentId = corpus.manifest.agentId,
                version = corpus.manifest.version,
                corpusId = corpus.manifest.id,
                sourceHash = corpusHash,
                required = corpus.manifest.installClass == V2InstallClass.REQUIRED,
                installClass = corpus.manifest.installClass.wireName,
                packageSha256 = corpus.packageSha256,
                packageSizeBytes = corpus.compressedSizeBytes,
                installedAt = now,
            ),
        )
    }

    private suspend fun refreshV2InstallState(
        agent: V2Agent,
        now: Long,
        evidenceIds: Set<String> = agent.requiredEvidenceIds(),
        evaluationCorpusIds: Set<String> = agent.evaluations.map(V2Evaluation::corpusId).toSet(),
    ): AgentEntity {
        val corpora = dao.listVersionCorpora(agent.manifest.id, agent.manifest.version)
        val installedRequired = corpora.count { it.required }
        val requiredPackagesPresent = agent.manifest.requiredCorpora.all { required ->
            corpora.any { it.corpusId == required && it.required }
        }
        val evidencePresent = evidenceIds.all { evidenceId ->
            dao.countRequiredEvidenceChunk(agent.manifest.id, agent.manifest.version, evidenceId) > 0
        }
        val evaluationsUseRequired = evaluationCorpusIds.all { it in agent.manifest.requiredCorpora }
        val status = if (requiredPackagesPresent && evidencePresent && evaluationsUseRequired) {
            AgentStatus.READY
        } else {
            AgentStatus.WAITING_FOR_CORPUS
        }
        dao.updateVersionState(agent.manifest.id, agent.manifest.version, status.name, now)
        dao.updateAgentInstallState(
            agent.manifest.id,
            status.name,
            agent.manifest.requiredCorpora.size,
            installedRequired,
            now,
        )
        return requireNotNull(dao.findAgent(agent.manifest.id))
    }

    private fun AgentVersionEntity.storedRequiredEvidenceIds(): Set<String> = runCatching {
        storageJson.decodeFromString<List<String>>(requiredEvidenceJson).toSet()
    }.getOrElse { emptySet() }

    private fun AgentVersionEntity.storedEvaluationCorpusIds(): Set<String> = runCatching {
        storageJson.decodeFromString<List<String>>(evaluationCorpusIdsJson).toSet()
    }.getOrElse { emptySet() }

    private fun validateOwnedSession(
        supplied: AgentPackageImportSession,
        owned: OwnedPackageSession,
    ) {
        val expected = owned.session
        val expired = timeProvider.nowMillis() - owned.createdAt >= IMPORT_STAGING_TTL_MILLIS
        val mismatch = supplied !== expected || supplied.id != expected.id ||
            supplied.stagedFile != expected.stagedFile || supplied.parsedPackage !== expected.parsedPackage ||
            supplied.sourceName != expected.sourceName ||
            supplied.publisherFingerprint != expected.publisherFingerprint ||
            supplied.packageSha256 != expected.packageSha256 || supplied.packageBytes != expected.packageBytes
        val modified = !expected.stagedFile.isFile || expected.stagedFile.length() != expected.packageBytes ||
            runCatching { expected.stagedFile.sha256Hex() }.getOrNull() != expected.packageSha256
        if (expired || mismatch || modified) throw AgentBundleException("导入会话已经失效、被修改或不匹配")
    }

    private fun purgeExpiredOwnedSessions(now: Long) {
        packageSessions.entries.removeIf { (_, owned) ->
            val expired = now - owned.createdAt >= IMPORT_STAGING_TTL_MILLIS
            if (expired) owned.session.stagedFile.delete()
            expired
        }
    }

    suspend fun removeOptionalCorpus(
        agentId: String,
        version: Int,
        corpusId: String,
    ): AgentCorpusRemovalResult = withContext(ioDispatcher) {
        val reference = dao.findVersionCorpus(agentId, version, corpusId)
            ?: return@withContext AgentCorpusRemovalResult(AgentCorpusRemovalOutcome.NOT_INSTALLED)
        if (reference.required || reference.installClass == V2InstallClass.REQUIRED.wireName) {
            return@withContext AgentCorpusRemovalResult(AgentCorpusRemovalOutcome.REQUIRED)
        }
        val chunkKeys = dao.listCorpusChunkKeys(reference.corpusId, reference.sourceHash)
        if (
            dao.countLegacyAgentSourceParts() > 0 ||
            chunkKeys.any { dao.countAgentSourcePartsReferencingChunkKey(it) > 0 }
        ) {
            return@withContext AgentCorpusRemovalResult(AgentCorpusRemovalOutcome.REFERENCED)
        }
        val packagePath = dao.findVersionPackage(agentId, version, corpusId)?.filePath.orEmpty()
        transactionRunner.run {
            dao.deleteVersionCorpus(agentId, version, corpusId)
            dao.markVersionPackageRemoved(agentId, version, corpusId)
            if (dao.countVersionCorpusReferences(reference.corpusId, reference.sourceHash) == 0) {
                dao.deleteCorpus(reference.corpusId, reference.sourceHash)
                dao.deleteOrphanChunks()
                dao.deleteOrphanChunkSearchRows()
                dao.deleteOrphanHierarchyNodes()
                dao.deleteOrphanHierarchySearchRows()
                dao.deleteOrphanSources()
            }
            val currentAgent = dao.findAgent(agentId)
            if (currentAgent?.activeVersion == version) {
                val currentVersion = dao.findVersion(agentId, version)
                val installed = dao.listVersionCorpora(agentId, version)
                dao.updateAgentInstallState(
                    agentId = agentId,
                    status = if (currentAgent.status == AgentStatus.DISABLED.name) {
                        AgentStatus.DISABLED.name
                    } else {
                        currentVersion?.state ?: AgentStatus.WAITING_FOR_CORPUS.name
                    },
                    requiredCount = currentVersion?.requiredCorpusCount ?: currentAgent.requiredCorpusCount,
                    installedCount = installed.count { it.required },
                    updatedAt = timeProvider.nowMillis(),
                )
            }
        }
        if (packagePath.isNotBlank() && dao.countInstalledPackagePathReferences(packagePath) == 0) {
            File(packagePath).delete()
        }
        AgentCorpusRemovalResult(AgentCorpusRemovalOutcome.REMOVED)
    }

    suspend fun setAgentEnabled(agentId: String, enabled: Boolean) = withContext(ioDispatcher) {
        val agent = requireNotNull(dao.findAgent(agentId)) { "智能体不存在" }
        val status = if (enabled) {
            dao.findVersion(agentId, agent.activeVersion)?.state ?: AgentStatus.WAITING_FOR_CORPUS.name
        } else {
            AgentStatus.DISABLED.name
        }
        check(dao.updateAgentStatus(agentId, status, timeProvider.nowMillis()) == 1) { "智能体状态更新失败" }
    }

    suspend fun versionCoverage(agentId: String, version: Int): AgentVersionCoverage =
        withContext(ioDispatcher) {
            val storedVersion = requireNotNull(dao.findVersion(agentId, version)) { "智能体版本不存在" }
            val corpora = dao.listVersionCorpora(agentId, version)
            AgentVersionCoverage(
                agentId = agentId,
                version = version,
                requiredCorpusCount = storedVersion.requiredCorpusCount,
                installedRequiredCorpusCount = corpora.count { it.required },
                installedCorpusCount = corpora.size,
            )
        }

    suspend fun removeVersion(agentId: String, version: Int): AgentVersionRemovalResult =
        withContext(ioDispatcher) {
            val stored = dao.findVersion(agentId, version)
                ?: return@withContext AgentVersionRemovalResult(AgentVersionRemovalOutcome.NOT_FOUND)
            val references = requireNotNull(conversationDao) {
                "删除智能体版本需要 ConversationDao"
            }.countByAgentVersion(agentId, version)
            if (references > 0) {
                return@withContext AgentVersionRemovalResult(AgentVersionRemovalOutcome.REFERENCED)
            }
            val agent = requireNotNull(dao.findAgent(agentId))
            val versions = dao.listVersions(agentId)
            if (agent.activeVersion == version && versions.size > 1) {
                return@withContext AgentVersionRemovalResult(AgentVersionRemovalOutcome.ACTIVE)
            }
            val packagePaths = dao.listVersionPackages(agentId, version)
                .map(AgentVersionPackageEntity::filePath)
                .filter(String::isNotBlank)
            transactionRunner.run {
                if (versions.size == 1) {
                    dao.deleteAgent(agentId)
                } else {
                    dao.deleteVersion(agentId, version)
                }
                dao.deleteOrphanCorpora()
                dao.deleteOrphanChunks()
                dao.deleteOrphanChunkSearchRows()
                dao.deleteOrphanHierarchyNodes()
                dao.deleteOrphanHierarchySearchRows()
                dao.deleteOrphanSources()
            }
            File(stored.bundlePath).delete()
            File(stored.bundlePath).parentFile?.delete()
            packagePaths.forEach { path ->
                if (dao.countInstalledPackagePathReferences(path) == 0) File(path).delete()
            }
            AgentVersionRemovalResult(AgentVersionRemovalOutcome.REMOVED)
        }

    suspend fun runtimeContext(
        agentId: String,
        version: Int,
        query: String,
        limit: Int = DEFAULT_EVIDENCE_LIMIT,
    ): AgentRuntimeContext? = withContext(ioDispatcher) {
        val storedVersion = dao.findVersion(agentId, version) ?: return@withContext null
        val corpusKeys = dao.listVersionCorpora(agentId, version).map {
            corpusKey(it.corpusId, it.sourceHash)
        }
        val evidence = if (corpusKeys.isEmpty()) {
            emptyList()
        } else {
            val ftsQuery = buildAgentFtsQuery(query)
            if (ftsQuery.isBlank()) {
                emptyList()
            } else {
                val candidateKeys = dao.searchChunkKeys(
                    corpusKeys = corpusKeys,
                    ftsQuery = ftsQuery,
                    limit = (limit * CANDIDATE_MULTIPLIER).coerceAtLeast(limit),
                )
                val chunks = dao.listChunks(candidateKeys).associateBy(AgentChunkEntity::chunkKey)
                val queryTerms = agentQueryTerms(query)
                candidateKeys.mapNotNull(chunks::get).map { chunk ->
                    AgentEvidence(
                        chunkId = chunk.chunkId,
                        sourceTitle = chunk.sourceTitle,
                        location = chunk.location,
                        text = chunk.text,
                        score = scoreChunk(chunk, query, queryTerms),
                        chunkKey = chunk.chunkKey,
                    )
                }.sortedWith(compareByDescending<AgentEvidence> { it.score }.thenBy { it.chunkId }).take(limit)
            }
        }
        AgentRuntimeContext(
            agentId = agentId,
            version = version,
            systemPrompt = buildAgentSystemPrompt(storedVersion, evidence),
            evidence = evidence,
        )
    }

    private suspend fun installCorpus(
        bundle: ParsedAgentBundle,
        corpus: AgentCorpusManifest,
        now: Long,
    ): AgentCorpusEntity {
        var sizeBytes = 0L
        val chunkBatch = ArrayList<AgentChunkEntity>(CHUNK_BATCH_SIZE)
        val searchBatch = ArrayList<AgentChunkFtsEntity>(CHUNK_BATCH_SIZE)
        suspend fun flush() {
            if (chunkBatch.isEmpty()) return
            val chunks = chunkBatch.toList()
            val insertResults = dao.insertChunks(chunks)
            val ignoredChunks = chunks.zip(insertResults)
                .filter { (_, rowId) -> rowId == -1L }
                .map { (chunk, _) -> chunk }
            if (ignoredChunks.isNotEmpty()) {
                val existingByKey = dao.listChunks(ignoredChunks.map(AgentChunkEntity::chunkKey))
                    .associateBy(AgentChunkEntity::chunkKey)
                ignoredChunks.forEach { candidate ->
                    val existing = existingByKey[candidate.chunkKey]
                    if (existing == null || !existing.hasSameImmutableEvidence(candidate)) {
                        throw AgentBundleException(
                            "physical chunk immutable evidence 冲突：${candidate.chunkKey}",
                        )
                    }
                }
            }
            dao.insertCorpusChunkRefs(
                chunks.map { chunk ->
                    AgentCorpusChunkCrossRef(
                        corpusId = corpus.id,
                        corpusHash = corpus.sourceHash,
                        chunkKey = chunk.chunkKey,
                    )
                },
            )
            dao.insertChunkSearchRows(
                searchBatch.zip(insertResults)
                    .filter { (_, rowId) -> rowId != -1L }
                    .map { (searchRow, _) -> searchRow },
            )
            chunkBatch.clear()
            searchBatch.clear()
        }
        val corpusEntity = AgentCorpusEntity(
            corpusId = corpus.id,
            sourceHash = corpus.sourceHash,
            title = corpus.title,
            indexedAt = now,
            sizeBytes = 0L,
        )
        dao.insertCorpus(corpusEntity)
        reader.forEachChunkSuspending(bundle, corpus) { chunk ->
            val key = chunkKey(chunk.sourceHash, chunk.id)
            val searchable = (chunk.keywords + chunk.ngrams).distinct().joinToString(" ")
            sizeBytes += chunk.text.encodeToByteArray().size
            chunkBatch += AgentChunkEntity(
                chunkKey = key,
                sourceId = chunk.sourceHash,
                sourceHash = chunk.sourceHash,
                chunkId = chunk.id,
                sourceTitle = chunk.sourceTitle,
                location = chunk.location,
                text = chunk.text,
                keywordsText = chunk.keywords.joinToString(" "),
            )
            searchBatch += AgentChunkFtsEntity(
                chunkKey = key,
                searchableText = searchable,
            )
            if (chunkBatch.size >= CHUNK_BATCH_SIZE) flush()
        }
        flush()
        dao.updateCorpusSize(corpus.id, corpus.sourceHash, sizeBytes)
        return corpusEntity.copy(sizeBytes = sizeBytes)
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: AtomicMoveNotSupportedException) {
            val part = File(destination.parentFile, "${destination.name}.part")
            try {
                source.inputStream().buffered().use { input ->
                    part.outputStream().buffered().use(input::copyTo)
                }
                if (!part.renameTo(destination)) throw AgentBundleException("无法原子安装智能体包")
                source.delete()
            } catch (failure: Throwable) {
                part.delete()
                throw failure
            }
        }
    }

    companion object {
        private const val DEFAULT_EVIDENCE_LIMIT = 8
        private const val CANDIDATE_MULTIPLIER = 4
        private const val CHUNK_BATCH_SIZE = 200
    }
}

internal fun buildAgentFtsQuery(query: String): String = agentQueryTerms(query)
    .take(MAX_QUERY_TERM_COUNT)
    .joinToString(" OR ") { term -> "\"${term.replace("\"", "")}\"" }

private fun agentQueryTerms(query: String): List<String> {
    val latin = Regex("[A-Za-z0-9_]{2,}").findAll(query.lowercase())
        .map(MatchResult::value)
        .filterNot { it in FTS_OPERATORS }
        .toList()
    val chinese = Regex("[\\u3400-\\u9fff]+").findAll(query).flatMap { match ->
        val value = match.value
        when {
            value.length == 1 -> sequenceOf(value)
            else -> (0 until value.length - 1).asSequence().map { index -> value.substring(index, index + 2) }
        }
    }.toList()
    return (latin + chinese).distinct()
}

private fun scoreChunk(chunk: AgentChunkEntity, rawQuery: String, terms: List<String>): Int {
    val keywords = chunk.keywordsText.split(' ').filter(String::isNotBlank).toSet()
    val keywordScore = terms.count(keywords::contains) * 4
    val textScore = terms.count(chunk.text::contains) * 2
    val phraseScore = if (rawQuery.isNotBlank() && chunk.text.contains(rawQuery.trim())) 8 else 0
    return keywordScore + textScore + phraseScore
}

private fun copyBundleWithLimit(input: InputStream, target: File) {
    target.outputStream().buffered().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_IMPORT_BUNDLE_BYTES) throw AgentBundleException("智能体包超过 2 GiB 上限")
            output.write(buffer, 0, read)
        }
    }
}

internal fun purgeStaleImportFiles(directory: File, nowMillis: Long) {
    directory.listFiles()?.filter { file ->
        file.isFile && nowMillis - file.lastModified() >= IMPORT_STAGING_TTL_MILLIS
    }?.forEach(File::delete)
}

private fun safeFileSegment(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]"), "-").trim('.', '-').ifBlank { "agent" }

private fun String.packageBaseName(): String = substringAfterLast('/').substringAfterLast('\\')

private fun corpusKey(corpusId: String, sourceHash: String): String = "$corpusId:$sourceHash"

private fun chunkKey(sourceHash: String, chunkId: String): String = "$sourceHash:$chunkId"

private fun AgentChunkEntity.hasSameImmutableEvidence(other: AgentChunkEntity): Boolean =
    chunkKey == other.chunkKey &&
        sourceId == other.sourceId &&
        sourceHash == other.sourceHash &&
        chunkId == other.chunkId &&
        sourceTitle == other.sourceTitle &&
        period == other.period &&
        genre == other.genre &&
        authorship == other.authorship &&
        location == other.location &&
        parentPath == other.parentPath &&
        context == other.context &&
        text == other.text &&
        keywordsText == other.keywordsText &&
        duplicateGroup == other.duplicateGroup

private fun AgentEntity.toDomain(): Agent = Agent(
    id = id,
    name = name,
    summary = summary,
    activeVersion = activeVersion,
    publisherFingerprint = publisherFingerprint,
    status = AgentStatus.valueOf(status),
    requiredCorpusCount = requiredCorpusCount,
    installedCorpusCount = installedCorpusCount,
)

private data class OwnedPackageSession(
    val session: AgentPackageImportSession,
    val createdAt: Long,
)

private data class PreparedSourceFile(
    val source: V2Source,
    val file: File,
    val created: Boolean,
)

private fun ParsedAgentPackage.toPreview(): AgentImportPreview = when (this) {
    is V1Bundle -> AgentImportPreview(
        agentId = bundle.agent.id,
        name = bundle.agent.name,
        version = bundle.agent.version,
        summary = bundle.agent.summary,
        publisherFingerprint = publisherFingerprint,
        corpora = bundle.corpora.map(AgentCorpusManifest::title),
        compressedSizeBytes = compressedSizeBytes,
        includesOriginalSources = false,
    )
    is V2Bundle -> AgentImportPreview(
        agentId = agent.manifest.id,
        name = agent.manifest.name,
        version = agent.manifest.version,
        summary = "",
        publisherFingerprint = publisherFingerprint,
        corpora = corpora.map { it.manifest.id },
        compressedSizeBytes = compressedSizeBytes,
        includesOriginalSources = sources.isNotEmpty(),
    )
    is V2Agent -> AgentImportPreview(
        agentId = manifest.id,
        name = manifest.name,
        version = manifest.version,
        summary = "",
        publisherFingerprint = publisherFingerprint,
        corpora = emptyList(),
        compressedSizeBytes = compressedSizeBytes,
        includesOriginalSources = false,
    )
    is V2Corpus -> AgentImportPreview(
        agentId = manifest.agentId,
        name = manifest.id,
        version = manifest.version,
        summary = "",
        publisherFingerprint = publisherFingerprint,
        corpora = listOf(manifest.id),
        compressedSizeBytes = compressedSizeBytes,
        includesOriginalSources = false,
    )
    is V2Source -> AgentImportPreview(
        agentId = manifest.agentId,
        name = manifest.fileName,
        version = manifest.version,
        summary = "",
        publisherFingerprint = publisherFingerprint,
        corpora = emptyList(),
        compressedSizeBytes = compressedSizeBytes,
        includesOriginalSources = true,
    )
}

private fun V2Agent.requiredEvidenceIds(): Set<String> = buildSet {
    identity.relationships.forEach { addAll(it.evidence) }
    addAll(voice.evidence)
    worldview.forEach { addAll(it.evidence) }
    episodes.forEach { addAll(it.evidence) }
    concepts.forEach { addAll(it.evidence) }
    examples.forEach { addAll(it.evidence) }
    evaluations.forEach { addAll(it.expectedEvidence) }
}

private fun List<String>.toJsonStrings(): JsonArray = JsonArray(map(::JsonPrimitive))

private fun V2Identity.toStorageJson(): JsonObject = buildJsonObject {
    put("selfNames", selfNames.toJsonStrings())
    put("timeHorizon", timeHorizon)
    put("roles", roles.toJsonStrings())
    put(
        "relationships",
        JsonArray(
            relationships.map { relationship ->
                buildJsonObject {
                    put("subject", relationship.subject)
                    put("relation", relationship.relation)
                    put("period", relationship.period)
                    put("evidence", relationship.evidence.toJsonStrings())
                }
            },
        ),
    )
}

private fun V2Voice.toStorageJson(): JsonObject = buildJsonObject {
    put("defaultForm", defaultForm)
    put("sentenceRhythm", sentenceRhythm.toJsonStrings())
    put("rhetoricalMoves", rhetoricalMoves.toJsonStrings())
    put("preferredTerms", preferredTerms.toJsonStrings())
    put("avoidPatterns", avoidPatterns.toJsonStrings())
    put("evidence", evidence.toJsonStrings())
}

private fun V2Worldview.toStorageJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("topic", topic)
    put("statement", statement)
    put("conditions", conditions.toJsonStrings())
    put("period", period)
    put("aliases", aliases.toJsonStrings())
    put("confidence", confidence)
    put("evidence", evidence.toJsonStrings())
}

private fun V2Episode.toStorageJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("period", period)
    put("location", location)
    put("participants", participants.toJsonStrings())
    put("summary", summary)
    put("meaning", meaning)
    put("evidence", evidence.toJsonStrings())
}

private fun V2Concept.toStorageJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("aliases", aliases.toJsonStrings())
    put("keywords", keywords.toJsonStrings())
    put("evidence", evidence.toJsonStrings())
}

private fun V2Example.toStorageJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("intent", intent)
    put("user", user)
    put("assistant", assistant)
    put("styleTags", styleTags.toJsonStrings())
    put("generationType", generationType)
    put("evidence", evidence.toJsonStrings())
}

private fun V2Openers.toStorageJson(): JsonObject = buildJsonObject {
    put("default", default)
    put("alternatives", alternatives.toJsonStrings())
}

private fun V2Evaluation.toStorageJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("category", category)
    put("question", question)
    put("period", period)
    put("expectedEvidence", expectedEvidence.toJsonStrings())
    put("corpusId", corpusId)
}

private fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private const val MAX_QUERY_TERM_COUNT = 24
private const val MAX_IMPORT_BUNDLE_BYTES = 2L * 1024 * 1024 * 1024
private const val IMPORT_STAGING_TTL_MILLIS = 24L * 60 * 60 * 1000
private val FTS_OPERATORS = setOf("and", "or", "not", "near")
