package com.harnessapk.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.harnessapk.HarnessApkApplication
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M4 G6 acceptance on an emulator against a local relay and a real dual
 * backend bridge (codex slot = appserver-stub, dsh = real dsh profile).
 *
 * Prereqs:
 *   - /sdcard/pairing.txt holds the bridge `pair` JSON payload (pushed via adb)
 *   - adb reverse tcp:8080 tcp:8080 points localhost at the local relay
 *
 * Run:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.harnessapk.remote.RemoteMultiBackendAcceptanceTest
 */
@RunWith(AndroidJUnit4::class)
class RemoteMultiBackendAcceptanceTest {

    @Test
    fun pairConnectSwitchAndRunAgainstDualBackend() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as HarnessApkApplication
        val container = app.container
        // Pushed with: adb push pair.json /data/local/tmp/pairing.txt &&         //   adb shell run-as com.harnessapk.debug cp /data/local/tmp/pairing.txt files/pairing.txt
        val pairingFile = File(instrumentation.targetContext.filesDir, "pairing.txt")
        val pairingJson = pairingFile.takeIf { it.exists() }?.readText()
            ?: error("${pairingFile.absolutePath} is required (run-as push the bridge pair payload)")

        // 1. Enroll through the real (local) relay.
        val profile = container.remoteEnrollmentClient.enroll(pairingJson, null)
        assertEquals("m4-accept", profile.hostId)
        container.remoteProfileStore.save(profile)

        // 2. Connect and wait for host.status with both backends.
        container.remoteRepository.connect()
        val connected = waitForRemoteState(app) { state ->
            state.connectionStatus == RemoteConnectionStatus.CONNECTED && state.backends.size >= 2
        }
        val backendIds = connected.backends.map { it.id }.toSet()
        assertEquals(setOf("codex", "dsh"), backendIds)
        assertEquals("codex", connected.selectedBackendId)
        assertTrue("codex must advertise approvals", "approvals.v1" in connected.capabilities)

        // 3. Switch to the dsh backend: the capability set must drop approvals.
        container.remoteRepository.selectBackend("dsh")
        val dshState = waitForRemoteState(app) { it.selectedBackendId == "dsh" }
        assertTrue("dsh must not advertise approvals", "approvals.v1" !in dshState.capabilities)

        // 4. Switch back to the codex (stub) slot and run a full turn end to end.
        container.remoteRepository.selectBackend("codex")
        waitForRemoteState(app) { it.selectedBackendId == "codex" }
        container.remoteRepository.createThread("/sdcard")
        waitForRemoteState(app, timeoutMillis = 30_000) { it.selectedThreadId != null }
        container.remoteRepository.startTurn("验收测试：请回复固定内容")
        waitForRemoteState(app, timeoutMillis = 30_000) { state ->
            state.timeline.any { it.kind == "agentMessage" }
        }
        val finalState = container.remoteRepository.state.value
        assertTrue(
            "stub turn must stream an agent message, got ${finalState.timeline.map { it.kind }}",
            finalState.timeline.any { it.kind == "agentMessage" && it.text.contains("固定回复") },
        )

        // 5. The dsh backend still lists only its own threads after switching back.
        container.remoteRepository.selectBackend("dsh")
        container.remoteRepository.refreshThreads()
        waitForRemoteState(app, timeoutMillis = 30_000) { !it.isThreadListLoading }
        val dshThreads = container.remoteRepository.state.value.threads
        assertTrue(
            "dsh thread list must not contain the stub thread, got ${dshThreads.map { it.id }}",
            dshThreads.none { it.id.startsWith("stub-thread") },
        )

        container.remoteRepository.disconnect()
    }

    private suspend fun waitForRemoteState(
        app: HarnessApkApplication,
        timeoutMillis: Long = 20_000,
        predicate: (RemoteUiState) -> Boolean,
    ): RemoteUiState {
        val repository = app.container.remoteRepository
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val state = repository.state.value
            if (predicate(state)) return state
            delay(250)
        }
        error("timed out waiting for remote state; last=${repository.state.value}")
    }
}
