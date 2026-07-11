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

    fun persist(source: PendingImageAttachment): PendingImageAttachment {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("无法创建会话图片目录")
        }
        val extension = when (source.mimeType.lowercase()) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        val destination = File(directory, "${UUID.randomUUID()}$extension")
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
}
