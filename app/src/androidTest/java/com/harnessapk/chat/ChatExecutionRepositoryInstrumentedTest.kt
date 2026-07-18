package com.harnessapk.chat

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.agent.AgentStatus
import com.harnessapk.agent.ConversationIdentityRepository
import com.harnessapk.common.AppDispatchers
import com.harnessapk.common.TimeProvider
import com.harnessapk.network.OpenAiCompatibleClient
import com.harnessapk.provider.ProviderRepository
import com.harnessapk.security.EncryptedValue
import com.harnessapk.security.StringCipher
import com.harnessapk.storage.AgentEntity
import com.harnessapk.storage.AppDatabase
import com.harnessapk.websearch.JinaWebSearchClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun enqueueUsesCallerRequestIdAndIsIdempotentForTheSameAcceptedRequest() = runBlocking {
        val conversationId = chatRepository.createConversation()
        val request = request(conversationId).copy(requestId = "stable-request-id")

        val first = repository.enqueue(request)
        val second = repository.enqueue(request)

        assertEquals("stable-request-id", first.id)
        assertEquals(first, second)
        assertEquals(1, database.chatExecutionEntryDao().listForConversation(conversationId).size)
        assertEquals(1, database.messageDao().countUserMessages(conversationId))
    }

    @Test
    fun concurrentEnqueueOfOneRequestCreatesOneExecutionAndOneUserMessage() = runBlocking {
        val conversationId = chatRepository.createConversation()
        val request = request(conversationId).copy(requestId = "concurrent-request")

        coroutineScope {
            listOf(
                async(Dispatchers.IO) { repository.enqueue(request) },
                async(Dispatchers.IO) { repository.enqueue(request) },
            ).awaitAll()
        }

        assertEquals(1, database.chatExecutionEntryDao().listForConversation(conversationId).size)
        assertEquals(1, database.messageDao().countUserMessages(conversationId))
    }

    @Test
    fun preCommitFailureIsReportedForTheExactRequestWithoutAnEntry() = runBlocking {
        val conversationId = chatRepository.createConversation()
        val request = request(conversationId).copy(requestId = "pre-commit-failure")
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_exact_request
            BEFORE INSERT ON chat_execution_entries
            WHEN NEW.id = 'pre-commit-failure'
            BEGIN
                SELECT RAISE(ABORT, 'pre commit failure');
            END
            """.trimIndent(),
        )
        val controller = ChatSendController(
            enqueue = repository::enqueue,
            requestExists = { requestId -> repository.entry(requestId) != null },
        )

        val result = controller.submit(request)

        assertTrue(result is ChatSendSettlement.Failed)
        assertEquals(null, repository.entry(request.requestId))
        assertEquals(0, database.messageDao().countUserMessages(conversationId))
    }

    @Test
    fun coordinatorAcceptsFirstAndLaterRequestsWhenSchedulingThrowsAfterPersistence() = runBlocking {
        val conversationId = chatRepository.createConversation()
        val coordinator = coordinatorThatFailsScheduling()
        val controller = ChatSendController(
            enqueue = coordinator::enqueue,
            requestExists = { requestId -> repository.entry(requestId) != null },
        )
        try {
            val first = controller.submit(request(conversationId).copy(requestId = "first-scheduled"))
            val later = controller.submit(request(conversationId).copy(requestId = "later-scheduled"))

            assertTrue(first is ChatSendSettlement.Accepted)
            assertTrue(later is ChatSendSettlement.Accepted)
            assertEquals("first-scheduled", first.requestId)
            assertEquals("later-scheduled", later.requestId)
            assertEquals(2, database.chatExecutionEntryDao().listForConversation(conversationId).size)
            assertEquals(2, database.messageDao().countUserMessages(conversationId))
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun coordinatorPropagatesPostCommitSchedulingCancellationForExactRequestSettlement() = runBlocking {
        val conversationId = chatRepository.createConversation()
        val originalCancellation = CancellationException("schedule cancelled")
        val coordinator = coordinatorThatFailsScheduling { throw originalCancellation }
        val controller = ChatSendController(
            enqueue = coordinator::enqueue,
            requestExists = { requestId -> repository.entry(requestId) != null },
        )
        val request = request(conversationId).copy(requestId = "cancelled-scheduled")
        try {
            val settlement = controller.submit(request)

            assertTrue(settlement is ChatSendSettlement.Cancelled)
            settlement as ChatSendSettlement.Cancelled
            assertTrue(settlement.persisted)
            assertTrue(settlement.cancellation === originalCancellation)
            assertEquals(1, database.chatExecutionEntryDao().listForConversation(conversationId).size)
            assertEquals(1, database.messageDao().countUserMessages(conversationId))
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun enqueueRollsBackPinnedIdentityAndUserMessageWhenQueueInsertFails() = runBlocking {
        database.agentDao().upsertAgent(readyAgent(id = "a1", activeVersion = 4))
        val conversationId = chatRepository.createConversation(agentId = "a1", agentVersion = 1)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_chat_execution_entry_insert
            BEFORE INSERT ON chat_execution_entries
            BEGIN
                SELECT RAISE(ABORT, 'queue insert failure');
            END
            """.trimIndent(),
        )

        try {
            repository.enqueue(request(conversationId))
            fail("Expected queue insert to fail")
        } catch (error: SQLiteConstraintException) {
            assertTrue(error.message.orEmpty().contains("queue insert failure"))
        }

        assertEquals(1, database.conversationDao().findById(conversationId)!!.agentVersion)
        assertEquals(0, database.messageDao().countUserMessages(conversationId))
        assertEquals(0, database.chatExecutionEntryDao().listForConversation(conversationId).size)
    }

    private fun request(conversationId: String) = EnqueueChatRequest(
        conversationId = conversationId,
        content = "第一条消息",
        attachments = emptyList(),
        providerId = null,
        model = null,
        reasoningEffort = defaultReasoningEffort(),
        requestContext = ChatExecutionRequestContext(),
    )

    private fun coordinatorThatFailsScheduling(
        onWorkScheduled: () -> Unit = { throw IllegalStateException("schedule failure") },
    ): ChatExecutionCoordinator {
        val dispatchers = AppDispatchers(
            io = Dispatchers.IO,
            default = Dispatchers.IO,
            main = Dispatchers.IO,
        )
        val providerRepository = ProviderRepository(
            dao = database.providerProfileDao(),
            cipher = TestCipher,
            timeProvider = TimeProvider { 10L },
        )
        return ChatExecutionCoordinator(
            executionRepository = repository,
            sendMessageUseCase = SendMessageUseCase(
                context = context,
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                client = OpenAiCompatibleClient(OkHttpClient(), Json {}),
                dispatchers = dispatchers,
            ),
            providerRepository = providerRepository,
            webSearchClient = JinaWebSearchClient(OkHttpClient()),
            attachmentStore = QueuedAttachmentStore(context),
            dispatchers = dispatchers,
            onWorkScheduled = onWorkScheduled,
        )
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

    private object TestCipher : StringCipher {
        override fun encrypt(plainText: String): EncryptedValue = EncryptedValue(byteArrayOf(), byteArrayOf())

        override fun decrypt(value: EncryptedValue): String = ""
    }
}
