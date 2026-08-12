package com.harnessapk.projectsearch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.storage.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RoomProjectMarkdownIndexerInstrumentedTest {
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
}
