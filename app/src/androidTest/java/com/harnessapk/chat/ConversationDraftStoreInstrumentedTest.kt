package com.harnessapk.chat

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationDraftStoreInstrumentedTest {
    @Test
    fun textAndPrivateAttachmentSurviveStoreRecreationUntilCleared() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val conversationId = "draft-${UUID.randomUUID()}"
        val expected = ConversationDraft(
            text = "原草稿 语音结果",
            attachments = listOf(
                PendingImageAttachment(
                    uri = Uri.parse("content://com.harnessapk.fileprovider/chat-images/draft.jpg"),
                    mimeType = "image/jpeg",
                ),
            ),
        )

        ConversationDraftStore(context).save(conversationId, expected)

        assertEquals(expected, ConversationDraftStore(context).load(conversationId))
        ConversationDraftStore(context).clear(conversationId)
        assertEquals(ConversationDraft(), ConversationDraftStore(context).load(conversationId))
    }
}
