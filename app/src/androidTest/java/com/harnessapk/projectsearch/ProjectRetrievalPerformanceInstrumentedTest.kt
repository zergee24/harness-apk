package com.harnessapk.projectsearch

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.LocalSearchDocumentEntity
import com.harnessapk.storage.LocalSearchFtsEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectRetrievalPerformanceInstrumentedTest {
    @Test
    fun tenThousandRoomFtsChunksStayUnderP95BudgetAndProjectScope() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.LOCAL_SEARCH_CALLBACK)
            .build()
        try {
            val documents = List(10_000) { index ->
                val isTarget = index == 9_876
                val projectId = if (index == 9_999) "foreign-project" else "performance-project"
                val token = if (isTarget || index == 9_999) "auroratarget" else "ordinarytoken"
                LocalSearchDocumentEntity(
                    id = "$projectId:perf-$index",
                    type = ProjectSourceType.MARKDOWN.name,
                    title = if (isTarget) "Aurora target" else "Chunk $index",
                    body = "$token local Room FTS chunk $index",
                    conversationId = null,
                    messageId = null,
                    projectId = projectId,
                    updatedAt = index.toLong(),
                    sourceType = ProjectSourceType.MARKDOWN.name,
                    authority = ProjectSourceAuthority.REVIEWED_ARTIFACT.name,
                    sourceKey = "file:docs/perf-$index.md",
                    relativePath = "docs/perf-$index.md",
                    searchableText = "$token room fts chunk $index",
                    sourceSha256 = index.toString().padEnd(64, '0'),
                    sourceUpdatedAt = index.toLong(),
                    indexedAt = index.toLong(),
                )
            }
            database.withTransaction {
                database.localSearchDao().upsertDocuments(documents)
                database.localSearchDao().insertFts(
                    documents.map { LocalSearchFtsEntity(it.id, it.searchableText) },
                )
            }
            val gateway = RoomProjectRetrievalGateway(
                dao = database.projectSearchDao(),
                localSearchDao = database.localSearchDao(),
                messageDao = database.messageDao(),
            )
            repeat(3) { gateway.retrieve("performance-project", "auroratarget") }

            val samples = List(10) {
                val startedAt = System.nanoTime()
                val result = gateway.retrieve("performance-project", "auroratarget")
                val elapsed = System.nanoTime() - startedAt
                assertEquals(ProjectRetrievalStatus.MATCH, result.status)
                assertEquals(listOf("performance-project:perf-9876"), result.evidence.map { it.documentKey })
                assertTrue(result.evidence.none { it.projectId == "foreign-project" })
                elapsed
            }
            val p95Millis = ProjectRetrievalStatistics.p95Millis(samples)
            println(
                "M3_ROOM_FTS_PERF chunks=${documents.size} samples=${samples.size} " +
                    "p95Ms=${"%.3f".format(p95Millis)}",
            )
            assertTrue("Room FTS 10k p95 was $p95Millis ms", p95Millis < 250.0)
        } finally {
            database.close()
        }
        Unit
    }
}
