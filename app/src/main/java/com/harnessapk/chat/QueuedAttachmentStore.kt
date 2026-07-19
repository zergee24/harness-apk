package com.harnessapk.chat

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayList
import java.util.Collections
import java.util.UUID

class PersistedAttachmentBatch internal constructor(
    attachments: List<PendingImageAttachment>,
    private val ownerToken: Any,
    generatedEntries: List<OwnedAttachmentEntry>,
    internal val directoryFileKey: Any,
) {
    val attachments: List<PendingImageAttachment> = Collections.unmodifiableList(ArrayList(attachments))

    private val generatedEntries: List<OwnedAttachmentEntry> = Collections.unmodifiableList(ArrayList(generatedEntries))

    internal fun isOwnedBy(token: Any): Boolean = ownerToken === token

    internal fun generatedEntries(): List<OwnedAttachmentEntry> = generatedEntries

    internal fun hasMatchingGeneratedEntries(): Boolean = attachments.size == generatedEntries.size
}

internal data class OwnedAttachmentEntry(
    val name: String,
    val fileKey: Any,
)

internal data class QueuedAttachmentStoreTestHooks(
    val beforeChildDirectoryCreate: () -> Unit = {},
    val beforeWrite: (temporaryName: String, finalName: String) -> Unit = { _, _ -> },
    val beforeFinalCreate: (temporaryName: String, finalName: String) -> Unit = { _, _ -> },
    val copyTemporaryToFinal: (InputStream, OutputStream) -> Unit = { input, output -> input.copyTo(output) },
    val beforeOwnedEntryMove: (name: String) -> Unit = {},
)

