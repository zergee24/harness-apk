package com.harnessapk.ui.agent

import com.harnessapk.agent.AgentVersionRemovalOutcome
import com.harnessapk.agent.AgentVersionRemovalResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentPackagesLogicTest {
    @Test
    fun removingInstalledAgentDeletesOldVersionsBeforeActiveVersion() = runTest {
        val removed = mutableListOf<Int>()

        val result = removeInstalledAgentVersions(
            activeVersion = 3,
            installedVersions = listOf(3, 1, 2),
        ) { version ->
            removed += version
            AgentVersionRemovalResult(AgentVersionRemovalOutcome.REMOVED)
        }

        assertEquals(listOf(1, 2, 3), removed)
        assertEquals(AgentVersionRemovalOutcome.REMOVED, result.outcome)
    }

    @Test
    fun removingInstalledAgentPreservesCleanupPendingResult() = runTest {
        val result = removeInstalledAgentVersions(
            activeVersion = 2,
            installedVersions = listOf(1, 2),
        ) { version ->
            AgentVersionRemovalResult(
                if (version == 1) {
                    AgentVersionRemovalOutcome.REMOVED_CLEANUP_PENDING
                } else {
                    AgentVersionRemovalOutcome.REMOVED
                },
            )
        }

        assertEquals(AgentVersionRemovalOutcome.REMOVED_CLEANUP_PENDING, result.outcome)
    }

    @Test
    fun removingInstalledAgentStopsWhenVersionIsReferenced() = runTest {
        val removed = mutableListOf<Int>()

        val result = removeInstalledAgentVersions(
            activeVersion = 3,
            installedVersions = listOf(1, 2, 3),
        ) { version ->
            removed += version
            AgentVersionRemovalResult(
                if (version == 2) AgentVersionRemovalOutcome.REFERENCED
                else AgentVersionRemovalOutcome.REMOVED,
            )
        }

        assertEquals(listOf(1, 2), removed)
        assertEquals(AgentVersionRemovalOutcome.REFERENCED, result.outcome)
    }
}
