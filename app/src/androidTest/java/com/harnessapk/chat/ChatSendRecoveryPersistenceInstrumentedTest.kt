package com.harnessapk.chat

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatSendRecoveryPersistenceInstrumentedTest {
    @Test
    fun filePersistenceRestoresRealImageUriAndTypedDocumentAfterReconstruction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileName = "chat-send-recovery-test-${UUID.randomUUID()}.json"
        val file = File(context.filesDir, fileName)
        try {
            val imageUri = Uri.parse("content://example.provider/image/42")
            val snapshots = listOf(
                ChatSendAttachmentSnapshot(
                    id = "image-42",
                    kind = ChatSendAttachmentKind.IMAGE,
                    uri = imageUri.toString(),
                    mimeType = "image/png",
                ),
                ChatSendAttachmentSnapshot(
                    id = "document-42",
                    kind = ChatSendAttachmentKind.DOCUMENT,
                    uri = "content://example.provider/document/42",
                    mimeType = "text/plain",
                    displayName = "说明.txt",
                    sizeBytes = 12L,
                    sha256 = "sha-42",
                    extractedText = "文档正文",
                    truncated = false,
                ),
            )
            val state = ChatSendRequestState(
                requestId = "request-42",
                submittedText = "原始问题",
                submittedAttachments = listOf(PendingImageAttachment(imageUri, "image/png")),
                isFirstUserMessage = false,
                currentDraftAttachments = listOf(PendingImageAttachment(imageUri, "image/png")),
                currentDraftAttachmentSnapshots = snapshots,
                intent = ChatSendIntent(
                    conversationId = "conversation-42",
                    requestId = "request-42",
                    originalText = "原始问题",
                    originalAttachments = snapshots,
                    providerId = "provider",
                    model = "model",
                ),
            )

            val firstStore = ChatSendRecoveryStore(FileChatSendRecoveryPersistence(context, fileName))
            assertTrue(
                firstStore.startPersisted("conversation-42", state) is ChatSendStoreStartResult.Started,
            )

            val restartedStore = ChatSendRecoveryStore(FileChatSendRecoveryPersistence(context, fileName))
            val restored = requireNotNull(restartedStore.current("conversation-42"))
            assertEquals(ChatSendRequestPhase.UNKNOWN, restored.phase)
            assertEquals(imageUri, restored.submittedAttachments.single().uri)
            assertEquals(
                "document-42",
                restored.currentDraftAttachmentSnapshots.single { it.kind == ChatSendAttachmentKind.DOCUMENT }.id,
            )
        } finally {
            file.delete()
        }
    }
}
