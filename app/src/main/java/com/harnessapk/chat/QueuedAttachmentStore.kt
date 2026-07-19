package com.harnessapk.chat

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.util.ArrayList
import java.util.Collections
import java.util.UUID

class PersistedAttachmentBatch internal constructor(
    attachments: List<PendingImageAttachment>,
    private val ownerToken: Any,
    generatedNames: List<String>,
    internal val directoryFileKey: Any,
) {
    val attachments: List<PendingImageAttachment> = Collections.unmodifiableList(ArrayList(attachments))

    private val generatedNames: List<String> = Collections.unmodifiableList(ArrayList(generatedNames))

    internal fun isOwnedBy(token: Any): Boolean = ownerToken === token

    internal fun generatedEntries(): List<Pair<PendingImageAttachment, String>> = attachments.zip(generatedNames)

    internal fun hasMatchingGeneratedEntries(): Boolean = attachments.size == generatedNames.size
}

internal data class QueuedAttachmentStoreTestHooks(
    val beforeChildDirectoryCreate: () -> Unit = {},
    val beforeWrite: (temporaryName: String, finalName: String) -> Unit = { _, _ -> },
    val beforeCleanupDelete: (finalName: String) -> Unit = {},
)

class QueuedAttachmentStore internal constructor(
    context: Context,
    private val inputOpener: (Uri) -> InputStream? = { uri ->
        context.applicationContext.contentResolver.openInputStream(uri)
    },
    private val onBatchPersisted: () -> Unit = {},
    private val testHooks: QueuedAttachmentStoreTestHooks = QueuedAttachmentStoreTestHooks(),
) {
    private val trustedFilesDirectory = context.applicationContext.filesDir.canonicalFile
    private val trustedFilesPath = trustedFilesDirectory.toPath()
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
                        record.generatedNames += persisted.finalName
                    }
                    onBatchPersisted()
                    requireCurrentDirectoryFileKey(parent, record.directoryFileKey)
                    PersistedAttachmentBatch(
                        attachments = record.attachments,
                        ownerToken = record.ownerToken,
                        generatedNames = record.generatedNames,
                        directoryFileKey = record.directoryFileKey,
                    )
                } catch (error: Throwable) {
                    runCatching { deleteGeneratedFiles(managedDirectory, record.generatedNames) }
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
                    batch.generatedEntries().forEach { (attachment, finalName) ->
                        if (!isCurrentManagedAttachment(attachment, finalName)) return@forEach
                        if (!isRegularNonSymlink(managedDirectory, finalName)) return@forEach
                        testHooks.beforeCleanupDelete(finalName)
                        deleteFileIfPresent(managedDirectory, finalName)
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
        try {
            testHooks.beforeWrite(temporaryName, finalName)
            inputOpener(source.uri).use { input ->
                requireNotNull(input) { "无法读取图片" }
                managedDirectory.newByteChannel(
                    Path.of(temporaryName),
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                ).use { channel ->
                    Channels.newOutputStream(channel).use { output -> input.copyTo(output) }
                }
            }
            managedDirectory.move(Path.of(temporaryName), managedDirectory, Path.of(finalName))
            return PersistedAttachment(
                attachment = PendingImageAttachment(
                    Uri.fromFile(rawManagedDirectory.resolve(finalName).toFile()),
                    source.mimeType,
                ),
                finalName = finalName,
            )
        } catch (error: Throwable) {
            deleteFileIfPresent(managedDirectory, temporaryName)
            deleteFileIfPresent(managedDirectory, finalName)
            throw error
        }
    }

    private fun openManagedDirectoryForPersist(parent: SecureDirectoryStream<Path>): SecureDirectoryStream<Path> =
        try {
            openCurrentManagedDirectory(parent)
        } catch (_: NoSuchFileException) {
            testHooks.beforeChildDirectoryCreate()
            try {
                Files.createDirectory(rawManagedDirectory)
            } catch (_: FileAlreadyExistsException) {
                // Another persist won creation; open it through the pinned parent below.
            }
            openCurrentManagedDirectory(parent)
        }

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

    private fun isRegularNonSymlink(directory: SecureDirectoryStream<Path>, finalName: String): Boolean = runCatching {
        val attributes = directory.getFileAttributeView(
            Path.of(finalName),
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).readAttributes()
        attributes.isRegularFile && !attributes.isSymbolicLink
    }.getOrDefault(false)

    private fun deleteGeneratedFiles(directory: SecureDirectoryStream<Path>, names: List<String>) {
        names.forEach { name -> deleteFileIfPresent(directory, name) }
    }

    private fun deleteFileIfPresent(directory: SecureDirectoryStream<Path>, name: String) {
        try {
            directory.deleteFile(Path.of(name))
        } catch (_: NoSuchFileException) {
        }
    }

    private inline fun <T> withTrustedParent(block: (SecureDirectoryStream<Path>) -> T): T {
        Files.newDirectoryStream(trustedFilesPath).use { stream ->
            return block(requireSecureDirectory(stream, "当前平台不支持安全应用文件目录句柄"))
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

    private class BatchRecord(
        val ownerToken: Any,
        val directoryFileKey: Any,
        val attachments: MutableList<PendingImageAttachment> = mutableListOf(),
        val generatedNames: MutableList<String> = mutableListOf(),
    )

    private data class PersistedAttachment(
        val attachment: PendingImageAttachment,
        val finalName: String,
    )

    private companion object {
        const val MANAGED_DIRECTORY_NAME = "chat-attachments"
        val managedFileName = Regex(
            """queued-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png|webp)""",
        )
    }
}
