package com.harnessapk.wiki

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.harnessapk.storage.WikiDao
import com.harnessapk.storage.WikiVersionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

interface WikiContentStore {
    suspend fun listDocuments(ref: WikiRef): List<WikiDocument>

    suspend fun findDocument(ref: WikiRef, documentId: String): WikiDocument?

    suspend fun listSections(ref: WikiRef, parentSectionId: String?): List<WikiSection>

    suspend fun findSection(ref: WikiRef, sectionId: String): WikiSection?

    suspend fun listChunks(ref: WikiRef, sectionId: String, limit: Int = MAX_WIKI_PAGE_SIZE): List<WikiChunk>

    suspend fun findChunk(ref: WikiRef, chunkId: String): WikiChunk?

    suspend fun chunkNeighbors(ref: WikiRef, chunkId: String): WikiChunkNeighbors?

    suspend fun stats(ref: WikiRef): WikiContentStats

    suspend fun summariesFor(ref: WikiRef, ownerType: String, ownerId: String): List<WikiSummary>

    suspend fun listTerms(ref: WikiRef, limit: Int = MAX_WIKI_PAGE_SIZE): List<WikiTerm>

    suspend fun aliasesFor(ref: WikiRef, termId: String): List<WikiAlias>

    suspend fun annotationsFor(ref: WikiRef, ownerType: String, ownerId: String): List<WikiAnnotation>

    suspend fun linksFrom(ref: WikiRef, sourceType: String, sourceId: String): List<WikiLink>

    suspend fun searchSources(ref: WikiRef, query: String, limit: Int = 20): List<WikiSourceHit>

    suspend fun evidenceFor(ref: WikiRef, ownerType: String, ownerId: String): List<WikiChunk>
}

