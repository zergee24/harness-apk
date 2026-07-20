package com.harnessapk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessApkApplicationRecoveryTest {
    @Test
    fun applicationRecoveryUsesSiblingSupervisorJobsAndIsolatesFailures() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val failures = mutableListOf<String>()
        var chatStarted = false

        val jobs = launchApplicationRecoveryJobs(
            scope = scope,
            recoverAgentFiles = { error("agent recovery failed") },
            hasOpenChatWork = { true },
            startChatService = { chatStarted = true },
            onFailure = { task, _ -> failures += task },
        )
        advanceUntilIdle()

        assertEquals(listOf("agent-files"), failures)
        assertTrue(chatStarted)
        assertTrue(jobs.agentFiles.isCompleted)
        assertTrue(jobs.chatExecution.isCompleted)
    }

    @Test
    fun applicationRecoveryDoesNotConvertCancellationIntoAnOrdinaryFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val failures = mutableListOf<Throwable>()

        val jobs = launchApplicationRecoveryJobs(
            scope = scope,
            recoverAgentFiles = { throw CancellationException("stop") },
            hasOpenChatWork = { false },
            startChatService = {},
            onFailure = { _, error -> failures += error },
        )
        advanceUntilIdle()

        assertTrue(jobs.agentFiles.isCancelled)
        assertTrue(failures.isEmpty())
    }
}
