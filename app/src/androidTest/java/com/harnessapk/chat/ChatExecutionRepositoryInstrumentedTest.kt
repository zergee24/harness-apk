package com.harnessapk.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.agent.AgentStatus
import com.harnessapk.agent.ConversationIdentityRepository
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AgentEntity
import com.harnessapk.storage.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatExecutionRepositoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val chatRepository = ChatRepository(
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        messagePartDao = database.messagePartDao(),
        attachmentDao = database.messageAttachmentDao(),
        memoryDao = database.conversationMemoryDao(),
        timeProvider = TimeProvider { 10L },
    )
    private val identityRepository = ConversationIdentityRepository(
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        agentDao = database.agentDao(),
        timeProvider = TimeProvider { 10L },
    )
    private val repository = ChatExecutionRepository(
        database = database,
        dao = database.chatExecutionEntryDao(),
        chatRepository = chatRepository,
        identityRepository = identityRepository,
        timeProvider = TimeProvider { 10L },
    )

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun enqueuePinsLatestIdentityAndInsertsFirstUserMessage() = runBlocking {
        database.agentDao().upsertAgent(readyAgent(id = "a1", activeVersion = 4))
        val conversationId = chatRepository.createConversation(agentId = "a1", agentVersion = 1)

        repository.enqueue(
            EnqueueChatRequest(
                conversationId = conversationId,
                content = "第一条消息",
                attachments = emptyList(),
                providerId = null,
                model = null,
                reasoningEffort = defaultReasoningEffort(),
                requestContext = ChatExecutionRequestContext(),
            ),
        )

        assertEquals(4, database.conversationDao().findById(conversationId)!!.agentVersion)
        assertEquals(1, database.messageDao().listForConversation(conversationId).count { it.role == "USER" })
    }

    private fun readyAgent(id: String, activeVersion: Int) = AgentEntity(
        id = id,
        name = "李德胜",
        summary = "",
        activeVersion = activeVersion,
        publisherPublicKey = byteArrayOf(),
        publisherFingerprint = "",
        installSource = "test",
        status = AgentStatus.READY.name,
        requiredCorpusCount = 0,
        installedCorpusCount = 0,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
