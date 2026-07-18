package com.harnessapk.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendRecoveryStoreTest {
    @Test
    fun appScopedOwnerCompletesInFlightAfterScreenACancelsAndScreenBAcknowledgesLandedRequest() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = ChatSendRecoveryStore()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val manager = ChatSendRecoveryManager(
            scope = managerScope,
            store = store,
            controller = ChatSendController(
                enqueue = {
                    started.complete(Unit)
                    release.await()
                    entryFor(it.requestId)
                },
                requestExists = { false },
            ),
            retryDelayMillis = 0,
        )
        val request = request("r1", first = true)
        val screenAScope = CoroutineScope(SupervisorJob() + dispatcher)
        val collector = screenAScope.launch { store.observe("c1").collect() }

        val owner = requireNotNull(manager.start("c1", request, enqueueRequest("r1")))
        runCurrent()
        started.await()
        assertEquals(ChatSendRequestPhase.IN_FLIGHT, store.current("c1")?.phase)
        assertTrue(identityLockedForPendingSend(store.current("c1")))

        screenAScope.cancel()
        collector.join()
        release.complete(Unit)
        owner.join()

        val screenBRequest = store.observe("c1").first()
        assertEquals("r1", screenBRequest?.requestId)
        assertEquals(ChatSendRequestPhase.LANDED, screenBRequest?.phase)
        assertTrue(store.acknowledgeTerminal("c1", "r1"))
        assertNull(store.current("c1"))
        managerScope.cancel()
    }

    @Test
    fun unknownRequestRetriesInOwnerUntilItLandsOrIsConfirmedNotLanded() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val landedStore = ChatSendRecoveryStore()
        var landedLookups = 0
        val landedManager = manager(
            dispatcher = dispatcher,
            store = landedStore,
            enqueue = { throw IllegalStateException("enqueue") },
            requestExists = {
                if (++landedLookups == 1) throw IllegalStateException("lookup")
                true
            },
        )
        requireNotNull(landedManager.manager.start("c1", request("landed"), enqueueRequest("landed"))).join()
        assertEquals(ChatSendRequestPhase.LANDED, landedStore.current("c1")?.phase)

        val notLandedStore = ChatSendRecoveryStore()
        var notLandedLookups = 0
        val notLandedManager = manager(
            dispatcher = dispatcher,
            store = notLandedStore,
            enqueue = { throw IllegalStateException("enqueue") },
            requestExists = {
                if (++notLandedLookups == 1) throw IllegalStateException("lookup")
                false
            },
        )
        requireNotNull(notLandedManager.manager.start("c1", request("not-landed"), enqueueRequest("not-landed"))).join()
        assertEquals(ChatSendRequestPhase.NOT_LANDED, notLandedStore.current("c1")?.phase)
        assertEquals("A", notLandedStore.current("c1")?.submittedText)

        landedManager.scope.cancel()
        notLandedManager.scope.cancel()
    }

    @Test
    fun oldTerminalRequestCannotOverwriteOrClearNewerRequest() {
        val store = ChatSendRecoveryStore()
        assertTrue(store.start("c1", request("old")))
        assertTrue(store.markLanded("c1", "old"))
        assertTrue(store.acknowledgeTerminal("c1", "old"))
        assertTrue(store.start("c1", request("new")))

        assertFalse(store.markNotLanded("c1", "old", IllegalStateException("old"), null))
        assertFalse(store.acknowledgeTerminal("c1", "old"))
        assertEquals("new", store.current("c1")?.requestId)
        assertEquals(ChatSendRequestPhase.IN_FLIGHT, store.current("c1")?.phase)
    }

    @Test
    fun managerRethrowsTheSameCancellationAfterPublishingItsTerminalState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = ChatSendRecoveryStore()
        val cancellation = CancellationException("after commit")
        val fixture = manager(
            dispatcher = dispatcher,
            store = store,
            enqueue = { throw cancellation },
            requestExists = { true },
        )

        val owner = requireNotNull(fixture.manager.start("c1", request("cancelled"), enqueueRequest("cancelled")))
        val completion = CompletableDeferred<Throwable?>()
        owner.invokeOnCompletion { completion.complete(it) }
        owner.join()
        assertTrue(completion.await() === cancellation)
        assertEquals(ChatSendRequestPhase.LANDED, store.current("c1")?.phase)
        fixture.scope.cancel()
    }

    @Test
    fun acknowledgeRejectsInFlightAndUnknownButAcceptsOnlyTheExactTerminalRequest() {
        val store = ChatSendRecoveryStore()
        val failure = IllegalStateException("enqueue")
        assertTrue(store.start("c1", request("in-flight")))
        assertFalse(store.acknowledgeTerminal("c1", "in-flight"))
        assertTrue(store.markUnknown("c1", "in-flight", failure, null, IllegalStateException("lookup")))
        assertFalse(store.acknowledgeTerminal("c1", "in-flight"))
        assertTrue(store.markNotLanded("c1", "in-flight", failure, null))
        assertFalse(store.acknowledgeTerminal("c1", "other"))
        assertTrue(store.acknowledgeTerminal("c1", "in-flight"))
    }

    @Test
    fun currentDraftSnapshotUpdatesOnlyWhileTheExactRequestIsActive() {
        val store = ChatSendRecoveryStore()
        assertTrue(store.start("c1", request("request")))
        assertTrue(store.updateCurrentDraft("c1", "request", "B", null, "image/png"))
        assertEquals("B", store.current("c1")?.currentDraftText)
        assertTrue(store.markLanded("c1", "request"))
        assertFalse(store.updateCurrentDraft("c1", "request", "C", null, "image/png"))
        assertEquals("B", store.current("c1")?.currentDraftText)
    }

    private fun manager(
        dispatcher: TestDispatcher,
        store: ChatSendRecoveryStore,
        enqueue: suspend (EnqueueChatRequest) -> ChatExecutionEntry,
        requestExists: suspend (String) -> Boolean,
    ): ManagerFixture {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return ManagerFixture(
            scope = scope,
            manager = ChatSendRecoveryManager(
                scope = scope,
                store = store,
                controller = ChatSendController(enqueue, requestExists),
                retryDelayMillis = 0,
            ),
        )
    }

    private fun request(requestId: String, first: Boolean = false) = ChatSendRequestState(
        requestId = requestId,
        submittedText = "A",
        submittedImage = null,
        submittedMimeType = "image/png",
        isFirstUserMessage = first,
    )

    private fun enqueueRequest(requestId: String) = EnqueueChatRequest(
        requestId = requestId,
        conversationId = "c1",
        content = "A",
        attachments = emptyList(),
        providerId = null,
        model = null,
        reasoningEffort = defaultReasoningEffort(),
        requestContext = ChatExecutionRequestContext(),
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
        providerId = null,
        model = null,
        reasoningEffort = defaultReasoningEffort(),
        requestContext = ChatExecutionRequestContext(),
        errorMessage = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private data class ManagerFixture(
        val scope: CoroutineScope,
        val manager: ChatSendRecoveryManager,
    )
}
