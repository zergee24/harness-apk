package com.harnessapk.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.RemoteRunEntity
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteCompletionFreezerInstrumentedTest {
    @Test
    fun firstCompletionRemainsFrozenWhenSnapshotDeliversDifferentPayload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            database.remoteDao().insertRun(remoteRun())
            val first = completionJson("completion-first", "first immutable evidence")
            val later = completionJson("completion-later", "mutated workspace evidence")

            val firstFrozen = freezeRemoteCompletion(database, "run-1", first, capturedAt = 10L)
            val laterFrozen = freezeRemoteCompletion(database, "run-1", later, capturedAt = 20L)
            val stored = requireNotNull(database.projectSearchDao().remoteCompletion("run-1"))

            assertEquals(Json.parseToJsonElement(first).toString(), firstFrozen)
            assertEquals(firstFrozen, laterFrozen)
            assertEquals(firstFrozen, stored.payloadJson)
            assertEquals("completion-first", stored.completionId)
            assertEquals("VERIFIED_V2", stored.verificationState)
            assertNotEquals(Json.parseToJsonElement(later).toString(), stored.payloadJson)
        } finally {
            database.close()
        }
        Unit
    }

    @Test
    fun freezerUsesTheSameV2EvidenceValidationAsTheParser() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            database.remoteDao().insertRun(remoteRun())
            val raw = """
                {
                  "schemaVersion":2,
                  "completionId":"completion-invalid",
                  "summary":"伪造哈希",
                  "changedFiles":[{"evidenceId":"file-1","evidenceSha256":"${"a".repeat(64)}","path":"docs/result.md","source":"git"}],
                  "tests":[],
                  "unresolved":[],
                  "completedAt":20
                }
            """.trimIndent()

            freezeRemoteCompletion(database, "run-1", raw, capturedAt = 20L)

            assertEquals(
                RemoteCompletionVerification.UNVERIFIED_V2,
                parseRemoteCompletionEvidence(raw).verification,
            )
            assertEquals(
                parseRemoteCompletionEvidence(raw).verification.name,
                database.projectSearchDao().remoteCompletion("run-1")?.verificationState,
            )
        } finally {
            database.close()
        }
        Unit
    }

    @Test
    fun terminalLocalRunDoesNotFreezeCompletionFromRunningSnapshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            database.remoteDao().insertRun(remoteRun())
            val state = RoomRemoteSyncState(database, RemoteEventReducer(database))

            state.applySnapshot(
                snapshot(
                    status = "RUNNING",
                    completionJson = completionJson("completion-late-running", "late-running"),
                    journalHead = 1L,
                ),
            )

            assertNull(database.projectSearchDao().remoteCompletion("run-1"))
            assertNull(database.remoteDao().run("run-1")?.completionJson)
        } finally {
            database.close()
        }
        Unit
    }

    @Test
    fun runningSnapshotCannotFreezeCompletionBeforeTerminalSnapshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            database.remoteDao().insertRun(remoteRun().copy(status = "RUNNING", completedAt = null))
            val state = RoomRemoteSyncState(database, RemoteEventReducer(database))
            val premature = completionJson("completion-premature", "premature")
            val terminal = completionJson("completion-terminal", "terminal")

            state.applySnapshot(snapshot(status = "RUNNING", completionJson = premature, journalHead = 1L))

            assertNull(database.projectSearchDao().remoteCompletion("run-1"))
            assertNull(database.remoteDao().run("run-1")?.completionJson)

            state.applySnapshot(snapshot(status = "COMPLETED", completionJson = terminal, journalHead = 2L))

            val frozen = requireNotNull(database.projectSearchDao().remoteCompletion("run-1"))
            assertEquals(Json.parseToJsonElement(terminal).toString(), frozen.payloadJson)
            assertEquals(frozen.payloadJson, database.remoteDao().run("run-1")?.completionJson)
        } finally {
            database.close()
        }
        Unit
    }

    private fun remoteRun() = RemoteRunEntity(
        id = "run-1",
        projectId = "project-1",
        projectNameSnapshot = "Project",
        bindingId = "binding-1",
        bindingSnapshotJson = "{}",
        hostId = "host-1",
        threadId = "thread-1",
        turnId = "turn-1",
        objective = "objective",
        status = "COMPLETED",
        latestLine = "done",
        lastLogicalSequence = 1L,
        startedAt = 1L,
        updatedAt = 2L,
        completedAt = 2L,
        completionJson = null,
        errorMessage = null,
    )

    private fun completionJson(completionId: String, text: String): String {
        val path = "docs/$text.md"
        val evidenceHash = sha256("""{"path":"$path","source":"git"}""")
        return """{"schemaVersion":2,"completionId":"$completionId","summary":"$text","changedFiles":[{"evidenceId":"file-$text","evidenceSha256":"$evidenceHash","path":"$path","source":"git"}],"tests":[],"unresolved":[],"completedAt":20}"""
    }

    private fun snapshot(status: String, completionJson: String, journalHead: Long) = RemoteRunSnapshotEnvelope(
        hostId = "host-1",
        deviceId = "device-1",
        journalHead = journalHead,
        processEpoch = "epoch-1",
        runs = listOf(
            RemoteRunSnapshot(
                runId = "run-1",
                status = status,
                threadId = "thread-1",
                turnId = "turn-1",
                latestLine = status,
                completionJson = completionJson,
                errorMessage = null,
            ),
        ),
        approvals = emptyList(),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}
