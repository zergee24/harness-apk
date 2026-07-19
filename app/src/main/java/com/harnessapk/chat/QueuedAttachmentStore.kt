package com.harnessapk.chat

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

class QueuedAttachmentStore internal constructor(
    context: Context,
    private val inputOpener: (Uri) -> InputStream? = { uri ->
        context.applicationContext.contentResolver.openInputStream(uri)
    },
) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "chat-attachments")

    fun persist(source: PendingImageAttachment): PendingImageAttachment = persistAll(listOf(source)).single()

    fun persistAll(sources: List<PendingImageAttachment>): List<PendingImageAttachment> {
        if (sources.isEmpty()) return emptyList()
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException("无法创建会话图片目录")
        }
        val persisted = mutableListOf<PendingImageAttachment>()
        try {
            sources.forEach { source -> persisted += persistOne(source) }
            return persisted
        } catch (error: Throwable) {
            runCatching { cleanup(persisted) }
            throw error
        }
    }

    fun cleanup(attachments: List<PendingImageAttachment>) {
        attachments.forEach { attachment ->
            runCatching {
                managedFileFor(attachment.uri)?.let { file ->
                    file.delete()
                }
            }
        }
    }

    private fun persistOne(source: PendingImageAttachment): PendingImageAttachment {
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
            return PendingImageAttachment(Uri.fromFile(destination), source.mimeType)
        } catch (error: Throwable) {
            temporary.delete()
            destination.delete()
            throw error
        }
    }

    private fun managedFileFor(uri: Uri): File? {
        if (uri.scheme != "file" || !uri.authority.isNullOrEmpty() || uri.path.isNullOrEmpty()) return null
        if (uri.pathSegments.any { it == "." || it == ".." }) return null

        val candidate = File(requireNotNull(uri.path))
        if (!managedFileName.matches(candidate.name)) return null
        if (Files.isSymbolicLink(candidate.toPath())) return null

        val managedDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (canonicalCandidate.parentFile != managedDirectory) return null
        if (candidate.exists() && !Files.isRegularFile(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return canonicalCandidate
    }

    private companion object {
        val managedFileName = Regex(
            """queued-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png|webp)""",
        )
    }
}
