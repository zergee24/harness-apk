package com.harnessapk.capture

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

class CaptureStagingStore(
    context: Context,
    private val outputOpener: (File) -> OutputStream = File::outputStream,
    private val inputOpener: (Uri) -> InputStream? = { uri ->
        context.applicationContext.contentResolver.openInputStream(uri)
    },
) {
    private val root = context.applicationContext.cacheDir.resolve("capture-staging")

    fun stage(
        draftId: String,
        request: IncomingShareRequest,
        onProgress: (completedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): List<CaptureItem> {
        require(request.items.size <= MAX_CAPTURE_ITEMS) { "一次最多分享 $MAX_CAPTURE_ITEMS 个文件" }
        request.items.firstOrNull { (it.declaredSizeBytes ?: 0L) > MAX_CAPTURE_ITEM_BYTES }?.let {
            error("单个文件不能超过 50 MiB：${it.displayName}")
        }
        val declaredTotal = request.items.mapNotNull(IncomingShareItem::declaredSizeBytes)
            .takeIf { it.size == request.items.size }
            ?.sum()
        require(declaredTotal == null || declaredTotal <= MAX_CAPTURE_TOTAL_BYTES) { "一次分享不能超过 100 MiB" }

        val draftDirectory = root.resolve(draftId)
        draftDirectory.deleteRecursively()
        check(draftDirectory.mkdirs()) { "无法创建分享暂存目录" }
        var totalCopied = 0L
        return try {
            request.items.mapIndexed { index, item ->
                val safeName = safeCaptureFileName(item.displayName, index)
                val temporary = draftDirectory.resolve(".$safeName.tmp")
                val destination = draftDirectory.resolve(safeName)
                val digest = MessageDigest.getInstance("SHA-256")
                var itemBytes = 0L
                inputOpener(Uri.parse(item.sourceUri)).use { input ->
                    requireNotNull(input) { "无法读取分享文件：${item.displayName}" }
                    outputOpener(temporary).buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            itemBytes += count
                            totalCopied += count
                            require(itemBytes <= MAX_CAPTURE_ITEM_BYTES) { "单个文件不能超过 50 MiB：${item.displayName}" }
                            require(totalCopied <= MAX_CAPTURE_TOTAL_BYTES) { "一次分享不能超过 100 MiB" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            onProgress(totalCopied, declaredTotal)
                        }
                    }
                }
                check(temporary.renameTo(destination)) { "无法完成分享文件暂存：${item.displayName}" }
                CaptureItem(
                    id = UUID.randomUUID().toString(),
                    kind = if (item.mimeType.startsWith("image/", ignoreCase = true)) {
                        CaptureItemKind.IMAGE
                    } else {
                        CaptureItemKind.FILE
                    },
                    displayName = item.displayName,
                    mimeType = item.mimeType,
                    localUri = Uri.fromFile(destination).toString(),
                    sizeBytes = itemBytes,
                    sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
                )
            }
        } catch (error: Throwable) {
            draftDirectory.deleteRecursively()
            throw error
        }
    }

    fun cleanup(draftId: String) {
        root.resolve(draftId).deleteRecursively()
    }

    private fun safeCaptureFileName(displayName: String, index: Int): String {
        val base = File(displayName).name
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim('.', ' ')
            .take(120)
            .ifBlank { "shared-file" }
        return "${index + 1}-$base"
    }
}
