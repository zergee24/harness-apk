package com.harnessapk.ui.chat

import com.harnessapk.wiki.ConversationWikiMount
import com.harnessapk.wiki.ConversationWikiMountSelection
import com.harnessapk.wiki.WikiRef
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationWikiControllerTest {
    @Test
    fun `concurrent applies are serialized and latest scope is published`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var persisted = listOf(selection("history.24", 1))
        val controller = ConversationWikiController(
            scope = scope,
            applyScope = { selections ->
                if (selections == listOf(selection("history.24", 1))) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                persisted = selections
            },
            restoreDefaultsAction = { persisted = listOf(selection("history.zztj", 2)) },
            reloadMounts = { persisted.map { mount(it.ref, it.enabled) } },
        )

        controller.apply(listOf(selection("history.24", 1)))
        runCurrent()
        firstStarted.await()
        controller.apply(listOf(selection("history.zztj", 2)))
        assertTrue(controller.state.value.writePending)

        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(selection("history.zztj", 2)), persisted)
        assertEquals(WikiRef("history.zztj", 2), controller.state.value.refreshedMounts?.single()?.ref)
        assertFalse(controller.state.value.writePending)
        scope.cancel()
    }

    @Test
    fun `failed write leaves persisted scope intact and exposes failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val persisted = listOf(selection("history.24", 1))
        val controller = ConversationWikiController(
            scope = scope,
            applyScope = { throw IllegalStateException("selected version became unavailable") },
            restoreDefaultsAction = {},
            reloadMounts = { persisted.map { mount(it.ref, it.enabled) } },
        )

        controller.apply(listOf(selection("history.zztj", 2)))
        advanceUntilIdle()

        assertEquals(listOf(selection("history.24", 1)), persisted)
        assertNotNull(controller.state.value.failure)
        assertFalse(controller.state.value.writePending)
        scope.cancel()
    }

    @Test
    fun `restore defaults refreshes mounted scope`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var persisted = listOf(selection("history.24", 1))
        val controller = ConversationWikiController(
            scope = scope,
            applyScope = { persisted = it },
            restoreDefaultsAction = { persisted = listOf(selection("history.zztj", 2)) },
            reloadMounts = { persisted.map { mount(it.ref, it.enabled) } },
        )

        controller.restoreDefaults()
        advanceUntilIdle()

        assertEquals(WikiRef("history.zztj", 2), controller.state.value.refreshedMounts?.single()?.ref)
        assertTrue(controller.canApply())
        scope.cancel()
    }
}

private fun selection(wikiId: String, version: Int, enabled: Boolean = true): ConversationWikiMountSelection =
    ConversationWikiMountSelection(WikiRef(wikiId, version), enabled)

private fun mount(ref: WikiRef, enabled: Boolean): ConversationWikiMount = ConversationWikiMount(
    conversationId = "conversation-1",
    ref = ref,
    enabled = enabled,
    mountedAt = 1L,
    updatedAt = 1L,
)
