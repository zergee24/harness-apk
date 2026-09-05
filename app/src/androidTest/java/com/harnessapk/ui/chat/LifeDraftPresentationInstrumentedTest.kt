package com.harnessapk.ui.chat

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.chat.ChatSendIntent
import com.harnessapk.chat.ChatSendRequestPhase
import com.harnessapk.chat.ChatSendRequestState
import com.harnessapk.chat.ConversationDraft
import com.harnessapk.chat.DraftImageAttachment
import com.harnessapk.chat.PendingImageAttachment
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifeDraftPresentationInstrumentedTest {
    @Test
    fun landedImageSnapshotKeepsOnlyTheNextDraftImageWithItsMetadata() {
        val sentImage = PendingImageAttachment(Uri.parse("content://fixture/sent.png"), "image/png")
        val nextImage = PendingImageAttachment(Uri.parse("content://fixture/next.png"), "image/png")
        fun metadata(image: PendingImageAttachment, hash: String) = DraftImageAttachment(
            id = "image-$hash", sourceUri = null, localUri = image.uri.toString(),
            displayName = "照片.png", mimeType = image.mimeType, sizeBytes = 8L, sha256 = hash,
        )
        val original = ConversationDraft("原问题", listOf(sentImage), imageMetadata = listOf(metadata(sentImage, "v1")))
        val next = ConversationDraft("下一条草稿", listOf(sentImage, nextImage), imageMetadata = listOf(metadata(sentImage, "v1"), metadata(nextImage, "v2")))
        val state = ChatSendRequestState(
            requestId = "one", submittedText = original.text, submittedAttachments = original.attachments,
            isFirstUserMessage = true, currentDraftText = next.text, currentDraftAttachments = next.attachments,
            currentDraftAttachmentSnapshots = next.sendAttachmentSnapshots(), phase = ChatSendRequestPhase.LANDED,
            intent = ChatSendIntent("conversation", "one", original.text, originalAttachments = original.sendAttachmentSnapshots()),
        )

        val settled = draftFromSendState(state, settle = true)

        assertEquals("下一条草稿", settled.text)
        assertEquals(listOf(nextImage), settled.attachments)
        assertEquals(listOf("image-v2"), settled.imageMetadata.map { it.id })
    }
}
