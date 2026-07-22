package com.harnessapk.wiki

import com.harnessapk.common.SystemTimeProvider
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.WikiDao
import com.harnessapk.storage.WikiEntity
import com.harnessapk.storage.WikiVersionEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

fun interface WikiTransactionRunner {
    suspend fun run(block: suspend () -> Unit)
}

fun interface WikiVersionReferenceChecker {
    suspend fun referenceCounts(ref: WikiRef): WikiVersionReferenceCounts
}

data class WikiVersionReferenceCounts(
    val mountCount: Int,
    val citationCount: Int,
) {
    init {
        require(mountCount >= 0) { "Wiki 会话挂载计数不能为负数" }
        require(citationCount >= 0) { "Wiki 历史引用计数不能为负数" }
    }

    val totalCount: Int
        get() = Math.addExact(mountCount, citationCount)

    fun removalBlockedMessage(): String =
        "该 Wiki 版本仍被 $mountCount 个会话挂载、$citationCount 条历史引用使用，无法删除"

    companion object {
        val EMPTY = WikiVersionReferenceCounts(mountCount = 0, citationCount = 0)
    }
}

enum class WikiVersionState {
    READY,
    INVALID,
}

enum class WikiInstallOutcome {
    INSTALLED,
    ALREADY_INSTALLED,
}

data class ConfirmedWikiImport(
    val inspection: WikiImportInspection,
    val packageHash: String,
    val enabledForNewConversations: Boolean,
)

data class WikiInstallResult(
    val outcome: WikiInstallOutcome,
    val ref: WikiRef,
)

data class WikiVersionRemovalResult(
    val ref: WikiRef,
    val removed: Boolean,
)

open class WikiInstallException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

class WikiInsufficientStorageException(
    val requiredBytes: Long,
    val availableBytes: Long,
) : WikiInstallException("Wiki 安装空间不足：需要 $requiredBytes 字节，当前可用 $availableBytes 字节")

internal object WikiInstallSpace {
    const val SAFETY_MARGIN_BYTES = 128L * 1024L * 1024L

    fun requiredBytes(archiveBytes: Long, contentBytes: Long): Long {
        if (archiveBytes <= 0L || contentBytes <= 0L) {
            throw WikiInstallException("Wiki 包大小无效")
        }
        return try {
            Math.addExact(Math.addExact(archiveBytes, contentBytes), SAFETY_MARGIN_BYTES)
        } catch (error: ArithmeticException) {
            throw WikiInstallException("Wiki 安装所需空间溢出", error)
        }
    }
}

class WikiRepository(
    private val filesDir: java.io.File,
    private val dao: WikiDao,
    private val transactionRunner: WikiTransactionRunner,
    private val fileOps: WikiFileOps = DefaultWikiFileOps(),
    private val timeProvider: TimeProvider = SystemTimeProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val privateInstallAvailableBytes: () -> Long = { Long.MAX_VALUE },
    private val referenceChecker: WikiVersionReferenceChecker = WikiVersionReferenceChecker { WikiVersionReferenceCounts.EMPTY },
) {
    private val mutationMutex = Mutex()

    suspend fun install(confirmedImport: ConfirmedWikiImport): WikiInstallResult = withContext(ioDispatcher) {
        mutationMutex.withLock { installUnlocked(confirmedImport) }
    }

    suspend fun activate(ref: WikiRef) = withContext(ioDispatcher) {
        mutationMutex.withLock {
            validateRef(ref)
            val version = dao.findVersion(ref.wikiId, ref.version)
                ?: throw WikiInstallException("Wiki 版本不存在")
            if (version.state != WikiVersionState.READY.name) {
                throw WikiInstallException("Wiki 版本当前不可用")
            }
            transactionRunner.run {
                check(dao.updateActiveVersion(ref.wikiId, ref.version, timeProvider.nowMillis()) == 1) {
                    "Wiki 激活目标不存在"
                }
            }
        }
    }

    suspend fun setEnabledForNewConversations(ref: WikiRef, enabled: Boolean) = withContext(ioDispatcher) {
        mutationMutex.withLock {
            validateRef(ref)
            val version = dao.findVersion(ref.wikiId, ref.version)
                ?: throw WikiInstallException("Wiki 版本不存在")
            if (version.state != WikiVersionState.READY.name) {
                throw WikiInstallException("Wiki 版本当前不可用")
            }
            transactionRunner.run {
                if (enabled) dao.disableReadyDefaultVersions(ref.wikiId)
                check(dao.setEnabledForNewConversations(ref.wikiId, ref.version, enabled) == 1) {
                    "无法更新 Wiki 默认范围"
                }
            }
        }
    }

    suspend fun hasReadyVersion(): Boolean = withContext(ioDispatcher) {
        dao.hasReadyVersion()
    }

    fun observeWikis(): Flow<List<WikiEntity>> = dao.observeWikis()

    suspend fun listVersions(wikiId: String): List<WikiVersionEntity> = withContext(ioDispatcher) {
        validateWikiId(wikiId)
        dao.listVersions(wikiId)
    }

    suspend fun manifestFor(ref: WikiRef): WikiManifest? = withContext(ioDispatcher) {
        validateRef(ref)
        val version = dao.findVersion(ref.wikiId, ref.version) ?: return@withContext null
        try {
            WikiManifestParser.parse(version.manifestJson.encodeToByteArray())
        } catch (error: WikiPackageException) {
            throw WikiInstallException("已安装 Wiki 元数据无效", error)
        }
    }

    suspend fun isPublisherKnown(fingerprint: String): Boolean = withContext(ioDispatcher) {
        require(SHA_256.matches(fingerprint)) { "发布者指纹无效" }
        dao.hasReadyVersionForPublisherFingerprint(fingerprint)
    }

    suspend fun removeVersion(ref: WikiRef): WikiVersionRemovalResult = withContext(ioDispatcher) {
        mutationMutex.withLock { removeVersionUnlocked(ref) }
    }

    suspend fun markInvalid(ref: WikiRef, reason: String) = withContext(ioDispatcher) {
        mutationMutex.withLock {
            validateRef(ref)
            val sanitized = reason.replace(Regex("[\\r\\n\\t]+"), " ").take(MAX_INVALID_REASON_LENGTH).trim()
            transactionRunner.run {
                dao.updateVersionState(ref.wikiId, ref.version, WikiVersionState.INVALID.name, sanitized)
                val active = dao.findWiki(ref.wikiId)?.activeVersion
                if (active == ref.version) {
                    val replacement = dao.listVersions(ref.wikiId)
                        .lastOrNull { it.version != ref.version && it.state == WikiVersionState.READY.name }
                    dao.updateActiveVersion(ref.wikiId, replacement?.version, timeProvider.nowMillis())
                }
            }
        }
    }

    fun versionDirectory(ref: WikiRef): Path {
        validateRef(ref)
        return versionsRoot().resolve(ref.wikiId).resolve(ref.version.toString())
    }

    private suspend fun installUnlocked(confirmedImport: ConfirmedWikiImport): WikiInstallResult {
        val inspection = confirmedImport.inspection
        val ref = inspection.manifest.ref
        validateRef(ref)
        validateHash(confirmedImport.packageHash, "packageHash")
        requirePositiveSize(inspection.archiveSizeBytes, "压缩包")
        requirePositiveSize(inspection.contentSizeBytes, "内容数据库")
        if (!Files.isRegularFile(inspection.stagedDatabase)) {
            throw WikiInstallException("待安装 Wiki 数据库不存在")
        }
        if (Files.size(inspection.stagedDatabase) != inspection.contentSizeBytes) {
            throw WikiInstallException("待安装 Wiki 数据库大小不匹配")
        }
        val requiredBytes = requiredInstallBytes(inspection.archiveSizeBytes, inspection.contentSizeBytes)
        val availableBytes = privateInstallAvailableBytes()
        if (availableBytes < 0L || availableBytes < requiredBytes) {
            throw WikiInsufficientStorageException(requiredBytes, availableBytes)
        }

        val existing = dao.findVersion(ref.wikiId, ref.version)
        if (existing != null) {
            if (existing.packageHash != confirmedImport.packageHash) {
                throw WikiInstallException("同一 Wiki 版本内容不同，请发布新版本")
            }
            if (!Files.isRegularFile(versionDirectory(ref).resolve(CONTENT_FILE_NAME))) {
                throw WikiInstallException("已安装 Wiki 内容文件缺失")
            }
            return WikiInstallResult(WikiInstallOutcome.ALREADY_INSTALLED, ref)
        }

        val finalDirectory = versionDirectory(ref)
        if (Files.exists(finalDirectory)) {
            throw WikiInstallException("Wiki 版本目录已存在但缺少元数据")
        }
        val stagingDirectory = versionsRoot().resolve(".staging").resolve(UUID.randomUUID().toString())
        var finalDirectoryCreated = false
        try {
            fileOps.createDirectories(stagingDirectory)
            val stagedCopy = stagingDirectory.resolve(CONTENT_FILE_NAME)
            val copiedBytes = fileOps.copy(inspection.stagedDatabase, stagedCopy)
            if (copiedBytes != inspection.contentSizeBytes) {
                throw WikiInstallException("Wiki 数据库复制大小不匹配")
            }
            fileOps.fsync(stagedCopy)
            fileOps.moveAtomically(stagingDirectory, finalDirectory)
            finalDirectoryCreated = true

            val now = timeProvider.nowMillis()
            transactionRunner.run {
                val rechecked = dao.findVersion(ref.wikiId, ref.version)
                if (rechecked != null) {
                    if (rechecked.packageHash != confirmedImport.packageHash) {
                        throw WikiInstallException("同一 Wiki 版本内容不同，请发布新版本")
                    }
                    return@run
                }
                val existingWiki = dao.findWiki(ref.wikiId)
                if (existingWiki == null) {
                    dao.insertWiki(
                        WikiEntity(
                            id = ref.wikiId,
                            title = inspection.manifest.title,
                            description = inspection.manifest.description,
                            activeVersion = ref.version,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                }
                dao.insertVersion(
                    WikiVersionEntity(
                        wikiId = ref.wikiId,
                        version = ref.version,
                        contentPath = finalDirectory.resolve(CONTENT_FILE_NAME).toString(),
                        schemaVersion = CONTENT_SCHEMA_VERSION,
                        contentHash = inspection.manifest.contentHash,
                        packageHash = confirmedImport.packageHash,
                        publisherKeyId = inspection.manifest.publisherKeyId,
                        publisherFingerprint = inspection.publisherFingerprint.hex,
                        manifestJson = inspection.manifestJson,
                        sizeBytes = inspection.contentSizeBytes,
                        enabledForNewConversations = false,
                        state = WikiVersionState.READY.name,
                        installedAt = now,
                    ),
                )
                if (confirmedImport.enabledForNewConversations) {
                    dao.disableReadyDefaultVersions(ref.wikiId)
                    check(dao.setEnabledForNewConversations(ref.wikiId, ref.version, true) == 1) {
                        "无法设置 Wiki 默认范围"
                    }
                }
                if (existingWiki?.activeVersion == null) {
                    check(dao.updateActiveVersion(ref.wikiId, ref.version, now) == 1) {
                        "无法设置 Wiki 当前版本"
                    }
                }
            }
            return WikiInstallResult(WikiInstallOutcome.INSTALLED, ref)
        } catch (error: WikiInstallException) {
            rollbackInstall(stagingDirectory, finalDirectory.takeIf { finalDirectoryCreated || Files.exists(it) })
            throw error
        } catch (error: Exception) {
            rollbackInstall(stagingDirectory, finalDirectory.takeIf { finalDirectoryCreated || Files.exists(it) })
            throw WikiInstallException("安装 Wiki 版本失败", error)
        }
    }

    private suspend fun removeVersionUnlocked(ref: WikiRef): WikiVersionRemovalResult {
        validateRef(ref)
        val existing = dao.findVersion(ref.wikiId, ref.version) ?: return WikiVersionRemovalResult(ref, removed = false)
        val referenceCounts = referenceChecker.referenceCounts(ref)
        if (referenceCounts.totalCount > 0) {
            throw WikiInstallException(referenceCounts.removalBlockedMessage())
        }
        val finalDirectory = versionDirectory(ref)
        val trashDirectory = versionsRoot().resolve(".trash").resolve(
            "${ref.wikiId}-${ref.version}-${UUID.randomUUID()}",
        )
        var movedToTrash = false
        try {
            if (Files.exists(finalDirectory)) {
                fileOps.moveAtomically(finalDirectory, trashDirectory)
                movedToTrash = true
            }
            transactionRunner.run {
                check(dao.deleteVersion(ref.wikiId, ref.version) == 1) { "Wiki 版本元数据不存在" }
                val remaining = dao.listVersions(ref.wikiId)
                    .lastOrNull { it.state == WikiVersionState.READY.name }
                val wiki = dao.findWiki(ref.wikiId)
                if (wiki?.activeVersion == ref.version) {
                    dao.updateActiveVersion(ref.wikiId, remaining?.version, timeProvider.nowMillis())
                }
                dao.deleteWikiIfNoVersions(ref.wikiId)
            }
        } catch (error: Exception) {
            if (movedToTrash && Files.exists(trashDirectory)) {
                runCatching { fileOps.moveAtomically(trashDirectory, finalDirectory) }
            }
            throw if (error is WikiInstallException) error else WikiInstallException("删除 Wiki 版本失败", error)
        }
        if (movedToTrash) fileOps.deleteRecursively(trashDirectory)
        return WikiVersionRemovalResult(existing.toRef(), removed = true)
    }

    private fun rollbackInstall(stagingDirectory: Path, finalDirectory: Path?) {
        fileOps.deleteRecursively(stagingDirectory)
        finalDirectory?.let(fileOps::deleteRecursively)
    }

    private fun versionsRoot(): Path = filesDir.toPath().resolve("wikis")

    private fun validateRef(ref: WikiRef) {
        if (ref.version <= 0) {
            throw WikiInstallException("Wiki 标识或版本无效")
        }
        validateWikiId(ref.wikiId)
    }

    private fun validateWikiId(wikiId: String) {
        if (!WIKI_ID_PATTERN.matches(wikiId)) throw WikiInstallException("Wiki 标识或版本无效")
    }

    private fun validateHash(value: String, label: String) {
        if (!SHA_256.matches(value)) throw WikiInstallException("$label 必须是 64 位小写 SHA-256")
    }

    private fun requirePositiveSize(value: Long, label: String) {
        if (value <= 0L) throw WikiInstallException("$label 大小无效")
    }

    private fun WikiVersionEntity.toRef(): WikiRef = WikiRef(wikiId, version)

    companion object {
        const val CONTENT_FILE_NAME = "content.sqlite"
        const val CONTENT_SCHEMA_VERSION = 1
        const val INSTALL_SAFETY_MARGIN_BYTES = WikiInstallSpace.SAFETY_MARGIN_BYTES
        private const val MAX_INVALID_REASON_LENGTH = 240
        private val WIKI_ID_PATTERN = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
        private val SHA_256 = Regex("[0-9a-f]{64}")

        fun requiredInstallBytes(archiveBytes: Long, contentBytes: Long): Long =
            WikiInstallSpace.requiredBytes(archiveBytes, contentBytes)
    }
}
