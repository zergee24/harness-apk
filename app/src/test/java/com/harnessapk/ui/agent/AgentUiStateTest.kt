package com.harnessapk.ui.agent

import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentBundleException
import com.harnessapk.agent.AgentImportPreview
import com.harnessapk.agent.AgentImportSession
import com.harnessapk.agent.AgentPackageManifest
import com.harnessapk.agent.ParsedAgentBundle
import com.harnessapk.agent.AgentStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentUiStateTest {
    @Test
    fun readyAgentCanStartAndShowsCorpusCoverage() {
        val agent = agent(status = AgentStatus.READY, installed = 2, required = 2)

        assertTrue(canStartAgent(agent))
        assertEquals("可用", agentStatusLabel(agent.status))
        assertEquals("资料 2/2", agentCorpusCoverage(agent))
    }

    @Test
    fun waitingAgentCannotStart() {
        val agent = agent(status = AgentStatus.WAITING_FOR_CORPUS, installed = 1, required = 2)

        assertFalse(canStartAgent(agent))
        assertEquals("缺少资料", agentStatusLabel(agent.status))
    }

    @Test
    fun formatsPackageSizeWithoutExcessPrecision() {
        assertEquals("900 B", formatAgentPackageSize(900))
        assertEquals("1.5 MB", formatAgentPackageSize(1_572_864))
    }

    @Test
    fun replacingPreviewDiscardsNewSessionWhenPreviousDiscardFails() = runTest {
        val discarded = mutableListOf<String>()
        val viewModel = AgentImportPreviewViewModel { session ->
            discarded += session.id
            if (session.id == "previous") throw AgentBundleException("previous expired")
        }
        val previous = session("previous")
        val replacement = session("replacement")
        viewModel.replace(previous)

        assertTrue(runCatching { viewModel.replace(replacement) }.isFailure)
        assertTrue(viewModel.session.value === previous)
        assertEquals(listOf("previous", "replacement"), discarded)
    }

    @Test
    fun concurrentPreviewSelectionsDiscardLoserAndKeepCurrentSession() = runTest {
        val firstDiscardStarted = CompletableDeferred<Unit>()
        val allowFirstDiscard = CompletableDeferred<Unit>()
        var previousDiscardCalls = 0
        val discarded = mutableListOf<String>()
        val viewModel = AgentImportPreviewViewModel { session ->
            discarded += session.id
            if (session.id == "previous" && ++previousDiscardCalls == 1) {
                firstDiscardStarted.complete(Unit)
                allowFirstDiscard.await()
            }
        }
        val previous = session("previous")
        val first = session("first")
        val second = session("second")
        viewModel.replace(previous)

        val firstReplacement = async { viewModel.replace(first) }
        firstDiscardStarted.await()
        assertTrue(viewModel.replace(second))
        allowFirstDiscard.complete(Unit)

        assertFalse(firstReplacement.await())
        assertTrue(viewModel.session.value === second)
        assertTrue("first" in discarded)
    }

    @Test
    fun failedNewSessionCleanupDoesNotLeaveItsStagingFileOrReplaceValidPreview() = runTest {
        val viewModel = AgentImportPreviewViewModel { session ->
            throw AgentBundleException("cannot discard ${session.id}")
        }
        val previous = session("previous")
        val replacement = session("replacement", createStagedFile = true)
        viewModel.replace(previous)

        assertTrue(runCatching { viewModel.replace(replacement) }.isFailure)
        assertTrue(viewModel.session.value === previous)
        assertFalse(replacement.stagedFile.exists())
    }

    private fun agent(status: AgentStatus, installed: Int, required: Int): Agent = Agent(
        id = "agent-1",
        name = "资料研究代理",
        summary = "基于资料模拟",
        activeVersion = 1,
        publisherFingerprint = "fingerprint",
        status = status,
        requiredCorpusCount = required,
        installedCorpusCount = installed,
    )

    private fun session(id: String, createStagedFile: Boolean = false): AgentImportSession {
        val staged = File.createTempFile("agent-preview-$id", ".hbundle").apply {
            if (!createStagedFile) delete()
        }
        val bundle = ParsedAgentBundle(
            file = staged,
            packageSha256 = "sha-$id",
            publisherPublicKey = byteArrayOf(1),
            publisherFingerprint = "publisher",
            manifestJson = "{}",
            agent = AgentPackageManifest(id, id, 1, "", "", "", "", "", "", emptyList()),
            corpora = emptyList(),
            persona = "",
            worldviewJsonl = "",
            compressedSizeBytes = 0L,
            uncompressedSizeBytes = 0L,
        )
        return AgentImportSession(
            id = id,
            stagedFile = staged,
            parsedBundle = bundle,
            preview = AgentImportPreview(id, id, 1, "", "publisher", emptyList(), 0L, false),
        )
    }
}
