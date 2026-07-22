package com.harnessapk.wiki

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WikiPackageImportSession(
    val id: String,
    val sourceName: String,
    val stagingDirectory: File,
    val stagedArchive: File,
    val inspection: WikiImportInspection,
    val packageHash: String,
    val packageBytes: Long,
    val isKnownPublisher: Boolean,
    val defaultEnabledForNewConversations: Boolean,
)

sealed interface WikiPackageLoadProgress {
    data class Copying(
        val copiedBytes: Long,
        val totalBytes: Long?,
    ) : WikiPackageLoadProgress

    data object Validating : WikiPackageLoadProgress
}

class WikiPackageImportSessionUnavailableException : IllegalStateException("知识库导入会话已经失效")

/**
 * Owns ephemeral package files between URI inspection and the user's install decision.
 * Only the exact issued session may consume or discard its private staging directory.
 */
class WikiPackageImportCoordinator(
    private val cacheDir: File,
    private val inspectPackage: (Path, Path) -> WikiImportInspection,
    private val install: suspend (ConfirmedWikiImport) -> WikiInstallResult,
    private val isKnownPublisher: suspend (String) -> Boolean,
    private val hasReadyVersion: suspend () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val sessions = ConcurrentHashMap<String, WikiPackageImportSession>()

    suspend fun prepareImport(
        sourceName: String,
        openInputStream: () -> InputStream,
        sourceBytes: Long? = null,
        onProgress: (WikiPackageLoadProgress) -> Unit = {},
    ): WikiPackageImportSession = withContext(ioDispatcher) {
        if (sourceBytes != null && (sourceBytes < 0L || sourceBytes > MAX_IMPORT_ARCHIVE_BYTES)) {
            throw WikiPackageException("知识库包大小超出允许范围")
        }
        val stagingDirectory = stagingRoot().resolve(UUID.randomUUID().toString())
        Files.createDirectories(stagingDirectory)
        val stagedArchive = stagingDirectory.resolve(ARCHIVE_FILE_NAME)
        try {
            val totalBytes = sourceBytes?.takeIf { it > 0L }
            onProgress(WikiPackageLoadProgress.Copying(copiedBytes = 0L, totalBytes = totalBytes))
            val copied = copyArchive(
                input = openInputStream(),
                destination = stagedArchive,
                totalBytes = totalBytes,
                onProgress = onProgress,
            )
            onProgress(WikiPackageLoadProgress.Validating)
            val inspection = inspectPackage(stagedArchive, stagingDirectory.resolve(INSPECTION_DIRECTORY_NAME))
            require(inspection.archiveSizeBytes == copied.bytes) { "知识库包检查大小不一致" }
            require(Files.isRegularFile(inspection.stagedDatabase)) { "知识库检查结果缺少内容数据库" }
            val session = WikiPackageImportSession(
                id = UUID.randomUUID().toString(),
                sourceName = sourceName.ifBlank { "知识库包" },
                stagingDirectory = stagingDirectory.toFile(),
                stagedArchive = stagedArchive.toFile(),
                inspection = inspection,
                packageHash = copied.sha256,
                packageBytes = copied.bytes,
                isKnownPublisher = isKnownPublisher(inspection.publisherFingerprint.hex),
                defaultEnabledForNewConversations = !hasReadyVersion(),
            )
            sessions[session.id] = session
            session
        } catch (error: CancellationException) {
            stagingDirectory.toFile().deleteRecursively()
            throw error
        } catch (error: WikiPackageException) {
            stagingDirectory.toFile().deleteRecursively()
            throw error
        } catch (error: Exception) {
            stagingDirectory.toFile().deleteRecursively()
            throw WikiPackageException("无法导入 ${sourceName.ifBlank { "知识库包" }}", error)
        }
    }

    suspend fun install(
        session: WikiPackageImportSession,
        enabledForNewConversations: Boolean,
    ): WikiInstallResult = withContext(ioDispatcher) {
        val owned = takeOwnedSession(session)
        try {
            install(
                ConfirmedWikiImport(
                    inspection = owned.inspection,
                    packageHash = owned.packageHash,
                    enabledForNewConversations = enabledForNewConversations,
                ),
            )
        } finally {
            owned.stagingDirectory.deleteRecursively()
        }
    }

    suspend fun discard(session: WikiPackageImportSession) = withContext(ioDispatcher) {
        takeOwnedSession(session).stagingDirectory.deleteRecursively()
    }

    private fun takeOwnedSession(session: WikiPackageImportSession): WikiPackageImportSession {
        val owned = sessions[session.id] ?: throw WikiPackageImportSessionUnavailableException()
        if (owned !== session || !sessions.remove(session.id, owned)) {
            throw WikiPackageImportSessionUnavailableException()
        }
        return owned
    }

    private fun copyArchive(
        input: InputStream,
        destination: Path,
        totalBytes: Long?,
        onProgress: (WikiPackageLoadProgress) -> Unit,
    ): CopiedArchive {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        input.use { source ->
            Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    copied = try {
                        Math.addExact(copied, read.toLong())
                    } catch (error: ArithmeticException) {
                        throw WikiPackageException("知识库包大小无效", error)
                    }
                    if (copied > MAX_IMPORT_ARCHIVE_BYTES) {
                        throw WikiPackageException("知识库包大小超出允许范围")
                    }
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    onProgress(WikiPackageLoadProgress.Copying(copied, totalBytes))
                }
            }
        }
        return CopiedArchive(copied, digest.digest().toHex())
    }

    private fun stagingRoot(): Path = cacheDir.toPath().resolve(STAGING_DIRECTORY_NAME)

    private data class CopiedArchive(
        val bytes: Long,
        val sha256: String,
    )

    private companion object {
        const val STAGING_DIRECTORY_NAME = "wiki-import"
        const val INSPECTION_DIRECTORY_NAME = "inspection"
        const val ARCHIVE_FILE_NAME = "package.hwiki"
        const val MAX_IMPORT_ARCHIVE_BYTES = 4L * 1024L * 1024L * 1024L
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
