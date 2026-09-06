package com.harnessapk.chat

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val CURRENT_CONVERSATION_DRAFT_SCHEMA_VERSION: Int = 2

/**
 * The old text/images projection remains available to existing chat code.  The
 * typed records are the source of truth for newly imported attachments.
 */
data class ConversationDraft(
    val text: String = "",
    val attachments: List<PendingImageAttachment> = emptyList(),
    val documents: List<DraftDocumentAttachment> = emptyList(),
    val imageMetadata: List<DraftImageAttachment> = emptyList(),
    val updatedAt: Long = 0L,
    val schemaVersion: Int = CURRENT_CONVERSATION_DRAFT_SCHEMA_VERSION,
    val error: DraftStoreError? = null,
    val conversationId: String? = null,
)

data class ConversationDraftEntry(
    val conversationId: String,
    val draft: ConversationDraft,
)

sealed interface DraftSaveResult {
    data class Saved(val draft: ConversationDraft) : DraftSaveResult

    data class Failed(
        val draft: ConversationDraft,
        val error: DraftStoreError,
    ) : DraftSaveResult
}

/** A small synchronous seam so tests can model a failed durable write. */
interface DraftStorageBackend {
    fun getString(key: String): String?
    fun allStrings(): Map<String, String>
    fun commitPut(key: String, value: String): Boolean
    fun commitRemove(key: String): Boolean
}

private class SharedPreferencesDraftStorage(context: Context) : DraftStorageBackend {
    private val preferences = context.applicationContext.getSharedPreferences(
        "conversation_drafts",
        Context.MODE_PRIVATE,
    )

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun allStrings(): Map<String, String> = preferences.all.mapNotNull { (key, value) ->
        value as? String ?: return@mapNotNull null
        key to value
    }.toMap()

    override fun commitPut(key: String, value: String): Boolean = preferences.edit()
        .putString(key, value)
        .commit()

    override fun commitRemove(key: String): Boolean = preferences.edit()
        .remove(key)
        .commit()
}

/**
 * Stores the user-editable draft separately from messages and queue records.
 * Import operations run on IO, while each durable state transition uses a
 * synchronous commit so a success result means the snapshot is actually on
 * disk.
 */
class ConversationDraftStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val storageBackend: DraftStorageBackend = SharedPreferencesDraftStorage(context),
    private val attachmentFiles: DraftAttachmentFiles = DraftAttachmentFiles(context),
    private val sourceOpener: (Uri) -> InputStream? = { uri ->
        context.applicationContext.contentResolver.openInputStream(uri)
    },
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val activeImportIds = mutableSetOf<String>()
    private val _allDrafts = MutableStateFlow<List<ConversationDraftEntry>>(emptyList())
    val allDrafts: StateFlow<List<ConversationDraftEntry>> = _allDrafts.asStateFlow()

    init {
        refreshAll()
    }

    fun observeAll(): Flow<List<ConversationDraftEntry>> = allDrafts

    fun load(conversationId: String): ConversationDraft {
        synchronized(lock) {
            val raw = storageBackend.getString(conversationId) ?: return ConversationDraft()
            val decoded = decodeConversationDraftSnapshot(raw, json)
            return normalizeLoaded(decoded, conversationId)
        }
    }

    /**
     * Validates a draft assembled by recovery before it is handed to the UI
     * or persisted. This deliberately has no storage or timestamp side effect.
     */
    fun validateSnapshot(conversationId: String, draft: ConversationDraft): ConversationDraft =
        synchronized(lock) {
            normalizeLoaded(draft, conversationId)
        }

    /**
     * Commits a whole draft snapshot.  A malformed or future-version snapshot
     * is never replaced by a new value because the old raw value may be the
     * only recoverable copy.
     */
    fun save(conversationId: String, draft: ConversationDraft): DraftSaveResult = synchronized(lock) {
        val previousRaw = storageBackend.getString(conversationId)
        val previous = previousRaw?.let { decodeConversationDraftSnapshot(it, json) }
        if (previous?.error?.code == DraftStoreErrorCode.CORRUPT_DATA ||
            previous?.error?.code == DraftStoreErrorCode.UNSUPPORTED_VERSION
        ) {
            return@synchronized DraftSaveResult.Failed(
                draft = draft.copy(conversationId = conversationId),
                error = previous.error ?: corruptError(),
            )
        }

        val candidate = draft.copy(
            conversationId = conversationId,
            schemaVersion = CURRENT_CONVERSATION_DRAFT_SCHEMA_VERSION,
        )
        if (isEmptyDraft(candidate)) {
            val removed = storageBackend.commitRemove(conversationId)
            if (!removed) {
                return@synchronized DraftSaveResult.Failed(
                    draft = candidate,
                    error = DraftStoreError(DraftStoreErrorCode.SAVE_FAILED, "草稿未能保存，请稍后重试"),
                )
            }
            refreshAll()
            return@synchronized DraftSaveResult.Saved(ConversationDraft())
        }

        val priorDraft = previous?.takeUnless {
            it.error?.code == DraftStoreErrorCode.CORRUPT_DATA ||
                it.error?.code == DraftStoreErrorCode.UNSUPPORTED_VERSION
        }
        val contentUnchanged = priorDraft != null && sameDraftContent(priorDraft, candidate)
        val timestamp = when {
            contentUnchanged && priorDraft.updatedAt > 0L -> {
                priorDraft.updatedAt
            }
            priorDraft == null && candidate.updatedAt > 0L -> candidate.updatedAt
            else -> nowMillis()
        }
        val persisted = candidate.copy(updatedAt = timestamp)
        val raw = encodeConversationDraftSnapshot(persisted, json)
        if (!storageBackend.commitPut(conversationId, raw)) {
            return@synchronized DraftSaveResult.Failed(
                draft = persisted,
                error = DraftStoreError(DraftStoreErrorCode.SAVE_FAILED, "草稿未能保存，请稍后重试"),
            )
        }
        refreshAll()
        DraftSaveResult.Saved(persisted)
    }

    fun clear(conversationId: String): DraftSaveResult = synchronized(lock) {
        if (!storageBackend.commitRemove(conversationId)) {
            return@synchronized DraftSaveResult.Failed(
                draft = load(conversationId),
                error = DraftStoreError(DraftStoreErrorCode.SAVE_FAILED, "草稿未能删除，请稍后重试"),
            )
        }
        refreshAll()
        DraftSaveResult.Saved(ConversationDraft())
    }

    suspend fun importImage(
        conversationId: String,
        sourceUri: Uri,
        mimeType: String,
    ): DraftAttachmentImportResult = withContext(ioDispatcher) {
        importAttachment(
            conversationId = conversationId,
            sourceUri = sourceUri,
            mimeType = mimeType,
            kind = DraftAttachmentKind.IMAGE,
        )
    }

    suspend fun importDocument(
        conversationId: String,
        sourceUri: Uri,
        mimeType: String,
    ): DraftAttachmentImportResult = withContext(ioDispatcher) {
        importAttachment(
            conversationId = conversationId,
            sourceUri = sourceUri,
            mimeType = mimeType,
            kind = DraftAttachmentKind.DOCUMENT,
        )
    }

    private suspend fun importAttachment(
        conversationId: String,
        sourceUri: Uri,
        mimeType: String,
        kind: DraftAttachmentKind,
    ): DraftAttachmentImportResult {
        val current = load(conversationId)
        val sourceString = sourceUri.toString()
        val legacyImageUriToReplace = if (kind == DraftAttachmentKind.IMAGE) {
            current.attachments.firstOrNull { attachment ->
                attachment.uri.toString() == sourceString &&
                    current.imageMetadata.none { metadata -> metadata.localUri == sourceString }
            }?.uri?.toString()
        } else {
            null
        }
        val limit = when (kind) {
            DraftAttachmentKind.IMAGE -> MAX_IMAGES_PER_DRAFT
            DraftAttachmentKind.DOCUMENT -> DocumentTextExtractor.MAX_DOCUMENTS_PER_MESSAGE
        }
        val count = when (kind) {
            DraftAttachmentKind.IMAGE -> maxOf(current.attachments.size, current.imageMetadata.size)
            DraftAttachmentKind.DOCUMENT -> current.documents.size
        }
        if (count >= limit && legacyImageUriToReplace == null) {
            return rejected(
                current,
                "附件数量已达上限（${limit} 个）",
            )
        }

        val displayName = runCatching { DocumentTextExtractor.displayName(contextForFiles(), sourceUri) }
            .getOrDefault(sourceUri.lastPathSegment ?: "document")
        val resolvedMimeType = mimeType.ifBlank {
            runCatching { contextForFiles().contentResolver.getType(sourceUri).orEmpty() }.getOrDefault("")
        }
        if (kind == DraftAttachmentKind.IMAGE && !resolvedMimeType.startsWith("image/")) {
            return rejected(current, "请选择图片文件")
        }
        if (kind == DraftAttachmentKind.DOCUMENT && !isSupportedDocument(displayName, resolvedMimeType)) {
            return rejected(current, "暂不支持该文件类型，可选 PDF / Word / Excel / CSV / TXT")
        }

        val importId = UUID.randomUUID().toString()
        val markerId = "${kind.name.lowercase()}-importing-$importId"
        val temporary = try {
            attachmentFiles.createTemporary(conversationId, importId)
        } catch (error: Throwable) {
            return failed(
                current,
                null,
                DraftStoreError(DraftStoreErrorCode.IMPORT_FAILED, error.message ?: "无法创建附件暂存文件"),
            )
        }
        val importingRecord: DraftAttachmentRecord = when (kind) {
            DraftAttachmentKind.IMAGE -> DraftImageAttachment(
                id = markerId,
                sourceUri = sourceString,
                localUri = temporary.uri.toString(),
                displayName = displayName,
                mimeType = resolvedMimeType,
                sizeBytes = 0L,
                sha256 = "",
                state = DraftAttachmentState.IMPORTING,
            )
            DraftAttachmentKind.DOCUMENT -> DraftDocumentAttachment(
                id = markerId,
                sourceUri = sourceString,
                localUri = temporary.uri.toString(),
                displayName = displayName,
                mimeType = resolvedMimeType,
                sizeBytes = 0L,
                sha256 = "",
                body = "",
                truncated = false,
                originalCharCount = 0,
                state = DraftAttachmentState.IMPORTING,
            )
        }
        synchronized(lock) { activeImportIds += markerId }
        try {
            val markerDraft = appendRecord(current, importingRecord)
            when (val markerSave = save(conversationId, markerDraft)) {
                is DraftSaveResult.Failed -> {
                    attachmentFiles.deleteTemporary(temporary)
                    return failed(markerSave.draft, importingRecord, markerSave.error)
                }
                is DraftSaveResult.Saved -> Unit
            }

            currentCoroutineContext().ensureActive()
            val maxBytes = when (kind) {
                DraftAttachmentKind.IMAGE -> MAX_IMAGE_BYTES
                DraftAttachmentKind.DOCUMENT -> DocumentTextExtractor.MAX_FILE_BYTES
            }
            val copied = try {
            copyBounded(sourceUri, temporary.file, maxBytes)
            } catch (error: AttachmentTooLargeException) {
                attachmentFiles.deleteTemporary(temporary)
                val latest = removeRecord(load(conversationId), markerId)
                save(conversationId, latest)
                return rejected(
                    latest,
                    error.message ?: sizeLimitMessage(kind),
                )
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val failedRecord = importingRecord.withFailure(error.message ?: "无法读取所选文件")
                val failedDraft = persistFailedRecord(conversationId, markerId, failedRecord)
                return failed(
                    failedDraft,
                    failedRecord,
                    DraftStoreError(DraftStoreErrorCode.IMPORT_FAILED, failedRecord.errorMessage.orEmpty()),
                )
            }

            currentCoroutineContext().ensureActive()
            val duplicate = findDuplicate(load(conversationId), kind, copied.sizeBytes, copied.sha256)
            if (duplicate != null) {
                attachmentFiles.deleteTemporary(temporary)
                val latest = removeRecord(load(conversationId), markerId)
                save(conversationId, latest)
                return DraftAttachmentImportResult(
                    outcome = DraftImportOutcome.DUPLICATE,
                    draft = latest,
                    attachment = duplicate,
                    duplicateOfId = duplicate.id,
                )
            }
            val failedRetry = findFailed(
                draft = load(conversationId),
                kind = kind,
                sourceUri = sourceString,
                sizeBytes = copied.sizeBytes,
                sha256 = copied.sha256,
            )

            val attachmentId = "${kind.name.lowercase()}-${copied.sha256}"
            val finalUri = try {
                attachmentFiles.finalize(
                    conversationId = conversationId,
                    attachmentId = attachmentId,
                    extension = extensionFor(displayName, resolvedMimeType),
                    temporary = temporary,
                )
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val failedRecord = importingRecord.withFailure(error.message ?: "无法保存附件副本")
                val failedDraft = persistFailedRecord(conversationId, markerId, failedRecord)
                return failed(
                    failedDraft,
                    failedRecord,
                    DraftStoreError(DraftStoreErrorCode.IMPORT_FAILED, failedRecord.errorMessage.orEmpty()),
                )
            }

            currentCoroutineContext().ensureActive()
            val materializedRecord = if (kind == DraftAttachmentKind.DOCUMENT) {
                val extraction = runCatching { DocumentTextExtractor.extract(contextForFiles(), finalUri) }
                extraction.exceptionOrNull()?.let { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                }
                when {
                    extraction.isFailure -> {
                        val failedRecord = DraftDocumentAttachment(
                            id = attachmentId,
                            sourceUri = sourceString,
                            localUri = finalUri.toString(),
                            displayName = displayName,
                            mimeType = resolvedMimeType,
                            sizeBytes = copied.sizeBytes,
                            sha256 = copied.sha256,
                            body = "",
                            truncated = false,
                            originalCharCount = 0,
                        ).withFailure(extraction.exceptionOrNull()?.message ?: "无法提取文档正文")
                        val failedDraft = persistFailedRecord(conversationId, markerId, failedRecord)
                        return failed(
                            failedDraft,
                            failedRecord,
                            DraftStoreError(DraftStoreErrorCode.IMPORT_FAILED, failedRecord.errorMessage.orEmpty()),
                        )
                    }
                    extraction.getOrThrow() is DocumentExtractionResult.ScannedPdf -> {
                        val failedRecord = DraftDocumentAttachment(
                            id = attachmentId,
                            sourceUri = sourceString,
                            localUri = finalUri.toString(),
                            displayName = displayName,
                            mimeType = resolvedMimeType,
                            sizeBytes = copied.sizeBytes,
                            sha256 = copied.sha256,
                            body = "",
                            truncated = false,
                            originalCharCount = 0,
                            state = DraftAttachmentState.FAILED,
                            errorMessage = "扫描版 PDF 需转为图片后再添加",
                        )
                        val failedDraft = persistFailedRecord(conversationId, markerId, failedRecord)
                        return failed(
                            failedDraft,
                            failedRecord,
                            DraftStoreError(DraftStoreErrorCode.IMPORT_FAILED, failedRecord.errorMessage.orEmpty()),
                        )
                    }
                    else -> {
                        val document = (extraction.getOrThrow() as DocumentExtractionResult.TextDocument).document
                        DraftDocumentAttachment(
                            id = attachmentId,
                            sourceUri = sourceString,
                            localUri = finalUri.toString(),
                            displayName = displayName,
                            mimeType = resolvedMimeType,
                            sizeBytes = copied.sizeBytes,
                            sha256 = copied.sha256,
                            body = document.text,
                            truncated = document.truncated,
                            originalCharCount = document.originalCharCount,
                        )
                    }
                }
            } else {
                DraftImageAttachment(
                    id = attachmentId,
                    sourceUri = sourceString,
                    localUri = finalUri.toString(),
                    displayName = displayName,
                    mimeType = resolvedMimeType,
                    sizeBytes = copied.sizeBytes,
                    sha256 = copied.sha256,
                )
            }

            currentCoroutineContext().ensureActive()
            val latestBeforeReplacement = failedRetry?.let { retry ->
                removeRecord(load(conversationId), retry.id)
            } ?: load(conversationId)
            val latest = replaceRecord(
                draft = latestBeforeReplacement,
                markerId = markerId,
                replacement = materializedRecord,
                legacyImageUriToReplace = legacyImageUriToReplace,
            ).clearResolvedAttachmentError()
            return when (val saved = save(conversationId, latest)) {
                is DraftSaveResult.Saved -> DraftAttachmentImportResult(
                    outcome = DraftImportOutcome.ADDED,
                    draft = saved.draft,
                    attachment = materializedRecord,
                )
                is DraftSaveResult.Failed -> failed(
                    saved.draft,
                    materializedRecord.withFailure(saved.error.message),
                    saved.error,
                )
            }
        } finally {
            synchronized(lock) { activeImportIds.remove(markerId) }
        }
    }

    private fun contextForFiles(): Context = attachmentFiles.appContext

    private fun refreshAll() {
        val entries = storageBackend.allStrings().mapNotNull { (conversationId, raw) ->
            if (raw.isBlank()) return@mapNotNull null
            ConversationDraftEntry(
                conversationId = conversationId,
                draft = normalizeLoaded(decodeConversationDraftSnapshot(raw, json), conversationId),
            )
        }.sortedWith(
            compareByDescending<ConversationDraftEntry> { it.draft.updatedAt }
                .thenBy { it.conversationId },
        )
        _allDrafts.value = entries
    }

    private fun normalizeLoaded(draft: ConversationDraft, conversationId: String): ConversationDraft {
        var rootError = draft.error
        val activeImports = synchronized(lock) { activeImportIds.toSet() }
        val images = draft.imageMetadata.map { image ->
            when {
                image.state == DraftAttachmentState.IMPORTING && image.id !in activeImports -> {
                    val message = "附件导入中断，可重新选择"
                    rootError = rootError ?: DraftStoreError(DraftStoreErrorCode.IMPORT_FAILED, message)
                    image.copy(state = DraftAttachmentState.FAILED, errorMessage = message)
                }
                image.state == DraftAttachmentState.READY && image.localUri.isBlank() -> {
                    val message = "附件副本不存在：${image.displayName}"
                    rootError = rootError ?: DraftStoreError(DraftStoreErrorCode.ATTACHMENT_MISSING, message)
                    image.copy(state = DraftAttachmentState.MISSING, errorMessage = message)
                }
                image.state == DraftAttachmentState.READY && image.privateCopyMissing() -> {
                    val message = "附件副本不存在：${image.displayName}"
                    rootError = rootError ?: DraftStoreError(DraftStoreErrorCode.ATTACHMENT_MISSING, message)
                    image.copy(state = DraftAttachmentState.MISSING, errorMessage = message)
                }
                else -> image
            }
        }
        val documents = draft.documents.map { document ->
            when {
                document.state == DraftAttachmentState.IMPORTING && document.id !in activeImports -> {
                    val message = "附件导入中断，可重新选择"
                    rootError = rootError ?: DraftStoreError(DraftStoreErrorCode.IMPORT_FAILED, message)
                    document.copy(state = DraftAttachmentState.FAILED, errorMessage = message)
                }
                document.state == DraftAttachmentState.READY && document.localUri.isBlank() -> {
                    val message = "附件副本不存在：${document.displayName}"
                    rootError = rootError ?: DraftStoreError(DraftStoreErrorCode.ATTACHMENT_MISSING, message)
                    document.copy(state = DraftAttachmentState.MISSING, errorMessage = message)
                }
                document.state == DraftAttachmentState.READY && document.privateCopyMissing() -> {
                    val message = "附件副本不存在：${document.displayName}"
                    rootError = rootError ?: DraftStoreError(DraftStoreErrorCode.ATTACHMENT_MISSING, message)
                    document.copy(state = DraftAttachmentState.MISSING, errorMessage = message)
                }
                else -> document
            }
        }
        val metadataByLocalUri = images.associateBy { it.localUri }
        val projectedAttachments = if (draft.attachments.isNotEmpty()) {
            draft.attachments.filterNot { attachment ->
                when (metadataByLocalUri[attachment.uri.toString()]?.state) {
                    DraftAttachmentState.FAILED, DraftAttachmentState.MISSING -> true
                    else -> false
                }
            }
        } else {
            images.filter { image ->
                image.state == DraftAttachmentState.READY ||
                    (image.state == DraftAttachmentState.IMPORTING && image.id in activeImports)
            }.map { it.toPendingImageAttachment() }
        }
        return draft.copy(
            conversationId = conversationId,
            imageMetadata = images,
            documents = documents,
            attachments = projectedAttachments,
            error = rootError,
        )
    }

    /**
     * New imports carry their picker URI as sourceUri and must resolve to an
     * app-owned copy. Records reconstructed from legacy/recovery snapshots
     * have no sourceUri and remain compatible with their external URI.
     */
    private fun DraftAttachmentRecord.privateCopyMissing(): Boolean {
        if (localUri.isBlank()) return true
        if (!sourceUri.isNullOrBlank() && !attachmentFiles.isOwnedUri(localUri)) return true
        return attachmentFiles.isOwnedUri(localUri) && !attachmentFiles.isReadableOwnedUri(localUri)
    }

    private fun persistFailedRecord(
        conversationId: String,
        markerId: String,
        failedRecord: DraftAttachmentRecord,
    ): ConversationDraft {
        val latest = replaceRecord(load(conversationId), markerId, failedRecord).copy(
            error = DraftStoreError(
                DraftStoreErrorCode.IMPORT_FAILED,
                failedRecord.errorMessage ?: "附件导入失败",
            ),
        )
        return when (val result = save(conversationId, latest)) {
            is DraftSaveResult.Saved -> result.draft
            is DraftSaveResult.Failed -> result.draft
        }
    }

    private fun appendRecord(draft: ConversationDraft, record: DraftAttachmentRecord): ConversationDraft = when (record) {
        is DraftImageAttachment -> draft.copy(
            attachments = draft.attachments + record.toPendingImageAttachment(),
            imageMetadata = draft.imageMetadata + record,
        )
        is DraftDocumentAttachment -> draft.copy(documents = draft.documents + record)
    }

    private fun replaceRecord(
        draft: ConversationDraft,
        markerId: String,
        replacement: DraftAttachmentRecord,
        legacyImageUriToReplace: String? = null,
    ): ConversationDraft {
        return when (replacement) {
            is DraftImageAttachment -> {
                val previousLocalUri = draft.imageMetadata.firstOrNull { it.id == markerId }?.localUri
                val replacementIsReady = replacement.state == DraftAttachmentState.READY
                draft.copy(
                    attachments = draft.attachments.mapNotNull { attachment ->
                        when {
                            attachment.uri.toString() == legacyImageUriToReplace && replacementIsReady ->
                                replacement.toPendingImageAttachment()
                            attachment.uri.toString() == previousLocalUri ||
                                attachment.uri.toString().contains(markerId) ->
                                if (replacementIsReady && legacyImageUriToReplace == null) {
                                    replacement.toPendingImageAttachment()
                                } else {
                                    null
                                }
                            else -> attachment
                        }
                    },
                    imageMetadata = draft.imageMetadata.map { if (it.id == markerId) replacement else it }
                        .filterIsInstance<DraftImageAttachment>(),
                )
            }
            is DraftDocumentAttachment -> draft.copy(
                documents = draft.documents.map { if (it.id == markerId) replacement else it },
            )
        }
    }

    private fun removeRecord(draft: ConversationDraft, markerId: String): ConversationDraft {
        val markerLocalUri = draft.imageMetadata.firstOrNull { it.id == markerId }?.localUri
        return draft.copy(
            attachments = draft.attachments.filterNot {
                it.uri.toString() == markerLocalUri || it.uri.toString().contains(markerId)
            },
            imageMetadata = draft.imageMetadata.filterNot { it.id == markerId },
            documents = draft.documents.filterNot { it.id == markerId },
        )
    }

    private fun findDuplicate(
        draft: ConversationDraft,
        kind: DraftAttachmentKind,
        sizeBytes: Long,
        sha256: String,
    ): DraftAttachmentRecord? = when (kind) {
        DraftAttachmentKind.IMAGE -> draft.imageMetadata.firstOrNull {
            it.sizeBytes == sizeBytes && it.sha256 == sha256 &&
                it.state == DraftAttachmentState.READY
        }
        DraftAttachmentKind.DOCUMENT -> draft.documents.firstOrNull {
            it.sizeBytes == sizeBytes && it.sha256 == sha256 &&
                it.state == DraftAttachmentState.READY
        }
    }

    private fun findFailed(
        draft: ConversationDraft,
        kind: DraftAttachmentKind,
        sourceUri: String,
        sizeBytes: Long,
        sha256: String,
    ): DraftAttachmentRecord? = when (kind) {
        DraftAttachmentKind.IMAGE -> draft.imageMetadata.firstOrNull {
            it.state in setOf(DraftAttachmentState.FAILED, DraftAttachmentState.MISSING) &&
                ((it.sizeBytes == sizeBytes && it.sha256 == sha256) || it.sourceUri == sourceUri)
        }
        DraftAttachmentKind.DOCUMENT -> draft.documents.firstOrNull {
            it.state in setOf(DraftAttachmentState.FAILED, DraftAttachmentState.MISSING) &&
                ((it.sizeBytes == sizeBytes && it.sha256 == sha256) || it.sourceUri == sourceUri)
        }
    }

    private suspend fun copyBounded(sourceUri: Uri, destination: java.io.File, maxBytes: Long): CopiedAttachment {
        val input = sourceOpener(sourceUri) ?: throw IllegalArgumentException("无法读取所选文件")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        try {
            input.use { source ->
                destination.outputStream().use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count.toLong()
                        if (size > maxBytes) throw AttachmentTooLargeException(sizeLimitMessageFor(maxBytes))
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (error: AttachmentTooLargeException) {
            throw error
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw IllegalArgumentException(error.message ?: "无法读取所选文件", error)
        }
        return CopiedAttachment(sizeBytes = size, sha256 = digest.digest().toHex())
    }

    private fun DraftAttachmentRecord.withFailure(message: String): DraftAttachmentRecord = when (this) {
        is DraftImageAttachment -> copy(state = DraftAttachmentState.FAILED, errorMessage = message)
        is DraftDocumentAttachment -> copy(state = DraftAttachmentState.FAILED, errorMessage = message)
    }

    private fun rejected(draft: ConversationDraft, message: String): DraftAttachmentImportResult =
        DraftAttachmentImportResult(
            outcome = DraftImportOutcome.REJECTED,
            draft = draft,
            error = DraftStoreError(DraftStoreErrorCode.IMPORT_FAILED, message),
        )

    private fun failed(
        draft: ConversationDraft,
        attachment: DraftAttachmentRecord?,
        error: DraftStoreError,
    ): DraftAttachmentImportResult = DraftAttachmentImportResult(
        outcome = DraftImportOutcome.FAILED,
        draft = draft,
        attachment = attachment,
        error = error,
    )

    private fun isEmptyDraft(draft: ConversationDraft): Boolean =
        draft.text.isEmpty() && draft.attachments.isEmpty() &&
            draft.documents.isEmpty() && draft.imageMetadata.isEmpty() && draft.error == null

    private fun sameDraftContent(left: ConversationDraft, right: ConversationDraft): Boolean =
        left.text == right.text && left.attachments == right.attachments &&
            left.documents == right.documents && left.imageMetadata == right.imageMetadata &&
            left.error == right.error

    private fun ConversationDraft.clearResolvedAttachmentError(): ConversationDraft {
        val errorCode = error?.code
        if (errorCode != DraftStoreErrorCode.IMPORT_FAILED &&
            errorCode != DraftStoreErrorCode.ATTACHMENT_MISSING
        ) return this
        val unresolved = imageMetadata.any {
            it.state == DraftAttachmentState.IMPORTING ||
                it.state == DraftAttachmentState.FAILED ||
                it.state == DraftAttachmentState.MISSING
        } || documents.any {
            it.state == DraftAttachmentState.IMPORTING ||
                it.state == DraftAttachmentState.FAILED ||
                it.state == DraftAttachmentState.MISSING
        }
        return if (unresolved) this else copy(error = null)
    }

    private fun sizeLimitMessage(kind: DraftAttachmentKind): String = when (kind) {
        DraftAttachmentKind.IMAGE -> "图片超过 8 MB，请压缩后重试"
        DraftAttachmentKind.DOCUMENT -> "文件超过 10 MB，请拆分后重试"
    }

    private fun sizeLimitMessageFor(maxBytes: Long): String = when (maxBytes) {
        MAX_IMAGE_BYTES -> "图片超过 8 MB，请压缩后重试"
        DocumentTextExtractor.MAX_FILE_BYTES -> "文件超过 10 MB，请拆分后重试"
        else -> "附件超过大小限制"
    }

    private fun isSupportedDocument(fileName: String, mimeType: String): Boolean {
        if (mimeType in DocumentTextExtractor.SUPPORTED_MIME_TYPES || mimeType.startsWith("text/")) return true
        return fileName.substringAfterLast('.', "").lowercase() in SUPPORTED_DOCUMENT_EXTENSIONS
    }

    private fun extensionFor(fileName: String, mimeType: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.matches(Regex("[a-z0-9]{1,8}"))) return ".${extension}"
        return when {
            mimeType == "application/pdf" -> ".pdf"
            mimeType.contains("spreadsheet") -> ".xlsx"
            mimeType.contains("wordprocessing") -> ".docx"
            mimeType.startsWith("text/") -> ".txt"
            else -> ""
        }
    }

    private data class CopiedAttachment(val sizeBytes: Long, val sha256: String)

    private class AttachmentTooLargeException(message: String) : IllegalArgumentException(message)

    private companion object {
        const val MAX_IMAGES_PER_DRAFT = 4
        const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
        const val COPY_BUFFER_BYTES = 32 * 1024
        val SUPPORTED_DOCUMENT_EXTENSIONS = setOf(
            "pdf", "xlsx", "xls", "docx", "doc", "txt", "csv", "md", "markdown", "tsv", "log", "json",
        )
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

private fun corruptError(message: String = "草稿数据损坏，已保留原始内容"): DraftStoreError =
    DraftStoreError(DraftStoreErrorCode.CORRUPT_DATA, message)

fun encodeConversationDraftSnapshot(
    draft: ConversationDraft,
    @Suppress("UNUSED_PARAMETER") json: Json = Json { ignoreUnknownKeys = true },
): String = buildJsonObject {
    put("schemaVersion", draft.schemaVersion)
    draft.conversationId?.let { put("conversationId", it) }
    put("text", draft.text)
    put("updatedAt", draft.updatedAt)
    put("attachments", buildJsonArray {
        draft.attachments.forEach { attachment ->
            add(buildJsonObject {
                put("uri", attachment.uri.toString())
                put("mimeType", attachment.mimeType)
            })
        }
    })
    put("imageMetadata", buildJsonArray {
        draft.imageMetadata.forEach { attachment ->
            add(attachment.toJson())
        }
    })
    put("documents", buildJsonArray {
        draft.documents.forEach { document ->
            add(document.toJson())
        }
    })
    draft.error?.let { error ->
        put("error", buildJsonObject {
            put("code", error.code.name)
            put("message", error.message)
        })
    }
}.toString()

fun decodeConversationDraftSnapshot(
    raw: String,
    json: Json = Json { ignoreUnknownKeys = true },
): ConversationDraft = runCatching {
    val root = json.parseToJsonElement(raw).jsonObject
    decodeDraftObject(root)
}.getOrElse { error ->
    ConversationDraft(
        text = recoverText(raw, json),
        schemaVersion = CURRENT_CONVERSATION_DRAFT_SCHEMA_VERSION,
        error = corruptError(error.message ?: "草稿数据损坏，已保留原始内容"),
    )
}

/** Short aliases for recovery code that does not need the longer store name. */
fun encodeDraftSnapshot(draft: ConversationDraft): String = encodeConversationDraftSnapshot(draft)
fun decodeDraftSnapshot(raw: String): ConversationDraft = decodeConversationDraftSnapshot(raw)

private fun decodeDraftObject(root: JsonObject): ConversationDraft {
    var malformedItem = false
    val schemaElement = root["schemaVersion"]
    val schemaVersion = root.intValue("schemaVersion") ?: 1
    if (schemaElement != null && root.intValue("schemaVersion") == null) malformedItem = true
    val text = root.stringValue("text").orEmpty()
    val conversationId = root.stringValue("conversationId")
    val updatedAt = root.longValue("updatedAt") ?: 0L
    val attachments = root["attachments"]?.let { element ->
        runCatching {
            element.jsonArray.mapNotNull { item ->
                runCatching {
                    val objectValue = item.jsonObject
                    val uri = objectValue.stringValue("uri") ?: error("missing uri")
                    PendingImageAttachment(
                        uri = Uri.parse(uri),
                        mimeType = objectValue.stringValue("mimeType").orEmpty(),
                    )
                }.getOrElse {
                    malformedItem = true
                    null
                }
            }
        }.getOrElse {
            malformedItem = true
            emptyList()
        }
    }.orEmpty()
    val images = root["imageMetadata"]?.let { element ->
        runCatching {
            element.jsonArray.mapNotNull { item ->
                runCatching { item.jsonObject.toImageAttachment() }.getOrElse {
                    malformedItem = true
                    null
                }
            }
        }.getOrElse {
            malformedItem = true
            emptyList()
        }
    }.orEmpty()
    val documents = root["documents"]?.let { element ->
        runCatching {
            element.jsonArray.mapNotNull { item ->
                runCatching { item.jsonObject.toDocumentAttachment() }.getOrElse {
                    malformedItem = true
                    null
                }
            }
        }.getOrElse {
            malformedItem = true
            emptyList()
        }
    }.orEmpty()
    val persistedErrorElement = root["error"]
    val persistedError = persistedErrorElement?.let { parseStoreError(it) }
    if (persistedErrorElement != null && persistedError == null) malformedItem = true
    val error = when {
        schemaVersion > CURRENT_CONVERSATION_DRAFT_SCHEMA_VERSION || schemaVersion < 1 -> DraftStoreError(
            DraftStoreErrorCode.UNSUPPORTED_VERSION,
            "草稿版本 $schemaVersion 暂不支持，已保留原始内容",
        )
        persistedError != null -> persistedError
        malformedItem -> corruptError("草稿附件数据损坏，已保留可恢复内容")
        else -> null
    }
    return ConversationDraft(
        text = text,
        attachments = attachments,
        documents = documents,
        imageMetadata = images,
        updatedAt = updatedAt,
        schemaVersion = schemaVersion,
        error = error,
        conversationId = conversationId,
    )
}

private fun JsonObject.toImageAttachment(): DraftImageAttachment = DraftImageAttachment(
    id = stringValue("id") ?: error("missing image id"),
    sourceUri = stringValue("sourceUri"),
    localUri = stringValue("localUri").orEmpty(),
    displayName = stringValue("displayName").orEmpty(),
    mimeType = stringValue("mimeType").orEmpty(),
    sizeBytes = longValue("sizeBytes") ?: 0L,
    sha256 = stringValue("sha256").orEmpty(),
    state = stateValue(),
    errorMessage = stringValue("errorMessage"),
)

private fun JsonObject.toDocumentAttachment(): DraftDocumentAttachment = DraftDocumentAttachment(
    id = stringValue("id") ?: error("missing document id"),
    sourceUri = stringValue("sourceUri"),
    localUri = stringValue("localUri").orEmpty(),
    displayName = stringValue("displayName").orEmpty(),
    mimeType = stringValue("mimeType").orEmpty(),
    sizeBytes = longValue("sizeBytes") ?: 0L,
    sha256 = stringValue("sha256").orEmpty(),
    body = stringValue("body").orEmpty(),
    truncated = stringValue("truncated")?.toBoolean() ?: false,
    originalCharCount = intValue("originalCharCount") ?: 0,
    state = stateValue(),
    errorMessage = stringValue("errorMessage"),
)

private fun DraftAttachmentRecord.toJson() = buildJsonObject {
    put("id", id)
    put("sourceUri", sourceUri)
    put("localUri", localUri)
    put("displayName", displayName)
    put("mimeType", mimeType)
    put("sizeBytes", sizeBytes)
    put("sha256", sha256)
    put("state", state.name)
    put("errorMessage", errorMessage)
    if (this@toJson is DraftDocumentAttachment) {
        put("body", body)
        put("truncated", truncated)
        put("originalCharCount", originalCharCount)
    }
}

private fun JsonObject.stringValue(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.longValue(key: String): Long? = stringValue(key)?.toLongOrNull()
    ?: this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
private fun JsonObject.intValue(key: String): Int? = stringValue(key)?.toIntOrNull()
    ?: this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

private fun JsonObject.stateValue(): DraftAttachmentState = runCatching {
    DraftAttachmentState.valueOf(stringValue("state") ?: DraftAttachmentState.READY.name)
}.getOrElse { throw IllegalArgumentException("unknown attachment state") }

private fun parseStoreError(element: JsonElement): DraftStoreError? = runCatching {
    val objectValue = element.jsonObject
    val code = DraftStoreErrorCode.valueOf(objectValue.stringValue("code") ?: return@runCatching null)
    DraftStoreError(code, objectValue.stringValue("message").orEmpty())
}.getOrNull()

private fun recoverText(raw: String, json: Json): String {
    val escaped = Regex("\\\"text\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)").find(raw)?.groupValues?.getOrNull(1)
        ?: return ""
    return runCatching {
        json.parseToJsonElement("\"$escaped\"").jsonPrimitive.content
    }.getOrDefault(escaped.replace("\\\"", "\"").replace("\\\\", "\\"))
}
