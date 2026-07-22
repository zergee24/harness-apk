package com.harnessapk.packageformat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class SignedPackageVerifier(
    private val signatureVerifier: Ed25519SignatureVerifier = PortableEd25519SignatureVerifier,
) {
    fun verify(stagedArchive: Path, policy: SignedPackagePolicy): VerifiedPackage =
        try {
            verifyInternal(stagedArchive, policy.normalized())
        } catch (error: SignedPackageException) {
            throw error
        } catch (error: IOException) {
            throw SignedPackageException("签名包验证失败：${error.message.orEmpty()}", error)
        }

    private fun verifyInternal(stagedArchive: Path, policy: NormalizedPolicy): VerifiedPackage {
        if (!Files.isRegularFile(stagedArchive, LinkOption.NOFOLLOW_LINKS)) {
            throw SignedPackageException("${policy.packageLabel}不存在或不是普通文件")
        }
        val archiveSize = Files.size(stagedArchive)
        if (archiveSize > policy.maxArchiveBytes) {
            throw SignedPackageException("${policy.packageLabel}超过压缩大小上限")
        }

        val file = stagedArchive.toFile()
        validateCentralDirectorySafety(file, policy)
        return ZipFile(file).use { archive ->
            val entries = archive.entries().asSequence().toList()
            val payloadEntries = validateEntries(entries, policy)
            val checksumsEntry = requireEntry(entries, CHECKSUMS_PATH)
            val signatureEntry = requireEntry(entries, SIGNATURE_PATH)
            val checksumsBytes = archive.readBounded(checksumsEntry, policy.maxChecksumsBytes, CHECKSUMS_PATH)
            val signatureBytes = archive.readBounded(signatureEntry, policy.maxSignatureBytes, SIGNATURE_PATH)
            val checksums = parseCanonicalChecksums(checksumsBytes)
            val signature = parseSignature(signatureBytes)
            val publicKey = verifySignature(signature, checksumsBytes, policy)

            val payloadNames = payloadEntries.keys
            val undeclared = payloadNames - checksums.keys
            val missing = checksums.keys - payloadNames
            if (undeclared.isNotEmpty()) {
                throw SignedPackageException("存在未声明 SHA-256 的文件：${undeclared.first()}")
            }
            if (missing.isNotEmpty()) {
                throw SignedPackageException("checksums.json 引用了不存在的文件：${missing.first()}")
            }

            val verifiedPayloads = linkedMapOf<String, VerifiedPackageEntry>()
            payloadEntries.forEach { (path, entry) ->
                val actual = archive.getInputStream(entry).use { input ->
                    input.sha256AndLength(
                        path = path,
                        expectedBytes = entry.size,
                        maxBytes = policy.maxEntryBytes,
                        payloadLimit = policy.payloadSizeLimitsByPath[path],
                    )
                }
                if (actual.sha256 != checksums.getValue(path)) {
                    throw SignedPackageException("SHA-256 校验失败：$path")
                }
                verifiedPayloads[path] = VerifiedPackageEntry(
                    path = path,
                    compressedSizeBytes = entry.compressedSize,
                    uncompressedSizeBytes = actual.bytes,
                    sha256 = actual.sha256,
                )
            }

            val manifestEntry = payloadEntries[policy.manifestPath]
                ?: throw SignedPackageException("缺少包内文件：${policy.manifestPath}")
            val manifestBytes = archive.readBounded(manifestEntry, policy.maxManifestBytes, policy.manifestPath)
            if (Files.size(stagedArchive) != archiveSize) {
                throw SignedPackageException("${policy.packageLabel}在验证期间发生变化")
            }
            VerifiedPackage(
                stagedArchive = stagedArchive,
                archiveSizeBytes = archiveSize,
                expandedSizeBytes = entries.sumOf(ZipEntry::getSize),
                manifestBytes = manifestBytes,
                payloads = verifiedPayloads,
                publisherFingerprint = PublisherFingerprint(
                    algorithm = "Ed25519",
                    hex = publicKey.sha256(),
                ),
                publisherPublicKey = publicKey,
            )
        }
    }

    private fun validateCentralDirectorySafety(file: File, policy: NormalizedPolicy) {
        try {
            RandomAccessFile(file, "r").use { archive ->
                val directory = archive.readCentralDirectory(policy.packageLabel)
                if (directory.entryCount > policy.maxEntryCount.toLong()) {
                    throw SignedPackageException("${policy.packageLabel}内条目超过 ${policy.maxEntryCount} 个")
                }
                archive.validateCentralEntries(directory, policy.packageLabel)
            }
        } catch (error: SignedPackageException) {
            throw error
        } catch (error: IOException) {
            throw SignedPackageException("${policy.packageLabel} ZIP central directory 无效", error)
        }
    }

    private fun RandomAccessFile.readCentralDirectory(packageLabel: String): CentralDirectoryInfo {
        val archiveLength = length()
        val tailSize = minOf(archiveLength, MAX_EOCD_SEARCH_BYTES.toLong()).toInt()
        if (tailSize < END_OF_CENTRAL_DIRECTORY_SIZE) {
            throw SignedPackageException("$packageLabel ZIP central directory 无效")
        }
        val tailStart = archiveLength - tailSize
        val tail = ByteArray(tailSize)
        seek(tailStart)
        readFully(tail)
        val candidates = (0..tail.size - END_OF_CENTRAL_DIRECTORY_SIZE).filter { offset ->
            tail.readUnsignedIntLe(offset) == END_OF_CENTRAL_DIRECTORY_SIGNATURE &&
                offset + END_OF_CENTRAL_DIRECTORY_SIZE + tail.readUnsignedShortLe(offset + 20) == tail.size
        }
        if (candidates.size != 1) {
            throw SignedPackageException("$packageLabel ZIP central directory EOCD/comment length 无效")
        }
        val eocdOffset = candidates.single()
        val eocdAbsoluteOffset = tailStart + eocdOffset
        val diskNumber = tail.readUnsignedShortLe(eocdOffset + 4)
        val centralDisk = tail.readUnsignedShortLe(eocdOffset + 6)
        val entriesOnDisk = tail.readUnsignedShortLe(eocdOffset + 8)
        val entryCount = tail.readUnsignedShortLe(eocdOffset + 10).toLong()
        val centralSize = tail.readUnsignedIntLe(eocdOffset + 12)
        val centralOffset = tail.readUnsignedIntLe(eocdOffset + 16)
        val needsZip64 = entryCount == ZIP64_ENTRY_SENTINEL ||
            centralSize == ZIP64_OFFSET_SENTINEL ||
            centralOffset == ZIP64_OFFSET_SENTINEL
        if (!needsZip64) {
            if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk.toLong() != entryCount) {
                throw SignedPackageException("$packageLabel 不支持分卷 ZIP")
            }
            return centralDirectoryInfo(
                entryCount = entryCount,
                centralOffset = centralOffset,
                centralSize = centralSize,
                trailerOffset = eocdAbsoluteOffset,
                packageLabel = packageLabel,
            )
        }
        return readZip64CentralDirectory(
            eocdAbsoluteOffset = eocdAbsoluteOffset,
            archiveLength = archiveLength,
            packageLabel = packageLabel,
        )
    }

    private fun RandomAccessFile.readZip64CentralDirectory(
        eocdAbsoluteOffset: Long,
        archiveLength: Long,
        packageLabel: String,
    ): CentralDirectoryInfo {
        val locatorOffset = eocdAbsoluteOffset - ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE
        if (locatorOffset < 0) throw SignedPackageException("$packageLabel ZIP64 locator 无效")
        val locator = readBytesAt(locatorOffset, ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE, packageLabel)
        if (locator.readUnsignedIntLe(0) != ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE) {
            throw SignedPackageException("$packageLabel ZIP64 locator 无效")
        }
        if (locator.readUnsignedIntLe(4) != 0L || locator.readUnsignedIntLe(16) != 1L) {
            throw SignedPackageException("$packageLabel 不支持分卷 ZIP")
        }
        val zip64EocdOffset = locator.readLongLe(8)
        if (zip64EocdOffset < 0) throw SignedPackageException("$packageLabel ZIP64 offset 无效")
        val fixedRecord = readBytesAt(zip64EocdOffset, ZIP64_END_OF_CENTRAL_DIRECTORY_MIN_SIZE, packageLabel)
        if (fixedRecord.readUnsignedIntLe(0) != ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
            throw SignedPackageException("$packageLabel ZIP64 central directory 无效")
        }
        val recordSize = fixedRecord.readLongLe(4)
        if (recordSize < ZIP64_END_OF_CENTRAL_DIRECTORY_MIN_RECORD_SIZE) {
            throw SignedPackageException("$packageLabel ZIP64 central directory 无效")
        }
        val recordEnd = checkedRangeEnd(
            start = zip64EocdOffset,
            length = checkedAdd(12L, recordSize, packageLabel),
            limit = locatorOffset,
            packageLabel = packageLabel,
        )
        if (recordEnd != locatorOffset || recordEnd > archiveLength) {
            throw SignedPackageException("$packageLabel ZIP64 central directory 边界无效")
        }
        val diskNumber = fixedRecord.readUnsignedIntLe(16)
        val centralDisk = fixedRecord.readUnsignedIntLe(20)
        val entriesOnDisk = fixedRecord.readLongLe(24)
        val entryCount = fixedRecord.readLongLe(32)
        val centralSize = fixedRecord.readLongLe(40)
        val centralOffset = fixedRecord.readLongLe(48)
        if (
            diskNumber != 0L || centralDisk != 0L || entriesOnDisk < 0 || entryCount < 0 ||
            centralSize < 0 || centralOffset < 0 || entriesOnDisk != entryCount
        ) {
            throw SignedPackageException("$packageLabel ZIP64 central directory 无效")
        }
        return centralDirectoryInfo(
            entryCount = entryCount,
            centralOffset = centralOffset,
            centralSize = centralSize,
            trailerOffset = zip64EocdOffset,
            packageLabel = packageLabel,
        )
    }

    private fun RandomAccessFile.validateCentralEntries(
        directory: CentralDirectoryInfo,
        packageLabel: String,
    ) {
        seek(directory.offset)
        repeat(directory.entryCount.toInt()) { index ->
            if (filePointer > directory.end - CENTRAL_DIRECTORY_HEADER_SIZE) {
                throw SignedPackageException("$packageLabel ZIP central directory 条目边界无效")
            }
            val header = ByteArray(CENTRAL_DIRECTORY_HEADER_SIZE)
            readFully(header)
            if (header.readUnsignedIntLe(0) != CENTRAL_DIRECTORY_SIGNATURE) {
                throw SignedPackageException("$packageLabel ZIP central directory 条目无效")
            }
            val hostSystem = header.readUnsignedShortLe(4) ushr 8
            val flags = header.readUnsignedShortLe(8)
            val nameLength = header.readUnsignedShortLe(28)
            val extraLength = header.readUnsignedShortLe(30)
            val commentLength = header.readUnsignedShortLe(32)
            val externalAttributes = header.readUnsignedIntLe(38)
            val variableLength = nameLength.toLong() + extraLength + commentLength
            val nextEntry = checkedRangeEnd(filePointer, variableLength, directory.end, packageLabel)
            val nameBytes = ByteArray(nameLength)
            readFully(nameBytes)
            val name = nameBytes.decodeToString().ifBlank { "#${index + 1}" }
            if (flags and ENCRYPTED_FLAG != 0) {
                throw SignedPackageException("$packageLabel 不允许加密条目：$name")
            }
            validateUnixMode(hostSystem, externalAttributes, name, packageLabel)
            seek(nextEntry)
        }
        if (filePointer != directory.end) {
            throw SignedPackageException("$packageLabel ZIP central directory size 与 entries 边界不一致")
        }
    }

    private fun centralDirectoryInfo(
        entryCount: Long,
        centralOffset: Long,
        centralSize: Long,
        trailerOffset: Long,
        packageLabel: String,
    ): CentralDirectoryInfo {
        if (entryCount < 0 || centralOffset < 0 || centralSize < 0) {
            throw SignedPackageException("$packageLabel ZIP central directory 无效")
        }
        val centralEnd = checkedRangeEnd(centralOffset, centralSize, trailerOffset, packageLabel)
        if (centralEnd != trailerOffset) {
            throw SignedPackageException("$packageLabel ZIP central directory offset/size/end 无效")
        }
        return CentralDirectoryInfo(entryCount, centralOffset, centralEnd)
    }

    private fun RandomAccessFile.readBytesAt(offset: Long, size: Int, packageLabel: String): ByteArray {
        if (offset < 0 || size < 0 || offset > length() - size) {
            throw SignedPackageException("$packageLabel ZIP central directory 边界无效")
        }
        seek(offset)
        return ByteArray(size).also(::readFully)
    }

    private fun checkedRangeEnd(start: Long, length: Long, limit: Long, packageLabel: String): Long {
        if (start < 0 || length < 0 || limit < 0 || start > limit || length > limit - start) {
            throw SignedPackageException("$packageLabel ZIP central directory 边界无效")
        }
        return start + length
    }

    private fun checkedAdd(left: Long, right: Long, packageLabel: String): Long =
        try {
            Math.addExact(left, right)
        } catch (error: ArithmeticException) {
            throw SignedPackageException("$packageLabel ZIP central directory 大小无效", error)
        }

    private fun validateUnixMode(hostSystem: Int, externalAttributes: Long, fileName: String, packageLabel: String) {
        if (hostSystem != UNIX_HOST_SYSTEM) return
        val mode = (externalAttributes ushr 16).toInt()
        val type = mode and UNIX_FILE_TYPE_MASK
        if (type != 0 && type != UNIX_REGULAR_FILE_TYPE) {
            val label = if (type == UNIX_SYMLINK_TYPE) "符号链接" else "特殊文件"
            throw SignedPackageException("$packageLabel 不允许$label：$fileName")
        }
        if (mode and UNIX_EXECUTABLE_MASK != 0) {
            throw SignedPackageException("$packageLabel 不允许可执行文件：$fileName")
        }
    }

    private fun validateEntries(entries: List<ZipEntry>, policy: NormalizedPolicy): LinkedHashMap<String, ZipEntry> {
        if (entries.isEmpty()) throw SignedPackageException("${policy.packageLabel}为空")
        if (entries.size > policy.maxEntryCount) {
            throw SignedPackageException("包内条目超过 ${policy.maxEntryCount} 个")
        }
        val entriesByPath = linkedMapOf<String, ZipEntry>()
        var expandedBytes = 0L
        entries.forEach { entry ->
            val path = canonicalPackagePath(entry.name)
            if (entry.isDirectory || path.endsWith('/')) {
                throw SignedPackageException("${policy.packageLabel}不允许目录条目：$path")
            }
            if (entriesByPath.put(path, entry) != null) {
                throw SignedPackageException("包内存在重复条目：$path")
            }
            if (path.count { it == '/' } + 1 > policy.maxPathDepth) {
                throw SignedPackageException("包内路径嵌套层级超过上限：$path")
            }
            if (entry.size < 0 || entry.compressedSize < 0) {
                throw SignedPackageException("ZIP 条目大小无效：$path")
            }
            if (entry.size > policy.maxEntryBytes) {
                throw SignedPackageException("包内文件超过大小上限：$path")
            }
            policy.declaredReadLimit(path)?.let { limit ->
                if (entry.size > limit.maxBytes) {
                    throw SignedPackageException("${limit.label}超过大小上限：$path")
                }
            }
            policy.payloadSizeLimitsByPath[path]?.let { limit ->
                if (entry.size > limit.maxBytes) {
                    throw SignedPackageException("${limit.label}超过大小上限：$path")
                }
            }
            expandedBytes = try {
                Math.addExact(expandedBytes, entry.size)
            } catch (error: ArithmeticException) {
                throw SignedPackageException("声明的解压总量超过上限", error)
            }
            if (expandedBytes > policy.maxExpandedBytes) {
                throw SignedPackageException("声明的解压总量超过上限")
            }
            if (entry.size > policy.minCompressionRatioCheckBytes) {
                val compressed = entry.compressedSize.coerceAtLeast(1L)
                if (entry.size / compressed > policy.maxCompressionRatio) {
                    throw SignedPackageException("ZIP 条目压缩比超过上限：$path")
                }
            }
        }

        val expected = policy.allowedPayloads + METADATA_PATHS
        val missing = expected - entriesByPath.keys
        if (missing.isNotEmpty()) {
            throw SignedPackageException("缺少包内文件：${missing.first()}")
        }
        val unexpected = entriesByPath.keys - expected
        if (unexpected.isNotEmpty()) {
            throw SignedPackageException("不允许的包内文件：${unexpected.first()}")
        }
        return linkedMapOf<String, ZipEntry>().apply {
            policy.allowedPayloads.forEach { path -> put(path, entriesByPath.getValue(path)) }
        }
    }

    private fun requireEntry(entries: List<ZipEntry>, name: String): ZipEntry =
        entries.singleOrNull { canonicalPackagePath(it.name) == name }
            ?: throw SignedPackageException("缺少包内文件：$name")

    private fun parseCanonicalChecksums(payload: ByteArray): Map<String, String> {
        val root = parseObject(payload, CHECKSUMS_PATH)
        if (root.keys != setOf("files")) {
            throw SignedPackageException("checksums.json 顶层契约无效")
        }
        val files = root["files"] as? JsonObject
            ?: throw SignedPackageException("checksums.json 缺少对象 files")
        if (files.isEmpty()) throw SignedPackageException("checksums.json files 不能为空")
        val result = sortedMapOf<String, String>()
        files.forEach { (rawPath, value) ->
            val path = canonicalPackagePath(rawPath)
            val digest = (value as? JsonPrimitive)?.asString("checksums.json files.$path")
                ?: throw SignedPackageException("checksums.json files.$path 必须是字符串")
            if (!SHA_256.matches(digest)) {
                throw SignedPackageException("SHA-256 格式无效：$digest")
            }
            if (result.put(path, digest) != null) {
                throw SignedPackageException("checksums.json 包含重复规范化路径")
            }
        }
        val canonical = JsonObject(
            mapOf("files" to JsonObject(result.mapValues { JsonPrimitive(it.value) })),
        ).toString().encodeToByteArray()
        if (!canonical.contentEquals(payload)) {
            throw SignedPackageException("checksums.json 不是规范 JSON")
        }
        return result
    }

    private fun parseSignature(payload: ByteArray): SignatureRecord {
        val root = parseObject(payload, SIGNATURE_PATH)
        if (root.keys != setOf("algorithm", "publicKey", "signature", "signedFile")) {
            throw SignedPackageException("signature.json 顶层契约无效")
        }
        return try {
            SignatureRecord(
                algorithm = root.requiredString("algorithm", SIGNATURE_PATH),
                publicKey = Base64.getDecoder().decode(root.requiredString("publicKey", SIGNATURE_PATH)),
                signature = Base64.getDecoder().decode(root.requiredString("signature", SIGNATURE_PATH)),
                signedFile = root.requiredString("signedFile", SIGNATURE_PATH),
            )
        } catch (error: IllegalArgumentException) {
            throw SignedPackageException("signature.json 格式无效", error)
        }
    }

    private fun verifySignature(
        record: SignatureRecord,
        checksumsBytes: ByteArray,
        policy: NormalizedPolicy,
    ): ByteArray {
        if (
            record.algorithm != "Ed25519" ||
            record.signedFile != CHECKSUMS_PATH ||
            record.publicKey.size != ED25519_PUBLIC_KEY_BYTES ||
            record.signature.size != ED25519_SIGNATURE_BYTES
        ) {
            throw SignedPackageException("不支持的签名声明")
        }
        if (!signatureVerifier.verify(record.publicKey, checksumsBytes, record.signature)) {
            throw SignedPackageException("${policy.packageLabel}签名校验失败")
        }
        return record.publicKey
    }

    private fun ZipFile.readBounded(entry: ZipEntry, maxBytes: Int, path: String): ByteArray {
        if (entry.size > maxBytes) throw SignedPackageException("包内文件超过读取上限：$path")
        return getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream(entry.size.coerceAtMost(DEFAULT_BUFFER_SIZE.toLong()).toInt())
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytes = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                bytes += read
                if (bytes > maxBytes) throw SignedPackageException("$path 超过读取上限")
                output.write(buffer, 0, read)
            }
            if (bytes != entry.size) throw SignedPackageException("ZIP 条目解压长度不匹配：$path")
            output.toByteArray()
        }
    }

    private fun InputStream.sha256AndLength(
        path: String,
        expectedBytes: Long,
        maxBytes: Long,
        payloadLimit: NormalizedPayloadSizeLimit?,
    ): HashedEntry {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytes = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            bytes = try {
                Math.addExact(bytes, read.toLong())
            } catch (error: ArithmeticException) {
                throw SignedPackageException("$path 解压长度无效", error)
            }
            if (payloadLimit != null && bytes > payloadLimit.maxBytes) {
                throw SignedPackageException("${payloadLimit.label}超过大小上限：$path")
            }
            if (bytes > maxBytes || (payloadLimit == null && bytes > expectedBytes)) {
                throw SignedPackageException("$path 解压长度超过声明上限")
            }
            digest.update(buffer, 0, read)
        }
        if (bytes != expectedBytes) {
            throw SignedPackageException("ZIP 条目解压长度不匹配：$path")
        }
        return HashedEntry(digest.digest().toHex(), bytes)
    }

    private fun parseObject(payload: ByteArray, label: String): JsonObject =
        try {
            Json.parseToJsonElement(payload.decodeStrictUtf8(label)) as? JsonObject
                ?: throw SignedPackageException("$label 必须是 JSON 对象")
        } catch (error: SignedPackageException) {
            throw error
        } catch (error: Exception) {
            throw SignedPackageException("$label JSON 格式无效", error)
        }

    private fun JsonObject.requiredString(key: String, label: String): String {
        val value = this[key] as? JsonPrimitive
        if (value == null || !value.isString || value.content.isBlank()) {
            throw SignedPackageException("$label 缺少 $key")
        }
        return value.content
    }

    private fun JsonPrimitive.asString(label: String): String {
        if (!isString) throw SignedPackageException("$label 必须是字符串")
        return content
    }

    private fun ByteArray.decodeStrictUtf8(path: String): String =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(this))
                .toString()
        } catch (error: CharacterCodingException) {
            throw SignedPackageException("$path JSON 格式无效", error)
        }

    private data class SignatureRecord(
        val algorithm: String,
        val publicKey: ByteArray,
        val signature: ByteArray,
        val signedFile: String,
    )

    private data class HashedEntry(
        val sha256: String,
        val bytes: Long,
    )

    private data class CentralDirectoryInfo(
        val entryCount: Long,
        val offset: Long,
        val end: Long,
    )

    private data class NormalizedPolicy(
        val allowedPayloads: Set<String>,
        val maxArchiveBytes: Long,
        val maxExpandedBytes: Long,
        val maxEntryCount: Int,
        val maxEntryBytes: Long,
        val maxCompressionRatio: Long,
        val minCompressionRatioCheckBytes: Long,
        val maxPathDepth: Int,
        val maxManifestBytes: Int,
        val maxChecksumsBytes: Int,
        val maxSignatureBytes: Int,
        val payloadSizeLimitsByPath: Map<String, NormalizedPayloadSizeLimit>,
        val manifestPath: String,
        val packageLabel: String,
    )

    private data class NormalizedPayloadSizeLimit(
        val maxBytes: Long,
        val label: String,
    )

    private fun NormalizedPolicy.declaredReadLimit(path: String): NormalizedPayloadSizeLimit? =
        when (path) {
            manifestPath -> NormalizedPayloadSizeLimit(maxManifestBytes.toLong(), "manifest")
            CHECKSUMS_PATH -> NormalizedPayloadSizeLimit(maxChecksumsBytes.toLong(), CHECKSUMS_PATH)
            SIGNATURE_PATH -> NormalizedPayloadSizeLimit(maxSignatureBytes.toLong(), SIGNATURE_PATH)
            else -> null
        }

    private fun ByteArray.readUnsignedShortLe(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.readUnsignedIntLe(offset: Int): Long =
        readUnsignedShortLe(offset).toLong() or (readUnsignedShortLe(offset + 2).toLong() shl 16)

    private fun ByteArray.readLongLe(offset: Int): Long {
        var value = 0L
        repeat(Long.SIZE_BYTES) { index ->
            value = value or ((this[offset + index].toLong() and 0xff) shl (index * Byte.SIZE_BITS))
        }
        return value
    }

    private fun SignedPackagePolicy.normalized(): NormalizedPolicy {
        if (
            maxArchiveBytes <= 0 ||
            maxExpandedBytes <= 0 ||
            maxEntryCount <= 0 ||
            maxEntryBytes <= 0 ||
            maxCompressionRatio <= 0 ||
            minCompressionRatioCheckBytes < 0 ||
            maxPathDepth <= 0 ||
            maxManifestBytes <= 0 ||
            maxChecksumsBytes <= 0 ||
            maxSignatureBytes <= 0 ||
            packageLabel.isBlank()
        ) {
            throw SignedPackageException("签名包校验策略无效")
        }
        val normalizedPayloads = linkedSetOf<String>()
        allowedPayloads.forEach { raw ->
            val path = canonicalPackagePath(raw)
            if (!normalizedPayloads.add(path)) {
                throw SignedPackageException("签名包校验策略包含重复 payload：$path")
            }
        }
        if (normalizedPayloads.isEmpty()) throw SignedPackageException("签名包校验策略缺少 payload")
        if (normalizedPayloads.any { it in METADATA_PATHS }) {
            throw SignedPackageException("签名包校验策略不允许将元数据作为 payload")
        }
        val normalizedManifestPath = canonicalPackagePath(manifestPath)
        if (normalizedManifestPath !in normalizedPayloads) {
            throw SignedPackageException("签名包校验策略缺少 manifest：$normalizedManifestPath")
        }
        val payloadSizeLimitsByPath = linkedMapOf<String, NormalizedPayloadSizeLimit>()
        payloadSizeLimits.forEach { limit ->
            if (limit.paths.isEmpty() || limit.maxBytes <= 0 || limit.label.isBlank()) {
                throw SignedPackageException("签名包校验策略 payload 大小限制无效")
            }
            limit.paths.forEach { rawPath ->
                val path = canonicalPackagePath(rawPath)
                if (
                    payloadSizeLimitsByPath.put(path, NormalizedPayloadSizeLimit(limit.maxBytes, limit.label)) != null
                ) {
                    throw SignedPackageException("签名包校验策略 payload 大小限制重复：$path")
                }
            }
        }
        return NormalizedPolicy(
            allowedPayloads = normalizedPayloads,
            maxArchiveBytes = maxArchiveBytes,
            maxExpandedBytes = maxExpandedBytes,
            maxEntryCount = maxEntryCount,
            maxEntryBytes = maxEntryBytes,
            maxCompressionRatio = maxCompressionRatio,
            minCompressionRatioCheckBytes = minCompressionRatioCheckBytes,
            maxPathDepth = maxPathDepth,
            maxManifestBytes = maxManifestBytes,
            maxChecksumsBytes = maxChecksumsBytes,
            maxSignatureBytes = maxSignatureBytes,
            payloadSizeLimitsByPath = payloadSizeLimitsByPath,
            manifestPath = normalizedManifestPath,
            packageLabel = packageLabel,
        )
    }

    private companion object {
        const val CHECKSUMS_PATH = "checksums.json"
        const val SIGNATURE_PATH = "signature.json"
        val METADATA_PATHS = setOf(CHECKSUMS_PATH, SIGNATURE_PATH)
        val SHA_256 = Regex("[0-9a-f]{64}")
        const val ED25519_PUBLIC_KEY_BYTES = 32
        const val ED25519_SIGNATURE_BYTES = 64
        const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50L
        const val ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06064b50L
        const val ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE = 0x07064b50L
        const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50L
        const val END_OF_CENTRAL_DIRECTORY_SIZE = 22
        const val ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIZE = 20
        const val ZIP64_END_OF_CENTRAL_DIRECTORY_MIN_SIZE = 56
        const val ZIP64_END_OF_CENTRAL_DIRECTORY_MIN_RECORD_SIZE = 44L
        const val CENTRAL_DIRECTORY_HEADER_SIZE = 46
        const val MAX_EOCD_SEARCH_BYTES = END_OF_CENTRAL_DIRECTORY_SIZE + 0xffff
        const val ZIP64_ENTRY_SENTINEL = 0xffffL
        const val ZIP64_OFFSET_SENTINEL = 0xffffffffL
        const val ENCRYPTED_FLAG = 0x0001
        const val UNIX_HOST_SYSTEM = 3
        const val UNIX_FILE_TYPE_MASK = 0xf000
        const val UNIX_REGULAR_FILE_TYPE = 0x8000
        const val UNIX_SYMLINK_TYPE = 0xa000
        const val UNIX_EXECUTABLE_MASK = 0x49
    }
}

object PortableEd25519SignatureVerifier : Ed25519SignatureVerifier {
    override fun verify(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(payload, 0, payload.size)
                verifySignature(signature)
            }
        } catch (error: Throwable) {
            throw SignedPackageException("当前设备不支持 Ed25519 签名校验", error)
        }
    }
}

fun canonicalPackagePath(value: String): String {
    if ('\\' in value) throw SignedPackageException("包内路径不允许反斜杠：$value")
    val parts = value.split('/')
    if (
        value.isBlank() ||
        value.startsWith('/') ||
        Regex("^[A-Za-z]:/").containsMatchIn(value) ||
        '\u0000' in value ||
        parts.any { it.isBlank() || it == "." || it == ".." }
    ) {
        throw SignedPackageException("不安全的包内路径：$value")
    }
    return parts.joinToString("/")
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
