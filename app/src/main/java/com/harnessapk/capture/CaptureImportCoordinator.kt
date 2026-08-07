package com.harnessapk.capture

import android.net.Uri
import com.harnessapk.chat.ChatImageStore
import com.harnessapk.chat.ConversationDraft
import com.harnessapk.chat.ConversationDraftStore
import com.harnessapk.chat.PendingImageAttachment
import com.harnessapk.common.AppDispatchers
import com.harnessapk.project.FileProjectRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class CaptureImportCoordinator(
    private val repository: CaptureDraftRepository,
    private val stagingStore: CaptureStagingStore,
    private val chatImageStore: ChatImageStore,
    private val conversationDraftStore: ConversationDraftStore,
    private val projectRepository: FileProjectRepository,
    private val dispatchers: AppDispatchers,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val _transferState = MutableStateFlow(CaptureTransferState())
    val transferState: StateFlow<CaptureTransferState> = _transferState.asStateFlow()

    fun clearTransferError() {
        if (!_transferState.value.active) _transferState.value = CaptureTransferState()
    }

    suspend fun stage(request: IncomingShareRequest): CaptureDraft = withContext(dispatchers.io) {
        require(request.text.isNotBlank() || request.items.isNotEmpty()) { "分享内容为空" }
        repository.current()?.let { previous ->
            stagingStore.cleanup(previous.id)
            repository.consume(previous.id)
        }
        val id = UUID.randomUUID().toString()
        _transferState.value = CaptureTransferState(
            active = true,
            totalBytes = request.items.mapNotNull(IncomingShareItem::declaredSizeBytes)
                .takeIf { it.size == request.items.size }
                ?.sum(),
        )
        try {
            val items = stagingStore.stage(id, request) { completed, total ->
                _transferState.value = CaptureTransferState(
                    active = true,
                    completedBytes = completed,
                    totalBytes = total,
                )
            }
            val now = nowMillis()
            CaptureDraft(
                id = id,
                source = CaptureSource.ANDROID_SHARE,
                text = request.text,
                stagedItems = items,
                status = CaptureStatus.READY,
                createdAt = now,
                expiresAt = if (items.isEmpty()) null else now + CAPTURE_EXPIRY_MILLIS,
            ).also(repository::save)
        } catch (error: Throwable) {
            stagingStore.cleanup(id)
            _transferState.value = CaptureTransferState(errorMessage = error.message ?: "分享内容暂存失败")
            throw error
        } finally {
            if (_transferState.value.errorMessage == null) {
                _transferState.value = CaptureTransferState()
            }
        }
    }

    suspend fun deliverToConversation(draftId: String, conversationId: String) = withContext(dispatchers.io) {
        val draft = requireDraft(draftId)
        require(draft.stagedItems.none { it.kind == CaptureItemKind.FILE }) { "普通文件不能直接进入模型上下文" }
        val persisted = mutableListOf<PendingImageAttachment>()
        try {
            draft.stagedItems.forEach { item ->
                val image = chatImageStore.persist(Uri.parse(item.localUri), item.mimeType)
                persisted += PendingImageAttachment(image.uri, image.mimeType)
            }
            val existing = conversationDraftStore.load(conversationId)
            conversationDraftStore.save(
                conversationId,
                ConversationDraft(
                    text = mergeCaptureText(existing.text, draft.text),
                    attachments = (existing.attachments + persisted).distinctBy { it.uri },
                ),
            )
            finish(draft)
        } catch (error: Throwable) {
            persisted.forEach { runCatching { chatImageStore.deleteIfManaged(it.uri) } }
            throw error
        }
    }

    suspend fun importFilesToProject(draftId: String, projectId: String): List<String> = withContext(dispatchers.io) {
        val draft = requireDraft(draftId)
        require(draft.stagedItems.isNotEmpty()) { "没有可导入的文件" }
        val imported = draft.stagedItems.map { item ->
            projectRepository.importFile(
                projectId = projectId,
                displayName = item.displayName,
                source = File(requireNotNull(Uri.parse(item.localUri).path)),
            )
        }
        finish(draft)
        imported
    }

    suspend fun discard(draftId: String) = withContext(dispatchers.io) {
        repository.current()?.takeIf { it.id == draftId }?.let(::finish)
    }

    private fun requireDraft(draftId: String): CaptureDraft = requireNotNull(
        repository.current()?.takeIf { it.id == draftId && it.status == CaptureStatus.READY },
    ) { "分享草稿不可用" }

    private fun finish(draft: CaptureDraft) {
        stagingStore.cleanup(draft.id)
        repository.consume(draft.id)
    }
}

internal fun mergeCaptureText(existing: String, incoming: String): String = when {
    incoming.isBlank() -> existing
    existing.isBlank() -> incoming
    else -> existing.trimEnd() + "\n" + incoming.trim()
}
