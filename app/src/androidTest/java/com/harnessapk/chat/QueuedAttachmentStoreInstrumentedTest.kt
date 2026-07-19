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
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

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
    fun persistAllKeepsPreexistingTemporaryFileWhenCreateNewFails() = runBlocking {
        lateinit var temporaryName: String
        lateinit var finalName: String
        val preexistingTemporaryContent = "preexisting temporary"
        val store = QueuedAttachmentStore(
            context = context,
            testHooks = QueuedAttachmentStoreTestHooks(
                beforeWrite = { temporary, final ->
                    temporaryName = temporary
                    finalName = final
                    File(managedDirectory, temporary).writeText(preexistingTemporaryContent)
                },
            ),
        )

        var persistFailure: FileAlreadyExistsException? = null
        try {
            store.persistAll(listOf(sourceAttachment("preexisting-temporary.jpg")))
        } catch (error: FileAlreadyExistsException) {
            persistFailure = error
        }

        assertNotNull("Expected CREATE_NEW to reject the preexisting temporary file", persistFailure)
        assertEquals(preexistingTemporaryContent, File(managedDirectory, temporaryName).readText())
        assertTrue("No final file should be created", !File(managedDirectory, finalName).exists())
    }

    @Test
    fun persistAllKeepsPreexistingFinalFileWhenFinalCreateNewFails() = runBlocking {
        lateinit var temporaryName: String
        lateinit var finalName: String
        val preexistingFinalContent = "preexisting final"
        val store = QueuedAttachmentStore(
            context = context,
            testHooks = QueuedAttachmentStoreTestHooks(
                beforeWrite = { temporary, final ->
                    temporaryName = temporary
                    finalName = final
                },
                beforeFinalCreate = { _, final ->
                    File(managedDirectory, final).writeText(preexistingFinalContent)
                },
            ),
        )

        var persistFailure: FileAlreadyExistsException? = null
        try {
            store.persistAll(listOf(sourceAttachment("preexisting-final.jpg")))
        } catch (error: FileAlreadyExistsException) {
            persistFailure = error
        }

        assertNotNull("Expected CREATE_NEW to reject the preexisting final file", persistFailure)
        assertTrue("This call must reclaim its temporary file", !File(managedDirectory, temporaryName).exists())
        assertEquals(preexistingFinalContent, File(managedDirectory, finalName).readText())
    }

    @Test
    fun persistAllRethrowsCopyFailureAndReclaimsOwnedTemporaryAndFinalFiles() = runBlocking {
        lateinit var temporaryName: String
        lateinit var finalName: String
        val originalFailure = IOException("copy from temporary to final interrupted")
        val store = QueuedAttachmentStore(
            context = context,
            testHooks = QueuedAttachmentStoreTestHooks(
                beforeWrite = { temporary, final ->
                    temporaryName = temporary
                    finalName = final
                },
                copyTemporaryToFinal = { input, output ->
                    output.write(input.read())
                    throw originalFailure
                },
            ),
        )

        var copyFailure: IOException? = null
        try {
            store.persistAll(listOf(sourceAttachment("final-copy-failure.jpg")))
        } catch (error: IOException) {
            copyFailure = error
        }

        assertSame(originalFailure, copyFailure)
        assertTrue("This call must reclaim its temporary file", !File(managedDirectory, temporaryName).exists())
        assertTrue("This call must reclaim its final file", !File(managedDirectory, finalName).exists())
    }

    @Test
    fun persistAllRethrowsTheOriginalSinglePartyBarrierTimeoutAndDeletesCopiedFiles() = runBlocking {
        val barrier = CyclicBarrier(2)
        val store = QueuedAttachmentStore(
            context = context,
            onBatchPersisted = { barrier.await(100L, TimeUnit.MILLISECONDS) },
        )

        try {
            store.persistAll(listOf(sourceAttachment("barrier-timeout.jpg")))
            fail("Expected the unmatched barrier to time out")
        } catch (error: TimeoutException) {
            assertTrue(barrier.isBroken)
        }

        assertTrue("A failed batch must not leave copied files", managedDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun storeConstructionRejectsASymlinkedManagedRootWithoutWritingOutsideIt() {
        val outsideDirectory = File(context.cacheDir, "symlinked-managed-root-persist").apply {
            deleteRecursively()
            mkdirs()
        }
        Files.createSymbolicLink(managedDirectory.toPath(), outsideDirectory.toPath())

        try {
            QueuedAttachmentStore(context)
            fail("Expected a symlinked managed root to be rejected during construction")
        } catch (_: IllegalStateException) {
        } finally {
            managedDirectory.delete()
        }

        assertTrue("Persist must never write through a managed-root symlink", outsideDirectory.listFiles().orEmpty().isEmpty())
        outsideDirectory.deleteRecursively()
        Unit
    }

    @Test
    fun persistAllKeepsWritesAndCleanupInsideThePinnedDirectoryWhenTheRawRootIsReplaced() = runBlocking {
        val pinnedDirectory = File(context.cacheDir, "pinned-managed-root-persist").apply {
            deleteRecursively()
        }
        val outsideDirectory = File(context.cacheDir, "swapped-managed-root-persist").apply {
            deleteRecursively()
            mkdirs()
        }
        lateinit var temporaryName: String
        lateinit var finalName: String
        val store = QueuedAttachmentStore(
            context = context,
            testHooks = QueuedAttachmentStoreTestHooks(
                beforeWrite = { temporary, final ->
                    temporaryName = temporary
                    finalName = final
                    File(outsideDirectory, temporary).writeText("outside temporary")
                    File(outsideDirectory, final).writeText("outside final")
                    assertTrue(managedDirectory.renameTo(pinnedDirectory))
                    Files.createSymbolicLink(managedDirectory.toPath(), outsideDirectory.toPath())
                },
            ),
        )

        try {
            try {
                store.persistAll(listOf(sourceAttachment("swapped-root.jpg")))
                fail("Expected replacement of the managed root to invalidate the batch")
            } catch (_: IllegalStateException) {
            }

            assertEquals("outside temporary", File(outsideDirectory, temporaryName).readText())
            assertEquals("outside final", File(outsideDirectory, finalName).readText())
            assertTrue("The original pinned directory must be reclaimed", pinnedDirectory.listFiles().orEmpty().isEmpty())
        } finally {
            managedDirectory.delete()
            pinnedDirectory.deleteRecursively()
            outsideDirectory.deleteRecursively()
        }
    }

    @Test
    fun cleanupDeletesOnlyFromThePinnedDirectoryWhenTheRawRootIsReplacedBeforeDelete() = runBlocking {
        val pinnedDirectory = File(context.cacheDir, "pinned-managed-root-cleanup").apply {
            deleteRecursively()
        }
        val outsideDirectory = File(context.cacheDir, "swapped-managed-root-cleanup").apply {
            deleteRecursively()
            mkdirs()
        }
        var replaceBeforeDelete = false
        lateinit var finalName: String
        val store = QueuedAttachmentStore(
            context = context,
            testHooks = QueuedAttachmentStoreTestHooks(
                beforeOwnedEntryMove = { name ->
                    if (replaceBeforeDelete && name == finalName && managedDirectory.exists()) {
                        assertTrue(managedDirectory.renameTo(pinnedDirectory))
                        Files.createSymbolicLink(managedDirectory.toPath(), outsideDirectory.toPath())
                    }
                },
            ),
        )
        val batch = store.persistAll(listOf(sourceAttachment("cleanup-swapped-root.jpg")))
        finalName = File(requireNotNull(batch.attachments.single().uri.path)).name
        File(outsideDirectory, finalName).writeText("outside final")
        replaceBeforeDelete = true

        try {
            store.cleanup(batch)

            assertEquals("outside final", File(outsideDirectory, finalName).readText())
            assertTrue("The opened directory must receive the deletion", pinnedDirectory.listFiles().orEmpty().isEmpty())
        } finally {
            managedDirectory.delete()
            pinnedDirectory.deleteRecursively()
            outsideDirectory.deleteRecursively()
        }
    }

    @Test
    fun concurrentFirstStoreConstructionInitializesTheManagedDirectoryOnce() = runBlocking {
        val stores = withTimeout(10_000L) {
            coroutineScope {
                listOf(
                    async(Dispatchers.IO) { QueuedAttachmentStore(context) },
                    async(Dispatchers.IO) { QueuedAttachmentStore(context) },
                ).awaitAll()
            }
        }

        assertEquals(2, stores.size)
        assertTrue("Store construction must initialize the managed child", managedDirectory.isDirectory)

        val batches = withTimeout(10_000L) {
            coroutineScope {
                listOf(
                    async(Dispatchers.IO) { stores[0].persistAll(listOf(sourceAttachment("first-race.jpg"))) },
                    async(Dispatchers.IO) { stores[1].persistAll(listOf(sourceAttachment("second-race.jpg"))) },
                ).awaitAll()
            }
        }

        assertEquals(2, batches.size)
        assertEquals(2, managedFiles().size)
        stores[0].cleanup(batches[0])
        stores[1].cleanup(batches[1])
        assertTrue(managedDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun storeConstructionFailsWithoutCreatingInReplacementRootWhenRootChangesBeforeChildCreation() {
        val originalRoot = File(context.cacheDir, "queued-store-construction-original-root").apply {
            deleteRecursively()
            mkdirs()
        }
        val pinnedRoot = File(context.cacheDir, "queued-store-construction-pinned-root").apply {
            deleteRecursively()
        }
        val replacementRoot = File(context.cacheDir, "queued-store-construction-replacement-root").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            try {
                QueuedAttachmentStore(
                    context = context,
                    testHooks = QueuedAttachmentStoreTestHooks(
                        beforeChildDirectoryCreate = {
                            assertTrue(originalRoot.renameTo(pinnedRoot))
                            Files.createSymbolicLink(originalRoot.toPath(), replacementRoot.toPath())
                        },
                    ),
                    filesDirectoryProvider = { originalRoot },
                )
                fail("Expected a files root replacement during construction to be rejected")
            } catch (_: IllegalStateException) {
            }

            assertTrue(
                "Construction must not create the managed child in the replacement root",
                replacementRoot.listFiles().orEmpty().isEmpty(),
            )
            assertTrue(
                "The anchored managed child may only exist in the original pinned root",
                File(pinnedRoot, "chat-attachments").isDirectory,
            )
        } finally {
            originalRoot.delete()
            pinnedRoot.deleteRecursively()
            replacementRoot.deleteRecursively()
        }
    }

    @Test
    fun persistAllFailsWithoutRecreatingAManagedChildDeletedAfterStoreConstruction() = runBlocking {
        val store = QueuedAttachmentStore(context)
        assertTrue(managedDirectory.deleteRecursively())

        try {
            store.persistAll(listOf(sourceAttachment("missing-managed-child.jpg")))
            fail("Expected a missing managed child to be rejected")
        } catch (_: IOException) {
        }

        assertTrue("Persist must not recreate a child deleted after construction", !managedDirectory.exists())
    }

    @Test
    fun persistAllDoesNotCreateInAReplacementRootAfterStoreConstruction() = runBlocking {
        val originalRoot = File(context.cacheDir, "queued-store-original-root").apply {
            deleteRecursively()
            mkdirs()
        }
        val pinnedRoot = File(context.cacheDir, "queued-store-pinned-root").apply { deleteRecursively() }
        val replacementRoot = File(context.cacheDir, "queued-store-replacement-root").apply {
            deleteRecursively()
            mkdirs()
        }
        val store = QueuedAttachmentStore(
            context = context,
            filesDirectoryProvider = { originalRoot },
        )

        try {
            assertTrue(originalRoot.renameTo(pinnedRoot))
            Files.createSymbolicLink(originalRoot.toPath(), replacementRoot.toPath())

            try {
                store.persistAll(listOf(sourceAttachment("replacement-root.jpg")))
                fail("Expected a replaced files root to be rejected")
            } catch (_: IllegalStateException) {
            }

            assertTrue("Persist must not recreate the managed child in the replacement root", replacementRoot.listFiles().orEmpty().isEmpty())
            assertTrue("The constructed root remains the only managed root", File(pinnedRoot, "chat-attachments").isDirectory)
        } finally {
            originalRoot.delete()
            pinnedRoot.deleteRecursively()
            replacementRoot.deleteRecursively()
        }
    }

    @Test
    fun persistedBatchAttachmentsCannotBeMutatedAndCleanupStillDeletesEveryFile() = runBlocking {
        val store = QueuedAttachmentStore(context)
        val batch = store.persistAll(
            listOf(
                sourceAttachment("immutable-first.jpg"),
                sourceAttachment("immutable-second.jpg"),
            ),
        )

        try {
            @Suppress("UNCHECKED_CAST")
            (batch.attachments as MutableList<PendingImageAttachment>).clear()
            fail("The batch attachment snapshot must not be mutable")
        } catch (_: UnsupportedOperationException) {
        }

        assertEquals(2, batch.attachments.size)
        store.cleanup(batch)
        assertTrue("Cleanup must use the intact internal batch snapshot", managedDirectory.listFiles().orEmpty().isEmpty())
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
        val barrierArrivals = AtomicInteger()
        val batchesPersisted = CyclicBarrier(2)
        val attachmentStore = QueuedAttachmentStore(
            context = context,
            onBatchPersisted = {
                barrierArrivals.incrementAndGet()
                batchesPersisted.await(5L, TimeUnit.SECONDS)
            },
        )
        val coordinator = coordinator(attachmentStore = attachmentStore)

        try {
            val entries = withTimeout(10_000L) {
                coroutineScope {
                    List(2) { async(Dispatchers.IO) { coordinator.enqueue(request) } }.awaitAll()
                }
            }
            val entry = requireNotNull(executionRepository.entry(request.requestId))
            val storedAttachments = database.messageAttachmentDao().listForMessage(entry.userMessageId)
            val storedFile = File(requireNotNull(Uri.parse(storedAttachments.single().uri).path))

            assertEquals(2, barrierArrivals.get())
            assertEquals(1, entries.map { it.id }.distinct().size)
            assertEquals(1, database.chatExecutionEntryDao().listForConversation(conversationId).size)
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
    fun committedRequestPropagatesSchedulingErrorAndKeepsStoredAttachments() = runBlocking {
        val conversationId = chatRepository.createConversation()
        val request = request(
            conversationId = conversationId,
            requestId = "attachment-scheduling-error",
            attachments = listOf(sourceAttachment("scheduled-error.jpg")),
        )
        val originalError = AssertionError("boom")
        val coordinator = coordinator(onWorkScheduled = { throw originalError })

        try {
            try {
                coordinator.enqueue(request)
                fail("Expected scheduling Error to propagate")
            } catch (error: AssertionError) {
                assertSame(originalError, error)
            }

            val entry = requireNotNull(executionRepository.entry(request.requestId))
            val attachment = database.messageAttachmentDao().listForMessage(entry.userMessageId).single()
            assertTrue(File(requireNotNull(Uri.parse(attachment.uri).path)).isFile)
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
        val batch = store.persistAll(listOf(sourceAttachment("idempotent.jpg")))

        store.cleanup(batch)
        store.cleanup(batch)

        assertTrue(managedDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cleanupDoesNotDeleteAReplacementCreatedBetweenOwnedFileCheckAndMove() = runBlocking {
        lateinit var finalName: String
        var replaceBeforeMove = false
        val replacementContent = "replacement must survive cleanup"
        val store = QueuedAttachmentStore(
            context = context,
            testHooks = QueuedAttachmentStoreTestHooks(
                beforeOwnedEntryMove = { name ->
                    if (replaceBeforeMove && name == finalName) {
                        assertTrue(File(managedDirectory, name).delete())
                        File(managedDirectory, name).writeText(replacementContent)
                    }
                },
            ),
        )
        val batch = store.persistAll(listOf(sourceAttachment("cleanup-replacement.jpg")))
        finalName = File(requireNotNull(batch.attachments.single().uri.path)).name
        replaceBeforeMove = true

        store.cleanup(batch)

        assertEquals(replacementContent, File(managedDirectory, finalName).readText())
    }

    @Test
    fun persistFailureCleanupDoesNotDeleteAReplacementFinalFile() = runBlocking {
        lateinit var finalName: String
        val replacementContent = "replacement final must survive failure cleanup"
        val copyFailure = IOException("copy failed after replacement")
        val store = QueuedAttachmentStore(
            context = context,
            testHooks = QueuedAttachmentStoreTestHooks(
                beforeWrite = { _, final -> finalName = final },
                copyTemporaryToFinal = { _, _ ->
                    assertTrue(File(managedDirectory, finalName).delete())
                    File(managedDirectory, finalName).writeText(replacementContent)
                    throw copyFailure
                },
            ),
        )

        try {
            store.persistAll(listOf(sourceAttachment("failure-replacement.jpg")))
            fail("Expected final copy failure")
        } catch (error: IOException) {
            assertSame(copyFailure, error)
        }

        assertEquals(replacementContent, File(managedDirectory, finalName).readText())
        assertTrue("Owned temporary entry should still be reclaimed", managedDirectory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
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
        val malformedUuid = File(managedDirectory, "queued-not-a-uuid.jpg").apply { writeText("malformed") }
        val traversalTarget = File(managedDirectory, "traversal.jpg").apply { writeText("traversal") }
        val symlink = File(managedDirectory, "outside-link.jpg")
        Files.createSymbolicLink(symlink.toPath(), sourceOutside.toPath())
        val traversal = Uri.parse("file://${managedDirectory.absolutePath}/nested/../traversal.jpg")

        store.cleanup(
            batchWithStoreOwner(
                store = store,
                attachments = listOf(
                    PendingImageAttachment(Uri.parse("content://shared/never-delete"), "image/jpeg"),
                    PendingImageAttachment(managedDirectory.toUri(), "image/jpeg"),
                    PendingImageAttachment(nested.toUri(), "image/jpeg"),
                    PendingImageAttachment(sourceLikeFile.toUri(), "image/jpeg"),
                    PendingImageAttachment(malformedUuid.toUri(), "image/jpeg"),
                    PendingImageAttachment(sourceOutside.toUri(), "image/jpeg"),
                    PendingImageAttachment(traversal, "image/jpeg"),
                    PendingImageAttachment(symlink.toUri(), "image/jpeg"),
                ),
                generatedNames = listOf(
                    "never-delete",
                    managedDirectory.name,
                    "nested/child.jpg",
                    sourceLikeFile.name,
                    malformedUuid.name,
                    sourceOutside.name,
                    "nested/../traversal.jpg",
                    symlink.name,
                ),
            ),
        )

        assertTrue(managedDirectory.isDirectory)
        assertTrue(nested.isFile)
        assertTrue(sourceLikeFile.isFile)
        assertTrue(malformedUuid.isFile)
        assertTrue(sourceOutside.isFile)
        assertTrue(traversalTarget.isFile)
        assertTrue(symlink.exists())
    }

    @Test
    fun cleanupRejectsForgedBatchEvenForAStrictUuidManagedName() = runBlocking {
        val store = QueuedAttachmentStore(context)
        managedDirectory.mkdirs()
        val fullUuid = File(
            managedDirectory,
            "queued-${UUID.randomUUID()}.jpg",
        ).apply { writeText("full uuid forged") }
        val forgedPrefix = File(managedDirectory, "queued-anything.jpg").apply { writeText("forged") }
        val nearUuid = File(
            managedDirectory,
            "queued-12345678-1234-1234-1234-123456789ab.jpg",
        ).apply { writeText("near uuid") }
        val attachments = listOf(
            PendingImageAttachment(fullUuid.toUri(), "image/jpeg"),
            PendingImageAttachment(forgedPrefix.toUri(), "image/jpeg"),
            PendingImageAttachment(nearUuid.toUri(), "image/jpeg"),
        )

        store.cleanup(
            PersistedAttachmentBatch(
                attachments = attachments,
                ownerToken = Any(),
                generatedEntries = attachments.map { attachment ->
                    OwnedAttachmentEntry(requireNotNull(attachment.uri.lastPathSegment), Any())
                },
                directoryFileKey = Any(),
            ),
        )

        assertTrue(fullUuid.isFile)
        assertTrue(forgedPrefix.isFile)
        assertTrue(nearUuid.isFile)
    }

    @Test
    fun cleanupRejectsSymlinkReplacingARealBatchPathAndKeepsItsTarget() = runBlocking {
        val store = QueuedAttachmentStore(context)
        val replacedBatch = store.persistAll(listOf(sourceAttachment("symlink-path.jpg")))
        val targetBatch = store.persistAll(listOf(sourceAttachment("symlink-target.jpg")))
        val replacedPath = File(requireNotNull(replacedBatch.attachments.single().uri.path))
        val targetFile = File(requireNotNull(targetBatch.attachments.single().uri.path))
        assertTrue(replacedPath.delete())
        Files.createSymbolicLink(replacedPath.toPath(), targetFile.toPath())

        store.cleanup(replacedBatch)

        assertTrue(Files.isSymbolicLink(replacedPath.toPath()))
        assertTrue(targetFile.isFile)
    }

    @Test
    fun cleanupDoesNotFollowASymlinkReplacingTheEntireManagedRoot() = runBlocking {
        val store = QueuedAttachmentStore(context)
        val batch = store.persistAll(listOf(sourceAttachment("root-symlink-cleanup.jpg")))
        val generatedFile = File(requireNotNull(batch.attachments.single().uri.path))
        val outsideDirectory = File(context.cacheDir, "symlinked-managed-root-cleanup").apply {
            deleteRecursively()
            mkdirs()
        }
        val outsideFile = File(outsideDirectory, generatedFile.name).apply { writeText("must survive cleanup") }

        assertTrue(managedDirectory.deleteRecursively())
        Files.createSymbolicLink(managedDirectory.toPath(), outsideDirectory.toPath())
        try {
            store.cleanup(batch)
            assertTrue("Cleanup must not treat a symlink target as a managed root", outsideFile.isFile)
        } finally {
            managedDirectory.delete()
            outsideDirectory.deleteRecursively()
        }
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

    private fun managedDirectoryFileKey(): Any = requireNotNull(
        Files.readAttributes(
            managedDirectory.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).fileKey(),
    )

    private fun sourceAttachment(name: String): PendingImageAttachment {
        val source = File(sourceDirectory, name).apply { writeText("$name bytes") }
        return PendingImageAttachment(source.toUri(), "image/jpeg")
    }

    private fun batchWithStoreOwner(
        store: QueuedAttachmentStore,
        attachments: List<PendingImageAttachment>,
        generatedNames: List<String>,
    ): PersistedAttachmentBatch {
        val ownerTokenField = QueuedAttachmentStore::class.java.getDeclaredField("ownerToken")
        ownerTokenField.isAccessible = true
        return PersistedAttachmentBatch(
            attachments = attachments,
            ownerToken = requireNotNull(ownerTokenField.get(store)),
            generatedEntries = generatedNames.map { name -> OwnedAttachmentEntry(name, Any()) },
            directoryFileKey = managedDirectoryFileKey(),
        )
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
        attachmentStore: QueuedAttachmentStore = QueuedAttachmentStore(context),
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
        attachmentStore = attachmentStore,
        dispatchers = dispatchers(),
        onWorkScheduled = onWorkScheduled,
        exactAttachmentBatchReferenced = exactAttachmentBatchReferenced,
        enqueueRunnerStarter = {},
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
