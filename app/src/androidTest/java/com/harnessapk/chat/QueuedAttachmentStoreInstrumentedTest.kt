package com.harnessapk.chat

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.agent.ConversationIdentityRepository
import com.harnessapk.common.AppDispatchers
import com.harnessapk.common.TimeProvider
import com.harnessapk.network.OpenAiCompatibleClient
import com.harnessapk.provider.ProviderRepository
import com.harnessapk.security.EncryptedValue
import com.harnessapk.security.StringCipher
import com.harnessapk.storage.AppDatabase
import com.harnessapk.websearch.JinaWebSearchClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class QueuedAttachmentStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val managedDirectory = File(context.filesDir, "chat-attachments")
    private val sourceDirectory = File(context.cacheDir, "queued-attachment-sources")
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
    private val executionRepository = ChatExecutionRepository(
        database = database,
        dao = database.chatExecutionEntryDao(),
        chatRepository = chatRepository,
        identityRepository = identityRepository,
        timeProvider = TimeProvider { 10L },
    )

    @Before
    fun setUp() {
        managedDirectory.deleteRecursively()
        sourceDirectory.deleteRecursively()
        sourceDirectory.mkdirs()
    }

    @After
    fun tearDown() {
        managedDirectory.deleteRecursively()
        sourceDirectory.deleteRecursively()
        database.close()
    }

    @Test
    fun enqueueDeletesCopiedAttachmentsWhenRoomRollsBackBeforeTheExactRequestCommits() = runBlocking {
        val conversationId = chatRepository.createConversation()
        val request = request(
            conversationId = conversationId,
            requestId = "attachment-pre-commit-failure",
            attachments = listOf(sourceAttachment("first.jpg")),
        )
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_attachment_request
            BEFORE INSERT ON chat_execution_entries
            WHEN NEW.id = 'attachment-pre-commit-failure'
            BEGIN
                SELECT RAISE(ABORT, 'attachment queue failure');
            END
            """.trimIndent(),
        )
        val coordinator = coordinator()

        try {
            coordinator.enqueue(request)
            fail("Expected Room enqueue to fail")
        } catch (_: Throwable) {
        } finally {
            coordinator.close()
        }

        assertTrue("Copied attachments must be reclaimed", managedDirectory.listFiles().orEmpty().isEmpty())
        assertTrue(executionRepository.entry(request.requestId) == null)
        assertTrue(database.messageDao().countUserMessages(conversationId) == 0)
    }

    @Test
    fun persistAllDeletesEarlierCopiesAndTheFailedCopyArtifactsWhenTheSecondSourceCannotBeRead() = runBlocking {
        val store = QueuedAttachmentStore(context)
        val first = sourceAttachment("first.jpg")
        val unreadableSecond = PendingImageAttachment(Uri.parse("content://missing/second.jpg"), "image/jpeg")

        try {
            store.persistAll(listOf(first, unreadableSecond))
            fail("Expected second attachment copy to fail")
        } catch (_: Throwable) {
        }

        assertTrue("Batch copy must leave no final or temporary files", managedDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun committedRequestKeepsStoredAttachmentsWhenSchedulingThrows() = runBlocking {
        val conversationId = chatRepository.createConversation()
        val request = request(
            conversationId = conversationId,
            requestId = "attachment-scheduled",
            attachments = listOf(sourceAttachment("scheduled.jpg")),
        )
        val coordinator = coordinator(onWorkScheduled = { throw IllegalStateException("scheduling failed") })

        try {
            val entry = coordinator.enqueue(request)
            val attachment = database.messageAttachmentDao().listForMessage(entry.userMessageId).single()

            assertTrue(executionRepository.entry(request.requestId) != null)
            assertTrue(database.messageDao().countUserMessages(conversationId) == 1)
            assertTrue(context.contentResolver.openInputStream(Uri.parse(attachment.uri))!!.use { it.read() } >= 0)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun successfulAttachmentEnqueueStoresAReadableManagedFile() = runBlocking {
        val conversationId = chatRepository.createConversation()
        val request = request(
            conversationId = conversationId,
            requestId = "attachment-success",
            attachments = listOf(sourceAttachment("success.jpg")),
        )
        val coordinator = coordinator()

        try {
            val entry = coordinator.enqueue(request)
            val attachment = database.messageAttachmentDao().listForMessage(entry.userMessageId).single()

            assertTrue(File(Uri.parse(attachment.uri).path!!).isFile)
            assertTrue(context.contentResolver.openInputStream(Uri.parse(attachment.uri))!!.use { it.read() } >= 0)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun committedRequestKeepsStoredAttachmentsWhenCallerCancellationIsObserved() = runBlocking {
        val conversationId = chatRepository.createConversation()
        val request = request(
            conversationId = conversationId,
            requestId = "attachment-cancelled",
            attachments = listOf(sourceAttachment("cancelled.jpg")),
        )
        val schedulingStarted = CompletableDeferred<Unit>()
        val allowSchedulingToReturn = CompletableDeferred<Unit>()
        val coordinator = coordinator(
            onWorkScheduled = {
                schedulingStarted.complete(Unit)
                runBlocking { allowSchedulingToReturn.await() }
            },
        )

        try {
            val enqueue = async(Dispatchers.IO) { coordinator.enqueue(request) }
            schedulingStarted.await()
            enqueue.cancel(CancellationException("caller cancelled"))
            allowSchedulingToReturn.complete(Unit)
            try {
                enqueue.await()
                fail("Expected caller cancellation")
            } catch (_: CancellationException) {
            }

            val entry = requireNotNull(executionRepository.entry(request.requestId))
            val attachment = database.messageAttachmentDao().listForMessage(entry.userMessageId).single()
            assertTrue(File(Uri.parse(attachment.uri).path!!).isFile)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun cleanupIsIdempotentForAStoredManagedFile() = runBlocking {
        val store = QueuedAttachmentStore(context)
        val persisted = store.persistAll(listOf(sourceAttachment("idempotent.jpg")))

        store.cleanup(persisted)
        store.cleanup(persisted)

        assertTrue(managedDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cleanupRejectsUnsafeUrisAndAnOutsideResolvingSymlink() = runBlocking {
        val store = QueuedAttachmentStore(context)
        val sourceOutside = File(sourceDirectory, "outside.jpg").apply { writeText("outside") }
        val nested = File(managedDirectory, "nested/child.jpg").apply {
            parentFile!!.mkdirs()
            writeText("nested")
        }
        val sourceLikeFile = File(managedDirectory, "source.jpg").apply { writeText("source") }
        val traversalTarget = File(managedDirectory, "traversal.jpg").apply { writeText("traversal") }
        val symlink = File(managedDirectory, "outside-link.jpg")
        Files.createSymbolicLink(symlink.toPath(), sourceOutside.toPath())
        val traversal = Uri.parse("file://${managedDirectory.absolutePath}/nested/../traversal.jpg")

        store.cleanup(
            listOf(
                PendingImageAttachment(Uri.parse("content://shared/never-delete"), "image/jpeg"),
                PendingImageAttachment(managedDirectory.toUri(), "image/jpeg"),
                PendingImageAttachment(nested.toUri(), "image/jpeg"),
                PendingImageAttachment(sourceLikeFile.toUri(), "image/jpeg"),
                PendingImageAttachment(sourceOutside.toUri(), "image/jpeg"),
                PendingImageAttachment(traversal, "image/jpeg"),
                PendingImageAttachment(symlink.toUri(), "image/jpeg"),
            ),
        )

        assertTrue(managedDirectory.isDirectory)
        assertTrue(nested.isFile)
        assertTrue(sourceLikeFile.isFile)
        assertTrue(sourceOutside.isFile)
        assertTrue(traversalTarget.isFile)
        assertTrue(symlink.exists())
    }

    @Test
    fun cleanupKeepsFilesWhenTheExactRequestLookupFails() = runBlocking {
        val conversationId = chatRepository.createConversation()
        val request = request(
            conversationId = conversationId,
            requestId = "attachment-unknown-landing",
            attachments = listOf(sourceAttachment("unknown.jpg")),
        )
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_unknown_attachment_request
            BEFORE INSERT ON chat_execution_entries
            WHEN NEW.id = 'attachment-unknown-landing'
            BEGIN
                SELECT RAISE(ABORT, 'unknown landing failure');
            END
            """.trimIndent(),
        )
        val coordinator = coordinator(
            exactRequestCommitted = { throw IllegalStateException("request lookup failed") },
        )

        try {
            coordinator.enqueue(request)
            fail("Expected Room enqueue to fail")
        } catch (_: Throwable) {
        } finally {
            coordinator.close()
        }

        assertTrue("Unknown landing must preserve files", managedDirectory.listFiles().orEmpty().single().isFile)
    }

    private fun sourceAttachment(name: String): PendingImageAttachment {
        val source = File(sourceDirectory, name).apply { writeText("$name bytes") }
        return PendingImageAttachment(source.toUri(), "image/jpeg")
    }

    private fun request(
        conversationId: String,
        requestId: String,
        attachments: List<PendingImageAttachment>,
    ) = EnqueueChatRequest(
        requestId = requestId,
        conversationId = conversationId,
        content = "attachment request",
        attachments = attachments,
        providerId = null,
        model = null,
        reasoningEffort = defaultReasoningEffort(),
        requestContext = ChatExecutionRequestContext(),
    )

    private fun coordinator(
        onWorkScheduled: () -> Unit = {},
        exactRequestCommitted: suspend (String) -> Boolean = { requestId -> executionRepository.entry(requestId) != null },
    ) = ChatExecutionCoordinator(
        executionRepository = executionRepository,
        sendMessageUseCase = SendMessageUseCase(
            context = context,
            chatRepository = chatRepository,
            providerRepository = providerRepository(),
            client = OpenAiCompatibleClient(OkHttpClient(), Json {}),
            dispatchers = dispatchers(),
        ),
        providerRepository = providerRepository(),
        webSearchClient = JinaWebSearchClient(OkHttpClient()),
        attachmentStore = QueuedAttachmentStore(context),
        dispatchers = dispatchers(),
        onWorkScheduled = onWorkScheduled,
        exactRequestCommitted = exactRequestCommitted,
    )

    private fun dispatchers() = AppDispatchers(
        io = Dispatchers.IO,
        default = Dispatchers.IO,
        main = Dispatchers.IO,
    )

    private fun providerRepository() = ProviderRepository(
        dao = database.providerProfileDao(),
        cipher = TestCipher,
        timeProvider = TimeProvider { 10L },
    )

    private object TestCipher : StringCipher {
        override fun encrypt(plainText: String): EncryptedValue = EncryptedValue(byteArrayOf(), byteArrayOf())

        override fun decrypt(value: EncryptedValue): String = ""
    }
}
