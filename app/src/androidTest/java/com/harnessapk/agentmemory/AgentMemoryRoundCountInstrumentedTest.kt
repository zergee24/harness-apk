package com.harnessapk.agentmemory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.chat.ChatRepository
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentMemoryRoundCountInstrumentedTest {
    @Test
    fun roomCountsOnlySuccessfulNonBlankAssistantTextInTheConversation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = ChatRepository(
                conversationDao = database.conversationDao(),
                messageDao = database.messageDao(),
                messagePartDao = database.messagePartDao(),
                attachmentDao = database.messageAttachmentDao(),
                memoryDao = database.conversationMemoryDao(),
                timeProvider = TimeProvider { 1L },
            )
            val conversationId = repository.createConversation()
            repository.insertAssistantPending(conversationId, "provider", "model").also {
                repository.appendAssistantText(it, "成功回复")
                repository.markAssistantSucceeded(it)
            }
            repository.insertAssistantPending(conversationId, "provider", "model").also {
                repository.markAssistantSucceeded(it)
            }
            repository.insertAssistantPending(conversationId, "provider", "model").also {
                repository.appendAssistantText(it, "失败回复")
                repository.markAssistantFailed(it, "失败")
            }
            repository.insertAssistantPending(conversationId, "provider", "model").also {
                repository.appendAssistantText(it, "仍在生成")
            }
            val otherConversationId = repository.createConversation()
            repository.insertAssistantPending(otherConversationId, "provider", "model").also {
                repository.appendAssistantText(it, "其他会话")
                repository.markAssistantSucceeded(it)
            }

            assertEquals(1, repository.completedAssistantTextCount(conversationId))
        } finally {
            database.close()
        }
    }
}
