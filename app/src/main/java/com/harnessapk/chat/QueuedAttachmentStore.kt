package com.harnessapk.chat

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

class QueuedAttachmentStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "chat-attachments")

    fun persist(source: PendingImageAttachment): PendingImageAttachment = persistAll(listOf(source)).single()

    fun persistAll(sources: List<PendingImageAttachment>): List<PendingImageAttachment> {
        if (sources.isEmpty()) return emptyList()
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("无法创建会话图片目录")
        }
        val persisted = mutableListOf<PendingImageAttachment>()
        try {
            sources.forEach { source -> persisted += persistOne(source) }
            return persisted
        } catch (error: Throwable) {
            cleanup(persisted)
            throw error
        }
    }

    fun cleanup(attachments: List<PendingImageAttachment>) {
        attachments.forEach { attachment ->
            managedFileFor(attachment.uri)?.let { file ->
                runCatching { file.delete() }
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
            appContext.contentResolver.openInputStream(source.uri).use { input ->
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

        val managedDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(requireNotNull(uri.path)).canonicalFile }.getOrNull() ?: return null
        if (candidate.parentFile != managedDirectory) return null
        if (!candidate.name.startsWith("queued-")) return null
        if (candidate.exists() && !candidate.isFile) return null
        return candidate
    }
}
