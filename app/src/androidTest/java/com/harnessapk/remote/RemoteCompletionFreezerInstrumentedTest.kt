package com.harnessapk.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.RemoteRunEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    private fun completionJson(completionId: String, text: String): String =
        """{"schemaVersion":2,"completionId":"$completionId","contentSha256":"ignored","finalAssistantText":"$text"}"""
}