class QueuedAttachmentStore internal constructor(
    context: Context,
    private val inputOpener: (Uri) -> InputStream? = { uri ->
        context.applicationContext.contentResolver.openInputStream(uri)
    },
    private val onBatchPersisted: () -> Unit = {},
    private val testHooks: QueuedAttachmentStoreTestHooks = QueuedAttachmentStoreTestHooks(),
    private val filesDirectoryProvider: () -> File = { context.applicationContext.filesDir },
) {
    private val trustedRoot = initializeTrustedRoot(filesDirectoryProvider())
    private val trustedFilesPath = trustedRoot.path
    private val trustedRootFileKey = trustedRoot.fileKey
    private val trustedRootIdentity = trustedRoot.identity
    private val rawManagedDirectory = trustedFilesPath.resolve(MANAGED_DIRECTORY_NAME)
    private val ownerToken = Any()

    fun persist(source: PendingImageAttachment): PendingImageAttachment = persistAll(listOf(source)).attachments.single()

    fun persistAll(sources: List<PendingImageAttachment>): PersistedAttachmentBatch =
        withTrustedParent { parent ->
            openManagedDirectoryForPersist(parent).use { managedDirectory ->
                val record = BatchRecord(ownerToken, directoryFileKey(managedDirectory))
                try {
                    sources.forEach { source ->
                        val persisted = persistOne(source, managedDirectory)
                        record.attachments += persisted.attachment
                        record.generatedEntries += persisted.finalEntry
                    }
                    onBatchPersisted()
                    requireCurrentDirectoryFileKey(parent, record.directoryFileKey)
                    requireCurrentRootIdentity(trustedFilesPath, trustedRootIdentity, "应用文件目录在保存期间发生变化")
                    PersistedAttachmentBatch(
                        attachments = record.attachments,
                        ownerToken = record.ownerToken,
                        generatedEntries = record.generatedEntries,
                        directoryFileKey = record.directoryFileKey,
                    )
                } catch (error: Throwable) {
                    deleteOwnedEntries(managedDirectory, record.generatedEntries)
                    throw error
                }
            }
        }

    fun cleanup(batch: PersistedAttachmentBatch) {
        if (!batch.isOwnedBy(ownerToken) || !batch.hasMatchingGeneratedEntries()) return
        runCatching {
            withTrustedParent { parent ->
                openCurrentManagedDirectory(parent).use { managedDirectory ->
                    if (directoryFileKey(managedDirectory) != batch.directoryFileKey) return@use
                    batch.attachments.zip(batch.generatedEntries()).forEach { (attachment, entry) ->
                        if (!isCurrentManagedAttachment(attachment, entry.name)) return@forEach
                        deleteOwnedEntry(managedDirectory, entry.name, entry.fileKey)
                    }
                }
            }
        }
    }

    private fun persistOne(
        source: PendingImageAttachment,
        managedDirectory: SecureDirectoryStream<Path>,
    ): PersistedAttachment {
        val extension = when (source.mimeType.lowercase()) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        val uuid = UUID.randomUUID().toString()
        val finalName = "queued-$uuid$extension"
        val temporaryName = "temporary-$uuid.tmp"
        var temporaryEntry: OwnedAttachmentEntry? = null
        var finalEntry: OwnedAttachmentEntry? = null
        try {
            testHooks.beforeWrite(temporaryName, finalName)
            inputOpener(source.uri).use { input ->
                requireNotNull(input) { "无法读取图片" }
                managedDirectory.newByteChannel(
                    Path.of(temporaryName),
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                ).use { channel ->
                    temporaryEntry = ownedRegularFile(managedDirectory, temporaryName)
                    Channels.newOutputStream(channel).use { output -> input.copyTo(output) }
                }
            }
            testHooks.beforeFinalCreate(temporaryName, finalName)
            managedDirectory.newByteChannel(
                Path.of(finalName),
                setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            ).use { finalChannel ->
                finalEntry = ownedRegularFile(managedDirectory, finalName)
                managedDirectory.newByteChannel(
                    Path.of(temporaryName),
                    setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                ).use { temporaryChannel ->
                    testHooks.copyTemporaryToFinal(
                        Channels.newInputStream(temporaryChannel),
                        Channels.newOutputStream(finalChannel),
                    )
                }
            }
            deleteOwnedEntry(managedDirectory, temporaryName, requireNotNull(temporaryEntry).fileKey)
            temporaryEntry = null
            return PersistedAttachment(
                attachment = PendingImageAttachment(
                    Uri.fromFile(rawManagedDirectory.resolve(finalName).toFile()),
                    source.mimeType,
                ),
                finalEntry = requireNotNull(finalEntry),
            )
        } catch (error: Throwable) {
            temporaryEntry?.let { entry -> runCatching { deleteOwnedEntry(managedDirectory, entry.name, entry.fileKey) } }
            finalEntry?.let { entry -> runCatching { deleteOwnedEntry(managedDirectory, entry.name, entry.fileKey) } }
            throw error
        }
    }

    private fun openManagedDirectoryForPersist(parent: SecureDirectoryStream<Path>): SecureDirectoryStream<Path> =
        openCurrentManagedDirectory(parent)

    private fun openCurrentManagedDirectory(parent: SecureDirectoryStream<Path>): SecureDirectoryStream<Path> {
        val child = try {
            requireSecureDirectory(
                parent.newDirectoryStream(Path.of(MANAGED_DIRECTORY_NAME), LinkOption.NOFOLLOW_LINKS),
                "当前平台不支持安全会话图片目录句柄",
            )
        } catch (error: NoSuchFileException) {
            throw error
        } catch (error: IOException) {
            throw IllegalStateException("会话图片目录无效", error)
        }
        try {
            directoryFileKey(child)
            return child
        } catch (error: Throwable) {
            child.close()
            throw error
        }
    }

    private fun requireCurrentDirectoryFileKey(parent: SecureDirectoryStream<Path>, expectedFileKey: Any) {
        openCurrentManagedDirectory(parent).use { current ->
            check(directoryFileKey(current) == expectedFileKey) { "会话图片目录在保存期间发生变化" }
        }
    }

    private fun directoryFileKey(directory: SecureDirectoryStream<Path>): Any {
        val attributes = directory.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()
        check(attributes.isDirectory && !attributes.isSymbolicLink) { "会话图片目录无效" }
        return requireNotNull(attributes.fileKey()) { "无法确认会话图片目录身份" }
    }

    private fun isCurrentManagedAttachment(attachment: PendingImageAttachment, finalName: String): Boolean {
        if (!managedFileName.matches(finalName)) return false
        if (attachment.uri.scheme != "file" || !attachment.uri.authority.isNullOrEmpty()) return false
        return attachment.uri.path == rawManagedDirectory.resolve(finalName).toString()
    }

    private fun ownedRegularFile(directory: SecureDirectoryStream<Path>, name: String): OwnedAttachmentEntry {
        val attributes = directory.getFileAttributeView(
            Path.of(name),
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).readAttributes()
        check(attributes.isRegularFile && !attributes.isSymbolicLink) { "会话图片文件无效" }
        return OwnedAttachmentEntry(name, requireNotNull(attributes.fileKey()) { "无法确认会话图片文件身份" })
    }

    private fun deleteOwnedEntries(directory: SecureDirectoryStream<Path>, entries: List<OwnedAttachmentEntry>) {
        entries.forEach { entry ->
            runCatching { deleteOwnedEntry(directory, entry.name, entry.fileKey) }
        }
    }

    private fun deleteOwnedEntry(
        directory: SecureDirectoryStream<Path>,
        name: String,
        expectedFileKey: Any,
    ) {
        if (!managedEntryName.matches(name)) return
        if (currentRegularFileKey(directory, name) != expectedFileKey) return

        testHooks.beforeOwnedEntryMove(name)
        val quarantineName = availableQuarantineName(directory)
        try {
            directory.move(Path.of(name), directory, Path.of(quarantineName))
        } catch (_: NoSuchFileException) {
            return
        }

        if (currentRegularFileKey(directory, quarantineName) == expectedFileKey) {
            try {
                directory.deleteFile(Path.of(quarantineName))
            } catch (_: NoSuchFileException) {
            }
            return
        }
        restoreQuarantineIfOriginalMissing(directory, quarantineName, name)
    }

    private fun currentRegularFileKey(directory: SecureDirectoryStream<Path>, name: String): Any? = try {
        val attributes = directory.getFileAttributeView(
            Path.of(name),
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).readAttributes()
        if (attributes.isRegularFile && !attributes.isSymbolicLink) attributes.fileKey() else null
    } catch (_: NoSuchFileException) {
        null
    }

    private fun availableQuarantineName(directory: SecureDirectoryStream<Path>): String {
        repeat(MAX_QUARANTINE_NAME_ATTEMPTS) {
            val name = "$QUARANTINE_PREFIX${UUID.randomUUID()}"
            if (!entryExists(directory, name)) return name
        }
        throw IOException("无法分配附件回收隔离名")
    }

    private fun entryExists(directory: SecureDirectoryStream<Path>, name: String): Boolean = try {
        directory.getFileAttributeView(
            Path.of(name),
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).readAttributes()
        true
    } catch (_: NoSuchFileException) {
        false
    }

    private fun restoreQuarantineIfOriginalMissing(
        directory: SecureDirectoryStream<Path>,
        quarantineName: String,
        originalName: String,
    ) {
        if (entryExists(directory, originalName)) return
        runCatching { directory.move(Path.of(quarantineName), directory, Path.of(originalName)) }
    }

    private inline fun <T> withTrustedParent(block: (SecureDirectoryStream<Path>) -> T): T {
        requireCurrentRootIdentity(trustedFilesPath, trustedRootIdentity, "应用文件目录在运行期间发生变化")
        Files.newDirectoryStream(trustedFilesPath).use { stream ->
            val parent = requireSecureDirectory(stream, "当前平台不支持安全应用文件目录句柄")
            check(directoryFileKey(parent) == trustedRootFileKey) { "应用文件目录在运行期间发生变化" }
            requireCurrentRootIdentity(trustedFilesPath, trustedRootIdentity, "应用文件目录在运行期间发生变化")
            return block(parent)
        }
    }

    private fun requireSecureDirectory(
        stream: DirectoryStream<Path>,
        unsupportedMessage: String,
    ): SecureDirectoryStream<Path> {
        if (stream is SecureDirectoryStream<Path>) return stream
        stream.close()
        throw IllegalStateException(unsupportedMessage)
    }

    private fun initializeTrustedRoot(filesDirectory: File): TrustedRoot {
        check(filesDirectory.isAbsolute) { "应用文件目录必须是绝对路径" }
        val absoluteDirectory = filesDirectory.absoluteFile
        val absolutePath = absoluteDirectory.toPath()
        return withOpenDirectoryNoFollow(absolutePath.toString(), "应用文件目录无效") { rootFd, rootIdentity ->
            ParcelFileDescriptor.dup(rootFd).use { pinnedRoot ->
                val anchoredRootPath = "/proc/self/fd/${pinnedRoot.fd}"
                val rootFileKey = Files.newDirectoryStream(Path.of(anchoredRootPath)).use { stream ->
                    directoryFileKey(
                        requireSecureDirectory(stream, "当前平台不支持安全应用文件目录句柄"),
                    )
                }

                synchronized(managedDirectoryInitializationLock) {
                    testHooks.beforeChildDirectoryCreate()
                    val anchoredChildPath = "$anchoredRootPath/$MANAGED_DIRECTORY_NAME"
                    try {
                        Os.mkdir(anchoredChildPath, OsConstants.S_IRWXU)
                    } catch (error: ErrnoException) {
                        if (error.errno != OsConstants.EEXIST) {
                            throw IllegalStateException("无法创建会话图片目录", error)
                        }
                    }
                    withOpenDirectoryNoFollow(anchoredChildPath, "会话图片目录无效") { _, _ -> }
                }

                requireCurrentRootIdentity(absolutePath, rootIdentity, "应用文件目录在初始化期间发生变化")
                TrustedRoot(absolutePath, rootFileKey, rootIdentity)
            }
        }
    }

    private inline fun <T> withOpenDirectoryNoFollow(
        path: String,
        errorMessage: String,
        block: (FileDescriptor, DirectoryIdentity) -> T,
    ): T {
        val descriptor = try {
            Os.open(
                path,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                0,
            )
        } catch (error: ErrnoException) {
            throw IllegalStateException(errorMessage, error)
        }
        var failure: Throwable? = null
        try {
            return block(descriptor, directoryIdentity(descriptor, errorMessage))
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                Os.close(descriptor)
            } catch (error: ErrnoException) {
                val originalFailure = failure
                if (originalFailure != null) {
                    originalFailure.addSuppressed(error)
                } else {
                    throw IllegalStateException("无法关闭应用文件目录句柄", error)
                }
            }
        }
    }

    private fun requireCurrentRootIdentity(
        path: Path,
        expectedIdentity: DirectoryIdentity,
        errorMessage: String,
    ) {
        withOpenDirectoryNoFollow(path.toString(), errorMessage) { _, identity ->
            check(identity == expectedIdentity) { errorMessage }
        }
    }

    private fun directoryIdentity(descriptor: FileDescriptor, errorMessage: String): DirectoryIdentity {
        val stat = try {
            Os.fstat(descriptor)
        } catch (error: ErrnoException) {
            throw IllegalStateException(errorMessage, error)
        }
        check(OsConstants.S_ISDIR(stat.st_mode)) { errorMessage }
        return DirectoryIdentity(stat.st_dev, stat.st_ino)
    }

    private fun directoryFileKey(path: Path): Any {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        check(attributes.isDirectory && !attributes.isSymbolicLink) { "应用文件目录无效" }
        return requireNotNull(attributes.fileKey()) { "无法确认应用文件目录身份" }
    }

    private class BatchRecord(
        val ownerToken: Any,
        val directoryFileKey: Any,
        val attachments: MutableList<PendingImageAttachment> = mutableListOf(),
        val generatedEntries: MutableList<OwnedAttachmentEntry> = mutableListOf(),
    )

    private data class PersistedAttachment(
        val attachment: PendingImageAttachment,
        val finalEntry: OwnedAttachmentEntry,
    )

    private data class TrustedRoot(
        val path: Path,
        val fileKey: Any,
        val identity: DirectoryIdentity,
    )

    private data class DirectoryIdentity(
        val device: Long,
        val inode: Long,
    )

    private companion object {
        const val MANAGED_DIRECTORY_NAME = "chat-attachments"
        const val QUARANTINE_PREFIX = ".queued-cleanup-"
        const val MAX_QUARANTINE_NAME_ATTEMPTS = 16
        val managedFileName = Regex(
            """queued-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png|webp)""",
        )
        val managedEntryName = Regex(
            """(?:queued-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png|webp)|temporary-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.tmp)""",
        )
        val managedDirectoryInitializationLock = Any()
    }
}
