package com.harnessapk.projectsearch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.search.LocalSearchTokenizer
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.RemoteRunEntity
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomProjectRunEvidenceIndexerInstrumentedTest {
    @Test
    fun refreshRemovesEvidenceForDeletedRunAndKeepsExistingRun() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.LOCAL_SEARCH_CALLBACK)
            .build()
        try {
            val indexer = RoomProjectRunEvidenceIndexer(database.remoteDao(), database.localSearchDao())
            database.remoteDao().insertRun(remoteRun("run-deleted", "deletedorphanmarker"))
            database.remoteDao().insertRun(remoteRun("run-retained", "retainedrunmarker"))
            indexer.refreshProject("project-a")
            assertTrue(search(database, "deletedorphanmarker").isNotEmpty())
            assertTrue(search(database, "retainedrunmarker").isNotEmpty())

            database.remoteDao().deleteRunById("run-deleted")
            indexer.refreshProject("project-a")

            assertTrue(search(database, "deletedorphanmarker").isEmpty())
            assertTrue(search(database, "retainedrunmarker").isNotEmpty())
            assertEquals(
                setOf(
                    "run:run-retained:completion-run-retained",
                    "run:run-retained:file-run-retained",
                ),
                database.localSearchDao().listDocuments()
                    .filter { it.projectId == "project-a" && it.sourceType == ProjectSourceType.RUN_EVIDENCE.name }
                    .mapTo(mutableSetOf()) { it.sourceKey },
            )
        } finally {
            database.close()
        }
        Unit
    }

    @Test
    fun unverifiedCompletionNeverEntersProjectSearchIndex() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.LOCAL_SEARCH_CALLBACK)
            .build()
        try {
            database.remoteDao().insertRun(
                remoteRun("run-unverified", "unverifiedrunmarker").copy(
                    completionJson = """
                        {
                          "schemaVersion":2,
                          "completionId":"completion-unverified",
                          "summary":"unverifiedrunmarker",
                          "changedFiles":[],
                          "tests":[],
                          "unresolved":[],
                          "completedAt":20
                        }
                    """.trimIndent(),
                ),
            )

            RoomProjectRunEvidenceIndexer(database.remoteDao(), database.localSearchDao())
                .refreshProject("project-a")

            assertTrue(search(database, "unverifiedrunmarker").isEmpty())
            assertTrue(
                database.localSearchDao().listDocuments().none {
                    it.projectId == "project-a" && it.sourceType == ProjectSourceType.RUN_EVIDENCE.name
                },
            )
        } finally {
            database.close()
        }
        Unit
    }

    private suspend fun search(database: AppDatabase, query: String) =
        database.projectSearchDao().searchProjectFts(
            "project-a",
            LocalSearchTokenizer.matchExpression(query),
            30,
        )

    private fun remoteRun(id: String, marker: String) = RemoteRunEntity(
        id = id,
        projectId = "project-a",
        projectNameSnapshot = "Project A",
        bindingId = "binding-1",
        bindingSnapshotJson = "{}",
        hostId = "host-1", backendId = "codex",
        threadId = "thread-1",
        turnId = "turn-1",
        objective = "完成 $marker",
        status = "COMPLETED",
        latestLine = "done",
        lastLogicalSequence = 1L,
        startedAt = 10L,
        updatedAt = 20L,
        completedAt = 20L,
        completionJson = verifiedCompletionJson(id, marker),
        errorMessage = null,
    )

    private fun verifiedCompletionJson(id: String, marker: String): String {
        val path = "docs/$id.md"
        val evidenceHash = sha256("""{"path":"$path","source":"git"}""")
        return """
            {
              "schemaVersion":2,
              "completionId":"completion-$id",
              "summary":"$marker",
              "changedFiles":[
                {
                  "evidenceId":"file-$id",
                  "evidenceSha256":"$evidenceHash",
                  "path":"$path",
                  "source":"git"
                }
              ],
              "tests":[],
              "unresolved":[],
              "completedAt":20
            }
        """.trimIndent()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}
