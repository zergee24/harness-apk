package com.harnessapk.wiki

import android.database.sqlite.SQLiteDatabase
import com.harnessapk.packageformat.PublisherFingerprint
import com.harnessapk.packageformat.SignedPackageException
import com.harnessapk.packageformat.SignedPackagePolicy
import com.harnessapk.packageformat.SignedPackageVerifier
import com.harnessapk.packageformat.VerifiedPackageEntry
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

class WikiPackageReader(
    private val stagingDirectory: Path,
    private val signedPackageVerifier: SignedPackageVerifier = SignedPackageVerifier(),
    private val databaseInspector: WikiDatabaseInspector = AndroidWikiDatabaseInspector,
) {
    fun inspect(stagedArchive: Path): WikiImportInspection {
        val verified = try {
            signedPackageVerifier.verify(stagedArchive, WIKI_PACKAGE_POLICY)
        } catch (error: SignedPackageException) {
            throw WikiPackageException("知识库包校验失败：${error.message.orEmpty()}", error)
        }
        val manifest = WikiManifestParser.parse(verified.manifestBytes)
        val publisherFingerprint = verified.publisherFingerprint.withKeyId(manifest.publisherKeyId)
        validatePublisherKeyId(manifest.publisherKeyId, publisherFingerprint)

        val privateStaging = try {
            Files.createDirectories(stagingDirectory)
            Files.createTempDirectory(stagingDirectory, ".hwiki-inspect-")
        } catch (error: IOException) {
            throw WikiPackageException("无法创建知识库检查目录", error)
        }
        val stagedDatabase = privateStaging.resolve(CONTENT_DATABASE_PATH)
        try {
            val content = verified.payloads[CONTENT_DATABASE_PATH]
                ?: throw WikiPackageException("知识库包缺少 $CONTENT_DATABASE_PATH")
            val extracted = extractContentDatabase(stagedArchive, content, stagedDatabase)
            if (extracted.sha256 != content.sha256) {
                throw WikiPackageException("$CONTENT_DATABASE_PATH 在验证后发生变化")
            }
            if (extracted.sha256 != manifest.contentHash) {
                throw WikiPackageException("manifest.wiki.contentHash 与 $CONTENT_DATABASE_PATH 不匹配")
            }
            databaseInspector.inspect(stagedDatabase)
            return WikiImportInspection(
                manifest = manifest,
                publisherFingerprint = publisherFingerprint,
                archiveSizeBytes = verified.archiveSizeBytes,
                contentSizeBytes = extracted.bytes,
                stagedDatabase = stagedDatabase,
            )
        } catch (error: WikiPackageException) {
            privateStaging.toFile().deleteRecursively()
            throw error
        } catch (error: Exception) {
            privateStaging.toFile().deleteRecursively()
            throw WikiPackageException("知识库包检查失败：${error.message.orEmpty()}", error)
        }
    }

    private fun extractContentDatabase(
        archive: Path,
        expected: VerifiedPackageEntry,
        destination: Path,
    ): HashedContent {
        return try {
            ZipFile(archive.toFile()).use { zip ->
                val entry = zip.getEntry(CONTENT_DATABASE_PATH)
                    ?: throw WikiPackageException("知识库包缺少 $CONTENT_DATABASE_PATH")
                if (entry.size != expected.uncompressedSizeBytes) {
                    throw WikiPackageException("$CONTENT_DATABASE_PATH 声明大小在验证后发生变化")
                }
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                        input.copyAndHashTo(output, expected.uncompressedSizeBytes)
                    }
                }
            }
        } catch (error: WikiPackageException) {
            throw error
        } catch (error: IOException) {
            throw WikiPackageException("无法解压 $CONTENT_DATABASE_PATH", error)
        }
    }

    private fun InputStream.copyAndHashTo(
        output: java.io.OutputStream,
        expectedBytes: Long,
    ): HashedContent {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytes = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            bytes = try {
                Math.addExact(bytes, read.toLong())
            } catch (error: ArithmeticException) {
                throw WikiPackageException("$CONTENT_DATABASE_PATH 解压长度无效", error)
            }
            if (bytes > expectedBytes) {
                throw WikiPackageException("$CONTENT_DATABASE_PATH 解压长度超过声明值")
            }
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
        }
        if (bytes != expectedBytes) {
            throw WikiPackageException("$CONTENT_DATABASE_PATH 解压长度与声明值不一致")
        }
        return HashedContent(bytes, digest.digest().toHex())
    }

    private fun validatePublisherKeyId(keyId: String, fingerprint: PublisherFingerprint) {
        val expected = "ed25519:${fingerprint.hex}"
        if (keyId != expected) {
            throw WikiPackageException("publisher.keyId 与签名公钥指纹不匹配")
        }
    }

    private data class HashedContent(
        val bytes: Long,
        val sha256: String,
    )

    private fun PublisherFingerprint.withKeyId(keyId: String): PublisherFingerprint =
        copy(keyId = keyId)

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}

