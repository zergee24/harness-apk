package com.harnessapk.chat

import com.harnessapk.ui.chat.ChatRequestLanding
import com.harnessapk.ui.chat.ChatSendController
import com.harnessapk.ui.chat.identityLockedForPendingSend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendRecoveryStoreTest {
    @Test
    fun unknownRequestSurvivesCollectorCancellationAndLaterLandedRecoveryClearsOnlyIt() = runTest {
        val store = ChatSendRecoveryStore()
        val conversationId = "c1"
        val request = request("r1", first = true)
        assertTrue(store.start(conversationId, request))
        assertTrue(store.updateUnknown(conversationId, request.requestId, IllegalStateException("enqueue"), null, IllegalStateException("lookup")))

        val screenAScope = CoroutineScope(SupervisorJob() + testScheduler)
        val collector = screenAScope.launch { store.observe(conversationId).collect() }
        advanceUntilIdle()
        screenAScope.cancel()
        collector.join()

        val screenBRequest = store.observe(conversationId).first()
        assertEquals(request.requestId, screenBRequest?.requestId)
        assertEquals(request, store.current(conversationId)?.copy(
            originalFailure = null,
            cancellation = null,
            lookupFailure = null,
        ))
        assertTrue(identityLockedForPendingSend(store.current(conversationId)))

        var lookups = 0
        val controller = ChatSendController(
            enqueue = { error("not used") },
            requestExists = {
                if (++lookups == 1) throw IllegalStateException("temporary")
                true
            },
        )
        assertTrue(controller.awaitLanding(request.requestId, retryDelayMillis = 0) is ChatRequestLanding.Landed)
        assertTrue(store.clearIfRequest(conversationId, request.requestId))
        assertNull(store.current(conversationId))
    }

    @Test
    fun notLandedRecoveryKeepsDraftUntilItsExactRequestIsCleared() = runTest {
        val store = ChatSendRecoveryStore()
        val conversationId = "c1"
        val request = request("r1")
        store.start(conversationId, request)
        store.updateUnknown(conversationId, request.requestId, IllegalStateException("enqueue"), null, IllegalStateException("lookup"))
        val controller = ChatSendController(
            enqueue = { error("not used") },
            requestExists = { false },
        )

        assertTrue(controller.awaitLanding(request.requestId, retryDelayMillis = 0) is ChatRequestLanding.NotLanded)
        assertEquals("A", store.current(conversationId)?.submittedText)
        assertTrue(store.clearIfRequest(conversationId, request.requestId))
        assertNull(store.current(conversationId))
    }

    @Test
    fun oldRequestCompletionCannotClearNewerRequest() {
        val store = ChatSendRecoveryStore()
        val conversationId = "c1"
        val oldRequest = request("old")
        val newRequest = request("new")
        store.start(conversationId, oldRequest)
        assertTrue(store.clearIfRequest(conversationId, oldRequest.requestId))
        assertTrue(store.start(conversationId, newRequest))

        assertFalse(store.clearIfRequest(conversationId, oldRequest.requestId))
        assertEquals("new", store.current(conversationId)?.requestId)
    }

    @Test
    fun handingOffInFlightRequestMakesItConservativelyRecoverable() {
        val store = ChatSendRecoveryStore()
        val request = request("r1")
        store.start("c1", request)

        assertTrue(store.handoffInFlight("c1", request.requestId))
        assertTrue(store.current("c1")?.originalFailure != null)
    }

    private fun request(requestId: String, first: Boolean = false) = ChatSendRequestState(
        requestId = requestId,
        submittedText = "A",
        draftImage = null,
        isFirstUserMessage = first,
    )
}
