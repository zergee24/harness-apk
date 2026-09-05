package com.harnessapk.chat

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDraftContractTest {
    @Test
    fun draftSnapshotRoundTripKeepsRawTextDocumentsAndAttachmentMetadata() {
        val draft = ConversationDraft(
            text = "请总结这份资料",
            attachments = listOf(PendingImageAttachment(android.net.Uri.parse("content://image"), "image/jpeg")),
            documents = listOf(
                DraftDocumentAttachment(
                    id = "document-hash",
                    sourceUri = "content://document",
                    localUri = "content://private-document",
                    displayName = "资料.txt",
                    mimeType = "text/plain",
                    sizeBytes = 42L,
                    sha256 = "a".repeat(64),
                    body = "正文",
                    truncated = false,
                    originalCharCount = 2,
                    state = DraftAttachmentState.READY,
                ),
            ),
            imageMetadata = listOf(
                DraftImageAttachment(
                    id = "image-hash",
                    sourceUri = "content://image",
                    localUri = "content://private-image",
                    displayName = "照片.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 8L,
                    sha256 = "b".repeat(64),
                    state = DraftAttachmentState.READY,
                ),
            ),
            updatedAt = 123L,
        )

        val decoded = decodeConversationDraftSnapshot(encodeConversationDraftSnapshot(draft))

        assertEquals(draft, decoded)
    }

    @Test
    fun boundedReadStopsBeforeConsumingMoreThanTheConfiguredLimit() {
        val input = ByteArrayInputStream(ByteArray(11))

        val failure = runCatching { readBounded(input, maxBytes = 10L) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("文件超过 10 MB，请拆分后重试", failure?.message)
    }
}