private object AndroidWikiDatabaseInspector : WikiDatabaseInspector {
    override fun inspect(stagedDatabase: Path) {
        val database = try {
            SQLiteDatabase.openDatabase(
                stagedDatabase.toString(),
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
        } catch (error: Exception) {
            throw WikiPackageException("无法以只读方式打开 $CONTENT_DATABASE_PATH", error)
        }
        try {
            validateWritableSchema(database)
            validateSchemaVersion(database)
            val ordinaryTables = validateSchemaObjects(database)
            validateIntegrity(database, ordinaryTables)
            validateForeignKeys(database)
        } finally {
            database.close()
        }
    }

    private fun validateWritableSchema(database: SQLiteDatabase) {
        if (database.singleLong("PRAGMA writable_schema", "writable_schema") != 0L) {
            throw WikiPackageException("SQLite writable_schema 必须关闭")
        }
    }

    private fun validateIntegrity(database: SQLiteDatabase, ordinaryTables: Set<String>) {
        // Android's FTS4 full-database integrity path writes to its inverted index even in read-only mode.
        (setOf("sqlite_schema") + ordinaryTables).sorted().forEach { table ->
            val results = database.stringRows("PRAGMA integrity_check(${table.sqliteIdentifier()})")
            if (results != listOf("ok")) {
                throw WikiPackageException(
                    "SQLite integrity_check 失败：$table: ${results.joinToString()}",
                )
            }
        }
    }

    private fun validateForeignKeys(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            if (cursor.moveToFirst()) {
                throw WikiPackageException("SQLite foreign_key_check 失败")
            }
        }
    }

    private fun validateSchemaVersion(database: SQLiteDatabase) {
        val actual = database.singleLong("PRAGMA user_version", "user_version")
        if (actual != CONTENT_SCHEMA_VERSION) {
            throw WikiPackageException("SQLite user_version 应为 $CONTENT_SCHEMA_VERSION，实际为 $actual")
        }
    }

