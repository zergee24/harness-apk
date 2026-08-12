package com.harnessapk.projectsearch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.common.AppDispatchers
import com.harnessapk.project.Project
import com.harnessapk.search.LocalSearchRepository
import com.harnessapk.search.LocalSearchTokenizer
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.ProjectEvidenceSnapshotEntity
import com.harnessapk.storage.RemoteRunEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RoomProjectMarkdownIndexerInstrumentedTest {
    @Test
    fun replacingProjectNamesKeepsProjectMemoryFtsRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.LOCAL_SEARCH_CALLBACK)
            .build()
        val root = File(context.cacheDir, "m3-project-name-${System.nanoTime()}").apply { mkdirs() }
        try {
            root.resolve("notes.md").writeText("# 状态\n\nkeepmemoryftstoken\n")
            RoomProjectMarkdownIndexer(database.localSearchDao()).refreshProject("project-a", root)
            val repository = LocalSearchRepository(database.localSearchDao(), AppDispatchers(io = Dispatchers.IO))
            assertTrue(search(database, "project-a", "keepmemoryftstoken").isNotEmpty())

            repository.replaceProjects(listOf(project(root)))

            assertTrue(search(database, "project-a", "keepmemoryftstoken").isNotEmpty())
        } finally {
            database.close()
            root.deleteRecursively()
        }
        Unit
    }

    @Test
    fun rebuildingGlobalTokensKeepsProjectPathAndHeadingSearchText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.LOCAL_SEARCH_CALLBACK)
            .build()
        val root = File(context.cacheDir, "m3-searchable-text-${System.nanoTime()}").apply { mkdirs() }
        try {
            root.resolve("notes").mkdirs()
            root.resolve("notes/ordinary.md").writeText("# launchorbitheading\n\n普通正文。\n")
            RoomProjectMarkdownIndexer(database.localSearchDao()).refreshProject("project-a", root)
            val repository = LocalSearchRepository(database.localSearchDao(), AppDispatchers(io = Dispatchers.IO))
            assertTrue(search(database, "project-a", "launchorbitheading").isNotEmpty())

            repository.rebuildTokens(listOf(project(root)))

            assertTrue(search(database, "project-a", "launchorbitheading").isNotEmpty())
        } finally {
            database.close()
            root.deleteRecursively()
        }
        Unit
    }

    @Test
    fun deletingProjectRemovesMarkdownAndRunIndexesButKeepsRunAndHistoricalSnapshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.LOCAL_SEARCH_CALLBACK)
            .build()
        val root = File(context.cacheDir, "m3-delete-index-${System.nanoTime()}").apply { mkdirs() }
        try {
            root.resolve("context.md").writeText("# 状态\n\nmarkdowncleanupmarker\n")
            val markdownIndexer = RoomProjectMarkdownIndexer(database.localSearchDao())
            markdownIndexer.refreshProject("project-a", root)
            database.remoteDao().insertRun(remoteRun("run-1", "project-a", "runcleanupmarker"))
            RoomProjectRunEvidenceIndexer(database.remoteDao(), database.localSearchDao()).refreshProject("project-a")
            database.projectSearchDao().insertEvidenceSnapshots(
                listOf(
                    ProjectEvidenceSnapshotEntity(
                        id = "snapshot-1",
                        executionId = "execution-1",
                        messageId = null,
                        token = "P1",
                        projectId = "project-a",
                        sourceType = ProjectSourceType.RUN_EVIDENCE.name,
                        authority = ProjectSourceAuthority.VERIFIED_RUN.name,
                        sourceKey = "run:run-1:completion",
                        title = "历史完成证据",
                        locatorLabel = "完成摘要",
                        relativePath = null,
                        sourceMessageId = null,
                        sourceSha256 = "b".repeat(64),
                        gitBlobId = null,
                        excerpt = "历史快照必须保留",
                        capturedAt = 20L,
                    ),
                ),
            )
            assertTrue(search(database, "project-a", "markdowncleanupmarker").isNotEmpty())
            assertTrue(search(database, "project-a", "runcleanupmarker").isNotEmpty())

            database.localSearchDao().deleteProjectSearchIndex("project-a")

            assertTrue(search(database, "project-a", "markdowncleanupmarker").isEmpty())
            assertTrue(search(database, "project-a", "runcleanupmarker").isEmpty())
            assertTrue(
                database.localSearchDao().listDocuments().none {
                    it.projectId == "project-a" && it.sourceType in setOf("CONTEXT", "MARKDOWN", "RUN_EVIDENCE")
                },
            )
            assertEquals("run-1", database.remoteDao().run("run-1")?.id)
            assertEquals("snapshot-1", database.projectSearchDao().evidenceById("snapshot-1")?.id)
        } finally {
            database.close()
            root.deleteRecursively()
        }
        Unit
    }

    @Test
    fun indexesAllVisibleMarkdownIncludingReadmeAndReplacesChangedRevision() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.LOCAL_SEARCH_CALLBACK)
            .build()
        val root = File(context.cacheDir, "m3-index-${System.nanoTime()}").apply { mkdirs() }
        try {
            root.resolve("README.md").writeText("# Harness\n\n项目采用 Room 索引。\n")
            root.resolve("context.md").writeText("# 决策\n\n不自动提交。\n")
            root.resolve("notes").mkdirs()
            root.resolve("notes/state.markdown").writeText("# 状态\n\nM3 正在验收。\n")
            root.resolve(".hidden").mkdirs()
            root.resolve(".hidden/secret.md").writeText("不应索引 hidden-secret")
            val indexer = RoomProjectMarkdownIndexer(database.localSearchDao())

            val first = indexer.refreshProject("project-a", root)
            val room = database.projectSearchDao().searchProjectFts("project-a", "room*", 6)
            val hidden = database.projectSearchDao().searchProjectFts("project-a", "hidden*", 6)

            assertEquals(3, first.indexedFiles)
            assertTrue(room.any { it.relativePath == "README.md" })
            assertTrue(hidden.isEmpty())

            root.resolve("README.md").writeText("# Harness\n\n项目采用本地 FTS。\n")
            indexer.refreshProject("project-a", root)
            assertTrue(database.projectSearchDao().searchProjectFts("project-a", "room*", 6).isEmpty())
            assertTrue(database.projectSearchDao().searchProjectFts("project-a", "fts*", 6).isNotEmpty())

            indexer.deleteProject("project-a")
            assertTrue(database.projectSearchDao().searchProjectFts("project-a", "fts*", 6).isEmpty())
        } finally {
            database.close()
            root.deleteRecursively()
        }
        Unit
    }

    private fun project(root: File) = Project(
        id = "project-a",
        name = "Project A",
        rootDirectory = root,
        updatedAt = 30L,
    )

    private suspend fun search(database: AppDatabase, projectId: String, query: String) =
        database.projectSearchDao().searchProjectFts(
            projectId,
            LocalSearchTokenizer.matchExpression(query),
            30,
        )

    private fun remoteRun(id: String, projectId: String, marker: String) = RemoteRunEntity(
        id = id,
        projectId = projectId,
        projectNameSnapshot = "Project A",
        bindingId = "binding-1",
        bindingSnapshotJson = "{}",
        hostId = "host-1",
        threadId = "thread-1",
        turnId = "turn-1",
        objective = "完成 $marker",
        status = "COMPLETED",
        latestLine = "done",
        lastLogicalSequence = 1L,
        startedAt = 10L,
        updatedAt = 20L,
        completedAt = 20L,
        completionJson = """
            {
              "schemaVersion":2,
              "completionId":"completion-$id",
              "summary":"$marker",
              "changedFiles":[],
              "tests":[],
              "unresolved":[],
              "completedAt":20
            }
        """.trimIndent(),
        errorMessage = null,
    )
}
