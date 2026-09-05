package com.harnessapk.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifeConversationOverviewDaoInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun projectionBatchesMessageExecutionFactsAndArchiveGuardIsReversible() = runBlocking {
        val conversationDao = database.conversationDao()
        val messageDao = database.messageDao()
        val executionDao = database.chatExecutionEntryDao()
        conversationDao.insert(conversation("c1", updatedAt = 10L))
        messageDao.insert(
            MessageEntity(
                id = "m-user",
                conversationId = "c1",
                role = "USER",
                content = "明天出门要带什么？",
                status = "SUCCEEDED",
                providerId = null,
                model = null,
                createdAt = 11L,
                updatedAt = 11L,
                errorCode = null,
                errorMessage = null,
            ),
        )
        messageDao.insert(
            MessageEntity(
                id = "m-assistant",
                conversationId = "c1",
                role = "ASSISTANT",
                content = "带伞、充电宝和水。",
                status = "SUCCEEDED",
                providerId = "provider",
                model = "model",
                createdAt = 12L,
                updatedAt = 12L,
                errorCode = null,
                errorMessage = null,
            ),
        )
        executionDao.insert(execution("e-queued", status = "QUEUED", sequence = 1L, updatedAt = 13L))

        val row = conversationDao.observeLifeOverviewRows(includeArchived = false).first().single()

        assertEquals(2L, row.messageCount)
        assertEquals("明天出门要带什么？", row.firstUserText)
        assertEquals("明天出门要带什么？", row.lastUserText)
        assertEquals("带伞、充电宝和水。", row.lastAssistantText)
        assertEquals("QUEUED", row.latestExecutionStatus)
        assertEquals("{}", row.firstUserRequestContextJson)
        assertEquals(1L, row.openExecutionCount)
        assertEquals(0, conversationDao.archiveLifeConversationIfIdle("c1"))

        executionDao.update(execution("e-queued", status = "SUCCEEDED", sequence = 1L, updatedAt = 14L))
        assertEquals(1, conversationDao.archiveLifeConversationIfIdle("c1"))
        assertTrue(conversationDao.findById("c1")!!.isArchived)
        assertEquals(10L, conversationDao.findById("c1")!!.updatedAt)
        assertEquals(1, conversationDao.restoreLifeConversation("c1"))
        assertTrue(!conversationDao.findById("c1")!!.isArchived)
    }

    private fun conversation(id: String, updatedAt: Long) = ConversationEntity(
        id = id,
        title = "新会话",
        createdAt = 1L,
        updatedAt = updatedAt,
        defaultProviderId = null,
        defaultModel = null,
        isArchived = false,
        projectId = null,
        promptOriginal = "",
        promptOptimized = "",
        promptFinal = "",
    )

    private fun execution(
        id: String,
        status: String,
        sequence: Long,
        updatedAt: Long,
    ) = ChatExecutionEntryEntity(
        id = id,
        conversationId = "c1",
        userMessageId = "m-user",
        assistantMessageId = "m-assistant",
        targetAssistantMessageId = null,
        sequence = sequence,
        type = "NORMAL",
        status = status,
        providerId = "provider",
        model = "model",
        reasoningEffort = "HIGH",
        requestContextJson = "{}",
        errorMessage = null,
        createdAt = 13L,
        updatedAt = updatedAt,
    )
}