class InstalledWikiContentStore(
    private val filesDir: File,
    private val wikiDao: WikiDao,
    private val healthReporter: WikiVersionHealthReporter,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WikiContentStore {
    override suspend fun listDocuments(ref: WikiRef): List<WikiDocument> = withDatabase(ref) { database ->
        database.rawQuery(
            """
            SELECT document_id, title, responsibility, edition, language, rights, source_hash, ordinal, metadata_json
            FROM documents
            ORDER BY ordinal, document_id
            LIMIT $MAX_WIKI_PAGE_SIZE
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toDocument())
                }
            }
        }
    }

    override suspend fun findDocument(ref: WikiRef, documentId: String): WikiDocument? {
        validateIdentifier(documentId, "documentId")
        return withDatabase(ref) { database ->
            database.rawQuery(
                """
                SELECT document_id, title, responsibility, edition, language, rights, source_hash, ordinal, metadata_json
                FROM documents
                WHERE document_id = ?
                """.trimIndent(),
                arrayOf(documentId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.toDocument().also {
                    require(!cursor.moveToNext()) { "document_id 重复：$documentId" }
                }
            }
        }
    }

    override suspend fun listSections(ref: WikiRef, parentSectionId: String?): List<WikiSection> = withDatabase(ref) { database ->
        val query = if (parentSectionId == null) {
            """
            SELECT section_id, document_id, parent_section_id, title, path, ordinal, metadata_json
            FROM sections
            WHERE parent_section_id IS NULL
            ORDER BY document_id, ordinal, section_id
            LIMIT $MAX_WIKI_PAGE_SIZE
            """.trimIndent()
        } else {
            """
            SELECT section_id, document_id, parent_section_id, title, path, ordinal, metadata_json
            FROM sections
            WHERE parent_section_id = ?
            ORDER BY ordinal, section_id
            LIMIT $MAX_WIKI_PAGE_SIZE
            """.trimIndent()
        }
        database.rawQuery(query, if (parentSectionId == null) null else arrayOf(parentSectionId)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toSection())
            }
        }
    }

    override suspend fun findSection(ref: WikiRef, sectionId: String): WikiSection? {
        validateIdentifier(sectionId, "sectionId")
        return withDatabase(ref) { database ->
            database.rawQuery(
                """
                SELECT section_id, document_id, parent_section_id, title, path, ordinal, metadata_json
                FROM sections
                WHERE section_id = ?
                """.trimIndent(),
                arrayOf(sectionId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.toSection().also {
                    require(!cursor.moveToNext()) { "section_id 重复：$sectionId" }
                }
            }
        }
    }

    override suspend fun listChunks(ref: WikiRef, sectionId: String, limit: Int): List<WikiChunk> {
        validatePageLimit(limit)
        validateIdentifier(sectionId, "sectionId")
        return withDatabase(ref) { database ->
            val ids = database.stringColumn(
                """
                SELECT chunk_id FROM chunks
                WHERE section_id = ?
                ORDER BY ordinal, chunk_id
                LIMIT ?
                """.trimIndent(),
                arrayOf(sectionId, limit.toString()),
            )
            ids.map { id -> requireNotNull(loadChunk(database, id)) { "chunk 不存在：$id" } }
        }
    }

    override suspend fun findChunk(ref: WikiRef, chunkId: String): WikiChunk? {
        validateIdentifier(chunkId, "chunkId")
        return withDatabase(ref) { database -> loadChunk(database, chunkId) }
    }

    override suspend fun chunkNeighbors(ref: WikiRef, chunkId: String): WikiChunkNeighbors? {
        validateIdentifier(chunkId, "chunkId")
        return withDatabase(ref) { database ->
            val chunk = loadChunk(database, chunkId) ?: return@withDatabase null
            val previousId = database.rawQuery(
                """
                SELECT chunk_id FROM chunks
                WHERE section_id = ?
                  AND (ordinal < ? OR (ordinal = ? AND chunk_id < ?))
                ORDER BY ordinal DESC, chunk_id DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(chunk.sectionId, chunk.ordinal.toString(), chunk.ordinal.toString(), chunk.id),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.requiredString(0, "chunk_id") else null }
            val nextId = database.rawQuery(
                """
                SELECT chunk_id FROM chunks
                WHERE section_id = ?
                  AND (ordinal > ? OR (ordinal = ? AND chunk_id > ?))
                ORDER BY ordinal, chunk_id
                LIMIT 1
                """.trimIndent(),
                arrayOf(chunk.sectionId, chunk.ordinal.toString(), chunk.ordinal.toString(), chunk.id),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.requiredString(0, "chunk_id") else null }
            WikiChunkNeighbors(
                previous = previousId?.let { id -> requireNotNull(loadChunk(database, id)) },
                next = nextId?.let { id -> requireNotNull(loadChunk(database, id)) },
            )
        }
    }

    override suspend fun stats(ref: WikiRef): WikiContentStats = withDatabase(ref) { database ->
        database.rawQuery(
            """
            SELECT
                (SELECT COUNT(*) FROM documents),
                (SELECT COUNT(*) FROM sections),
                (SELECT COUNT(*) FROM chunks)
            """.trimIndent(),
            null,
        ).use { cursor ->
            require(cursor.moveToFirst()) { "Wiki 统计查询未返回结果" }
            WikiContentStats(
                documentCount = cursor.getLong(0).toInt(),
                sectionCount = cursor.getLong(1).toInt(),
                sourceChunkCount = cursor.getLong(2).toInt(),
            )
        }
    }

    override suspend fun summariesFor(ref: WikiRef, ownerType: String, ownerId: String): List<WikiSummary> {
        validateOwner(ownerType, ownerId)
        return withDatabase(ref) { database ->
            database.rawQuery(
                """
                SELECT summary_id, owner_type, owner_id, level, text
                FROM summaries
                WHERE owner_type = ? AND owner_id = ?
                ORDER BY level, summary_id
                LIMIT $MAX_WIKI_PAGE_SIZE
                """.trimIndent(),
                arrayOf(ownerType, ownerId),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            WikiSummary(
                                id = cursor.requiredString(0, "summary_id"),
                                ownerType = cursor.requiredString(1, "owner_type"),
                                ownerId = cursor.requiredString(2, "owner_id"),
                                level = cursor.requiredString(3, "level"),
                                text = WikiSourceSearch.truncateSnippet(cursor.requiredString(4, "text")),
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun listTerms(ref: WikiRef, limit: Int): List<WikiTerm> {
        validatePageLimit(limit)
        return withDatabase(ref) { database ->
            database.rawQuery(
                """
                SELECT term_id, concept_key, canonical_text, kind, confidence, metadata_json
                FROM terms
                ORDER BY canonical_text, term_id
                LIMIT ?
                """.trimIndent(),
                arrayOf(limit.toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.toTerm())
                }
            }
        }
    }

    override suspend fun aliasesFor(ref: WikiRef, termId: String): List<WikiAlias> {
        validateIdentifier(termId, "termId")
        return withDatabase(ref) { database ->
            database.rawQuery(
                """
                SELECT alias_id, term_id, alias_text, normalized_alias, confidence
                FROM aliases
                WHERE term_id = ?
                ORDER BY alias_text, alias_id
                LIMIT $MAX_WIKI_PAGE_SIZE
                """.trimIndent(),
                arrayOf(termId),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            WikiAlias(
                                id = cursor.requiredString(0, "alias_id"),
                                termId = cursor.requiredString(1, "term_id"),
                                text = cursor.requiredString(2, "alias_text"),
                                normalizedText = cursor.requiredString(3, "normalized_alias"),
                                confidence = cursor.getDouble(4),
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun annotationsFor(ref: WikiRef, ownerType: String, ownerId: String): List<WikiAnnotation> {
        validateOwner(ownerType, ownerId)
        return withDatabase(ref) { database ->
            database.rawQuery(
                """
                SELECT annotation_id, owner_type, owner_id, kind, value_json, confidence
                FROM annotations
                WHERE owner_type = ? AND owner_id = ?
                ORDER BY kind, annotation_id
                LIMIT $MAX_WIKI_PAGE_SIZE
                """.trimIndent(),
                arrayOf(ownerType, ownerId),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            WikiAnnotation(
                                id = cursor.requiredString(0, "annotation_id"),
                                ownerType = cursor.requiredString(1, "owner_type"),
                                ownerId = cursor.requiredString(2, "owner_id"),
                                kind = cursor.requiredString(3, "kind"),
                                valueJson = cursor.requiredString(4, "value_json"),
                                confidence = cursor.getDouble(5),
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun linksFrom(ref: WikiRef, sourceType: String, sourceId: String): List<WikiLink> {
        validateOwner(sourceType, sourceId)
        return withDatabase(ref) { database ->
            database.rawQuery(
                """
                SELECT link_id, source_type, source_id, target_namespace, target_type, target_id, kind, confidence,
                    metadata_json
                FROM links
                WHERE source_type = ? AND source_id = ?
                ORDER BY kind, link_id
                LIMIT $MAX_WIKI_PAGE_SIZE
                """.trimIndent(),
                arrayOf(sourceType, sourceId),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            WikiLink(
                                id = cursor.requiredString(0, "link_id"),
                                sourceType = cursor.requiredString(1, "source_type"),
                                sourceId = cursor.requiredString(2, "source_id"),
                                targetNamespace = cursor.requiredString(3, "target_namespace"),
                                targetType = cursor.requiredString(4, "target_type"),
                                targetId = cursor.requiredString(5, "target_id"),
                                kind = cursor.requiredString(6, "kind"),
                                confidence = cursor.getDouble(7),
                                metadataJson = cursor.requiredString(8, "metadata_json"),
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun searchSources(ref: WikiRef, query: String, limit: Int): List<WikiSourceHit> {
        WikiSourceSearch.validateQuery(query)
        WikiSourceSearch.validateLimit(limit)
        return withDatabase(ref) { database ->
            val original = ftsCandidates(
                database = database,
                table = "chunks_original_fts",
                idColumn = "chunk_id",
                tokens = WikiSourceSearch.originalTokens(query),
                channel = WikiSearchChannel.ORIGINAL,
                label = "原文",
            )
            val normalized = ftsCandidates(
                database = database,
                table = "chunks_normalized_fts",
                idColumn = "chunk_id",
                tokens = WikiSourceSearch.normalizedTokens(query),
                channel = WikiSearchChannel.NORMALIZED,
                label = "归一化原文",
            )
            val summaries = ftsIds(
                database = database,
                table = "summaries_fts",
                idColumn = "summary_id",
                tokens = WikiSourceSearch.normalizedTokens(query),
            ).flatMap { summaryId ->
                evidenceIds(database, "summary", summaryId).map { chunkId ->
                    WikiSearchCandidate(chunkId, WikiSourceMatch(WikiSearchChannel.SUMMARY, "摘要"))
                }
            }
            val terms = ftsIds(
                database = database,
                table = "terms_aliases_fts",
                idColumn = "owner_id",
                tokens = WikiSourceSearch.normalizedTokens(query),
            ).flatMap { termId ->
                val term = findTermLabel(database, termId)
                val label = "术语：${term.canonicalText}"
                (mentionIds(database, termId) + evidenceIds(database, "term", termId)).map { chunkId ->
                    WikiSearchCandidate(
                        chunkId,
                        WikiSourceMatch(WikiSearchChannel.TERM, label, term.conceptKey),
                    )
                }
            }
            val temporal = temporalCandidates(database, query)
            val ranked = WikiSourceSearch.fuse(listOf(original, normalized, summaries, terms, temporal), limit)
            ranked.map { candidate ->
                val chunk = requireNotNull(loadChunk(database, candidate.chunkId)) {
                    "检索索引引用了不存在的 chunk：${candidate.chunkId}"
                }
                WikiSourceHit(chunk, candidate.matches)
            }
        }
    }

    override suspend fun evidenceFor(ref: WikiRef, ownerType: String, ownerId: String): List<WikiChunk> {
        validateOwner(ownerType, ownerId)
        return withDatabase(ref) { database ->
            evidenceIds(database, ownerType, ownerId)
                .take(MAX_WIKI_PAGE_SIZE)
                .map { chunkId -> requireNotNull(loadChunk(database, chunkId)) { "证据引用不存在的 chunk：$chunkId" } }
        }
    }

    internal suspend fun <T> withDatabase(ref: WikiRef, block: (SQLiteDatabase) -> T): T = withContext(ioDispatcher) {
        validateRef(ref)
        val version = wikiDao.findVersion(ref.wikiId, ref.version)
            ?: throw WikiContentUnavailableException("Wiki 版本不存在")
        if (version.state != WikiVersionState.READY.name) {
            throw WikiContentUnavailableException("Wiki 版本当前不可用")
        }
        var database: SQLiteDatabase? = null
        try {
            val contentPath = canonicalContentPath(ref, version)
            // Re-check the immutable on-disk protocol before exposing any data to callers.
            AndroidWikiDatabaseInspector.inspect(contentPath)
            database = SQLiteDatabase.openDatabase(
                contentPath.toString(),
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            verifyReadOnlyDatabase(database)
            block(database)
        } catch (error: CancellationException) {
            throw error
        } catch (error: WikiContentUnavailableException) {
            throw error
        } catch (error: Exception) {
            markInvalid(ref, error)
            throw WikiContentUnavailableException("该 Wiki 版本无法读取", error)
        } finally {
            database?.close()
        }
    }

    private fun canonicalContentPath(ref: WikiRef, version: WikiVersionEntity): Path {
        val expectedDirectory = filesDir.toPath()
            .resolve("wikis")
            .resolve(ref.wikiId)
            .resolve(ref.version.toString())
            .normalize()
        val recorded = Paths.get(version.contentPath).toAbsolutePath().normalize()
        require(recorded.fileName.toString() == WikiRepository.CONTENT_FILE_NAME) { "Wiki 内容文件名无效" }
        require(recorded.startsWith(expectedDirectory)) { "Wiki 内容路径越界" }
        val expectedRealDirectory = expectedDirectory.toRealPath()
        val contentRealPath = recorded.toRealPath()
        require(contentRealPath.startsWith(expectedRealDirectory)) { "Wiki 内容真实路径越界" }
        require(Files.isRegularFile(contentRealPath)) { "Wiki 内容文件不存在" }
        return contentRealPath
    }

    private fun verifyReadOnlyDatabase(database: SQLiteDatabase) {
        database.execSQL("PRAGMA query_only=ON")
        val schemaVersion = database.singleString("PRAGMA user_version", "user_version").toIntOrNull()
        require(schemaVersion == WikiRepository.CONTENT_SCHEMA_VERSION) { "Wiki 内容 schemaVersion 不兼容" }
        val normalizationVersion = metadataValue(database, "normalizationVersion")
        require(normalizationVersion == "1") { "Wiki 检索归一化版本不兼容" }
        val normalizationMapHash = metadataValue(database, "normalizationMapHash")
        require(normalizationMapHash == WikiSourceSearch.normalizationMapHash) { "Wiki 检索归一化规则不兼容" }
    }

    private fun metadataValue(database: SQLiteDatabase, key: String): String =
        database.rawQuery("SELECT value FROM build_metadata WHERE key = ?", arrayOf(key)).use { cursor ->
            require(cursor.moveToFirst()) { "Wiki 缺少 build_metadata.$key" }
            val value = cursor.requiredString(0, "build_metadata.$key")
            require(!cursor.moveToNext()) { "Wiki build_metadata.$key 重复" }
            value
        }

    private fun ftsCandidates(
        database: SQLiteDatabase,
        table: String,
        idColumn: String,
        tokens: List<String>,
        channel: WikiSearchChannel,
        label: String,
    ): List<WikiSearchCandidate> = ftsIds(database, table, idColumn, tokens).map { chunkId ->
        WikiSearchCandidate(chunkId, WikiSourceMatch(channel, label))
    }

    private fun ftsIds(
        database: SQLiteDatabase,
        table: String,
        idColumn: String,
        tokens: List<String>,
    ): List<String> {
        val match = WikiSourceSearch.ftsMatch(tokens)
        if (match.isBlank()) return emptyList()
        return database.rawQuery(
            """
            SELECT $idColumn, matchinfo($table, 'pcx')
            FROM $table
            WHERE $table MATCH ?
            ORDER BY $idColumn
            LIMIT ?
            """.trimIndent(),
            arrayOf(match, WikiSourceSearch.MAX_CHANNEL_CANDIDATES.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(ScoredFtsId(cursor.requiredString(0, idColumn), cursor.matchInfoScore(1)))
                }
            }.sortedWith(compareByDescending(ScoredFtsId::score).thenBy(ScoredFtsId::id)).map(ScoredFtsId::id)
        }
    }

    private fun evidenceIds(database: SQLiteDatabase, ownerType: String, ownerId: String): List<String> =
        database.stringColumn(
            """
            SELECT chunk_id FROM evidence_refs
            WHERE owner_type = ? AND owner_id = ?
            ORDER BY ordinal, chunk_id
            """.trimIndent(),
            arrayOf(ownerType, ownerId),
        )

    private fun mentionIds(database: SQLiteDatabase, termId: String): List<String> =
        database.stringColumn(
            "SELECT chunk_id FROM mentions WHERE term_id = ? ORDER BY mention_id",
            arrayOf(termId),
        )

    private fun findTermLabel(database: SQLiteDatabase, termId: String): TermSearchLabel =
        database.rawQuery("SELECT canonical_text, concept_key FROM terms WHERE term_id = ?", arrayOf(termId)).use { cursor ->
            require(cursor.moveToFirst()) { "术语检索索引引用了不存在的 term：$termId" }
            val value = WikiSourceSearch.truncateSnippet(cursor.requiredString(0, "canonical_text"))
            val conceptKey = cursor.requiredString(1, "concept_key")
            require(!cursor.moveToNext()) { "term_id 重复：$termId" }
            TermSearchLabel(canonicalText = value, conceptKey = conceptKey)
        }

    private fun temporalCandidates(database: SQLiteDatabase, query: String): List<WikiSearchCandidate> {
        val tokens = WikiSourceSearch.normalizedTokens(query).take(MAX_TEMPORAL_QUERY_TOKENS)
        if (tokens.isEmpty()) return emptyList()
        val owners = linkedSetOf<Pair<String, String>>()
        tokens.forEach { token ->
            database.rawQuery(
                """
                SELECT owner_type, owner_id FROM annotations
                WHERE kind = 'temporal' AND value_json LIKE ?
                ORDER BY owner_type, owner_id, annotation_id
                LIMIT ?
                """.trimIndent(),
                arrayOf("%$token%", WikiSourceSearch.MAX_CHANNEL_CANDIDATES.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    owners += cursor.requiredString(0, "owner_type") to cursor.requiredString(1, "owner_id")
                }
            }
        }
        return owners.flatMap { (ownerType, ownerId) ->
            evidenceIds(database, ownerType, ownerId).map { chunkId ->
                WikiSearchCandidate(chunkId, WikiSourceMatch(WikiSearchChannel.TEMPORAL, "时间注记"))
            }
        }
    }

    private fun loadChunk(database: SQLiteDatabase, chunkId: String): WikiChunk? =
        database.rawQuery(
            """
            SELECT c.chunk_id, c.section_id, c.ordinal, c.original_text, c.content_hash,
                l.locator_id, l.chunk_id, l.label, l.locator_json
            FROM chunks c
            LEFT JOIN source_locators l ON l.chunk_id = c.chunk_id
            WHERE c.chunk_id = ?
            ORDER BY l.locator_id
            LIMIT 1
            """.trimIndent(),
            arrayOf(chunkId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val locatorId = cursor.requiredString(5, "locator_id")
            val locatorChunkId = cursor.requiredString(6, "source_locators.chunk_id")
            val locatorLabel = cursor.requiredString(7, "source_locators.label")
            val locatorJson = cursor.requiredString(8, "source_locators.locator_json")
            require(locatorChunkId == chunkId && locatorLabel.isNotBlank()) { "chunk 缺少有效来源定位" }
            validateLocatorJson(locatorJson)
            WikiChunk(
                id = cursor.requiredString(0, "chunk_id"),
                sectionId = cursor.requiredString(1, "section_id"),
                ordinal = cursor.getInt(2),
                originalText = cursor.requiredString(3, "original_text"),
                locator = WikiSourceLocator(locatorId, locatorChunkId, locatorLabel, locatorJson),
                contentHash = cursor.requiredString(4, "content_hash"),
            )
        }

    private suspend fun markInvalid(ref: WikiRef, error: Exception) {
        val reason = error.message.orEmpty()
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(MAX_INVALID_REASON_LENGTH)
            .ifBlank { "无法读取内容数据库" }
        try {
            healthReporter.markInvalid(ref, reason)
        } catch (healthError: CancellationException) {
            throw healthError
        } catch (_: Exception) {
            // The original content read failure is still more useful to the caller.
        }
    }

    private fun validateLocatorJson(value: String) {
        JSONObject(value)
    }

    private fun validateRef(ref: WikiRef) {
        require(ref.version > 0 && WIKI_ID_PATTERN.matches(ref.wikiId)) { "Wiki 标识或版本无效" }
    }

    private fun validateOwner(ownerType: String, ownerId: String) {
        validateIdentifier(ownerType, "ownerType")
        validateIdentifier(ownerId, "ownerId")
    }

    private fun validateIdentifier(value: String, label: String) {
        require(value.isNotBlank() && value.length <= MAX_IDENTIFIER_LENGTH) { "$label 无效" }
    }

    private fun validatePageLimit(limit: Int) {
        require(limit in 1..MAX_WIKI_PAGE_SIZE) { "分页数量必须在 1 到 $MAX_WIKI_PAGE_SIZE 之间" }
    }

    private fun Cursor.toSection(): WikiSection = WikiSection(
        id = requiredString(0, "section_id"),
        documentId = requiredString(1, "document_id"),
        parentSectionId = if (isNull(2)) null else requiredString(2, "parent_section_id"),
        title = requiredString(3, "title"),
        path = requiredString(4, "path"),
        ordinal = getInt(5),
        metadataJson = requiredString(6, "metadata_json"),
    )

    private fun Cursor.toDocument(): WikiDocument = WikiDocument(
        id = requiredString(0, "document_id"),
        title = requiredString(1, "title"),
        responsibility = requiredString(2, "responsibility"),
        edition = requiredString(3, "edition"),
        language = requiredString(4, "language"),
        rights = requiredString(5, "rights"),
        sourceHash = requiredString(6, "source_hash"),
        ordinal = getInt(7),
        metadataJson = requiredString(8, "metadata_json"),
    )

    private fun Cursor.toTerm(): WikiTerm = WikiTerm(
        id = requiredString(0, "term_id"),
        conceptKey = requiredString(1, "concept_key"),
        canonicalText = requiredString(2, "canonical_text"),
        kind = requiredString(3, "kind"),
        confidence = getDouble(4),
        metadataJson = requiredString(5, "metadata_json"),
    )

    private fun Cursor.requiredString(index: Int, label: String): String {
        require(!isNull(index)) { "Wiki $label 为空" }
        return getString(index).orEmpty()
    }

    private fun Cursor.matchInfoScore(index: Int): Double {
        val bytes = getBlob(index) ?: return 0.0
        return WikiFtsScore.decode(bytes)
    }

    private fun SQLiteDatabase.singleString(query: String, label: String): String =
        rawQuery(query, null).use { cursor ->
            require(cursor.moveToFirst()) { "Wiki $label 未返回结果" }
            val value = cursor.requiredString(0, label)
            require(!cursor.moveToNext()) { "Wiki $label 返回多行" }
            value
        }

    private fun SQLiteDatabase.stringColumn(query: String, args: Array<String>): List<String> =
        rawQuery(query, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.requiredString(0, "查询值"))
            }
        }

    private data class ScoredFtsId(
        val id: String,
        val score: Double,
    )

    private data class TermSearchLabel(
        val canonicalText: String,
        val conceptKey: String,
    )

    private companion object {
        const val MAX_WIKI_PAGE_SIZE = 100
        const val MAX_TEMPORAL_QUERY_TOKENS = 12
        const val MAX_IDENTIFIER_LENGTH = 512
        const val MAX_INVALID_REASON_LENGTH = 240
        val WIKI_ID_PATTERN = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
    }
}

private object WikiFtsScore {
    fun decode(bytes: ByteArray): Double {
        if (bytes.size < 8 || bytes.size % Int.SIZE_BYTES != 0) return 0.0
        val values = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer()
        val phraseCount = values.get(0)
        val columnCount = values.get(1)
        if (phraseCount < 0 || columnCount < 0) return 0.0
        val expected = 2L + phraseCount.toLong() * columnCount * 3L
        if (expected != values.limit().toLong()) return 0.0
        var score = 0.0
        var offset = 2
        repeat(phraseCount) {
            repeat(columnCount) {
                val hitsInRow = values.get(offset)
                val rowsWithHits = values.get(offset + 2)
                if (hitsInRow > 0 && rowsWithHits >= 0) {
                    score += hitsInRow.toDouble() / (1.0 + rowsWithHits)
                }
                offset += 3
            }
        }
        return score
    }
}

private const val MAX_WIKI_PAGE_SIZE = 100
