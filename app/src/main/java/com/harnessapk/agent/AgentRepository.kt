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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
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
    private val lifecycleCoordinator: AgentLifecycleCoordinator = AgentLifecycleCoordinator(),
    private val fileOps: AgentFileOps = DefaultAgentFileOps(),
    private val timeProvider: TimeProvider = SystemTimeProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AgentContextDataSource {
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
        val compatibilitySession = AgentImportSession(session.id, session.stagedFile, bundle, session.preview)
        requireNotNull(packageSessions[session.id]).v1CompatibilitySession = compatibilitySession
        return compatibilitySession
    }

    suspend fun preparePackageImport(
        sourceName: String,
        openInputStream: () -> InputStream,
    ): AgentPackageImportSession = withContext(ioDispatcher) {
        val stagingDirectory = File(cacheDir, "agent-staging").also(fileOps::createDirectories)
        val now = timeProvider.nowMillis()
        recoverFileLifecycle()
        purgeExpiredOwnedSessions(now)
        purgeStaleImportFilesChecked(stagingDirectory, now)
        val stagedFile = File(stagingDirectory, "${UUID.randomUUID()}.package")
        try {
            openInputStream().use { input -> fileOps.copyBounded(input, stagedFile, MAX_IMPORT_BUNDLE_BYTES) }
            val parsed = reader.readPackageSuspending(stagedFile)
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
            deleteOrRecordOrphan(stagedFile)
            throw when (error) {
                is CancellationException -> error
                is AgentBundleException -> error
                else -> AgentBundleException("无法导入 $sourceName", error)
            }
        }
    }

    suspend fun install(session: AgentImportSession): AgentInstallResult = withContext(ioDispatcher) {
        lifecycleCoordinator.serialized {
            val owned = consumeOwnedV1SessionUnlocked(session)
            installConsumedPackageUnlocked(owned, profileId = "balanced")
        }
    }

    suspend fun installPackage(
        session: AgentPackageImportSession,
        profileId: String = "balanced",
    ): AgentInstallResult = withContext(ioDispatcher) {
        lifecycleCoordinator.serialized {
            val owned = consumeOwnedPackageSessionUnlocked(session)
            installConsumedPackageUnlocked(owned, profileId)
        }
    }

    private suspend fun installV1Unlocked(session: AgentImportSession): AgentInstallResult {
        val bundle = session.parsedBundle
        require(session.stagedFile == bundle.file && session.stagedFile.isFile) { "导入会话已经失效" }
        val existingVersion = dao.findVersion(bundle.agent.id, bundle.agent.version)
        if (existingVersion != null) {
            if (existingVersion.bundleSha256 != bundle.packageSha256) {
                throw AgentBundleException("同一版本的智能体内容不同，请发布新版本")
            }
            deleteOrRecordOrphan(session.stagedFile)
            val existingAgent = requireNotNull(dao.findAgent(bundle.agent.id))
            return AgentInstallResult(AgentInstallOutcome.ALREADY_INSTALLED, existingAgent.toDomain())
        }
        val existingAgent = dao.findAgent(bundle.agent.id)
        if (existingAgent != null && existingAgent.publisherFingerprint != bundle.publisherFingerprint) {
            throw AgentBundleException("发布者指纹发生变化，拒绝覆盖现有智能体")
        }

        val versionDirectory = File(filesDir, "agents/${safeFileSegment(bundle.agent.id)}/${bundle.agent.version}")
        val finalBundle = File(versionDirectory, "bundle.hbundle")
        fileOps.createDirectories(versionDirectory)
        fileOps.moveAtomically(session.stagedFile, finalBundle)
        val installedBundle = bundle.copy(file = finalBundle)
        return try {
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
                    status = if (existingAgent?.status == AgentStatus.DISABLED.name) {
                        AgentStatus.DISABLED.name
                    } else {
                        status.name
                    },
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
            retireUnreferencedFile(finalBundle)
            deleteEmptyDirectory(versionDirectory)
            throw error
        }
    }

    suspend fun discardImport(session: AgentImportSession) = withContext(ioDispatcher) {
        lifecycleCoordinator.serialized {
            val owned = packageSessions[session.id]
                ?: throw AgentBundleException("导入会话已经失效或已使用")
            if (session !== owned.v1CompatibilitySession) {
                throw AgentBundleException("V1 导入会话并非 repository 实际签发对象")
            }
            discardOwnedSessionUnlocked(owned)
        }
    }

    suspend fun discardPackageImport(session: AgentPackageImportSession) = withContext(ioDispatcher) {
        lifecycleCoordinator.serialized {
            val owned = packageSessions[session.id]
                ?: throw AgentBundleException("导入会话已经失效或已使用")
            if (session !== owned.session) {
                throw AgentBundleException("导入会话并非 repository 实际签发对象")
            }
            discardOwnedSessionUnlocked(owned)
        }
    }

    private suspend fun consumeOwnedV1SessionUnlocked(session: AgentImportSession): OwnedPackageSession {
        val owned = packageSessions.remove(session.id)
            ?: throw AgentBundleException("导入会话已经失效或已使用")
        val expected = owned.v1CompatibilitySession
        if (session !== expected) {
            deleteOrRecordOrphan(owned.session.stagedFile)
            throw AgentBundleException("V1 导入会话并非 repository 实际签发对象")
        }
        return owned
    }

    private suspend fun consumeOwnedPackageSessionUnlocked(
        session: AgentPackageImportSession,
    ): OwnedPackageSession {
        val owned = packageSessions.remove(session.id)
            ?: throw AgentBundleException("导入会话已经失效或已使用")
        if (session !== owned.session) {
            deleteOrRecordOrphan(owned.session.stagedFile)
            throw AgentBundleException("导入会话并非 repository 实际签发对象")
        }
        return owned
    }

    private fun discardOwnedSessionUnlocked(owned: OwnedPackageSession) {
        if (packageSessions[owned.session.id] !== owned) {
            throw AgentBundleException("导入会话已经失效或已使用")
        }
        deleteOrRecordOrphan(owned.session.stagedFile)
        if (!packageSessions.remove(owned.session.id, owned)) {
            throw AgentBundleException("导入会话已经失效或已使用")
        }
    }

    private suspend fun installConsumedPackageUnlocked(
        owned: OwnedPackageSession,
        profileId: String,
    ): AgentInstallResult {
        var snapshot: File? = null
        try {
            recoverFileLifecycleLocked()
            validateOwnedSessionForConsumption(owned)
            val consumed = snapshotOwnedPackageUnlocked(owned)
            snapshot = consumed.file
            return when (val parsed = consumed.parsed) {
                is V1Bundle -> installV1Unlocked(
                    AgentImportSession(owned.session.id, consumed.file, parsed.bundle, consumed.preview),
                )
                is V2Bundle -> installV2Bundle(parsed, consumed.file, profileId)
                is V2Agent -> installV2Agent(parsed, consumed.file)
                is V2Corpus -> installStandaloneV2Corpus(parsed, consumed.file, owned.session.sourceName)
                is V2Source -> installStandaloneV2Source(parsed, consumed.file, owned.session.sourceName)
            }
        } finally {
            snapshot?.let(::deleteOrRecordOrphan)
            deleteEmptyDirectory(File(cacheDir, INSTALL_SNAPSHOT_DIRECTORY))
        }
    }

    private suspend fun snapshotOwnedPackageUnlocked(owned: OwnedPackageSession): ConsumedPackageSnapshot {
        val snapshotDirectory = File(cacheDir, INSTALL_SNAPSHOT_DIRECTORY).also(fileOps::createDirectories)
        val snapshot = File(snapshotDirectory, "${UUID.randomUUID()}.package")
        val stagedFile = owned.session.stagedFile
        val sourceFileKey = stagedFile.fileKeyOrNull()
        try {
            stagedFile.inputStream().use { input ->
                fileOps.copyBounded(input, snapshot, MAX_IMPORT_BUNDLE_BYTES)
            }
            val packageBytes = snapshot.length()
            val packageSha256 = snapshot.sha256Hex()
            val parsed = reader.readPackageSuspending(snapshot)
            val preview = if (parsed is V1Bundle) reader.inspect(snapshot) else parsed.toPreview()
            if (
                packageBytes != owned.session.packageBytes ||
                packageSha256 != owned.session.packageSha256 ||
                parsed::class != owned.session.parsedPackage::class ||
                parsed.publisherFingerprint != owned.session.publisherFingerprint ||
                preview != owned.session.preview
            ) {
                throw AgentBundleException("导入会话快照与签发内容不匹配")
            }
            if (stagedFile.fileKeyOrNull() == sourceFileKey &&
                (!stagedFile.isFile ||
                    stagedFile.length() != owned.session.packageBytes ||
                    stagedFile.sha256Hex() != owned.session.packageSha256)
            ) {
                throw AgentBundleException("导入会话在创建私有快照期间被原地修改")
            }
            return ConsumedPackageSnapshot(snapshot, parsed, preview)
        } catch (error: Throwable) {
            deleteOrRecordOrphan(snapshot)
            throw when (error) {
                is CancellationException, is AgentBundleException -> error
                else -> AgentBundleException("无法验证导入会话快照", error)
            }
        } finally {
            deleteOrRecordOrphan(owned.session.stagedFile)
        }
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
            fileOps.createDirectories(versionDirectory)
            fileOps.moveAtomically(stagedFile, finalPackage)
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
                        status = if (existingAgent?.status == AgentStatus.DISABLED.name) {
                            AgentStatus.DISABLED.name
                        } else {
                            AgentStatus.WAITING_FOR_CORPUS.name
                        },
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
                retireUnreferencedFile(finalPackage)
                deleteEmptyDirectory(versionDirectory)
            }
            preparedSources.filter(PreparedSourceFile::created).forEach { prepared ->
                retireUnreferencedFile(prepared.file)
                prepared.file.parentFile?.let(::deleteEmptyDirectory)
            }
            throw error
        }
    }

    private suspend fun prepareV2SourceFile(packageFile: File, source: V2Source): PreparedSourceFile {
        val directory = File(filesDir, "agents/sources/${source.manifest.sourceHash}").also(fileOps::createDirectories)
        val destination = File(directory, SOURCE_PAYLOAD_NAME)
        if (destination.isFile) {
            if (
                destination.length() != source.manifest.rawSizeBytes ||
                destination.sha256Hex() != source.manifest.sourceHash
            ) {
                throw AgentBundleException("已安装 hsource 原始文件被修改")
            }
            return PreparedSourceFile(source, destination, false)
        }
        val part = File(directory, ".$SOURCE_PAYLOAD_NAME.${UUID.randomUUID()}.part")
        try {
            fileOps.write(part) { output ->
                reader.copyV2SourcePayload(packageFile, source.manifest.id, output)
            }
            if (
                part.length() != source.manifest.rawSizeBytes ||
                part.sha256Hex() != source.manifest.sourceHash
            ) {
                throw AgentBundleException("hsource 原始文件复制 hash/bytes 不匹配")
            }
            fileOps.moveAtomically(part, destination)
            return PreparedSourceFile(source, destination, true)
        } catch (error: Throwable) {
            deleteOrRecordOrphan(part)
            deleteEmptyDirectory(directory)
            throw error
        }
    }

    private suspend fun persistV2Source(source: V2Source, file: File, now: Long) {
        val existing = dao.findSource(source.manifest.sourceId, source.manifest.sourceHash)
        existing?.requireSameDescriptor(source.manifest)
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
        val packageDirectory = File(filesDir, "agents/packages").also(fileOps::createDirectories)
        val finalPackage = File(packageDirectory, "${corpus.packageSha256}.hcorpus")
        val createdFile = !finalPackage.exists()
        val installCorpus = if (createdFile) {
            fileOps.moveAtomically(stagedFile, finalPackage)
            corpus.copy(file = finalPackage)
        } else {
            if (
                !finalPackage.isFile ||
                finalPackage.length() != declaration.packageSizeBytes ||
                finalPackage.sha256Hex() != declaration.packageSha256
            ) {
                throw AgentBundleException("已存在 hcorpus 与 install plan 的 hash/bytes 不一致")
            }
            val existingPackage = reader.readPackageSuspending(finalPackage) as? V2Corpus
                ?: throw AgentBundleException("已存在目标文件不是 hcorpus")
            if (!existingPackage.matchesStandaloneCorpus(corpus, declaration)) {
                throw AgentBundleException("已存在 hcorpus 与当前 session package/manifest 不一致")
            }
            existingPackage
        }
        try {
            val now = timeProvider.nowMillis()
            var updated: AgentEntity? = null
            transactionRunner.run {
                installV2Corpus(finalPackage, installCorpus, now)
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
            if (createdFile) retireUnreferencedFile(finalPackage)
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
            stored?.requireSameDescriptor(source.manifest)
            val installed = stored?.filePath?.takeIf(String::isNotBlank)?.let(::File)
            if (
                installed == null || !installed.isFile || installed.length() != source.manifest.rawSizeBytes ||
                installed.sha256Hex() != source.manifest.sourceHash
            ) {
                throw AgentBundleException("已安装 hsource 原始文件缺失或被修改")
            }
            return AgentInstallResult(AgentInstallOutcome.ALREADY_INSTALLED, agent.toDomain())
        }
        val sourceDirectory = File(filesDir, "agents/sources/${source.manifest.sourceHash}").also(fileOps::createDirectories)
        val finalSource = File(sourceDirectory, SOURCE_PAYLOAD_NAME)
        val createdFile = !finalSource.exists()
        if (createdFile) {
            val part = File(sourceDirectory, ".$SOURCE_PAYLOAD_NAME.${UUID.randomUUID()}.part")
            try {
                fileOps.write(part) { output ->
                    reader.copyV2SourcePayload(stagedFile, source.manifest.id, output)
                }
                if (part.length() != source.manifest.rawSizeBytes) {
                    throw AgentBundleException("hsource 原始文件复制大小不匹配")
                }
                if (part.sha256Hex() != source.manifest.sourceHash) {
                    throw AgentBundleException("hsource 原始文件复制 hash 不匹配")
                }
                fileOps.moveAtomically(part, finalSource)
            } catch (error: Throwable) {
                deleteOrRecordOrphan(part)
                deleteEmptyDirectory(sourceDirectory)
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
                existing?.requireSameDescriptor(source.manifest)
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
                retireUnreferencedFile(finalSource)
                deleteEmptyDirectory(sourceDirectory)
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
                    conflictKey = chunk.conflictKey,
                    sourceAliasesJson = storageJson.encodeToString(chunk.sourceAliases.sorted()),
                    simHash = chunk.simHash,
                )
                searchBatch += AgentChunkFtsEntity(key, (chunk.keywords + chunk.ngrams).distinct().joinToString(" "))
                if (chunkBatch.size >= CHUNK_BATCH_SIZE) flushChunks()
            }
            flushChunks()
            val sourcesById = corpus.sources.associateBy(V2SourceRecord::sourceId)
            corpus.sources.forEach { source ->
                val existing = dao.findSource(source.sourceId, source.sourceHash)
                existing?.requireSameDescriptor(source)
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
        val currentAgent = requireNotNull(dao.findAgent(agent.manifest.id))
        if (currentAgent.activeVersion == agent.manifest.version) {
            dao.updateAgentInstallState(
                agent.manifest.id,
                if (currentAgent.status == AgentStatus.DISABLED.name) AgentStatus.DISABLED.name else status.name,
                agent.manifest.requiredCorpora.size,
                installedRequired,
                now,
            )
        }
        return requireNotNull(dao.findAgent(agent.manifest.id))
    }

    private fun AgentVersionEntity.storedRequiredEvidenceIds(): Set<String> = runCatching {
        storageJson.decodeFromString<List<String>>(requiredEvidenceJson).toSet()
    }.getOrElse { emptySet() }

    private fun AgentVersionEntity.storedEvaluationCorpusIds(): Set<String> = runCatching {
        storageJson.decodeFromString<List<String>>(evaluationCorpusIdsJson).toSet()
    }.getOrElse { emptySet() }

    private fun validateOwnedSessionForConsumption(owned: OwnedPackageSession) {
        val expected = owned.session
        val expired = timeProvider.nowMillis() - owned.createdAt >= IMPORT_STAGING_TTL_MILLIS
        if (expired || !expected.stagedFile.isFile) {
            throw AgentBundleException("导入会话已经失效、被修改或不匹配")
        }
    }

    private fun purgeExpiredOwnedSessions(now: Long) {
        packageSessions.entries.toList().forEach { (id, owned) ->
            if (now - owned.createdAt >= IMPORT_STAGING_TTL_MILLIS && packageSessions.remove(id, owned)) {
                deleteOrRecordOrphan(owned.session.stagedFile)
            }
        }
    }

    private fun purgeStaleImportFilesChecked(directory: File, now: Long) {
        directory.listFiles().orEmpty().filter { file ->
            file.isFile && now - file.lastModified() >= IMPORT_STAGING_TTL_MILLIS
        }.forEach(::deleteOrRecordOrphan)
    }

    suspend fun removeOptionalCorpus(
        agentId: String,
        version: Int,
        corpusId: String,
    ): AgentCorpusRemovalResult = withContext(ioDispatcher) {
        lifecycleCoordinator.serialized {
        recoverFileLifecycleLocked()
        val reference = dao.findVersionCorpus(agentId, version, corpusId)
            ?: return@serialized AgentCorpusRemovalResult(AgentCorpusRemovalOutcome.NOT_INSTALLED)
        if (reference.required || reference.installClass == V2InstallClass.REQUIRED.wireName) {
            return@serialized AgentCorpusRemovalResult(AgentCorpusRemovalOutcome.REQUIRED)
        }
        val chunkKeys = dao.listCorpusChunkKeys(reference.corpusId, reference.sourceHash)
        if (
            dao.countLegacyAgentSourceParts(agentId, version) > 0 ||
            chunkKeys.any { dao.countAgentSourcePartsReferencingChunkKey(it) > 0 }
        ) {
            return@serialized AgentCorpusRemovalResult(AgentCorpusRemovalOutcome.REFERENCED)
        }
        val packagePath = dao.findVersionPackage(agentId, version, corpusId)?.filePath.orEmpty()
        val removedSources = if (dao.countVersionCorpusReferences(reference.corpusId, reference.sourceHash) <= 1) {
            dao.listCorpusSources(reference.corpusId, reference.sourceHash)
        } else {
            emptyList()
        }
        val removablePaths = buildList {
            if (
                packagePath.isNotBlank() &&
                dao.countInstalledPackagePathReferences(packagePath) <= 1
            ) {
                add(packagePath)
            }
            addAll(sourcePayloadPathsToRetire(removedSources, listOf(packagePath)))
        }.distinct()
        val tombstones = stageFilesForDeletion(removablePaths)
        try {
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
        } catch (error: Throwable) {
            withContext(NonCancellable) { restoreTombstones(tombstones) }
            throw error
        }
        val cleanupPending = !finalizeTombstones(tombstones)
        AgentCorpusRemovalResult(
            if (cleanupPending) {
                AgentCorpusRemovalOutcome.REMOVED_CLEANUP_PENDING
            } else {
                AgentCorpusRemovalOutcome.REMOVED
            },
        )
        }
    }

    suspend fun setAgentEnabled(agentId: String, enabled: Boolean) = withContext(ioDispatcher) {
        lifecycleCoordinator.serialized {
        val agent = requireNotNull(dao.findAgent(agentId)) { "智能体不存在" }
        val status = if (enabled) {
            dao.findVersion(agentId, agent.activeVersion)?.state ?: AgentStatus.WAITING_FOR_CORPUS.name
        } else {
            AgentStatus.DISABLED.name
        }
        check(dao.updateAgentStatus(agentId, status, timeProvider.nowMillis()) == 1) { "智能体状态更新失败" }
        }
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
            lifecycleCoordinator.serialized {
            recoverFileLifecycleLocked()
            val stored = dao.findVersion(agentId, version)
                ?: return@serialized AgentVersionRemovalResult(AgentVersionRemovalOutcome.NOT_FOUND)
            val references = requireNotNull(conversationDao) {
                "删除智能体版本需要 ConversationDao"
            }.countByAgentVersion(agentId, version)
            if (references > 0) {
                return@serialized AgentVersionRemovalResult(AgentVersionRemovalOutcome.REFERENCED)
            }
            val agent = requireNotNull(dao.findAgent(agentId))
            val versions = dao.listVersions(agentId)
            if (agent.activeVersion == version && versions.size > 1) {
                return@serialized AgentVersionRemovalResult(AgentVersionRemovalOutcome.ACTIVE)
            }
            val packagePaths = dao.listVersionPackages(agentId, version)
                .map(AgentVersionPackageEntity::filePath)
                .filter(String::isNotBlank)
            val versionCorpora = dao.listVersionCorpora(agentId, version)
            val orphanedCorpora = versionCorpora.filter {
                dao.countVersionCorpusReferences(it.corpusId, it.sourceHash) <= 1
            }
            val versionSources = dao.listVersionSources(agentId, version)
            val removedSources = buildList {
                addAll(versionSources)
                orphanedCorpora.forEach { corpus ->
                    addAll(dao.listCorpusSources(corpus.corpusId, corpus.sourceHash))
                }
            }
            val removablePaths = buildList {
                add(stored.bundlePath)
                packagePaths.distinct().forEach { path ->
                    val targetReferences = dao.listVersionPackages(agentId, version)
                        .count { it.installed && it.filePath == path }
                    if (dao.countInstalledPackagePathReferences(path) <= targetReferences) add(path)
                }
                addAll(sourcePayloadPathsToRetire(removedSources, packagePaths))
            }.filter(String::isNotBlank).distinct()
            val tombstones = stageFilesForDeletion(removablePaths)
            try {
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
            } catch (error: Throwable) {
                withContext(NonCancellable) { restoreTombstones(tombstones) }
                throw error
            }
            val cleanupPending = !finalizeTombstones(tombstones)
            File(stored.bundlePath).parentFile?.let(::deleteEmptyDirectory)
            AgentVersionRemovalResult(
                if (cleanupPending) {
                    AgentVersionRemovalOutcome.REMOVED_CLEANUP_PENDING
                } else {
                    AgentVersionRemovalOutcome.REMOVED
                },
            )
            }
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
                val queryTerms = agentQueryTerms(query)
                scanTopChunkMatches(corpusKeys, ftsQuery, query, limit).map { chunk ->
                    AgentEvidence(
                        chunkId = chunk.chunkId,
                        sourceTitle = chunk.sourceTitle,
                        location = chunk.location,
                        text = chunk.text,
                        score = scoreChunk(chunk, query, queryTerms),
                        chunkKey = chunk.chunkKey,
                    )
                }
            }
        }
        AgentRuntimeContext(
            agentId = agentId,
            version = version,
            systemPrompt = buildAgentSystemPrompt(storedVersion, evidence),
            evidence = evidence,
        )
    }

    suspend fun opening(agentId: String, version: Int): String? = withContext(ioDispatcher) {
        val storedVersion = dao.findVersion(agentId, version) ?: return@withContext null
        if (storedVersion.schemaVersion < 2) return@withContext null
        parseContextObject(storedVersion.openersJson)?.string("default")?.trim()?.takeIf(String::isNotBlank)
    }

    override suspend fun loadPackage(agentId: String, version: Int): AgentContextPackage? =
        withContext(ioDispatcher) {
            val storedVersion = dao.findVersion(agentId, version) ?: return@withContext null
            val corpora = dao.listVersionCorpora(agentId, version)
            val missingOptionalCoverage = dao.listVersionPackages(agentId, version)
                .filter { declaration ->
                    declaration.type == V2PackageType.CORPUS.wireName &&
                        declaration.installClass != V2InstallClass.REQUIRED.wireName &&
                        !declaration.installed
                }
                .map(AgentVersionPackageEntity::packageId)
            storedVersion.toContextPackage(
                installedRequiredCorpusCount = corpora.count { it.required },
                missingOptionalCoverage = missingOptionalCoverage,
            )
        }

    override suspend fun searchHierarchy(
        agentId: String,
        version: Int,
        query: String,
        limit: Int,
    ): List<AgentHierarchyRoute> = withContext(ioDispatcher) {
        val corpusKeys = versionCorpusKeys(agentId, version)
        val ftsQuery = buildAgentFtsQuery(query)
        if (corpusKeys.isEmpty() || ftsQuery.isBlank() || limit <= 0) return@withContext emptyList()
        val terms = agentQueryTerms(query)
        val topRoutes = mutableListOf<AgentHierarchyRoute>()
        var afterNodeKey = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            val nodeKeys = dao.searchHierarchyNodeKeysForCorpora(
                corpusKeys = corpusKeys,
                ftsQuery = ftsQuery,
                afterNodeKey = afterNodeKey,
                limit = FTS_SCAN_PAGE_SIZE,
            )
            currentCoroutineContext().ensureActive()
            if (nodeKeys.isEmpty()) break
            val nodes = loadHierarchyClosure(nodeKeys)
            nodeKeys.mapNotNull(nodes::get).forEach { node ->
                val root = resolveHierarchyRoot(node, nodes)
                topRoutes += AgentHierarchyRoute(
                    nodeKey = node.nodeKey,
                    sourceId = node.sourceId,
                    topLevelId = root.nodeId,
                    physicalScore = scoreHierarchyNode(node, query, terms),
                )
            }
            topRoutes.sortWith(repositoryRouteComparator())
            if (topRoutes.size > limit) topRoutes.subList(limit, topRoutes.size).clear()
            currentCoroutineContext().ensureActive()
            if (nodeKeys.size < FTS_SCAN_PAGE_SIZE) break
            val nextKey = nodeKeys.last()
            check(nextKey != afterNodeKey) { "层级 FTS 分页游标未前进" }
            afterNodeKey = nextKey
        }
        topRoutes.toList()
    }

    override suspend fun searchChunks(
        agentId: String,
        version: Int,
        query: String,
        limit: Int,
        hierarchyRoutes: List<AgentHierarchyRoute>,
    ): List<AgentRetrievalChunk> = withContext(ioDispatcher) {
        val corpusKeys = versionCorpusKeys(agentId, version)
        val ftsQuery = buildAgentFtsQuery(query)
        if (corpusKeys.isEmpty() || limit <= 0) return@withContext emptyList()
        val hierarchyKeys = hierarchyRoutes.map(AgentHierarchyRoute::nodeKey)
        val routedKeys = if (hierarchyKeys.isEmpty()) {
            emptyList()
        } else {
            dao.listChunkKeysForHierarchyNodes(corpusKeys, hierarchyKeys, limit)
        }
        val ftsChunks = if (ftsQuery.isBlank()) {
            emptyList()
        } else {
            scanTopChunkMatches(corpusKeys, ftsQuery, query, limit)
        }
        val routedChunks = dao.listChunks(routedKeys)
        val chunks = (routedChunks + ftsChunks).associateBy(AgentChunkEntity::chunkKey).values
        val terms = agentQueryTerms(query)
        chunks.map { chunk ->
            val parentIds = chunk.parentPath.split('/').filter(String::isNotBlank).toSet()
            AgentRetrievalChunk(
                chunkKey = chunk.chunkKey,
                chunkId = chunk.chunkId,
                sourceId = chunk.sourceId.ifBlank { chunk.sourceHash },
                sourceTitle = chunk.sourceTitle,
                period = chunk.period,
                authorship = V2Authorship.entries.firstOrNull { it.wireName == chunk.authorship }
                    ?: V2Authorship.UNKNOWN,
                location = chunk.location,
                text = chunk.text,
                duplicateGroup = chunk.duplicateGroup,
                physicalScore = scoreChunk(chunk, query, terms),
                routeIds = hierarchyRoutes.filter { route ->
                    route.sourceId == chunk.sourceId &&
                        (route.topLevelId in parentIds || route.nodeKey.substringAfter(':') in parentIds)
                }.map { route -> "${route.sourceId}/${route.topLevelId}" }.toSet(),
            )
        }.sortedWith(
            compareByDescending<AgentRetrievalChunk>(AgentRetrievalChunk::physicalScore)
                .thenBy(AgentRetrievalChunk::sourceId)
                .thenBy(AgentRetrievalChunk::period)
                .thenBy(AgentRetrievalChunk::chunkKey),
        ).take(limit)
    }

    private suspend fun scanTopChunkMatches(
        corpusKeys: List<String>,
        ftsQuery: String,
        rawQuery: String,
        limit: Int,
    ): List<AgentChunkEntity> {
        if (limit <= 0) return emptyList()
        val terms = agentQueryTerms(rawQuery)
        val topMatches = mutableListOf<ScoredChunkMatch>()
        var afterChunkKey = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            val chunkKeys = dao.searchChunkKeys(
                corpusKeys = corpusKeys,
                ftsQuery = ftsQuery,
                afterChunkKey = afterChunkKey,
                limit = FTS_SCAN_PAGE_SIZE,
            )
            currentCoroutineContext().ensureActive()
            if (chunkKeys.isEmpty()) break
            val pageChunks = dao.listChunks(chunkKeys)
            currentCoroutineContext().ensureActive()
            pageChunks.forEach { chunk ->
                topMatches += ScoredChunkMatch(chunk, scoreChunk(chunk, rawQuery, terms))
            }
            topMatches.sortWith(scoredChunkMatchComparator())
            if (topMatches.size > limit) topMatches.subList(limit, topMatches.size).clear()
            currentCoroutineContext().ensureActive()
            if (chunkKeys.size < FTS_SCAN_PAGE_SIZE) break
            val nextKey = chunkKeys.last()
            check(nextKey != afterChunkKey) { "语料 FTS 分页游标未前进" }
            afterChunkKey = nextKey
        }
        return topMatches.map(ScoredChunkMatch::chunk)
    }

    private suspend fun versionCorpusKeys(agentId: String, version: Int): List<String> =
        dao.listVersionCorpora(agentId, version).map { corpusKey(it.corpusId, it.sourceHash) }

    private suspend fun loadHierarchyClosure(nodeKeys: List<String>): Map<String, AgentHierarchyNodeEntity> {
        val nodes = linkedMapOf<String, AgentHierarchyNodeEntity>()
        var pending = nodeKeys.distinct()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val loaded = dao.listHierarchyNodes(pending)
            currentCoroutineContext().ensureActive()
            val loadedKeys = loaded.mapTo(mutableSetOf(), AgentHierarchyNodeEntity::nodeKey)
            if (pending.any { it !in loadedKeys }) throw AgentBundleException("层级路由父链缺失")
            loaded.forEach { node -> nodes[node.nodeKey] = node }
            pending = loaded.mapNotNull(AgentHierarchyNodeEntity::parentNodeKey)
                .filterNot(nodes::containsKey)
                .distinct()
        }
        return nodes
    }

    private fun resolveHierarchyRoot(
        start: AgentHierarchyNodeEntity,
        nodes: Map<String, AgentHierarchyNodeEntity>,
    ): AgentHierarchyNodeEntity {
        var current = start
        val visited = mutableSetOf<String>()
        while (current.parentNodeKey != null) {
            if (!visited.add(current.nodeKey)) throw AgentBundleException("层级路由父链存在循环")
            val parent = nodes[current.parentNodeKey]
                ?: throw AgentBundleException("层级路由父链缺失")
            if (parent.sourceId != start.sourceId || parent.sourceHash != start.sourceHash) {
                throw AgentBundleException("层级路由父链跨越来源")
            }
            current = parent
        }
        return current
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

    suspend fun recoverFileLifecycle() = withContext(ioDispatcher) {
        lifecycleCoordinator.serialized { recoverFileLifecycleLocked() }
    }

    private suspend fun recoverFileLifecycleLocked() {
        val directory = tombstoneDirectory()
        if (directory.isDirectory) {
            directory.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(TOMBSTONE_META_SUFFIX) }
                .sortedBy(File::getName)
                .forEach { meta ->
                    val id = meta.name.removeSuffix(TOMBSTONE_META_SUFFIX)
                    val data = directory.resolve("$id$TOMBSTONE_DATA_SUFFIX")
                    val original = runCatching {
                        File(String(Base64.getUrlDecoder().decode(meta.readText()), Charsets.UTF_8))
                    }.getOrElse {
                        if (data.exists()) return@forEach
                        fileOps.delete(meta)
                        return@forEach
                    }
                    val referenced = dao.countVersionBundlePathReferences(original.absolutePath) > 0 ||
                        dao.countInstalledPackagePathReferences(original.absolutePath) > 0 ||
                        dao.countSourceFilePathReferences(original.absolutePath) > 0
                    if (referenced && data.isFile) {
                        fileOps.createDirectories(requireNotNull(original.parentFile))
                        if (original.exists()) {
                            if (!fileOps.delete(data)) return@forEach
                        } else {
                            fileOps.moveAtomically(data, original)
                        }
                        fileOps.delete(meta)
                    } else if (!referenced) {
                        finalizeTombstone(FileTombstone(original, data, meta))
                    }
                }
            deleteEmptyDirectory(directory)
        }
        canonicalizeLegacySourcePayloads()
    }

    private suspend fun canonicalizeLegacySourcePayloads() {
        dao.listSources().filter { it.filePath.isNotBlank() }
            .groupBy(AgentSourceFileEntity::sourceHash).forEach { (sourceHash, rows) ->
            val expectedBytes = rows.map(AgentSourceFileEntity::rawSizeBytes).distinct()
            if (expectedBytes.size != 1) {
                throw AgentBundleException("同一 sourceHash 的 descriptor bytes 不一致：$sourceHash")
            }
            val canonical = File(filesDir, "agents/sources/$sourceHash/$SOURCE_PAYLOAD_NAME").canonicalFile
            val installedFiles = rows.map(AgentSourceFileEntity::filePath)
                .filter(String::isNotBlank)
                .map { File(it).canonicalFile }
                .distinctBy(File::getAbsolutePath)
            val legacyFiles = installedFiles.filter { it != canonical }
            val canonicalIsValid = canonical.isFile &&
                canonical.length() == expectedBytes.single() &&
                canonical.sha256Hex() == sourceHash
            if (canonical.exists() && !canonicalIsValid) {
                throw AgentBundleException("canonical source payload 被修改：$sourceHash")
            }
            legacyFiles.filter(File::isFile).forEach { file ->
                if (
                    file.length() != expectedBytes.single() ||
                    file.sha256Hex() != sourceHash
                ) {
                    throw AgentBundleException("已安装 source descriptor/hash/bytes 不一致：$sourceHash")
                }
            }
            if (legacyFiles.any { !it.isFile } && !canonicalIsValid) {
                throw AgentBundleException("source payload 文件缺失：$sourceHash")
            }
            val rowsToUpdate = rows.filter { it.filePath != canonical.absolutePath }
            if (rowsToUpdate.isEmpty()) return@forEach

            var movedFrom: File? = null
            if (!canonicalIsValid) {
                val source = legacyFiles.firstOrNull(File::isFile)
                    ?: throw AgentBundleException("source payload 文件缺失：$sourceHash")
                fileOps.moveAtomically(source, canonical)
                movedFrom = source
            }
            val duplicates = legacyFiles.filter { it.isFile && it != movedFrom }
            val tombstones = stageFilesForDeletion(duplicates.map(File::getAbsolutePath))
            try {
                transactionRunner.run {
                    check(
                        dao.updateSourcePathsByHashAndOldPaths(
                            sourceHash = sourceHash,
                            oldPaths = rowsToUpdate.map(AgentSourceFileEntity::filePath).distinct(),
                            filePath = canonical.absolutePath,
                        ) == rowsToUpdate.size,
                    ) {
                        "source canonical path 更新失败"
                    }
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    restoreTombstones(tombstones)
                    movedFrom?.let { original ->
                        if (canonical.isFile) fileOps.moveAtomically(canonical, original)
                    }
                }
                throw error
            }
            finalizeTombstones(tombstones)
        }
    }

    /** Computes physical payload retirement from the database state after the pending logical removals. */
    private suspend fun sourcePayloadPathsToRetire(
        removedSources: List<AgentSourceFileEntity>,
        removedPackagePaths: List<String>,
    ): List<String> {
        val removedReferencesBySource = removedSources.groupingBy {
            it.sourceId to it.sourceHash
        }.eachCount()
        return removedSources.filter { it.filePath.isNotBlank() }
            .groupBy { it.sourceHash to File(it.filePath).canonicalPath }
            .mapNotNull { (payload, sources) ->
                val (sourceHash, filePath) = payload
                val sourceDescriptors = sources.distinctBy {
                    it.sourceId to it.sourceHash
                }
                val removedDescriptorCount = sourceDescriptors.count { source ->
                    dao.countSourceReferences(source.sourceId, source.sourceHash) <=
                        requireNotNull(removedReferencesBySource[source.sourceId to source.sourceHash])
                }
                val removedReferenceCount = sourceDescriptors.sumOf { source ->
                    requireNotNull(removedReferencesBySource[source.sourceId to source.sourceHash])
                }
                val removedPackageCount = removedPackagePaths.count { path ->
                    path.isNotBlank() && File(path).canonicalPath == filePath
                }
                val installedPackageReferenceCount = dao.listInstalledPackagePaths().count { path ->
                    File(path).canonicalPath == filePath
                }
                if (
                    dao.countSourcePayloadReferences(sourceHash, filePath) + installedPackageReferenceCount <=
                        removedReferenceCount + removedDescriptorCount + removedPackageCount
                ) {
                    filePath
                } else {
                    null
                }
            }
    }

    private suspend fun stageFilesForDeletion(paths: List<String>): List<FileTombstone> {
        val uniqueFiles = paths.map { File(it).canonicalFile }.distinctBy(File::getAbsolutePath)
        val staged = mutableListOf<FileTombstone>()
        try {
            uniqueFiles.forEach { file ->
                if (!file.isFile) throw AgentBundleException("数据库引用文件缺失：${file.absolutePath}")
                staged += stageFileForDeletion(file)
            }
            return staged
        } catch (error: Throwable) {
            restoreTombstones(staged)
            throw error
        }
    }

    private suspend fun stageFileForDeletion(file: File): FileTombstone {
        val canonicalFilesRoot = filesDir.canonicalFile
        val canonicalFile = file.canonicalFile
        if (!canonicalFile.toPath().startsWith(canonicalFilesRoot.toPath())) {
            throw AgentBundleException("拒绝移动私有目录外文件：${file.absolutePath}")
        }
        val directory = tombstoneDirectory().also(fileOps::createDirectories)
        val id = UUID.randomUUID().toString()
        val data = directory.resolve("$id$TOMBSTONE_DATA_SUFFIX")
        val meta = directory.resolve("$id$TOMBSTONE_META_SUFFIX")
        val metaPart = directory.resolve(".$id.meta.part")
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(canonicalFile.absolutePath.toByteArray(Charsets.UTF_8))
        fileOps.write(metaPart) { it.write(encoded.toByteArray(Charsets.UTF_8)) }
        fileOps.moveAtomically(metaPart, meta)
        try {
            fileOps.moveAtomically(canonicalFile, data)
        } catch (error: Throwable) {
            if (!fileOps.delete(meta)) throw AgentBundleException("tombstone metadata 清理失败", error)
            throw error
        }
        return FileTombstone(canonicalFile, data, meta)
    }

    private suspend fun restoreTombstones(tombstones: List<FileTombstone>) {
        var failure: Throwable? = null
        tombstones.asReversed().forEach { tombstone ->
            try {
                if (tombstone.data.isFile) {
                    fileOps.createDirectories(requireNotNull(tombstone.original.parentFile))
                    fileOps.moveAtomically(tombstone.data, tombstone.original)
                }
                if (!fileOps.delete(tombstone.meta)) {
                    throw AgentBundleException("tombstone metadata 恢复清理失败")
                }
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        deleteEmptyDirectory(tombstoneDirectory())
        failure?.let { throw AgentBundleException("文件 tombstone 恢复失败", it) }
    }

    private fun finalizeTombstones(tombstones: List<FileTombstone>): Boolean =
        tombstones.fold(true) { complete, tombstone -> finalizeTombstone(tombstone) && complete }

    private fun finalizeTombstone(tombstone: FileTombstone): Boolean {
        if (!fileOps.delete(tombstone.data)) return false
        return fileOps.delete(tombstone.meta)
    }

    private suspend fun retireUnreferencedFile(file: File) {
        if (!file.exists()) return
        val tombstone = stageFileForDeletion(file)
        finalizeTombstone(tombstone)
    }

    private fun deleteOrRecordOrphan(file: File) {
        if (!fileOps.delete(file)) throw AgentBundleException("无法删除文件：${file.absolutePath}")
    }

    private fun deleteEmptyDirectory(directory: File) {
        if (directory.isDirectory && directory.listFiles().orEmpty().isEmpty() && !fileOps.delete(directory)) {
            throw AgentBundleException("无法删除空目录：${directory.absolutePath}")
        }
    }

    private fun tombstoneDirectory(): File = File(filesDir, "agents/.tombstones")

    companion object {
        private const val DEFAULT_EVIDENCE_LIMIT = 8
        private const val CHUNK_BATCH_SIZE = 200
        private const val FTS_SCAN_PAGE_SIZE = 64
        private const val SOURCE_PAYLOAD_NAME = "payload"
        private const val TOMBSTONE_META_SUFFIX = ".meta"
        private const val TOMBSTONE_DATA_SUFFIX = ".data"
    }
}

private fun File.fileKeyOrNull(): Any? = runCatching {
    Files.readAttributes(toPath(), BasicFileAttributes::class.java).fileKey()
}.getOrNull()

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

private fun scoreHierarchyNode(
    node: AgentHierarchyNodeEntity,
    rawQuery: String,
    terms: List<String>,
): Int {
    val searchable = "${node.title} ${node.summary}"
    val termScore = terms.count(searchable::contains) * 2
    val phraseScore = if (rawQuery.isNotBlank() && searchable.contains(rawQuery.trim())) 8 else 0
    return termScore + phraseScore
}

private data class ScoredChunkMatch(
    val chunk: AgentChunkEntity,
    val score: Int,
)

private fun scoredChunkMatchComparator(): Comparator<ScoredChunkMatch> =
    compareByDescending<ScoredChunkMatch>(ScoredChunkMatch::score)
        .thenBy { match -> match.chunk.sourceId.ifBlank { match.chunk.sourceHash } }
        .thenBy { match -> match.chunk.period }
        .thenBy { match -> match.chunk.chunkKey }

private fun repositoryRouteComparator(): Comparator<AgentHierarchyRoute> =
    compareByDescending<AgentHierarchyRoute>(AgentHierarchyRoute::physicalScore)
        .thenBy(AgentHierarchyRoute::sourceId)
        .thenBy(AgentHierarchyRoute::topLevelId)
        .thenBy(AgentHierarchyRoute::nodeKey)

private fun AgentVersionEntity.toContextPackage(
    installedRequiredCorpusCount: Int,
    missingOptionalCoverage: List<String>,
): AgentContextPackage {
    val decoded = if (schemaVersion >= 2) decodeStoredV2RuntimeAssets() else null
    val stances = decoded?.stances
        ?: parseContextJsonLines(worldviewJsonl).mapNotNull(JsonObject::toWorldview)
    return AgentContextPackage(
        agentId = agentId,
        version = version,
        schemaVersion = schemaVersion,
        persona = persona,
        identity = decoded?.identity,
        voice = decoded?.voice,
        stances = stances,
        episodes = decoded?.episodes.orEmpty(),
        examples = decoded?.examples.orEmpty(),
        opener = if (schemaVersion >= 2) parseContextObject(openersJson)?.string("default") else null,
        requiredCorpusCount = requiredCorpusCount,
        installedRequiredCorpusCount = installedRequiredCorpusCount,
        missingOptionalCoverage = missingOptionalCoverage,
    )
}

private val contextJson = Json { ignoreUnknownKeys = true }

private data class StoredV2RuntimeAssets(
    val identity: V2Identity,
    val voice: V2Voice,
    val stances: List<V2Worldview>,
    val episodes: List<V2Episode>,
    val examples: List<V2Example>,
)

private fun AgentVersionEntity.decodeStoredV2RuntimeAssets(): StoredV2RuntimeAssets {
    val identity = parseRequiredContextObject(identityJson, "identity").toStrictIdentity("identity")
    val voice = parseRequiredContextObject(voiceJson, "voice").toStrictVoice("voice")
    val stances = parseRequiredContextJsonLines(worldviewJsonl, "worldview", JsonObject::toStrictWorldview)
    val episodes = parseRequiredContextJsonLines(episodesJsonl, "episodes", JsonObject::toStrictEpisode)
    parseRequiredConcepts(conceptsJson)
    val examples = parseRequiredContextJsonLines(examplesJsonl, "examples", JsonObject::toStrictExample)
    return StoredV2RuntimeAssets(identity, voice, stances, episodes, examples)
}

private fun parseRequiredContextObject(raw: String, label: String): JsonObject {
    val element = try {
        contextJson.parseToJsonElement(raw)
    } catch (error: Throwable) {
        throw invalidStoredRuntimeAsset(label, error.message)
    }
    return element as? JsonObject ?: throw invalidStoredRuntimeAsset(label, "必须是 JSON 对象")
}

private fun <T> parseRequiredContextJsonLines(
    raw: String,
    label: String,
    decode: JsonObject.(String) -> T,
): List<T> = raw.lineSequence()
    .filter(String::isNotBlank)
    .mapIndexed { index, line ->
        val rowLabel = "$label 第 ${index + 1} 行"
        parseRequiredContextObject(line, rowLabel).decode(rowLabel)
    }
    .toList()

private fun parseRequiredConcepts(raw: String) {
    val element = try {
        contextJson.parseToJsonElement(raw)
    } catch (error: Throwable) {
        throw invalidStoredRuntimeAsset("concepts", error.message)
    }
    val concepts = element as? JsonArray
        ?: throw invalidStoredRuntimeAsset("concepts", "必须是 JSON 数组")
    concepts.forEachIndexed { index, value ->
        val label = "concepts[$index]"
        val row = value as? JsonObject ?: throw invalidStoredRuntimeAsset(label, "必须是 JSON 对象")
        row.requiredStoredString("id", label)
        row.requiredStoredString("name", label)
        row.storedStrings("aliases", label)
        row.storedStrings("keywords", label)
        row.storedStrings("evidence", label)
    }
}

private fun JsonObject.toStrictIdentity(label: String): V2Identity {
    val relationships = storedArray("relationships", label).mapIndexed { index, value ->
        val rowLabel = "$label relationships[$index]"
        val row = value as? JsonObject ?: throw invalidStoredRuntimeAsset(rowLabel, "必须是 JSON 对象")
        V2Relationship(
            subject = row.requiredStoredString("subject", rowLabel),
            relation = row.requiredStoredString("relation", rowLabel),
            period = row.optionalStoredString("period", rowLabel),
            evidence = row.storedStrings("evidence", rowLabel),
        )
    }
    return V2Identity(
        selfNames = storedStrings("selfNames", label),
        timeHorizon = optionalStoredString("timeHorizon", label),
        roles = storedStrings("roles", label),
        relationships = relationships,
    )
}

private fun JsonObject.toStrictVoice(label: String): V2Voice = V2Voice(
    defaultForm = optionalStoredString("defaultForm", label),
    sentenceRhythm = storedStrings("sentenceRhythm", label),
    rhetoricalMoves = storedStrings("rhetoricalMoves", label),
    preferredTerms = storedStrings("preferredTerms", label),
    avoidPatterns = storedStrings("avoidPatterns", label),
    evidence = storedStrings("evidence", label),
)

private fun JsonObject.toStrictWorldview(label: String): V2Worldview {
    val confidence = when (val value = this["confidence"]) {
        null -> 0.0
        is JsonPrimitive -> value.doubleOrNull?.takeIf(Double::isFinite)
            ?: throw invalidStoredRuntimeAsset(label, "confidence 必须是有限数值")
        else -> throw invalidStoredRuntimeAsset(label, "confidence 必须是有限数值")
    }
    return V2Worldview(
        id = requiredStoredString("id", label),
        topic = requiredStoredString("topic", label),
        statement = requiredStoredString("statement", label),
        conditions = storedStrings("conditions", label),
        period = optionalStoredString("period", label),
        aliases = storedStrings("aliases", label),
        confidence = confidence,
        evidence = storedStrings("evidence", label),
    )
}

private fun JsonObject.toStrictEpisode(label: String): V2Episode = V2Episode(
    id = requiredStoredString("id", label),
    period = optionalStoredString("period", label),
    location = optionalStoredString("location", label),
    participants = storedStrings("participants", label),
    summary = requiredStoredString("summary", label),
    meaning = optionalStoredString("meaning", label),
    evidence = storedStrings("evidence", label),
)

private fun JsonObject.toStrictExample(label: String): V2Example = V2Example(
    id = requiredStoredString("id", label),
    intent = optionalStoredString("intent", label),
    user = requiredStoredString("user", label),
    assistant = requiredStoredString("assistant", label),
    styleTags = storedStrings("styleTags", label),
    generationType = requiredStoredString("generationType", label),
    evidence = storedStrings("evidence", label),
)

private fun JsonObject.requiredStoredString(key: String, label: String): String =
    optionalStoredString(key, label).takeIf(String::isNotBlank)
        ?: throw invalidStoredRuntimeAsset(label, "缺少 $key")

private fun JsonObject.optionalStoredString(key: String, label: String): String = when (val value = this[key]) {
    null -> ""
    is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.contentOrNull?.trim()
        ?: throw invalidStoredRuntimeAsset(label, "$key 必须是字符串")
    else -> throw invalidStoredRuntimeAsset(label, "$key 必须是字符串")
}

private fun JsonObject.storedArray(key: String, label: String): JsonArray = when (val value = this[key]) {
    null -> JsonArray(emptyList())
    is JsonArray -> value
    else -> throw invalidStoredRuntimeAsset(label, "$key 必须是数组")
}

private fun JsonObject.storedStrings(key: String, label: String): List<String> =
    storedArray(key, label).mapIndexed { index, value ->
        (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull?.trim()
            ?.takeIf(String::isNotBlank)
            ?: throw invalidStoredRuntimeAsset(label, "$key[$index] 必须是非空字符串")
    }

private fun invalidStoredRuntimeAsset(label: String, detail: String?): AgentBundleException {
    val boundedLabel = label.take(64)
    val boundedDetail = detail.orEmpty().replace(Regex("\\s+"), " ").take(96)
    return AgentBundleException("V2 runtime asset $boundedLabel 无效：$boundedDetail")
}

private fun parseContextObject(raw: String): JsonObject? = raw.trim().takeIf(String::isNotBlank)?.let { value ->
    runCatching { contextJson.parseToJsonElement(value) as? JsonObject }.getOrNull()
}

private fun parseContextJsonLines(raw: String): List<JsonObject> = raw.lineSequence()
    .filter(String::isNotBlank)
    .mapNotNull(::parseContextObject)
    .toList()

private fun JsonObject.toWorldview(): V2Worldview? {
    val statement = string("statement") ?: return null
    return V2Worldview(
        id = string("id").orEmpty().ifBlank { "stance-${statement.hashCode()}" },
        topic = string("topic").orEmpty(),
        statement = statement,
        conditions = stringsOrSingle("conditions"),
        period = string("period").orEmpty(),
        aliases = strings("aliases"),
        confidence = (this["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
        evidence = strings("evidence"),
    )
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeIf(JsonPrimitive::isString)
    ?.contentOrNull
    ?.trim()
    ?.takeIf(String::isNotBlank)

private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray).orEmpty()
    .mapNotNull { value ->
        (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull?.trim()
    }
    .filter(String::isNotBlank)

private fun JsonObject.stringsOrSingle(key: String): List<String> = when (this[key]) {
    is JsonArray -> strings(key)
    is JsonPrimitive -> listOfNotNull(string(key))
    else -> emptyList()
}

internal fun purgeStaleImportFiles(directory: File, nowMillis: Long) {
    directory.listFiles()?.filter { file ->
        file.isFile && nowMillis - file.lastModified() >= IMPORT_STAGING_TTL_MILLIS
    }?.forEach { file ->
        if (!file.delete()) throw AgentBundleException("过期 staging 文件清理失败：${file.absolutePath}")
    }
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
        duplicateGroup == other.duplicateGroup &&
        conflictKey == other.conflictKey &&
        sourceAliasesJson == other.sourceAliasesJson &&
        simHash == other.simHash

private fun AgentSourceFileEntity.requireSameDescriptor(source: V2SourceManifest) {
    if (
        sourceId != source.sourceId ||
        sourceHash != source.sourceHash ||
        fileName != source.fileName ||
        rawSizeBytes != source.rawSizeBytes
    ) {
        throw AgentBundleException("同一 sourceHash 的 hsource descriptor 冲突：${source.sourceHash}")
    }
}

private fun AgentSourceFileEntity.requireSameDescriptor(source: V2SourceRecord) {
    val enrichedDescriptor = genre != V2SourceGenre.UNKNOWN.wireName ||
        authorship != V2Authorship.UNKNOWN.wireName ||
        period.isNotBlank()
    if (
        sourceId != source.sourceId ||
        sourceHash != source.sourceHash ||
        fileName != source.fileName ||
        rawSizeBytes != source.rawSizeBytes ||
        (
            enrichedDescriptor && (
                title != source.title ||
                    format != source.format ||
                    genre != source.genre.wireName ||
                    authorship != source.authorship.wireName ||
                    period != source.period
                )
            )
    ) {
        throw AgentBundleException("同一 sourceHash 的 corpus descriptor 冲突：${source.sourceHash}")
    }
}

private fun V2Corpus.matchesStandaloneCorpus(
    other: V2Corpus,
    declaration: AgentVersionPackageEntity,
): Boolean =
    packageSha256 == declaration.packageSha256 &&
        compressedSizeBytes == declaration.packageSizeBytes &&
        packageSha256 == other.packageSha256 &&
        compressedSizeBytes == other.compressedSizeBytes &&
        publisherFingerprint == other.publisherFingerprint &&
        manifestJson == other.manifestJson &&
        manifest == other.manifest &&
        sources == other.sources &&
        nodeCount == other.nodeCount &&
        chunkCount == other.chunkCount &&
        duplicateCount == other.duplicateCount &&
        validationDiagnostics == other.validationDiagnostics

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
) {
    var v1CompatibilitySession: AgentImportSession? = null
}

private data class ConsumedPackageSnapshot(
    val file: File,
    val parsed: ParsedAgentPackage,
    val preview: AgentImportPreview,
)

private data class PreparedSourceFile(
    val source: V2Source,
    val file: File,
    val created: Boolean,
)

private data class FileTombstone(
    val original: File,
    val data: File,
    val meta: File,
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

private suspend fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
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
private const val INSTALL_SNAPSHOT_DIRECTORY = "agent-install-snapshots"
private val FTS_OPERATORS = setOf("and", "or", "not", "near")
