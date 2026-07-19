package com.harnessapk.chat

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

class PersistedAttachmentBatch internal constructor(
    attachments: List<PendingImageAttachment>,
    private val ownerToken: Any,
    generatedPaths: List<File>,
) {
    val attachments: List<PendingImageAttachment> = attachments.toList()

    private val generatedPaths: List<File> = generatedPaths.toList()

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
    private val directory = File(appContext.filesDir, "chat-attachments")
    private val ownerToken = Any()

    fun persist(source: PendingImageAttachment): PendingImageAttachment = persistAll(listOf(source)).attachments.single()

    fun persistAll(sources: List<PendingImageAttachment>): PersistedAttachmentBatch {
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException("无法创建会话图片目录")
        }
        val record = BatchRecord(ownerToken)
        try {
            sources.forEach { source ->
                val persisted = persistOne(source)
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
                    file.delete()
                }
            }
        }
    }

    private fun cleanup(record: BatchRecord) {
        if (record.ownerToken !== ownerToken) return
        record.attachments.zip(record.generatedPaths).forEach { (attachment, generatedPath) ->
            runCatching {
                managedFileFor(attachment.uri, generatedPath)?.delete()
            }
        }
    }

    private fun persistOne(source: PendingImageAttachment): PersistedAttachment {
        val extension = when (source.mimeType.lowercase()) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        val destination = File(directory, "queued-${UUID.randomUUID()}$extension")
        val temporary = File(directory, ".${destination.name}.tmp")
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
            temporary.delete()
            destination.delete()
            throw error
        }
    }

    private fun managedFileFor(uri: Uri, generatedPath: File): File? {
        if (uri.scheme != "file" || !uri.authority.isNullOrEmpty() || uri.path.isNullOrEmpty()) return null
        if (uri.pathSegments.any { it == "." || it == ".." }) return null

        val candidate = File(requireNotNull(uri.path))
        if (candidate.absoluteFile != generatedPath.absoluteFile) return null
        if (!managedFileName.matches(candidate.name)) return null
        if (Files.isSymbolicLink(candidate.toPath())) return null

        val managedDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: return null
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