    private fun validateSchemaObjects(database: SQLiteDatabase): Set<String> {
        val objects = database.schemaObjects()
        val tableNames = objects.filter { it.type == "table" }.mapTo(linkedSetOf(), SchemaObject::name)
        val missingTables = (REQUIRED_CORE_TABLES + REQUIRED_FTS_TABLES) - tableNames
        if (missingTables.isNotEmpty()) {
            throw WikiPackageException("SQLite 缺少必需表：${missingTables.sorted().joinToString()}")
        }
        val forbidden = objects.filter { it.type == "trigger" || it.type == "view" }
        if (forbidden.isNotEmpty()) {
            throw WikiPackageException("SQLite 包含禁止对象：${forbidden.map(SchemaObject::name).sorted().joinToString()}")
        }

        val virtualTables = objects.filter { it.type == "table" && it.sql.isVirtualTable() }
        val unknownVirtual = virtualTables.map(SchemaObject::name).toSet() - REQUIRED_FTS_TABLES
        if (unknownVirtual.isNotEmpty()) {
            throw WikiPackageException("SQLite 包含未知虚拟表：${unknownVirtual.sorted().joinToString()}")
        }
        REQUIRED_FTS_TABLES.forEach { name ->
            val table = virtualTables.singleOrNull { it.name == name }
                ?: throw WikiPackageException("SQLite 表 $name 必须是 FTS4 虚拟表")
            if (!table.sql.uppercase(Locale.ROOT).contains("USING FTS4")) {
                throw WikiPackageException("SQLite 表 $name 必须使用 FTS4")
            }
        }

        val ordinaryTableNames = objects
            .filter { it.type == "table" && !it.sql.isVirtualTable() }
            .map(SchemaObject::name)
            .toSet()
        val expectedOrdinaryTables = REQUIRED_CORE_TABLES + ALLOWED_FTS_BACKING_TABLES
        val missingOrdinaryTables = expectedOrdinaryTables - ordinaryTableNames
        val unexpectedOrdinaryTables = ordinaryTableNames - expectedOrdinaryTables
        if (missingOrdinaryTables.isNotEmpty() || unexpectedOrdinaryTables.isNotEmpty()) {
            val detail = buildList {
                if (missingOrdinaryTables.isNotEmpty()) {
                    add("缺少 ${missingOrdinaryTables.sorted().joinToString()}")
                }
                if (unexpectedOrdinaryTables.isNotEmpty()) {
                    add("新增 ${unexpectedOrdinaryTables.sorted().joinToString()}")
                }
            }.joinToString("；")
            throw WikiPackageException("SQLite 普通表结构不匹配：$detail")
        }

        val indexNames = objects.filter { it.type == "index" }.mapTo(linkedSetOf(), SchemaObject::name)
        val missingIndexes = REQUIRED_INDEXES - indexNames
        val unexpectedIndexes = indexNames - REQUIRED_INDEXES
        if (missingIndexes.isNotEmpty() || unexpectedIndexes.isNotEmpty()) {
            val detail = buildList {
                if (missingIndexes.isNotEmpty()) add("缺少 ${missingIndexes.sorted().joinToString()}")
                if (unexpectedIndexes.isNotEmpty()) add("新增 ${unexpectedIndexes.sorted().joinToString()}")
            }.joinToString("；")
            throw WikiPackageException("SQLite 索引结构不匹配：$detail")
        }
        val actualSignature = objects
            .filter { schemaObject ->
                schemaObject.type == "index" ||
                    schemaObject.type == "table"
            }
            .associate { schemaObject ->
                SchemaObjectKey(schemaObject.type, schemaObject.name) to schemaObject.sql.normalizeSchemaSql()
            }
        if (actualSignature != expectedSchemaSignature) {
            val missingObjects = expectedSchemaSignature.keys - actualSignature.keys
            val extraObjects = actualSignature.keys - expectedSchemaSignature.keys
            val changedObjects = actualSignature.keys.intersect(expectedSchemaSignature.keys)
                .filter { key -> actualSignature.getValue(key) != expectedSchemaSignature.getValue(key) }
            val detail = buildList {
                if (missingObjects.isNotEmpty()) add("缺少 ${missingObjects.displayNames()}")
                if (extraObjects.isNotEmpty()) add("新增 ${extraObjects.displayNames()}")
                if (changedObjects.isNotEmpty()) add("变更 ${changedObjects.displayNames()}")
            }.joinToString("；")
            throw WikiPackageException("SQLite 协议结构漂移：$detail")
        }
        return expectedOrdinaryTables
    }

    private fun SQLiteDatabase.singleLong(query: String, label: String): Long =
        rawQuery(query, null).use { cursor ->
            if (!cursor.moveToFirst()) throw WikiPackageException("SQLite $label 未返回结果")
            val value = cursor.getLong(0)
            if (cursor.moveToNext()) throw WikiPackageException("SQLite $label 返回多行")
            value
        }

