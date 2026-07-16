package com.harnessapk.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

interface AgentBundleAccess {
    fun inspect(file: File): AgentImportPreview
    fun read(file: File): ParsedAgentBundle
    suspend fun forEachChunkSuspending(
        bundle: ParsedAgentBundle,
        corpus: AgentCorpusManifest,
        block: suspend (AgentCorpusChunk) -> Unit,
    )
}

class AgentBundleReader(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val signatureVerifier: AgentSignatureVerifier = PortableEd25519Verifier,
) : AgentBundleAccess {
    override fun inspect(file: File): AgentImportPreview {
        val parsed = read(file)
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

    override fun read(file: File): ParsedAgentBundle {
        if (!file.isFile) throw AgentBundleException("智能体包不存在")
        if (file.length() > MAX_COMPRESSED_BYTES) throw AgentBundleException("智能体包超过 2 GiB 上限")
        try {
            rejectUnixSymlinkEntries(file)
            return ZipFile(file).use { archive ->
                val entries = validateEntries(archive)
                val checksumsBytes = archive.readRequired("checksums.json", MAX_CHECKSUMS_BYTES)
                val signatureBytes = archive.readRequired("signature.json", MAX_SIGNATURE_BYTES)
                val checksumMap = parseChecksums(checksumsBytes)
                val signatureRecord = parseSignature(signatureBytes)
                verifySignature(signatureRecord, checksumsBytes)
                verifyChecksums(archive, entries, checksumMap)
                val manifestBytes = archive.readRequired("bundle-manifest.json", MAX_MANIFEST_BYTES)
                val manifest = parseManifest(manifestBytes)
                val persona = archive.readRequired(manifest.agent.personaPath, MAX_PERSONA_BYTES).decodeToString().trim()
                if (persona.isBlank()) throw AgentBundleException("persona.md 不能为空")
                val worldview = archive.readRequired(
                    manifest.agent.worldviewPath,
                    MAX_WORLDVIEW_BYTES,
                ).decodeToString()
                val uncompressedSize = entries.sumOf { entry -> entry.size.coerceAtLeast(0L) }
                ParsedAgentBundle(
                    file = file,
                    packageSha256 = file.sha256(),
                    publisherPublicKey = signatureRecord.publicKey,
                    publisherFingerprint = signatureRecord.publicKey.sha256(),
                    manifestJson = manifestBytes.decodeToString(),
                    agent = manifest.agent,
                    corpora = manifest.corpora,
                    persona = persona,
                    worldviewJsonl = worldview,
                    compressedSizeBytes = file.length(),
                    uncompressedSizeBytes = uncompressedSize,
                )
            }
        } catch (error: AgentBundleException) {
            throw error
        } catch (error: Throwable) {
            throw AgentBundleException("智能体包读取失败：${error.message.orEmpty()}", error)
        }
    }

    fun forEachChunk(
        bundle: ParsedAgentBundle,
        corpus: AgentCorpusManifest,
        block: (AgentCorpusChunk) -> Unit,
    ) {
        if (corpus !in bundle.corpora) throw AgentBundleException("资料包不属于当前智能体")
        ZipFile(bundle.file).use { archive ->
            val entry = archive.getEntry(corpus.chunksPath)
                ?: throw AgentBundleException("缺少资料块文件：${corpus.chunksPath}")
            archive.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter(String::isNotBlank).forEachIndexed { index, line ->
                    if (line.length > MAX_JSONL_LINE_CHARS) {
                        throw AgentBundleException("资料块第 ${index + 1} 行超过大小上限")
                    }
                    block(parseChunk(line, corpus, index + 1))
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
        ZipFile(bundle.file).use { archive ->
            val entry = archive.getEntry(corpus.chunksPath)
                ?: throw AgentBundleException("缺少资料块文件：${corpus.chunksPath}")
            archive.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { lines ->
                var lineNumber = 0
                while (true) {
                    val line = lines.readLine() ?: break
                    lineNumber += 1
                    if (line.isBlank()) continue
                    if (line.length > MAX_JSONL_LINE_CHARS) {
                        throw AgentBundleException("资料块第 $lineNumber 行超过大小上限")
                    }
                    block(parseChunk(line, corpus, lineNumber))
                }
            }
        }
    }

    private fun validateEntries(archive: ZipFile): List<ZipEntry> {
        val entries = archive.entries().asSequence().toList()
        if (entries.size > MAX_ENTRY_COUNT) throw AgentBundleException("包内条目超过 50,000 个")
        val names = mutableSetOf<String>()
        var declaredSize = 0L
        entries.forEach { entry ->
            val name = safePath(entry.name)
            if (!names.add(name)) throw AgentBundleException("包内存在重复条目：$name")
            if (entry.isDirectory) return@forEach
            if (!isAllowedFile(name)) throw AgentBundleException("不允许的包内文件：$name")
            if (entry.size > MAX_UNCOMPRESSED_BYTES) throw AgentBundleException("包内文件超过大小上限：$name")
            if (entry.size > 0) {
                declaredSize += entry.size
                if (declaredSize > MAX_UNCOMPRESSED_BYTES) {
                    throw AgentBundleException("声明的解压总量超过 4 GiB")
                }
            }
        }
        REQUIRED_ROOT_ENTRIES.forEach { required ->
            if (required !in names) throw AgentBundleException("缺少包内文件：$required")
        }
        return entries.filterNot(ZipEntry::isDirectory)
    }

    private fun rejectUnixSymlinkEntries(file: File) {
        RandomAccessFile(file, "r").use { archive ->
            val tailSize = minOf(archive.length(), MAX_EOCD_SEARCH_BYTES.toLong()).toInt()
            val tail = ByteArray(tailSize)
            archive.seek(archive.length() - tailSize)
            archive.readFully(tail)
            val eocdOffset = tail.findSignatureBackwards(END_OF_CENTRAL_DIRECTORY_SIGNATURE)
            if (eocdOffset < 0) throw AgentBundleException("ZIP central directory 无效")
            val entryCount = tail.readUnsignedShortLe(eocdOffset + 10)
            val centralOffset = tail.readUnsignedIntLe(eocdOffset + 16)
            if (entryCount == ZIP64_ENTRY_SENTINEL || centralOffset == ZIP64_OFFSET_SENTINEL) {
                throw AgentBundleException("不支持 ZIP64 智能体包")
            }
            archive.seek(centralOffset)
            repeat(entryCount) {
                val header = ByteArray(CENTRAL_DIRECTORY_HEADER_SIZE)
                archive.readFully(header)
                if (header.readIntLe(0) != CENTRAL_DIRECTORY_SIGNATURE) {
                    throw AgentBundleException("ZIP central directory 条目无效")
                }
                val hostSystem = header.readUnsignedShortLe(4) ushr 8
                val fileNameLength = header.readUnsignedShortLe(28)
                val extraLength = header.readUnsignedShortLe(30)
                val commentLength = header.readUnsignedShortLe(32)
                val externalAttributes = header.readUnsignedIntLe(38)
                val fileNameBytes = ByteArray(fileNameLength)
                archive.readFully(fileNameBytes)
                val fileName = fileNameBytes.decodeToString()
                val unixMode = (externalAttributes ushr 16).toInt()
                if (hostSystem == UNIX_HOST_SYSTEM && unixMode and UNIX_FILE_TYPE_MASK == UNIX_SYMLINK_TYPE) {
                    throw AgentBundleException("智能体包不允许符号链接：$fileName")
                }
                archive.seek(archive.filePointer + extraLength + commentLength)
            }
        }
    }

    private fun verifyChecksums(
        archive: ZipFile,
        entries: List<ZipEntry>,
        checksums: Map<String, String>,
    ) {
        val contentEntries = entries.filterNot { it.name in UNSIGNED_METADATA_ENTRIES }
        val contentNames = contentEntries.mapTo(mutableSetOf(), ZipEntry::getName)
        val undeclared = contentNames - checksums.keys
        val missing = checksums.keys - contentNames
        if (undeclared.isNotEmpty()) throw AgentBundleException("存在未声明 SHA-256 的文件：${undeclared.first()}")
        if (missing.isNotEmpty()) throw AgentBundleException("checksums.json 引用了不存在的文件：${missing.first()}")
        contentEntries.forEach { entry ->
            val actual = archive.getInputStream(entry).use(InputStream::sha256)
            val expected = checksums.getValue(entry.name)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw AgentBundleException("SHA-256 校验失败：${entry.name}")
            }
        }
    }

    private fun verifySignature(record: SignatureRecord, payload: ByteArray) {
        if (record.algorithm != "Ed25519" || record.signedFile != "checksums.json") {
            throw AgentBundleException("不支持的签名声明")
        }
        if (!signatureVerifier.verify(record.publicKey, payload, record.signature)) {
            throw AgentBundleException("智能体包签名校验失败")
        }
    }

    private fun parseChecksums(payload: ByteArray): Map<String, String> {
        val root = json.parseToJsonElement(payload.decodeToString()).jsonObject
        val files = root["files"]?.jsonObject ?: throw AgentBundleException("checksums.json 缺少 files")
        return files.mapValues { (path, value) ->
            safePath(path)
            value.jsonPrimitive.content
        }
    }

    private fun parseSignature(payload: ByteArray): SignatureRecord {
        val root = json.parseToJsonElement(payload.decodeToString()).jsonObject
        return try {
            SignatureRecord(
                algorithm = root.requiredString("algorithm"),
                publicKey = Base64.getDecoder().decode(root.requiredString("publicKey")),
                signature = Base64.getDecoder().decode(root.requiredString("signature")),
                signedFile = root.requiredString("signedFile"),
            )
        } catch (error: IllegalArgumentException) {
            throw AgentBundleException("signature.json 格式无效", error)
        }
    }

    private fun parseManifest(payload: ByteArray): BundleManifest {
        val root = json.parseToJsonElement(payload.decodeToString()).jsonObject
        val schemaVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw AgentBundleException("不支持的 schemaVersion：$schemaVersion")
        }
        val agentObject = root["agent"]?.jsonObject ?: throw AgentBundleException("manifest 缺少 agent")
        val agent = AgentPackageManifest(
            id = agentObject.requiredString("id"),
            name = agentObject.requiredString("name"),
            version = agentObject.requiredPositiveInt("version"),
            summary = agentObject.optionalString("summary"),
            personaPath = safePath(agentObject.requiredString("personaPath")),
            worldviewPath = safePath(agentObject.requiredString("worldviewPath")),
            conceptsPath = safePath(agentObject.requiredString("conceptsPath")),
            examplesPath = safePath(agentObject.requiredString("examplesPath")),
            evalPath = safePath(agentObject.requiredString("evalPath")),
            requiredCorpora = agentObject.stringList("requiredCorpora"),
        )
        val corpora = root["corpora"]?.jsonArray?.map { element ->
            val corpus = element.jsonObject
            AgentCorpusManifest(
                id = corpus.requiredString("id"),
                title = corpus.requiredString("title"),
                sourceHash = corpus.requiredString("sourceHash"),
                sourcesPath = safePath(corpus.requiredString("sourcesPath")),
                chunksPath = safePath(corpus.requiredString("chunksPath")),
                required = corpus["required"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }.orEmpty()
        if (corpora.map(AgentCorpusManifest::id).toSet().size != corpora.size) {
            throw AgentBundleException("manifest 包含重复 corpus id")
        }
        return BundleManifest(agent, corpora)
    }

    private fun parseChunk(line: String, corpus: AgentCorpusManifest, lineNumber: Int): AgentCorpusChunk {
        try {
            val row = json.parseToJsonElement(line).jsonObject
            return AgentCorpusChunk(
                id = row.requiredString("id"),
                sourceTitle = row.requiredString("sourceTitle"),
                sourceHash = row.optionalString("sourceHash").ifBlank { corpus.sourceHash },
                location = row.optionalString("location"),
                text = row.requiredString("text"),
                keywords = row.stringList("keywords"),
                ngrams = row.stringList("ngrams"),
            )
        } catch (error: Throwable) {
            if (error is AgentBundleException) throw error
            throw AgentBundleException("资料块第 $lineNumber 行格式无效", error)
        }
    }

    private fun ZipFile.readRequired(name: String, maxBytes: Int): ByteArray {
        val safeName = safePath(name)
        val entry = getEntry(safeName) ?: throw AgentBundleException("缺少包内文件：$safeName")
        if (entry.size > maxBytes) throw AgentBundleException("包内文件超过读取上限：$safeName")
        return getInputStream(entry).use { input ->
            input.readBytesLimited(maxBytes, safeName)
        }
    }

    companion object {
        private const val SUPPORTED_SCHEMA_VERSION = 1
        private const val MAX_ENTRY_COUNT = 50_000
        private const val MAX_COMPRESSED_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_UNCOMPRESSED_BYTES = 4L * 1024 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        private const val MAX_CHECKSUMS_BYTES = 16 * 1024 * 1024
        private const val MAX_SIGNATURE_BYTES = 64 * 1024
        private const val MAX_PERSONA_BYTES = 2 * 1024 * 1024
        private const val MAX_WORLDVIEW_BYTES = 32 * 1024 * 1024
        private const val MAX_JSONL_LINE_CHARS = 4 * 1024 * 1024
        private const val MAX_EOCD_SEARCH_BYTES = 65_557
        private const val CENTRAL_DIRECTORY_HEADER_SIZE = 46
        private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50
        private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50
        private const val ZIP64_ENTRY_SENTINEL = 0xffff
        private const val ZIP64_OFFSET_SENTINEL = 0xffffffffL
        private const val UNIX_HOST_SYSTEM = 3
        private const val UNIX_FILE_TYPE_MASK = 0xf000
        private const val UNIX_SYMLINK_TYPE = 0xa000
        private val REQUIRED_ROOT_ENTRIES = setOf("bundle-manifest.json", "checksums.json", "signature.json")
        private val UNSIGNED_METADATA_ENTRIES = setOf("checksums.json", "signature.json")
        private val DECLARATIVE_SUFFIXES = setOf("json", "jsonl", "md", "txt")
        private val SOURCE_SUFFIXES = DECLARATIVE_SUFFIXES + setOf("pdf", "epub")
    }
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

private data class SignatureRecord(
    val algorithm: String,
    val publicKey: ByteArray,
    val signature: ByteArray,
    val signedFile: String,
)

private data class BundleManifest(
    val agent: AgentPackageManifest,
    val corpora: List<AgentCorpusManifest>,
)

private fun safePath(value: String): String {
    val normalized = value.replace('\\', '/')
    val parts = normalized.split('/')
    if (
        normalized.isBlank() ||
        normalized.startsWith('/') ||
        Regex("^[A-Za-z]:/").containsMatchIn(normalized) ||
        parts.any { it.isBlank() || it == "." || it == ".." }
    ) {
        throw AgentBundleException("不安全的包内路径：$value")
    }
    return normalized
}

private fun isAllowedFile(path: String): Boolean {
    if (path in setOf("bundle-manifest.json", "checksums.json", "signature.json")) return true
    val suffix = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when {
        path.startsWith("agent/") -> suffix in setOf("json", "jsonl", "md", "txt")
        path.startsWith("corpora/") -> suffix in setOf("json", "jsonl", "md", "txt")
        path.startsWith("sources/") -> suffix in setOf("json", "jsonl", "md", "txt", "pdf", "epub")
        else -> false
    }
}

private fun JsonObject.requiredString(key: String): String =
    optionalString(key).ifBlank { throw AgentBundleException("manifest 缺少 $key") }

private fun JsonObject.optionalString(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty().trim()

private fun JsonObject.requiredPositiveInt(key: String): Int =
    this[key]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
        ?: throw AgentBundleException("manifest 的 $key 必须大于 0")

private fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.ifBlank { null } }.orEmpty()

private fun InputStream.readBytesLimited(maxBytes: Int, name: String): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw AgentBundleException("包内文件超过读取上限：$name")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
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
