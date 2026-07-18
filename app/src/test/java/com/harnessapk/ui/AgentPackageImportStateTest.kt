package com.harnessapk.ui

import com.harnessapk.agent.Agent
import com.harnessapk.agent.AgentStatus
import com.harnessapk.agent.InitialConversationIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPackageImportStateTest {
    @Test
    fun externalBundleFromProjectCapturesCurrentProjectForOneTimeConversation() {
        val transition = reduceAgentPackageImport(
            AgentPackageImportState(),
            AgentPackageImportEvent.ExternalBundleReceived(
                uri = "content://bundles/agent.hbundle",
                mainMode = MainMode.PROJECT,
                currentProjectId = "project-1",
            ),
        )

        assertTrue(transition.navigateToPackages)
        assertEquals("project-1", transition.state.sourceProjectId)
    }

    @Test
    fun externalBundleFromSessionClearsPriorProjectSource() {
        val transition = reduceAgentPackageImport(
            AgentPackageImportState(sourceProjectId = "project-1"),
            AgentPackageImportEvent.ExternalBundleReceived(
                uri = "content://bundles/agent.hbundle",
                mainMode = MainMode.SESSION,
                currentProjectId = "project-2",
            ),
        )

        assertEquals(null, transition.state.sourceProjectId)
    }

    @Test
    fun settingsEntryClearsPriorProjectSource() {
        val transition = reduceAgentPackageImport(
            AgentPackageImportState(sourceProjectId = "project-1"),
            AgentPackageImportEvent.SettingsOpened,
        )

        assertEquals(null, transition.state.sourceProjectId)
        assertFalse(transition.navigateToPackages)
    }

    @Test
    fun startConversationConsumesProjectSource() {
        val transition = reduceAgentPackageImport(
            AgentPackageImportState(sourceProjectId = "project-1"),
            AgentPackageImportEvent.StartConversation,
        )

        assertEquals(null, transition.state.sourceProjectId)
    }

    @Test
    fun doneClearsProjectSource() {
        val transition = reduceAgentPackageImport(
            AgentPackageImportState(sourceProjectId = "project-1"),
            AgentPackageImportEvent.Done,
        )

        assertEquals(null, transition.state.sourceProjectId)
    }

    @Test
    fun leavingPackagesRouteClearsProjectSource() {
        val transition = reduceAgentPackageImport(
            AgentPackageImportState(sourceProjectId = "project-1"),
            AgentPackageImportEvent.RouteChanged(isAgentPackagesRoute = false),
        )

        assertEquals(null, transition.state.sourceProjectId)
    }

    @Test
    fun repeatedUnconsumedExternalBundleDoesNotNavigateAgain() {
        val first = reduceAgentPackageImport(
            AgentPackageImportState(),
            AgentPackageImportEvent.ExternalBundleReceived(
                uri = "content://bundles/agent.hbundle",
                mainMode = MainMode.PROJECT,
                currentProjectId = "project-1",
            ),
        )

        val repeated = reduceAgentPackageImport(
            first.state,
            AgentPackageImportEvent.ExternalBundleReceived(
                uri = "content://bundles/agent.hbundle",
                mainMode = MainMode.PROJECT,
                currentProjectId = "project-1",
            ),
        )

        assertFalse(repeated.navigateToPackages)
        assertEquals("project-1", repeated.state.sourceProjectId)
    }

    @Test
    fun installedAgentConversationRequestKeepsAgentAndSourceProject() {
        val agent = Agent(
            id = "agent-1",
            name = "李德胜",
            summary = "",
            activeVersion = 1,
            publisherFingerprint = "fingerprint",
            status = AgentStatus.READY,
            requiredCorpusCount = 0,
            installedCorpusCount = 0,
        )

        val request = installedAgentConversationRequest(agent, sourceProjectId = "project-1")

        assertEquals("李德胜", request.title)
        assertEquals("project-1", request.projectId)
        assertEquals(InitialConversationIdentity.Agent("agent-1"), request.identity)
    }
}
