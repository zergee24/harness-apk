package com.harnessapk.agent

import com.harnessapk.common.SystemTimeProvider
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AgentChunkEntity
import com.harnessapk.storage.AgentChunkFtsEntity
import com.harnessapk.storage.AgentCorpusEntity
import com.harnessapk.storage.AgentDao
import com.harnessapk.storage.AgentEntity
import com.harnessapk.storage.AgentVersionCorpusCrossRef
import com.harnessapk.storage.AgentVersionEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

fun interface AgentTransactionRunner {
    suspend fun run(block: suspend () -> Unit)
}

class AgentRepository(
    private val filesDir: File,
    private val cacheDir: File,
    private val dao: AgentDao,
    private val reader: AgentBundleAccess = AgentBundleReader(),
    private val transactionRunner: AgentTransactionRunner = AgentTransactionRunner { block -> block() },
    private val timeProvider: TimeProvider = SystemTimeProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun observeAgents(): Flow<List<Agent>> = dao.observeAgents().map { rows -> rows.map(AgentEntity::toDomain) }

    suspend fun agent(id: String): Agent? = withContext(ioDispatcher) { dao.findAgent(id)?.toDomain() }

    suspend fun prepareImport(
        sourceName: String,
        openInputStream: () -> InputStream,
    ): AgentImportSession = withContext(ioDispatcher) {
        val stagingDirectory = File(cacheDir, "agent-staging").apply { mkdirs() }
        val stagedFile = File(stagingDirectory, "${UUID.randomUUID()}.hbundle")
        try {
            openInputStream().use { input -> copyBundleWithLimit(input, stagedFile) }
            val parsed = reader.read(stagedFile)
            AgentImportSession(
                id = UUID.randomUUID().toString(),
                stagedFile = stagedFile,
                parsedBundle = parsed,
                preview = reader.inspect(stagedFile),
            )
        } catch (error: Throwable) {
            stagedFile.delete()
            throw if (error is AgentBundleException) error else AgentBundleException("无法导入 $sourceName", error)
        }
    }

    suspend fun install(session: AgentImportSession): AgentInstallResult = withContext(ioDispatcher) {
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
        try {
            val now = timeProvider.nowMillis()
            var resultAgent: AgentEntity? = null
            transactionRunner.run {
                val availableCorpora = linkedMapOf<String, AgentCorpusEntity>()
                bundle.corpora.forEach { corpus ->
                    val existingCorpus = dao.findCorpus(corpus.id, corpus.sourceHash)
                    val storedCorpus = existingCorpus ?: installCorpus(bundle, corpus, now)
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
        session.stagedFile.delete()
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
            dao.insertChunks(chunkBatch.toList())
            dao.insertChunkSearchRows(searchBatch.toList())
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
            val key = chunkKey(corpus.id, corpus.sourceHash, chunk.id)
            val searchable = (chunk.keywords + chunk.ngrams).distinct().joinToString(" ")
            sizeBytes += chunk.text.encodeToByteArray().size
            chunkBatch += AgentChunkEntity(
                chunkKey = key,
                corpusId = corpus.id,
                sourceHash = corpus.sourceHash,
                chunkId = chunk.id,
                sourceTitle = chunk.sourceTitle,
                location = chunk.location,
                text = chunk.text,
                keywordsText = chunk.keywords.joinToString(" "),
            )
            searchBatch += AgentChunkFtsEntity(
                chunkKey = key,
                corpusKey = corpusKey(corpus.id, corpus.sourceHash),
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
            source.inputStream().buffered().use { input ->
                part.outputStream().buffered().use(input::copyTo)
            }
            if (!part.renameTo(destination)) throw AgentBundleException("无法原子安装智能体包")
            source.delete()
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

private fun buildAgentSystemPrompt(
    version: AgentVersionEntity,
    evidence: List<AgentEvidence>,
): String = buildString {
    appendLine("你正在扮演一个基于资料模拟的智能体，不是真实人物本人。")
    appendLine("必须使用第一人称表达，但不得使用模型通用知识补写人物立场。")
    appendLine("所有核心判断必须由下方资料支持，并在相关段落使用 [资料 N] 标记来源。")
    appendLine("资料之间存在冲突时必须保留差异，不得强行合并。")
    appendLine()
    appendLine("人格定义：")
    appendLine(version.persona.trim())
    if (version.worldviewJsonl.isNotBlank()) {
        appendLine()
        appendLine("结构化观点：")
        appendLine(version.worldviewJsonl.trim())
    }
    appendLine()
    if (evidence.isEmpty()) {
        appendLine("当前检索没有找到可支持回答的资料。请明确回答“当前资料不足，无法据此判断”，不要猜测。")
    } else {
        appendLine("本轮可用资料：")
        evidence.forEachIndexed { index, item ->
            appendLine("[资料 ${index + 1}] ${item.sourceTitle} · ${item.location}")
            appendLine(item.text.trim())
            appendLine()
        }
    }
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

private fun safeFileSegment(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]"), "-").trim('.', '-').ifBlank { "agent" }

private fun corpusKey(corpusId: String, sourceHash: String): String = "$corpusId:$sourceHash"

private fun chunkKey(corpusId: String, sourceHash: String, chunkId: String): String =
    "${corpusKey(corpusId, sourceHash)}:$chunkId"

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

private const val MAX_QUERY_TERM_COUNT = 24
private const val MAX_IMPORT_BUNDLE_BYTES = 2L * 1024 * 1024 * 1024
private val FTS_OPERATORS = setOf("and", "or", "not", "near")
