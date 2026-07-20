package com.harnessapk.agentmemory

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentMemoryCoordinatorTest {
    @Test
    fun replyTriggersOnlyAtCompletedRoundMultiplesOfTen() = runTest {
        var completedRounds = 0
        val extractedRounds = mutableListOf<Int>()
        val coordinator = AgentMemoryCoordinator(
            scope = this,
            completedRoundCount = { completedRounds },
            extract = {
                extractedRounds += completedRounds
                AgentMemoryExtractionResult.Succeeded(savedCount = 1, ignoredCount = 0)
            },
        )

        (1..9).forEach { round ->
            completedRounds = round
            coordinator.onReplyCompleted("conversation-1")
            advanceUntilIdle()
        }
        completedRounds = 10
        coordinator.onReplyCompleted("conversation-1")
        advanceUntilIdle()
        (11..19).forEach { round ->
            completedRounds = round
            coordinator.onReplyCompleted("conversation-1")
            advanceUntilIdle()
        }
        completedRounds = 20
        coordinator.onReplyCompleted("conversation-1")
        advanceUntilIdle()

        assertEquals(listOf(10, 20), extractedRounds)
    }

    @Test
    fun leaveDoesNotRepeatHandledRoundButNextTenthReplyStillRuns() = runTest {
        val completedRounds = mutableMapOf("at-ten" to 10, "at-nine" to 9)
        val calls = mutableMapOf<String, Int>()
        val coordinator = AgentMemoryCoordinator(
            scope = this,
            completedRoundCount = { conversationId -> completedRounds.getValue(conversationId) },
            extract = { conversationId ->
                calls[conversationId] = calls.getOrDefault(conversationId, 0) + 1
                AgentMemoryExtractionResult.Succeeded(savedCount = 0, ignoredCount = 0)
            },
        )

        coordinator.onReplyCompleted("at-ten")
        advanceUntilIdle()
        coordinator.onConversationLeft("at-ten")
        coordinator.onConversationLeft("at-ten")
        advanceUntilIdle()

        coordinator.onConversationLeft("at-nine")
        advanceUntilIdle()
        completedRounds["at-nine"] = 10
        coordinator.onReplyCompleted("at-nine")
        advanceUntilIdle()

        assertEquals(1, calls["at-ten"])
        assertEquals(2, calls["at-nine"])
    }

    @Test
    fun duplicateReplyAndLeaveEventsAtTheSameRoundRunOnce() = runTest {
        val rounds = mapOf("reply" to 10, "leave" to 7)
        val calls = mutableMapOf<String, Int>()
        val coordinator = AgentMemoryCoordinator(
            scope = this,
            completedRoundCount = { rounds.getValue(it) },
            extract = {
                calls[it] = calls.getOrDefault(it, 0) + 1
                AgentMemoryExtractionResult.Succeeded(savedCount = 1, ignoredCount = 0)
            },
        )

        repeat(3) { coordinator.onReplyCompleted("reply") }
        repeat(3) { coordinator.onConversationLeft("leave") }
        advanceUntilIdle()

        assertEquals(mapOf("reply" to 1, "leave" to 1), calls)
    }

    @Test
    fun eventArrivingDuringExtractionDrainsTheLatestCompletedRound() = runTest {
        var completedRounds = 10
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val extractedRounds = mutableListOf<Int>()
        val coordinator = AgentMemoryCoordinator(
            scope = this,
            completedRoundCount = { completedRounds },
            extract = {
                val round = completedRounds
                extractedRounds += round
                if (round == 10) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                AgentMemoryExtractionResult.Succeeded(savedCount = 1, ignoredCount = 0)
            },
        )

        coordinator.onReplyCompleted("conversation-1")
        runCurrent()
        assertTrue(firstStarted.isCompleted)
        completedRounds = 20
        coordinator.onReplyCompleted("conversation-1")
        runCurrent()
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(10, 20), extractedRounds)
    }

    @Test
    fun failedAndUnstableSkippedResultsRetryButSuccessfulAndStableSkippedResultsDoNot() = runTest {
        val attempts = mutableMapOf<String, Int>()
        val coordinator = AgentMemoryCoordinator(
            scope = this,
            completedRoundCount = { 10 },
            extract = { conversationId ->
                val attempt = attempts.getOrDefault(conversationId, 0) + 1
                attempts[conversationId] = attempt
                when (conversationId) {
                    "failed" -> if (attempt == 1) {
                        AgentMemoryExtractionResult.Failed(AgentMemoryExtractionFailureCategory.NETWORK)
                    } else {
                        AgentMemoryExtractionResult.Succeeded(savedCount = 0, ignoredCount = 0)
                    }
                    "unstable" -> if (attempt == 1) {
                        AgentMemoryExtractionResult.Skipped(AgentMemoryExtractionSkipReason.IDENTITY_CHANGED)
                    } else {
                        AgentMemoryExtractionResult.Succeeded(savedCount = 0, ignoredCount = 0)
                    }
                    else -> AgentMemoryExtractionResult.Skipped(AgentMemoryExtractionSkipReason.ARCHIVED)
                }
            },
        )

        listOf("failed", "unstable", "stable").forEach { conversationId ->
            coordinator.onReplyCompleted(conversationId)
            advanceUntilIdle()
            coordinator.onReplyCompleted(conversationId)
            advanceUntilIdle()
            coordinator.onReplyCompleted(conversationId)
            advanceUntilIdle()
        }

        assertEquals(2, attempts["failed"])
        assertEquals(2, attempts["unstable"])
        assertEquals(1, attempts["stable"])
    }

    @Test
    fun differentConversationsRunInParallelAndOneFailureDoesNotCancelTheOther() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val coordinator = AgentMemoryCoordinator(
            scope = this,
            completedRoundCount = { 10 },
            extract = { conversationId ->
                if (conversationId == "first") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    AgentMemoryExtractionResult.Failed(AgentMemoryExtractionFailureCategory.NETWORK)
                } else {
                    secondStarted.complete(Unit)
                    AgentMemoryExtractionResult.Succeeded(savedCount = 1, ignoredCount = 0)
                }
            },
        )

        coordinator.onReplyCompleted("first")
        coordinator.onReplyCompleted("second")
        runCurrent()

        assertTrue(firstStarted.isCompleted)
        assertTrue(secondStarted.isCompleted)
        releaseFirst.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun roundCountFailureAndCancellationLeaveCheckpointRetryable() = runTest {
        var countAttempts = 0
        var extractionAttempts = 0
        val countFailureCoordinator = AgentMemoryCoordinator(
            scope = this,
            completedRoundCount = {
                countAttempts += 1
                if (countAttempts == 1) throw IOException("database unavailable")
                10
            },
            extract = {
                extractionAttempts += 1
                AgentMemoryExtractionResult.Succeeded(savedCount = 1, ignoredCount = 0)
            },
        )

        countFailureCoordinator.onReplyCompleted("count-failure")
        advanceUntilIdle()
        countFailureCoordinator.onReplyCompleted("count-failure")
        advanceUntilIdle()

        var cancellationAttempts = 0
        val cancellationCoordinator = AgentMemoryCoordinator(
            scope = this,
            completedRoundCount = { 10 },
            extract = {
                cancellationAttempts += 1
                if (cancellationAttempts == 1) throw CancellationException("cancel worker")
                AgentMemoryExtractionResult.Succeeded(savedCount = 1, ignoredCount = 0)
            },
        )
        cancellationCoordinator.onReplyCompleted("cancelled")
        advanceUntilIdle()
        cancellationCoordinator.onReplyCompleted("cancelled")
        advanceUntilIdle()

        assertEquals(2, countAttempts)
        assertEquals(1, extractionAttempts)
        assertEquals(2, cancellationAttempts)
    }

    @Test
    fun eventPendingDuringCancelledExtractionIsHandedToANewDrain() = runTest {
        var completedRounds = 10
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val extractedRounds = mutableListOf<Int>()
        val coordinator = AgentMemoryCoordinator(
            scope = this,
            completedRoundCount = { completedRounds },
            extract = {
                val round = completedRounds
                extractedRounds += round
                if (round == 10) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    throw CancellationException("cancel first drain")
                }
                AgentMemoryExtractionResult.Succeeded(savedCount = 1, ignoredCount = 0)
            },
        )

        coordinator.onReplyCompleted("conversation-1")
        runCurrent()
        assertTrue(firstStarted.isCompleted)
        completedRounds = 20
        coordinator.onReplyCompleted("conversation-1")
        runCurrent()
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(10, 20), extractedRounds)
    }

    @Test
    fun finishedStateIsReleasedAndSuccessfulCheckpointsStayBounded() = runTest {
        val coordinator = AgentMemoryCoordinator(
            scope = this,
            completedRoundCount = { 1 },
            extract = {
                AgentMemoryExtractionResult.Succeeded(savedCount = 0, ignoredCount = 0)
            },
            maxCheckpointEntries = 32,
        )

        repeat(200) { index ->
            coordinator.onConversationLeft("conversation-$index")
        }
        advanceUntilIdle()

        assertEquals(0, coordinator.activeStateCount())
        assertTrue(coordinator.checkpointCount() <= 32)
    }

    @Test
    fun finishingDrainCannotRemoveItsReplacementState() {
        val original = Any()
        val replacement = Any()

        assertTrue(shouldRemoveAgentMemoryState(original, original))
        assertTrue(!shouldRemoveAgentMemoryState(replacement, original))
    }
}
