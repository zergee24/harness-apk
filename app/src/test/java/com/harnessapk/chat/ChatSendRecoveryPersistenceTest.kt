package com.harnessapk.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendRecoveryPersistenceTest {
    @Test
    fun intentIsPersistedBeforeEnqueueAndCarriesDocumentContextWithoutSecrets() = runTest {
        val persistence = FakePersistence()
        val store = ChatSendRecoveryStore(persistence)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var enqueueCalls = 0
        val request = enqueueRequest("intent", userInputText = "用户原文", withDocument = true)
        val state = requestState("intent")
        val manager = ChatSendRecoveryManager(
            scope = scope,
            store = store,
            controller = ChatSendController(
                enqueue = {
                    enqueueCalls += 1
                    entryFor(it.requestId)
                },
                requestExists = { true },
            ),
            retryDelayMillis = 0,
        )

        val result = manager.startWithResult("c1", state, request)

        assertTrue(result is ChatSendStartResult.Started)
        assertEquals(0, enqueueCalls)
        val persisted = persistence.records.single()
        assertEquals("用户原文", persisted.intent.originalText)
        assertTrue(
            persisted.intent.originalAttachments.any {
                it.kind == ChatSendAttachmentKind.DOCUMENT && it.id == "doc-1"
            },
        )
        assertTrue(persisted.intent.requestContextJson.contains("userInputText"))
        assertTrue(persisted.intent.requestContextJson.contains("documents"))
        assertFalse(persisted.intent.requestContextJson.contains("providerKey"))

        runCurrent()
        assertEquals(1, enqueueCalls)
        assertEquals(ChatSendRequestPhase.LANDED, store.current("c1")?.phase)
        scope.cancel()
    }

    @Test
    fun persistenceFailureDoesNotPublishStateOrStartEnqueue() = runTest {
        val persistence = FakePersistence().apply { failSave = true }
        val store = ChatSendRecoveryStore(persistence)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var enqueueCalls = 0
        val manager = manager(
            scope = scope,
            store = store,
            enqueue = {
                enqueueCalls += 1
                entryFor(it.requestId)
            },
            requestExists = { true },
        )

        val result = manager.startWithResult("c1", requestState("write-failure"), enqueueRequest("write-failure"))

        assertTrue(result is ChatSendStartResult.PersistenceFailed)
        assertEquals(0, enqueueCalls)
        assertNull(store.current("c1"))
        scope.cancel()
    }

    @Test
    fun reconstructedStoreTurnsInFlightIntoUnknownAndRecoveryOnlyQueriesOriginalId() = runTest {
        val persistence = FakePersistence()
        val firstStore = ChatSendRecoveryStore(persistence)
        assertTrue(firstStore.start("c1", requestState("restart"), intent("restart")))

        val recoveredStore = ChatSendRecoveryStore(persistence)
        assertEquals(ChatSendRequestPhase.UNKNOWN, recoveredStore.current("c1")?.phase)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val enqueuedIds = mutableListOf<String>()
        val queriedIds = mutableListOf<String>()
        val manager = manager(
            scope = scope,
            store = recoveredStore,
            enqueue = {
                enqueuedIds += it.requestId
                entryFor(it.requestId)
            },
            requestExists = {
                queriedIds += it
                true
            },
        )

        manager.recoverPending()
        advanceUntilIdle()

        assertEquals(listOf("restart"), queriedIds)
        assertTrue(enqueuedIds.isEmpty())
        assertEquals(ChatSendRequestPhase.LANDED, recoveredStore.current("c1")?.phase)
        scope.cancel()
    }

    @Test
    fun manualRecheckCannotDeclareNotLandedWhileOriginalEnqueueIsStillRunning() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var lookupCalls = 0
        val store = ChatSendRecoveryStore()
        val manager = manager(
            scope = scope,
            store = store,
            enqueue = {
                started.complete(Unit)
                release.await()
                entryFor(it.requestId)
            },
            requestExists = {
                lookupCalls += 1
                false
            },
        )
        val owner = requireNotNull(manager.start("c1", requestState("in-flight"), enqueueRequest("in-flight")))
        runCurrent()
        started.await()

        val result = manager.recheck("c1", "in-flight")

        assertTrue(result is ChatRequestLanding.Unknown)
        assertEquals(0, lookupCalls)
        assertEquals(ChatSendRequestPhase.IN_FLIGHT, store.current("c1")?.phase)
        release.complete(Unit)
        owner.join()
        assertEquals(ChatSendRequestPhase.LANDED, store.current("c1")?.phase)
        scope.cancel()
    }

    @Test
    fun twoManualRechecksShareOneLookupAndSettleTheSameRequest() = runTest {
        val persistence = FakePersistence()
        val store = ChatSendRecoveryStore(persistence)
        assertTrue(store.start("c1", requestState("double"), intent("double")))
        assertTrue(store.markUnknown("c1", "double", IllegalStateException("enqueue"), null, IllegalStateException("lookup")))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val release = CompletableDeferred<Unit>()
        var lookupCalls = 0
        val manager = manager(
            scope = scope,
            store = store,
            enqueue = { entryFor(it.requestId) },
            requestExists = {
                lookupCalls += 1
                release.await()
                true
            },
        )

        val first = async { manager.recheck("c1", "double") }
        val second = async { manager.recheck("c1", "double") }
        runCurrent()
        assertTrue(manager.isChecking("c1"))
        assertEquals(1, lookupCalls)
        release.complete(Unit)
        assertEquals(ChatRequestLanding.Landed, first.await())
        assertEquals(ChatRequestLanding.Landed, second.await())
        assertFalse(manager.isChecking("c1"))
        scope.cancel()
    }

    @Test
    fun unknownRecoveryUsesAtMostFiveBoundedAutomaticAttempts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var lookupCalls = 0
        val store = ChatSendRecoveryStore()
        val manager = manager(
            scope = scope,
            store = store,
            enqueue = { throw IllegalStateException("enqueue") },
            requestExists = {
                lookupCalls += 1
                throw IllegalStateException("lookup-$lookupCalls")
            },
        )

        val owner = requireNotNull(manager.start("c1", requestState("bounded"), enqueueRequest("bounded")))
        owner.join()

        assertEquals(6, lookupCalls) // one submit lookup plus five automatic rechecks
        assertEquals(5, store.current("c1")?.automaticRecheckAttempts)
        assertEquals(ChatSendRequestPhase.UNKNOWN, store.current("c1")?.phase)
        scope.cancel()
    }

    @Test
    fun terminalConsumeKeepsDurableHandoffForRestartUntilAcknowledged() {
        val persistence = FakePersistence()
        val store = ChatSendRecoveryStore(persistence)
        val original = requestState("terminal")
        assertTrue(store.start("c1", original, intent("terminal")))
        assertTrue(store.updateCurrentDraft("c1", "terminal", "后续草稿", emptyList()))
        assertTrue(store.markLanded("c1", "terminal"))

        val consumed = store.consumeTerminal("c1", "terminal")

        assertEquals("后续草稿", consumed?.currentDraftText)
        assertNull(store.current("c1"))
        assertTrue(persistence.records.single().consumed)
        val restarted = ChatSendRecoveryStore(persistence)
        assertEquals("后续草稿", restarted.current("c1")?.currentDraftText)
        assertEquals(ChatSendRequestPhase.LANDED, restarted.current("c1")?.phase)
        assertTrue(restarted.acknowledgeTerminal("c1", "terminal"))
        assertTrue(persistence.records.isEmpty())
    }

    @Test
    fun finishTerminalKeepsTheOriginalRequestBlockingWhenRemovalCannotBePersisted() {
        val persistence = FakePersistence()
        val store = ChatSendRecoveryStore(persistence)
        assertTrue(store.start("c1", requestState("finish"), intent("finish")))
        assertTrue(store.markNotLanded("c1", "finish", IllegalStateException("enqueue"), null))

        persistence.failSave = true
        assertFalse(store.finishTerminal("c1", "finish"))
        assertEquals("finish", store.current("c1")?.requestId)
        assertFalse(store.start("c1", requestState("next"), intent("next")))

        persistence.failSave = false
        assertTrue(store.finishTerminal("c1", "finish"))
        assertNull(store.current("c1"))
        assertTrue(persistence.records.isEmpty())
    }

    @Test
    fun legacyAcknowledgeFailureRestoresTheVisibleTerminalBeforeAllowingAReplacement() {
        val persistence = FakePersistence()
        val store = ChatSendRecoveryStore(persistence)
        assertTrue(store.start("c1", requestState("ack"), intent("ack")))
        assertTrue(store.markLanded("c1", "ack"))
        assertEquals("ack", store.consumeTerminal("c1", "ack")?.requestId)

        persistence.failSave = true
        assertFalse(store.acknowledgeTerminal("c1", "ack"))
        assertEquals("ack", store.current("c1")?.requestId)
        assertFalse(
            store.startPersisted("c1", requestState("replacement"), intent("replacement"))
                is ChatSendStoreStartResult.Started,
        )
    }

    @Test
    fun archiveGuardRejectsAConversationWithPendingRecovery() = runTest {
        val store = ChatSendRecoveryStore()
        assertTrue(
            store.startPersisted("c1", requestState("pending-archive"), intent("pending-archive"))
                is ChatSendStoreStartResult.Started,
        )
        var actionCalls = 0

        val result = store.withArchiveGuard("c1") {
            actionCalls += 1
            "archived"
        }

        assertNull(result)
        assertEquals(0, actionCalls)
    }

    @Test
    fun archiveGuardAllowsKnownLandedAndNotLandedStates() = runTest {
        val landedStore = ChatSendRecoveryStore()
        assertTrue(
            landedStore.startPersisted("c1", requestState("landed"), intent("landed"))
                is ChatSendStoreStartResult.Started,
        )
        assertTrue(landedStore.markLanded("c1", "landed"))
        assertEquals("archived-landed", landedStore.withArchiveGuard("c1") { "archived-landed" })

        val notLandedStore = ChatSendRecoveryStore()
        assertTrue(
            notLandedStore.startPersisted("c1", requestState("not-landed"), intent("not-landed"))
                is ChatSendStoreStartResult.Started,
        )
        assertTrue(
            notLandedStore.markNotLanded(
                "c1",
                "not-landed",
                IllegalStateException("enqueue failed"),
                null,
            ),
        )
        assertEquals("archived-not-landed", notLandedStore.withArchiveGuard("c1") { "archived-not-landed" })
    }

    @Test
    fun archiveGuardBlocksPersistedStartUntilTheArchiveActionFinishes() = runTest {
        val store = ChatSendRecoveryStore()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val archive = async {
            store.withArchiveGuard("c1") {
                entered.complete(Unit)
                release.await()
                "archived"
            }
        }
        entered.await()

        val duplicate = store.withArchiveGuard("c1") { "duplicate" }
        assertNull(duplicate)

        val blocked = store.startPersisted(
            "c1",
            requestState("during-archive"),
            intent("during-archive"),
        )

        assertTrue(blocked is ChatSendStoreStartResult.PersistenceFailed)
        assertTrue(
            (blocked as ChatSendStoreStartResult.PersistenceFailed).failure.message.orEmpty()
                .contains("归档"),
        )
        release.complete(Unit)
        assertEquals("archived", archive.await())
        assertTrue(
            store.startPersisted("c1", requestState("after-archive"), intent("after-archive"))
                is ChatSendStoreStartResult.Started,
        )
    }

    @Test
    fun archiveGuardReleasesItsMarkerWhenActionThrows() = runTest {
        val store = ChatSendRecoveryStore()
        val expected = IllegalStateException("archive failed")

        val thrown = runCatching {
            store.withArchiveGuard("c1") {
                throw expected
            }
        }.exceptionOrNull()

        assertEquals(expected, thrown)
        assertTrue(
            store.startPersisted("c1", requestState("after-error"), intent("after-error"))
                is ChatSendStoreStartResult.Started,
        )
    }

    @Test
    fun archiveGuardReleasesItsMarkerWhenActionIsCancelled() = runTest {
        val store = ChatSendRecoveryStore()
        val entered = CompletableDeferred<Unit>()
        val archive = launch {
            store.withArchiveGuard("c1") {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()

        archive.cancelAndJoin()

        assertTrue(
            store.startPersisted("c1", requestState("after-cancel"), intent("after-cancel"))
            is ChatSendStoreStartResult.Started,
        )
    }

    @Test
    fun failedLandedTerminalWriteRetainsCompletionProofForManualRecheck() = runTest {
        val persistence = FakePersistence()
        val store = ChatSendRecoveryStore(persistence)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var enqueueCalls = 0
        var lookupCalls = 0
        val manager = manager(
            scope = scope,
            store = store,
            enqueue = {
                enqueueCalls += 1
                entryFor(it.requestId)
            },
            requestExists = {
                lookupCalls += 1
                true
            },
        )
        val owner = requireNotNull(
            manager.start("c1", requestState("landed-write-failure"), enqueueRequest("landed-write-failure")),
        )
        persistence.failSave = true

        owner.join()

        assertEquals(1, enqueueCalls)
        assertEquals(5, lookupCalls)
        assertEquals(ChatSendRequestPhase.IN_FLIGHT, store.current("c1")?.phase)
        assertEquals(ChatSendRequestPhase.IN_FLIGHT, persistence.records.single().phase)
        assertTrue(store.current("c1")?.transientPersistenceFailure != null)

        val foregroundResult = manager.recheckAll()
        assertTrue(foregroundResult["landed-write-failure"] is ChatRequestLanding.Unknown)
        assertEquals(6, lookupCalls)

        persistence.failSave = false
        assertEquals(
            ChatRequestLanding.Landed,
            manager.recheck("c1", "landed-write-failure"),
        )
        assertEquals(1, enqueueCalls)
        assertEquals(ChatSendRequestPhase.LANDED, store.current("c1")?.phase)
        scope.cancel()
    }

    @Test
    fun failedNotLandedTerminalWriteAutomaticallyRechecksAfterStorageRecovers() = runTest {
        val persistence = FakePersistence()
        val store = ChatSendRecoveryStore(persistence)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var enqueueCalls = 0
        var lookupCalls = 0
        val manager = manager(
            scope = scope,
            store = store,
            enqueue = {
                enqueueCalls += 1
                throw IllegalStateException("enqueue failed")
            },
            requestExists = {
                lookupCalls += 1
                false
            },
        )
        val owner = requireNotNull(
            manager.start("c1", requestState("not-landed-write-failure"), enqueueRequest("not-landed-write-failure")),
        )
        // The first terminal journal write fails; the automatic same-ID lookup
        // then writes NOT_LANDED once storage is healthy again.
        persistence.failNextSave = true

        owner.join()

        assertEquals(1, enqueueCalls)
        assertEquals(2, lookupCalls) // submit lookup + one automatic recheck
        assertEquals(ChatSendRequestPhase.NOT_LANDED, store.current("c1")?.phase)
        assertNull(store.current("c1")?.transientPersistenceFailure)
        scope.cancel()
    }

    private fun manager(
        scope: CoroutineScope,
        store: ChatSendRecoveryStore,
        enqueue: suspend (EnqueueChatRequest) -> ChatExecutionEntry,
        requestExists: suspend (String) -> Boolean,
    ): ChatSendRecoveryManager = ChatSendRecoveryManager(
        scope = scope,
        store = store,
        controller = ChatSendController(enqueue, requestExists),
        retryDelayMillis = 0,
    )

    private fun requestState(requestId: String) = ChatSendRequestState(
        requestId = requestId,
        submittedText = "协议正文",
        // Keep JVM state-machine tests independent of Android Uri parsing. The
        // real URI round-trip is covered by the instrumented persistence test.
        submittedAttachments = emptyList(),
        isFirstUserMessage = false,
        currentDraftAttachmentSnapshots = listOf(documentSnapshot(requestId)),
    )

    private fun intent(requestId: String) = ChatSendIntent(
        conversationId = "c1",
        requestId = requestId,
        originalText = "原始问题",
        originalAttachments = listOf(documentSnapshot(requestId)),
        providerId = "provider",
        model = "model",
    )

    private fun documentSnapshot(requestId: String) = ChatSendAttachmentSnapshot(
        id = "doc-$requestId",
        kind = ChatSendAttachmentKind.DOCUMENT,
        uri = "content://document/$requestId",
        mimeType = "text/plain",
        displayName = "说明.txt",
        sizeBytes = 12L,
        sha256 = "hash-$requestId",
        extractedText = "文档正文",
        truncated = false,
    )

    private fun enqueueRequest(
        requestId: String,
        userInputText: String? = null,
        withDocument: Boolean = false,
    ) = EnqueueChatRequest(
        requestId = requestId,
        conversationId = "c1",
        content = "协议正文",
        attachments = requestState(requestId).submittedAttachments,
        providerId = "provider",
        model = "model",
        reasoningEffort = defaultReasoningEffort(),
        requestContext = ChatExecutionRequestContext(
            userInputText = userInputText,
            documents = if (withDocument) listOf(
                ChatDocumentPresentation(
                    id = "doc-1",
                    uri = "content://document/1",
                    fileName = "说明.txt",
                    mimeType = "text/plain",
                    sizeBytes = 12L,
                    sha256 = "hash",
                    extractedText = "文档正文",
                    truncated = false,
                ),
            ) else emptyList(),
        ),
    )

    private fun entryFor(requestId: String) = ChatExecutionEntry(
        id = requestId,
        conversationId = "c1",
        userMessageId = "m1",
        assistantMessageId = null,
        targetAssistantMessageId = null,
        sequence = 1L,
        type = ChatExecutionType.NORMAL,
        status = ChatExecutionStatus.QUEUED,
        providerId = "provider",
        model = "model",
        reasoningEffort = defaultReasoningEffort(),
        requestContext = ChatExecutionRequestContext(),
        errorMessage = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private class FakePersistence(
        initial: List<ChatSendRecoveryRecord> = emptyList(),
    ) : ChatSendRecoveryPersistence {
        var failSave: Boolean = false
        var failNextSave: Boolean = false
        var records: List<ChatSendRecoveryRecord> = initial
            .map { it.copy() }
            .toList()

        override fun load(): List<ChatSendRecoveryRecord> = records.toList()

        override fun save(records: List<ChatSendRecoveryRecord>) {
            if (failSave || failNextSave) {
                failNextSave = false
                throw IllegalStateException("journal unavailable")
            }
            this.records = records.toList()
        }
    }
}