    private fun SQLiteDatabase.stringRows(query: String): List<String> =
        rawQuery(query, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0).orEmpty())
            }
        }

    private fun SQLiteDatabase.schemaObjects(): List<SchemaObject> =
        rawQuery(
            "SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SchemaObject(
                            type = cursor.getString(0).orEmpty(),
                            name = cursor.getString(1).orEmpty(),
                            sql = if (cursor.isNull(2)) "" else cursor.getString(2).orEmpty(),
                        ),
                    )
                }
            }
        }

    private fun String.isVirtualTable(): Boolean =
        trimStart().uppercase(Locale.ROOT).startsWith("CREATE VIRTUAL TABLE")

    private fun String.sqliteIdentifier(): String =
        "\"${replace("\"", "\"\"")}\""

    private fun String.normalizeSchemaSql(): String =
        trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun Collection<SchemaObjectKey>.displayNames(): String =
        sortedWith(compareBy(SchemaObjectKey::type, SchemaObjectKey::name))
            .joinToString { key -> "${key.type}:${key.name}" }

    private fun schemaSignature(ddl: String): Map<SchemaObjectKey, String> =
        ddl.splitToSequence(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .associate { statement ->
                schemaObjectKey(statement) to statement.normalizeSchemaSql()
            }

    private fun schemaObjectKey(statement: String): SchemaObjectKey {
        val normalized = statement.normalizeSchemaSql()
        val typeAndName = when {
            normalized.startsWith("create virtual table ") -> "table" to normalized.removePrefix("create virtual table ")
            normalized.startsWith("create table ") -> "table" to normalized.removePrefix("create table ")
            normalized.startsWith("create index ") -> "index" to normalized.removePrefix("create index ")
            else -> throw IllegalStateException("不支持的 SQLite schema 声明：$statement")
        }
        return SchemaObjectKey(
            type = typeAndName.first,
            name = typeAndName.second
                .takeWhile { character -> !character.isWhitespace() && character != '(' }
                .removeSurrounding("'")
                .removeSurrounding("\"")
                .removeSurrounding("`"),
        )
    }

    private data class SchemaObject(
        val type: String,
        val name: String,
        val sql: String,
    )

    private data class SchemaObjectKey(
        val type: String,
        val name: String,
    )

    private val expectedSchemaSignature: Map<SchemaObjectKey, String> by lazy {
        schemaSignature(EXPECTED_SCHEMA_DDL) + expectedFtsBackingSchemaSignature()
    }

    private fun expectedFtsBackingSchemaSignature(): Map<SchemaObjectKey, String> =
        EXPECTED_FTS_BACKING_COLUMNS.flatMap { (ftsTable, columns) ->
            val contentColumns = columns.mapIndexed { index, column -> "'c${index}$column'" }.joinToString(", ")
            listOf(
                "CREATE TABLE '${ftsTable}_content'(docid INTEGER PRIMARY KEY, $contentColumns)",
                "CREATE TABLE '${ftsTable}_segments'(blockid INTEGER PRIMARY KEY, block BLOB)",
                "CREATE TABLE '${ftsTable}_segdir'(level INTEGER,idx INTEGER,start_block INTEGER,leaves_end_block INTEGER,end_block INTEGER,root BLOB,PRIMARY KEY(level, idx))",
                "CREATE TABLE '${ftsTable}_docsize'(docid INTEGER PRIMARY KEY, size BLOB)",
                "CREATE TABLE '${ftsTable}_stat'(id INTEGER PRIMARY KEY, value BLOB)",
            )
        }.associate { statement ->
            schemaObjectKey(statement) to statement.normalizeSchemaSql()
        }
}

