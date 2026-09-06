package com.harnessapk.ui.chat

import com.harnessapk.chat.ChatDocumentPresentation
import com.harnessapk.chat.ChatSendAttachmentKind
import com.harnessapk.chat.ChatSendAttachmentSnapshot
import com.harnessapk.chat.ChatSendRequestPhase
import com.harnessapk.chat.ChatSendRequestState
import com.harnessapk.chat.ConversationDraft
import com.harnessapk.chat.DraftAttachmentState
import com.harnessapk.chat.DraftDocumentAttachment
import com.harnessapk.chat.DraftImageAttachment

internal fun DraftDocumentAttachment.toMessageDocument() = ChatDocumentPresentation(
    id = id, uri = localUri, fileName = displayName, mimeType = mimeType,
    sizeBytes = sizeBytes, sha256 = sha256, extractedText = body, truncated = truncated,
)

internal fun ConversationDraft.sendAttachmentSnapshots(): List<ChatSendAttachmentSnapshot> =
    attachments.map { image ->
        val metadata = imageMetadata.firstOrNull { it.localUri == image.uri.toString() }
        ChatSendAttachmentSnapshot(
            id = metadata?.id ?: "image-${image.uri}", kind = ChatSendAttachmentKind.IMAGE,
            uri = image.uri.toString(), mimeType = image.mimeType,
            displayName = metadata?.displayName, sizeBytes = metadata?.sizeBytes, sha256 = metadata?.sha256,
        )
    } + documents.filter { it.state == DraftAttachmentState.READY }.map { document ->
        ChatSendAttachmentSnapshot(
            id = document.id, kind = ChatSendAttachmentKind.DOCUMENT, uri = document.localUri,
            mimeType = document.mimeType, displayName = document.displayName,
            sizeBytes = document.sizeBytes, sha256 = document.sha256,
            extractedText = document.body, truncated = document.truncated,
            originalCharCount = document.originalCharCount,
        )
    }

/** The UI uses the original question, while submittedText remains the provider protocol payload. */
internal fun draftFromSendState(request: ChatSendRequestState, settle: Boolean = false): ConversationDraft {
    val submitted = request.intent?.originalAttachments.orEmpty()
    val current = request.currentDraftAttachmentSnapshots
    val remaining = if (settle && request.phase == ChatSendRequestPhase.LANDED) {
        current.filterNot { item -> submitted.any { it.id == item.id && it.sha256 == item.sha256 && it.uri == item.uri } }
    } else current
    val terminal = if (settle) reduceTerminalDraft(
        request.phase, request.intent?.originalText ?: request.submittedText,
        request.submittedAttachments, request.currentDraftText, request.currentDraftAttachments,
    ) else ChatDraftUiState(request.currentDraftText, request.currentDraftAttachments)
    return ConversationDraft(
        text = terminal.text,
        attachments = terminal.attachments,
        documents = remaining.filter { it.kind == ChatSendAttachmentKind.DOCUMENT }.map { item ->
            DraftDocumentAttachment(
                id = item.id, sourceUri = null, localUri = item.uri,
                displayName = item.displayName ?: "文件", mimeType = item.mimeType,
                sizeBytes = item.sizeBytes ?: 0L, sha256 = item.sha256.orEmpty(),
                body = item.extractedText.orEmpty(), truncated = item.truncated,
                originalCharCount = item.originalCharCount ?: item.extractedText.orEmpty().length,
            )
        },
        imageMetadata = remaining.filter { it.kind == ChatSendAttachmentKind.IMAGE }.map { item ->
            DraftImageAttachment(
                id = item.id, sourceUri = null, localUri = item.uri,
                displayName = item.displayName ?: "照片", mimeType = item.mimeType,
                sizeBytes = item.sizeBytes ?: 0L, sha256 = item.sha256.orEmpty(),
            )
        },
    )
}
