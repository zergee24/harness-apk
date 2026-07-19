package com.harnessapk.chat

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.ArrayList
import java.util.Collections
import java.util.UUID

class PersistedAttachmentBatch internal constructor(
    attachments: List<PendingImageAttachment>,
    private val ownerToken: Any,
    generatedPaths: List<File>,
) {
    val attachments: List<PendingImageAttachment> = Collections.unmodifiableList(ArrayList(attachments))

    private val generatedPaths: List<File> = Collections.unmodifiableList(ArrayList(generatedPaths))

    internal fun isOwnedBy(token: Any): Boolean = ownerToken === token

    internal fun generatedEntries(): List<Pair<PendingImageAttachment, File>> = attachments.zip(generatedPaths)

    internal fun hasMatchingGeneratedEntries(): Boolean = attachments.size == generatedPaths.size
}

class QueuedAttachmentStore internal constructor(
    context: Context,
    private val inputOpener: (Uri) -> InputStream? = { uri ->
        context.applicationContext.contentResolver.openInputStream(uri)
    },
    private val onBatchPersisted: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val trustedFilesDirectory = appContext.filesDir.canonicalFile
    private val directory = File(appContext.filesDir, "chat-attachments")
    private val ownerToken = Any()

    fun persist(source: PendingImageAttachment): PendingImageAttachment = persistAll(listOf(source)).attachments.single()

    fun persistAll(sources: List<PendingImageAttachment>): PersistedAttachmentBatch {
        val managedDirectory = managedDirectoryForPersist()
        val record = BatchRecord(ownerToken)
        try {
            sources.forEach { source ->
                val persisted = persistOne(source, managedDirectory)
                record.attachments += persisted.attachment
                record.generatedPaths += persisted.file
            }
            onBatchPersisted()
            return PersistedAttachmentBatch(
                attachments = record.attachments,
                ownerToken = record.ownerToken,
                generatedPaths = record.generatedPaths,
            )
        } catch (error: Throwable) {
            cleanup(record)
            throw error
        }
    }

    fun cleanup(batch: PersistedAttachmentBatch) {
        if (!batch.isOwnedBy(ownerToken)) return
        if (!batch.hasMatchingGeneratedEntries()) return
        batch.generatedEntries().forEach { (attachment, generatedPath) ->
            runCatching {
                managedFileFor(attachment.uri, generatedPath)?.let { file ->
                    Files.deleteIfExists(file.toPath())
                }
            }
        }
    }

    private fun cleanup(record: BatchRecord) {
        if (record.ownerToken !== ownerToken) return
        record.attachments.zip(record.generatedPaths).forEach { (attachment, generatedPath) ->
            runCatching {
                managedFileFor(attachment.uri, generatedPath)?.let { file ->
                    Files.deleteIfExists(file.toPath())
                }
            }
        }
    }

    private fun persistOne(source: PendingImageAttachment, managedDirectory: File): PersistedAttachment {
        val extension = when (source.mimeType.lowercase()) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        val destination = File(managedDirectory, "queued-${UUID.randomUUID()}$extension")
        val temporary = File(managedDirectory, ".${destination.name}.tmp")
        try {
            inputOpener(source.uri).use { input ->
                requireNotNull(input) { "无法读取图片" }
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (!temporary.renameTo(destination)) {
                throw IllegalStateException("无法保存图片")
            }
            return PersistedAttachment(
                attachment = PendingImageAttachment(Uri.fromFile(destination), source.mimeType),
                file = destination,
            )
        } catch (error: Throwable) {
            deleteGeneratedPathIfSafe(temporary)
            deleteGeneratedPathIfSafe(destination)
            throw error
        }
    }

    private fun managedFileFor(uri: Uri, generatedPath: File): File? {
        if (uri.scheme != "file" || !uri.authority.isNullOrEmpty() || uri.path.isNullOrEmpty()) return null
        if (uri.pathSegments.any { it == "." || it == ".." }) return null

        val managedDirectory = managedDirectoryForCleanup() ?: return null
        val candidate = File(requireNotNull(uri.path))
        if (candidate.absoluteFile != generatedPath.absoluteFile) return null
        if (candidate.parentFile?.absoluteFile != managedDirectory.absoluteFile) return null
        if (!managedFileName.matches(candidate.name)) return null
        if (Files.isSymbolicLink(candidate.toPath())) return null

        val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (canonicalCandidate.parentFile != managedDirectory) return null
        if (
            Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            return null
        }
        return canonicalCandidate
    }

    private fun managedDirectoryForPersist(): File {
        val path = directory.toPath()
        if (Files.isSymbolicLink(path)) {
            throw IllegalStateException("会话图片目录不能是符号链接")
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(path)
            } catch (error: Throwable) {
                if (Files.isSymbolicLink(path)) {
                    throw IllegalStateException("会话图片目录不能是符号链接", error)
                }
                throw error
            }
        }
        return requireManagedDirectory()
    }

    private fun managedDirectoryForCleanup(): File? = runCatching { requireManagedDirectory() }.getOrNull()

    private fun requireManagedDirectory(): File {
        val path = directory.toPath()
        require(!Files.isSymbolicLink(path)) { "会话图片目录不能是符号链接" }
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "会话图片目录无效" }
        val canonicalDirectory = directory.canonicalFile
        require(canonicalDirectory.parentFile == trustedFilesDirectory) { "会话图片目录越出应用文件目录" }
        return canonicalDirectory
    }

    private fun deleteGeneratedPathIfSafe(file: File) {
        val managedDirectory = managedDirectoryForCleanup() ?: return
        if (file.parentFile?.absoluteFile != managedDirectory.absoluteFile) return
        if (Files.isSymbolicLink(file.toPath())) return
        if (
            Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            return
        }
        runCatching { Files.deleteIfExists(file.toPath()) }
    }

    private class BatchRecord(
        val ownerToken: Any,
        val attachments: MutableList<PendingImageAttachment> = mutableListOf(),
        val generatedPaths: MutableList<File> = mutableListOf(),
    )

    private data class PersistedAttachment(
        val attachment: PendingImageAttachment,
        val file: File,
    )

    private companion object {
        val managedFileName = Regex(
            """queued-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png|webp)""",
        )
    }
}
