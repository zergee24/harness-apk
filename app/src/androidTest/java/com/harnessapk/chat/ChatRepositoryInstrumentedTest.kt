package com.harnessapk.chat

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatRepositoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val repository = ChatRepository(
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        messagePartDao = database.messagePartDao(),
        attachmentDao = database.messageAttachmentDao(),
        memoryDao = database.conversationMemoryDao(),
        timeProvider = TimeProvider { 10L },
    )

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun observeAttachmentsMapsPersistedAndroidUri() = runBlocking {
        val conversationId = repository.createConversation()
        val messageId = repository.insertUserMessage(
            conversationId = conversationId,
            content = "图片",
            attachments = listOf(
                PendingImageAttachment(Uri.parse("content://app/image/1"), "image/jpeg"),
            ),
        )

        val attachments = repository.observeAttachments(messageId).first()

        assertEquals(listOf("content://app/image/1"), attachments.map { it.uri })
    }
}
