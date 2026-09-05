package com.harnessapk.chat

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

enum class DraftAttachmentKind {
    IMAGE,
    DOCUMENT,
}

enum class DraftAttachmentState {
    IMPORTING,
    READY,
    FAILED,
    MISSING,
}

enum class DraftStoreErrorCode {
    CORRUPT_DATA,
    UNSUPPORTED_VERSION,
    SAVE_FAILED,
    IMPORT_FAILED,
    ATTACHMENT_MISSING,
}

data class DraftStoreError(
    val code: DraftStoreErrorCode,
    val message: String,
)

sealed interface DraftAttachmentRecord {
    val id: String
    val kind: DraftAttachmentKind
    val sourceUri: String?
    val localUri: String
    val displayName: String
    val mimeType: String
    val sizeBytes: Long
    val sha256: String
    val state: DraftAttachmentState
    val errorMessage: String?
}

data class DraftImageAttachment(
    override val id: String,
    override val sourceUri: String?,
    override val localUri: String,
    override val displayName: String,
    override val mimeType: String,
    override val sizeBytes: Long,
    override val sha256: String,
    override val state: DraftAttachmentState = DraftAttachmentState.READY,
    override val errorMessage: String? = null,
) : DraftAttachmentRecord {
    override val kind: DraftAttachmentKind = DraftAttachmentKind.IMAGE

    fun toPendingImageAttachment(): PendingImageAttachment = PendingImageAttachment(
        uri = Uri.parse(localUri.ifBlank { sourceUri.orEmpty() }),
        mimeType = mimeType,
    )
}

data class DraftDocumentAttachment(
    override val id: String,
    override val sourceUri: String?,
    override val localUri: String,
    override val displayName: String,
    override val mimeType: String,
    override val sizeBytes: Long,
    override val sha256: String,
    val body: String,
    val truncated: Boolean,
    val originalCharCount: Int,
    override val state: DraftAttachmentState = DraftAttachmentState.READY,
    override val errorMessage: String? = null,
) : DraftAttachmentRecord {
    override val kind: DraftAttachmentKind = DraftAttachmentKind.DOCUMENT

    fun toExtractedDocument(): ExtractedDocument = ExtractedDocument(
        uri = localUri.ifBlank { sourceUri.orEmpty() },
        fileName = displayName,
        mimeType = mimeType,
        text = body,
        truncated = truncated,
        originalCharCount = originalCharCount,
    )
}

enum class DraftImportOutcome {
    ADDED,
    DUPLICATE,
    REJECTED,
    FAILED,
}

data class DraftAttachmentImportResult(
    val outcome: DraftImportOutcome,
    val draft: ConversationDraft,
    val attachment: DraftAttachmentRecord? = null,
    val duplicateOfId: String? = null,
    val error: DraftStoreError? = null,
)

class DraftAttachmentFiles(
    private val context: Context,
    private val uriForFile: (File) -> Uri = { file ->
        FileProvider.getUriForFile(context.applicationContext, "${context.packageName}.fileprovider", file)
    },
) {
    internal val appContext: Context = context.applicationContext
    private val root = File(appContext.filesDir, "chat-images/drafts")

    fun createTemporary(conversationId: String, importId: String): DraftOwnedFile {
        val directory = directoryFor(conversationId)
        val file = File(directory, ".importing-$importId.tmp")
        if (!file.createNewFile()) throw IllegalStateException("无法创建附件暂存文件")
        return DraftOwnedFile(file = file, uri = uriForFile(file))
    }

    fun finalize(
        conversationId: String,
        attachmentId: String,
        extension: String,
        temporary: DraftOwnedFile,
    ): Uri {
        val directory = directoryFor(conversationId)
        val safeExtension = extension.takeIf { it.matches(Regex("\\.[a-z0-9]{1,8}")) }.orEmpty()
        val finalFile = File(directory, "$attachmentId$safeExtension")
        if (finalFile.exists()) {
            deleteTemporary(temporary)
            return uriForFile(finalFile)
        }
        try {
            Files.move(
                temporary.file.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            if (!temporary.file.renameTo(finalFile)) throw IllegalStateException("无法保存附件副本")
        } catch (_: UnsupportedOperationException) {
            if (!temporary.file.renameTo(finalFile)) throw IllegalStateException("无法保存附件副本")
        } catch (_: FileAlreadyExistsException) {
            deleteTemporary(temporary)
        } catch (error: Throwable) {
            if (finalFile.exists()) {
                deleteTemporary(temporary)
            } else {
                throw IllegalStateException("无法保存附件副本", error)
            }
        }
        return uriForFile(finalFile)
    }

    fun deleteTemporary(temporary: DraftOwnedFile) {
        if (isOwnedFile(temporary.file)) temporary.file.delete()
    }

    fun isReadableOwnedUri(rawUri: String): Boolean {
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return false
        if (!isOwnedUri(rawUri)) return true
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input -> input.read() }
                ?: throw IllegalStateException("附件不存在")
        }.isSuccess
    }

    fun isOwnedUri(rawUri: String): Boolean {
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return false
        if (uri.scheme == "file") {
            val path = uri.path ?: return false
            return isOwnedFile(File(path))
        }
        return uri.scheme == "content" &&
            uri.authority == "${appContext.packageName}.fileprovider" &&
            uri.pathSegments.firstOrNull() == "chat_images" &&
            uri.pathSegments.getOrNull(1) == "drafts"
    }

    private fun directoryFor(conversationId: String): File = File(
        root,
        sha256Hex(conversationId.encodeToByteArray()).take(32),
    ).apply {
        if (!exists() && !mkdirs()) throw IllegalStateException("无法创建草稿附件目录")
    }

    private fun isOwnedFile(file: File): Boolean {
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return candidate.path == rootCanonical.path || candidate.path.startsWith(rootCanonical.path + File.separator)
    }
}

data class DraftOwnedFile(
    val file: File,
    val uri: Uri,
)

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }
