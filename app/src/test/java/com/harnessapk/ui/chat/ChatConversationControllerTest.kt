package com.harnessapk.ui.chat

import com.harnessapk.chat.ChatExecutionEntry
import com.harnessapk.chat.ChatExecutionRequestContext
import com.harnessapk.chat.ChatExecutionStatus
import com.harnessapk.chat.ChatExecutionType
import com.harnessapk.chat.EnqueueChatRequest
import com.harnessapk.chat.defaultReasoningEffort
import com.harnessapk.chat.Conversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatConversationControllerTest {
    @Test
    fun synchronouslyAcceptedSelectionsPersistInFifoOrderEvenWhenWorkerDispatcherRunsLifo() {
        val dispatcher = LifoDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val persisted = mutableListOf<String>()
        val controller = ConversationIdentityController(
            scope = scope,
            selectDraft = { persisted += requireNotNull(it) },
            reloadConversation = { conversation(agentId = persisted.last()) },
        )

        controller.selectIdentity("a")
        controller.selectIdentity("b")
        assertTrue(controller.state.value.selectionPending)

        dispatcher.runAll()

        assertEquals(listOf("a", "b"), persisted)
        assertEquals("b", controller.state.value.refreshedConversation?.agentId)
        assertFalse(controller.state.value.selectionPending)
        scope.cancel()
    }

    @Test
    fun latestAcceptedIdentitySelectionWinsAndBlocksSendUntilItSettles() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        var persisted = "initial"
        val controller = ConversationIdentityController(
            scope = scope,
            selectDraft = { agentId ->
                when (agentId) {
                    "a" -> {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                    "b" -> {
                        secondStarted.complete(Unit)
                        releaseSecond.await()
                    }
                }
                persisted = requireNotNull(agentId)
            },
            reloadConversation = { conversation(agentId = persisted) },
        )

        controller.selectIdentity("a")
        assertTrue(controller.state.value.selectionPending)
        assertFalse(controller.canSend())
        runCurrent()
        firstStarted.await()

        controller.selectIdentity("b")
        assertTrue(controller.state.value.selectionPending)
        assertFalse(controller.canSend())

        releaseFirst.complete(Unit)
        runCurrent()
        secondStarted.await()
        assertTrue(controller.state.value.selectionPending)

        releaseSecond.complete(Unit)
        advanceUntilIdle()

        assertEquals("b", persisted)
        assertEquals("b", controller.state.value.refreshedConversation?.agentId)
        assertFalse(controller.state.value.selectionPending)
        assertTrue(controller.canSend())
        scope.cancel()
    }

    @Test
    fun cancelledStaleSelectionDoesNotPublishFailureOrUnlockNewerSelection() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val controller = ConversationIdentityController(
            scope = scope,
            selectDraft = { agentId ->
                when (agentId) {
                    "a" -> {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                        throw CancellationException("stale request cancelled")
                    }
                    "b" -> {
                        secondStarted.complete(Unit)
                        releaseSecond.await()
                    }
                }
            },
            reloadConversation = { conversation(agentId = "b") },
        )

        controller.selectIdentity("a")
        runCurrent()
        firstStarted.await()
        controller.selectIdentity("b")
        releaseFirst.complete(Unit)
        runCurrent()
        secondStarted.await()

        assertTrue(controller.state.value.selectionPending)
        assertEquals(null, controller.state.value.failure)
        assertFalse(controller.canSend())

        releaseSecond.complete(Unit)
        advanceUntilIdle()
        assertEquals("b", controller.state.value.refreshedConversation?.agentId)
        assertEquals(null, controller.state.value.failure)
        scope.cancel()
    }

    @Test
    fun cancelledLatestSelectionReloadsPersistedConversationWithoutPublishingCancellation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val controller = ConversationIdentityController(
            scope = scope,
            selectDraft = { throw CancellationException("latest request cancelled") },
            reloadConversation = { conversation(agentId = "persisted") },
        )

        controller.selectIdentity("a")
        advanceUntilIdle()

        assertFalse(controller.state.value.selectionPending)
        assertEquals("persisted", controller.state.value.refreshedConversation?.agentId)
        assertEquals(null, controller.state.value.failure)
        scope.cancel()
    }

    @Test
    fun workerScopeCancellationDoesNotPublishIdentityUiState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val started = CompletableDeferred<Unit>()
        val controller = ConversationIdentityController(
            scope = scope,
            selectDraft = {
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
            },
            reloadConversation = { conversation(agentId = "should-not-publish") },
        )

        controller.selectIdentity("a")
        runCurrent()
        started.await()
        scope.cancel()
        advanceUntilIdle()

        assertTrue(controller.state.value.selectionPending)
        assertEquals(null, controller.state.value.refreshedConversation)
        assertEquals(null, controller.state.value.failure)
    }

    @Test
    fun acceptedCancellationIsSettledOnlyWhenTheExactRequestLanded() = runTest {
        val landedIds = mutableSetOf<String>()
        val controller = ChatSendController(
            enqueue = { request ->
                landedIds += request.requestId
                throw CancellationException("cancel after commit")
            },
            requestExists = { it in landedIds },
        )

        val result = controller.submit(request(requestId = "landed"))

        assertTrue(result is ChatSendSettlement.Cancelled)
        assertTrue((result as ChatSendSettlement.Cancelled).persisted)
    }

    @Test
    fun nonLandedCancellationKeepsTheSubmittedDraft() = runTest {
        val controller = ChatSendController(
            enqueue = { throw CancellationException("cancel before commit") },
            requestExists = { false },
        )

        val result = controller.submit(request(requestId = "not-landed"))

        assertTrue(result is ChatSendSettlement.Cancelled)
        assertFalse((result as ChatSendSettlement.Cancelled).persisted)
    }

    @Test
    fun lookupFailureBecomesConservativeUnknownInsteadOfEscapingAsSendFailure() = runTest {
        val originalFailure = IllegalStateException("enqueue failed")
        val lookupFailure = IllegalStateException("lookup failed")
        val controller = ChatSendController(
            enqueue = { throw originalFailure },
            requestExists = { throw lookupFailure },
        )

        val result = controller.submit(request(requestId = "unknown"))

        assertTrue(result is ChatSendSettlement.Unknown)
        result as ChatSendSettlement.Unknown
        assertEquals(originalFailure, result.originalFailure)
        assertEquals(lookupFailure, result.lookupFailure)
    }

    @Test
    fun unknownCancellationKeepsTheOriginalCancellationForTheCaller() = runTest {
        val originalCancellation = CancellationException("enqueue cancelled")
        val controller = ChatSendController(
            enqueue = { throw originalCancellation },
            requestExists = { throw IllegalStateException("lookup failed") },
        )

        val result = controller.submit(request(requestId = "cancel-unknown"))

        assertTrue(result is ChatSendSettlement.Unknown)
        assertEquals(originalCancellation, (result as ChatSendSettlement.Unknown).cancellation)
    }

    @Test
    fun retryLandingReturnsLandedAndNotLandedWithoutLeakingLookupFailures() = runTest {
        var landedLookups = 0
        val landedController = ChatSendController(
            enqueue = { entryFor(it.requestId) },
            requestExists = {
                if (++landedLookups == 1) throw IllegalStateException("temporary")
                true
            },
        )
        var notLandedLookups = 0
        val notLandedController = ChatSendController(
            enqueue = { entryFor(it.requestId) },
            requestExists = {
                if (++notLandedLookups == 1) throw IllegalStateException("temporary")
                false
            },
        )

        assertTrue(landedController.awaitLanding("landed", retryDelayMillis = 0) is ChatRequestLanding.Landed)
        assertEquals("", landedController.settleText(currentText = "A", submittedText = "A"))
        assertTrue(notLandedController.awaitLanding("not-landed", retryDelayMillis = 0) is ChatRequestLanding.NotLanded)
        assertEquals("B", notLandedController.settleText(currentText = "B", submittedText = "A"))
    }

    @Test
    fun unknownIdentityStateOrPendingRequestCannotAcceptAnotherSend() {
        assertFalse(canAcceptChatSend(identityMessageStateKnown = false, request = null))
        assertFalse(canAcceptChatSend(identityMessageStateKnown = true, request = pendingRequest()))
        assertTrue(canAcceptChatSend(identityMessageStateKnown = true, request = null))
    }

    @Test
    fun postScheduleFailureAfterTheExactRequestLandsIsAcceptedWithoutResend() = runTest {
        val landedIds = mutableSetOf<String>()
        val controller = ChatSendController(
            enqueue = { request ->
                landedIds += request.requestId
                throw IllegalStateException("runner unavailable")
            },
            requestExists = { it in landedIds },
        )

        val result = controller.submit(request(requestId = "post-schedule-failure"))

        assertTrue(result is ChatSendSettlement.AcceptedAfterFailure)
        assertEquals("post-schedule-failure", result.requestId)
    }

    @Test
    fun acceptedSendClearsOnlyTheUnchangedSubmittedText() = runTest {
        val controller = ChatSendController(
            enqueue = { entryFor(it.requestId) },
            requestExists = { false },
        )
        val submitted = "A  "
        val result = controller.submit(request(requestId = "request-a", content = submitted.trim()))

        assertTrue(result is ChatSendSettlement.Accepted)
        assertEquals("B", controller.settleText(currentText = "B", submittedText = submitted))
        assertEquals("", controller.settleText(currentText = submitted, submittedText = submitted))
    }

    @Test
    fun blockedSubmitRetainsReplacementDraftAndClearsUnchangedDraft() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val controller = ChatSendController(
            enqueue = {
                started.complete(Unit)
                release.await()
                entryFor(it.requestId)
            },
            requestExists = { false },
        )
        var currentText = "A"
        val submit = async { controller.submit(request(requestId = "blocked", content = currentText)) }

        started.await()
        currentText = "B"
        release.complete(Unit)
        assertTrue(submit.await() is ChatSendSettlement.Accepted)
        assertEquals("B", controller.settleText(currentText, "A"))
        assertEquals("", controller.settleText("A", "A"))
    }

    private fun request(requestId: String, content: String = "A"): EnqueueChatRequest = EnqueueChatRequest(
        requestId = requestId,
        conversationId = "c1",
        content = content,
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

    private fun conversation(agentId: String): Conversation = Conversation(
        id = "c1",
        title = "Conversation",
        updatedAt = 1L,
        promptOriginal = "",
        promptOptimized = "",
        promptFinal = "",
        agentId = agentId,
    )

    private fun pendingRequest() = ChatSendRequestState(
        requestId = "pending",
        submittedText = "A",
        draftImage = null,
        isFirstUserMessage = false,
        originalFailure = IllegalStateException("pending"),
        cancellation = null,
    )

    private class LifoDispatcher : CoroutineDispatcher() {
        private val tasks = mutableListOf<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks += block
        }

        fun runAll() {
            while (tasks.isNotEmpty()) {
                tasks.removeAt(tasks.lastIndex).run()
            }
        }
    }
}