private const val CONTENT_DATABASE_PATH = "content.sqlite"
private const val CONTENT_SCHEMA_VERSION = 1L
private const val MEBIBYTE = 1024L * 1024L
private const val GIBIBYTE = 1024L * MEBIBYTE
private val WIKI_PACKAGE_POLICY = SignedPackagePolicy(
    allowedPayloads = setOf("manifest.json", CONTENT_DATABASE_PATH),
    maxArchiveBytes = 4L * GIBIBYTE,
    maxExpandedBytes = 8L * GIBIBYTE + (3L * 4L * MEBIBYTE),
    maxEntryCount = 4,
    maxEntryBytes = 8L * GIBIBYTE,
    maxCompressionRatio = 100L,
    minCompressionRatioCheckBytes = 0L,
    maxManifestBytes = (4L * MEBIBYTE).toInt(),
    maxChecksumsBytes = (4L * MEBIBYTE).toInt(),
    maxSignatureBytes = (4L * MEBIBYTE).toInt(),
    packageLabel = "知识库包",
)
private val REQUIRED_CORE_TABLES = setOf(
    "documents",
    "sections",
    "chunks",
    "summaries",
    "terms",
    "aliases",
    "mentions",
    "annotations",
    "links",
    "evidence_refs",
    "source_locators",
    "build_metadata",
)
private val REQUIRED_FTS_TABLES = setOf(
    "chunks_original_fts",
    "chunks_normalized_fts",
    "summaries_fts",
    "terms_aliases_fts",
)
private val EXPECTED_FTS_BACKING_COLUMNS = linkedMapOf(
    "chunks_original_fts" to listOf("chunk_id", "original_text", "original_ngrams"),
    "chunks_normalized_fts" to listOf("chunk_id", "normalized_text", "normalized_ngrams"),
    "summaries_fts" to listOf("summary_id", "text"),
    "terms_aliases_fts" to listOf("owner_id", "canonical_text", "aliases_text"),
)
private val ALLOWED_FTS_BACKING_TABLES = REQUIRED_FTS_TABLES.flatMapTo(linkedSetOf()) { name ->
    listOf("${name}_content", "${name}_segments", "${name}_segdir", "${name}_docsize", "${name}_stat")
}
private val REQUIRED_INDEXES = setOf(
    "index_sections_document_ordinal",
    "index_sections_parent_ordinal",
    "index_chunks_section_ordinal",
    "index_terms_concept_key",
    "index_aliases_term_id",
    "index_mentions_term_id",
    "index_mentions_chunk_id",
    "index_annotations_owner",
    "index_annotations_kind",
    "index_links_source",
    "index_links_target",
    "index_evidence_refs_chunk_id",
    "index_source_locators_chunk_id",
)
private const val EXPECTED_SCHEMA_DDL = """
CREATE TABLE documents(
  document_id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  responsibility TEXT NOT NULL,
  edition TEXT NOT NULL,
  language TEXT NOT NULL,
  rights TEXT NOT NULL,
  source_hash TEXT NOT NULL,
  ordinal INTEGER NOT NULL,
  metadata_json TEXT NOT NULL
);
CREATE TABLE sections(
  section_id TEXT PRIMARY KEY,
  document_id TEXT NOT NULL REFERENCES documents(document_id),
  parent_section_id TEXT REFERENCES sections(section_id),
  title TEXT NOT NULL,
  path TEXT NOT NULL,
  ordinal INTEGER NOT NULL,
  metadata_json TEXT NOT NULL
);
CREATE TABLE chunks(
  chunk_id TEXT PRIMARY KEY,
  section_id TEXT NOT NULL REFERENCES sections(section_id),
  ordinal INTEGER NOT NULL,
  original_text TEXT NOT NULL,
  normalized_text TEXT NOT NULL,
  original_ngrams TEXT NOT NULL,
  normalized_ngrams TEXT NOT NULL,
  locator_json TEXT NOT NULL,
  content_hash TEXT NOT NULL
);
CREATE TABLE summaries(
  summary_id TEXT PRIMARY KEY,
  owner_type TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  level TEXT NOT NULL,
  text TEXT NOT NULL
);
CREATE TABLE terms(
  term_id TEXT PRIMARY KEY,
  concept_key TEXT NOT NULL,
  canonical_text TEXT NOT NULL,
  kind TEXT NOT NULL,
  confidence REAL NOT NULL,
  metadata_json TEXT NOT NULL
);
CREATE TABLE aliases(
  alias_id TEXT PRIMARY KEY,
  term_id TEXT NOT NULL REFERENCES terms(term_id),
  alias_text TEXT NOT NULL,
  normalized_alias TEXT NOT NULL,
  confidence REAL NOT NULL
);
CREATE TABLE mentions(
  mention_id TEXT PRIMARY KEY,
  term_id TEXT NOT NULL REFERENCES terms(term_id),
  chunk_id TEXT NOT NULL REFERENCES chunks(chunk_id),
  start_offset INTEGER NOT NULL,
  end_offset INTEGER NOT NULL,
  confidence REAL NOT NULL
);
CREATE TABLE annotations(
  annotation_id TEXT PRIMARY KEY,
  owner_type TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  value_json TEXT NOT NULL,
  confidence REAL NOT NULL
);
CREATE TABLE links(
  link_id TEXT PRIMARY KEY,
  source_type TEXT NOT NULL,
  source_id TEXT NOT NULL,
  target_namespace TEXT NOT NULL,
  target_type TEXT NOT NULL,
  target_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  confidence REAL NOT NULL,
  metadata_json TEXT NOT NULL
);
CREATE TABLE evidence_refs(
  owner_type TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  chunk_id TEXT NOT NULL REFERENCES chunks(chunk_id),
  role TEXT NOT NULL,
  ordinal INTEGER NOT NULL,
  PRIMARY KEY(owner_type, owner_id, chunk_id)
);
CREATE TABLE source_locators(
  locator_id TEXT PRIMARY KEY,
  chunk_id TEXT NOT NULL REFERENCES chunks(chunk_id),
  label TEXT NOT NULL,
  locator_json TEXT NOT NULL
);
CREATE TABLE build_metadata(
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE INDEX index_sections_document_ordinal
  ON sections(document_id, ordinal);
CREATE INDEX index_sections_parent_ordinal
  ON sections(parent_section_id, ordinal);
CREATE INDEX index_chunks_section_ordinal
  ON chunks(section_id, ordinal);
CREATE INDEX index_terms_concept_key
  ON terms(concept_key);
CREATE INDEX index_aliases_term_id
  ON aliases(term_id);
CREATE INDEX index_mentions_term_id
  ON mentions(term_id);
CREATE INDEX index_mentions_chunk_id
  ON mentions(chunk_id);
CREATE INDEX index_annotations_owner
  ON annotations(owner_type, owner_id);
CREATE INDEX index_annotations_kind
  ON annotations(kind);
CREATE INDEX index_links_source
  ON links(source_type, source_id);
CREATE INDEX index_links_target
  ON links(target_namespace, target_id);
CREATE INDEX index_evidence_refs_chunk_id
  ON evidence_refs(chunk_id);
CREATE INDEX index_source_locators_chunk_id
  ON source_locators(chunk_id);

CREATE VIRTUAL TABLE chunks_original_fts
  USING FTS4(chunk_id, original_text, original_ngrams, tokenize=unicode61);
CREATE VIRTUAL TABLE chunks_normalized_fts
  USING FTS4(chunk_id, normalized_text, normalized_ngrams, tokenize=unicode61);
CREATE VIRTUAL TABLE summaries_fts
  USING FTS4(summary_id, text, tokenize=unicode61);
CREATE VIRTUAL TABLE terms_aliases_fts
  USING FTS4(owner_id, canonical_text, aliases_text, tokenize=unicode61);
"""
