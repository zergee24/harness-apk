package com.harnessapk.chat

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID

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

        var enqueueFailure: SQLiteConstraintException? = null
        try {
            coordinator.enqueue(request)
        } catch (error: SQLiteConstraintException) {
            enqueueFailure = error
        } finally {
            coordinator.close()
        }

        assertNotNull("Expected Room enqueue to fail", enqueueFailure)
        assertTrue(enqueueFailure!!.message.orEmpty().contains("attachment queue failure"))
        assertTrue("Copied attachments must be reclaimed", managedDirectory.listFiles().orEmpty().isEmpty())
        assertTrue(executionRepository.entry(request.requestId) == null)
        assertTrue(database.messageDao().countUserMessages(conversationId) == 0)
    }

    @Test
    fun persistAllRethrowsMidCopyFailureAndDeletesEarlierFinalAndCurrentTemporaryFiles() = runBlocking {
        val first = sourceAttachment("first.jpg")
        val second = sourceAttachment("second.jpg")
        val originalFailure = IOException("second attachment interrupted")
        var observedWrittenTemporary = false
        val failingInput = object : InputStream() {
            private var emittedPartialBytes = false

            override fun read(): Int = error("Buffered read expected")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!emittedPartialBytes) {
                    val partial = "partial second attachment".toByteArray()
                    partial.copyInto(buffer, offset, endIndex = partial.size.coerceAtMost(length))
                    emittedPartialBytes = true
                    return partial.size.coerceAtMost(length)
                }
                observedWrittenTemporary = managedDirectory.listFiles().orEmpty().any { file ->
                    file.name.endsWith(".tmp") && file.length() > 0L
                }
                throw originalFailure
            }
        }
        val store = QueuedAttachmentStore(
            context = context,
            inputOpener = { uri ->
                if (uri == second.uri) failingInput else context.contentResolver.openInputStream(uri)
            },
        )

        var copyFailure: IOException? = null
        try {
            store.persistAll(listOf(first, second))
        } catch (error: IOException) {
            copyFailure = error
        }

        assertNotNull("Expected second attachment copy to fail", copyFailure)
        assertSame(originalFailure, copyFailure)
        assertTrue("Second copy must write its temporary file before failing", observedWrittenTemporary)
        assertTrue("Batch copy must leave no final or temporary files", managedDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun sequentialDuplicateRequestReclaimsOnlyTheSecondCopiedBatch() = runBlocking {
        val conversationId = chatRepository.createConversation()
        val request = request(
            conversationId = conversationId,
            requestId = "attachment-sequential-duplicate",
            attachments = listOf(sourceAttachment("sequential.jpg")),
        )
        val coordinator = coordinator()

        try {
            val first = coordinator.enqueue(request)
            val second = coordinator.enqueue(request)
            val storedAttachments = database.messageAttachmentDao().listForMessage(first.userMessageId)
            val storedFile = File(requireNotNull(Uri.parse(storedAttachments.single().uri).path))

            assertEquals(first.id, second.id)
            assertEquals(first.userMessageId, second.userMessageId)
            assertEquals(1, database.messageDao().countUserMessages(conversationId))
            assertEquals(listOf(storedFile.canonicalFile), managedFiles())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun concurrentDuplicateRequestReclaimsTheBatchNotReferencedByTheUserMessage() = runBlocking {
        val conversationId = chatRepository.createConversation()
        val request = request(
            conversationId = conversationId,
            requestId = "attachment-concurrent-duplicate",
            attachments = listOf(sourceAttachment("concurrent.jpg")),
        )
        val coordinator = coordinator()

        try {
            val entries = coroutineScope {
                List(2) { async(Dispatchers.IO) { coordinator.enqueue(request) } }.awaitAll()
            }
            val entry = requireNotNull(executionRepository.entry(request.requestId))
            val storedAttachments = database.messageAttachmentDao().listForMessage(entry.userMessageId)
            val storedFile = File(requireNotNull(Uri.parse(storedAttachments.single().uri).path))

            assertEquals(1, entries.map { it.id }.distinct().size)
            assertEquals(1, database.messageDao().countUserMessages(conversationId))
            assertEquals(listOf(storedFile.canonicalFile), managedFiles())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun failedRequestReclaimsItsBatchWhenAnotherSameIdBatchCommitsBeforeTheLandingLookup() = runBlocking {
        val conversationId = chatRepository.createConversation()
        val requestId = "attachment-failure-race"
        val losingRequest = request(
            conversationId = conversationId,
            requestId = requestId,
            attachments = listOf(sourceAttachment("race-loser.jpg")),
            content = "loser",
        )
        val winningRequest = request(
            conversationId = conversationId,
            requestId = requestId,
            attachments = listOf(sourceAttachment("race-winner.jpg")),
            content = "winner",
        )
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_losing_attachment_request
            BEFORE INSERT ON chat_execution_entries
            WHEN NEW.id = '$requestId'
                AND (SELECT content FROM messages WHERE id = NEW.userMessageId) = 'loser'
            BEGIN
                SELECT RAISE(ABORT, 'losing attachment failure');
            END
            """.trimIndent(),
        )
        val landingLookupStarted = CompletableDeferred<Unit>()
        val winningRequestCommitted = CompletableDeferred<Unit>()
        val losingCoordinator = coordinator(
            exactAttachmentBatchReferenced = { exactRequestId, attachments ->
                landingLookupStarted.complete(Unit)
                winningRequestCommitted.await()
                executionRepository.isAttachmentBatchReferenced(exactRequestId, attachments)
            },
        )
        val winningCoordinator = coordinator()

        try {
            val losingEnqueue = async(Dispatchers.IO) {
                try {
                    losingCoordinator.enqueue(losingRequest)
                    null
                } catch (error: SQLiteConstraintException) {
                    error
                }
            }
            withTimeout(5_000L) { landingLookupStarted.await() }
            val winningEntry = try {
                winningCoordinator.enqueue(winningRequest)
            } finally {
                winningRequestCommitted.complete(Unit)
            }
            val losingFailure = losingEnqueue.await()
            val storedAttachments = database.messageAttachmentDao().listForMessage(winningEntry.userMessageId)
            val storedFile = File(requireNotNull(Uri.parse(storedAttachments.single().uri).path))

            assertNotNull("Expected the losing Room enqueue to fail", losingFailure)
            assertTrue(losingFailure!!.message.orEmpty().contains("losing attachment failure"))
            assertEquals(1, database.messageDao().countUserMessages(conversationId))
            assertEquals(listOf(storedFile.canonicalFile), managedFiles())
        } finally {
            winningRequestCommitted.complete(Unit)
            losingCoordinator.close()
            winningCoordinator.close()
        }
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
    fun cleanupRejectsForgedPrefixAndNearUuidNames() = runBlocking {
        val store = QueuedAttachmentStore(context)
        managedDirectory.mkdirs()
        val forgedPrefix = File(managedDirectory, "queued-anything.jpg").apply { writeText("forged") }
        val nearUuid = File(
            managedDirectory,
            "queued-12345678-1234-1234-1234-123456789ab.jpg",
        ).apply { writeText("near uuid") }

        store.cleanup(
            listOf(
                PendingImageAttachment(forgedPrefix.toUri(), "image/jpeg"),
                PendingImageAttachment(nearUuid.toUri(), "image/jpeg"),
            ),
        )

        assertTrue(forgedPrefix.isFile)
        assertTrue(nearUuid.isFile)
    }

    @Test
    fun cleanupRejectsSameDirectorySymlinkWithoutDeletingItsManagedTarget() = runBlocking {
        val store = QueuedAttachmentStore(context)
        val target = store.persistAll(listOf(sourceAttachment("symlink-target.jpg"))).single()
        val targetFile = File(requireNotNull(target.uri.path))
        val symlink = File(managedDirectory, "queued-${UUID.randomUUID()}.jpg")
        Files.createSymbolicLink(symlink.toPath(), targetFile.toPath())

        store.cleanup(listOf(PendingImageAttachment(symlink.toUri(), "image/jpeg")))

        assertTrue(Files.isSymbolicLink(symlink.toPath()))
        assertTrue(targetFile.isFile)
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
            exactAttachmentBatchReferenced = { _, _ -> throw IllegalStateException("request lookup failed") },
        )

        var enqueueFailure: SQLiteConstraintException? = null
        try {
            coordinator.enqueue(request)
        } catch (error: SQLiteConstraintException) {
            enqueueFailure = error
        } finally {
            coordinator.close()
        }

        assertNotNull("Expected Room enqueue to fail", enqueueFailure)
        assertTrue(enqueueFailure!!.message.orEmpty().contains("unknown landing failure"))
        assertTrue("Unknown landing must preserve files", managedDirectory.listFiles().orEmpty().single().isFile)
    }

    private fun managedFiles(): List<File> = managedDirectory.listFiles()
        .orEmpty()
        .filter(File::isFile)
        .map(File::getCanonicalFile)
        .sortedBy(File::getName)

    private fun sourceAttachment(name: String): PendingImageAttachment {
        val source = File(sourceDirectory, name).apply { writeText("$name bytes") }
        return PendingImageAttachment(source.toUri(), "image/jpeg")
    }

    private fun request(
        conversationId: String,
        requestId: String,
        attachments: List<PendingImageAttachment>,
        content: String = "attachment request",
    ) = EnqueueChatRequest(
        requestId = requestId,
        conversationId = conversationId,
        content = content,
        attachments = attachments,
        providerId = null,
        model = null,
        reasoningEffort = defaultReasoningEffort(),
        requestContext = ChatExecutionRequestContext(),
    )

    private fun coordinator(
        onWorkScheduled: () -> Unit = {},
        exactAttachmentBatchReferenced: suspend (String, List<PendingImageAttachment>) -> Boolean =
            executionRepository::isAttachmentBatchReferenced,
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
        exactAttachmentBatchReferenced = exactAttachmentBatchReferenced,
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
