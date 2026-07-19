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
import com.harnessapk.provider.ProviderDraft
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    fun enqueueWithOutcomeIdentifiesTheCallThatCreatedTheStableRequest() = runBlocking {
        val conversationId = chatRepository.createConversation()
        val request = request(conversationId).copy(requestId = "stable-outcome-request")
        val barrierArrivals = AtomicInteger()
        val readyToEnqueue = CyclicBarrier(2)

        val outcomes = withTimeout(10_000L) {
            coroutineScope {
                List(2) {
                    async(Dispatchers.IO) {
                        barrierArrivals.incrementAndGet()
                        readyToEnqueue.await(5L, TimeUnit.SECONDS)
                        repository.enqueueWithOutcome(request)
                    }
                }.awaitAll()
            }
        }

        assertEquals(2, barrierArrivals.get())
        assertEquals(listOf(false, true), outcomes.map { it.insertedByThisCall }.sorted())
        assertEquals(1, outcomes.map { it.entry }.distinct().size)
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

    @Test
    fun productionRunnerMarksMissingProviderFailuresAndContinuesQueuedConversation() = runBlocking {
        val conversationId = chatRepository.createConversation()
        val first = repository.enqueue(request(conversationId).copy(requestId = "missing-provider-first"))
        val second = repository.enqueue(request(conversationId).copy(requestId = "missing-provider-second"))
        val coordinator = productionCoordinator(testProviderRepository())

        try {
            coordinator.resumePending()
            awaitTerminal(first.id)
            awaitTerminal(second.id)

            assertEquals(ChatExecutionStatus.FAILED, repository.entry(first.id)!!.status)
            assertEquals(ChatExecutionStatus.FAILED, repository.entry(second.id)!!.status)
            assertEquals("请先在模型配置中保存供应商", repository.entry(first.id)!!.errorMessage)
            val failedAssistant = chatRepository.listMessages(conversationId).last()
            assertEquals(MessageRole.ASSISTANT, failedAssistant.role)
            assertEquals(MessageStatus.FAILED, failedAssistant.status)
            assertEquals("请先在模型配置中保存供应商", failedAssistant.errorMessage)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun productionRunnerMarksDeletedExplicitProviderFailed() = runBlocking {
        val providerRepository = testProviderRepository()
        val deletedProviderId = providerRepository.saveProvider(
            ProviderDraft(
                name = "待删除供应商",
                baseUrl = "https://example.com/v1",
                apiKey = "secret",
                defaultModel = "model",
                defaultVisionModel = null,
                supportsVision = false,
            ),
        )
        val conversationId = chatRepository.createConversation()
        val entry = repository.enqueue(
            request(conversationId).copy(
                requestId = "deleted-provider",
                providerId = deletedProviderId,
            ),
        )
        providerRepository.deleteProvider(deletedProviderId)
        val coordinator = productionCoordinator(providerRepository)

        try {
            coordinator.resumePending()
            awaitTerminal(entry.id)

            assertEquals(ChatExecutionStatus.FAILED, repository.entry(entry.id)!!.status)
            assertEquals("请先在模型配置中保存供应商", repository.entry(entry.id)!!.errorMessage)
        } finally {
            coordinator.close()
        }
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
        val providerRepository = testProviderRepository()
        return ChatExecutionCoordinator(
            executionRepository = repository,
            sendMessageUseCase = sendMessageUseCase(providerRepository, dispatchers),
            providerRepository = providerRepository,
            webSearchClient = JinaWebSearchClient(OkHttpClient()),
            attachmentStore = QueuedAttachmentStore(context),
            dispatchers = dispatchers,
            onWorkScheduled = onWorkScheduled,
            enqueueRunnerStarter = {},
        )
    }

    private fun productionCoordinator(providerRepository: ProviderRepository): ChatExecutionCoordinator {
        val dispatchers = AppDispatchers(
            io = Dispatchers.IO,
            default = Dispatchers.IO,
            main = Dispatchers.IO,
        )
        return ChatExecutionCoordinator(
            executionRepository = repository,
            sendMessageUseCase = sendMessageUseCase(providerRepository, dispatchers),
            providerRepository = providerRepository,
            webSearchClient = JinaWebSearchClient(OkHttpClient()),
            attachmentStore = QueuedAttachmentStore(context),
            dispatchers = dispatchers,
        )
    }

    private fun sendMessageUseCase(
        providerRepository: ProviderRepository,
        dispatchers: AppDispatchers,
    ) = SendMessageUseCase(
        context = context,
        chatRepository = chatRepository,
        providerRepository = providerRepository,
        client = OpenAiCompatibleClient(OkHttpClient(), Json {}),
        dispatchers = dispatchers,
    )

    private fun testProviderRepository(): ProviderRepository = ProviderRepository(
            dao = database.providerProfileDao(),
            cipher = TestCipher,
            timeProvider = TimeProvider { 10L },
        )

    private suspend fun awaitTerminal(entryId: String) {
        withTimeout(5_000L) {
            while (repository.entry(entryId)?.status !in setOf(
                    ChatExecutionStatus.SUCCEEDED,
                    ChatExecutionStatus.FAILED,
                    ChatExecutionStatus.CANCELLED,
                    ChatExecutionStatus.INTERRUPTED,
                    ChatExecutionStatus.STEERED,
                )
            ) {
                delay(25L)
            }
        }
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
