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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatConversationControllerTest {
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
}
