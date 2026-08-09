package com.harnessapk.search

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.common.AppDispatchers
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.ConversationEntity
import com.harnessapk.storage.MessageEntity
import com.harnessapk.storage.MessageWikiCitationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSearchRepositoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun triggersIndexChineseMessagesAndSourcesAndDeleteWithPrimaryData() = runBlocking {
        val db = database()
        val repository = LocalSearchRepository(db.localSearchDao(), AppDispatchers(io = Dispatchers.IO))
        db.conversationDao().insert(conversation("conversation-1", "家庭健康计划"))
        db.messageDao().insert(message("message-1", "conversation-1", "讨论老人用药提醒"))
        db.conversationWikiDao().insertCitation(
            MessageWikiCitationEntity(
                id = "citation-1",
                messageId = "message-1",
                displayOrdinal = 1,
                wikiId = "health",
                wikiVersion = 1,
                wikiTitle = "家庭健康手册",
                documentId = "doc-1",
                sectionId = "section-1",
                chunkId = "chunk-1",
                sourceTitle = "用药安全",
                sectionPath = "老人照护",
                locatorLabel = "第 1 段",
                originalTextSnapshot = "按医嘱核对药物和剂量",
                originalTextSha256 = "a".repeat(64),
                answerRangesJson = "[]",
                verificationState = "VERIFIED",
                createdAt = 2L,
            ),
        )
        repository.rebuildTokens()

        val messageResult = repository.search("老人用药").first()
        val sourceResult = repository.search("医嘱核对").first()
        assertEquals(LocalSearchTarget.ConversationMessage("conversation-1", "message-1"), messageResult.target())
        assertEquals(LocalSearchDocumentType.MESSAGE_SOURCE, sourceResult.type)

        db.messageDao().deleteById("message-1")
        assertTrue(repository.search("老人用药").isEmpty())
        assertTrue(repository.search("医嘱核对").isEmpty())
        db.close()
    }

    @Test
    fun fiftyThousandMessageSearchP95StaysBelowTwoHundredMilliseconds() = runBlocking {
        val db = database()
        val sqlite = db.openHelper.writableDatabase
        sqlite.beginTransaction()
        try {
            val documents = sqlite.compileStatement(
                """
                INSERT INTO local_search_documents
                    (id,type,title,body,conversationId,messageId,projectId,updatedAt,
                     sourceType,authority,sourceKey,headingPath,ordinal,searchableText,
                     sourceSha256,sourceUpdatedAt,indexedAt,dirty)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            )
            val fts = sqlite.compileStatement(
                "INSERT INTO local_search_fts(documentId,searchText) VALUES (?,?)",
            )
            repeat(50_000) { index ->
                val id = "message:benchmark-$index"
                val body = if (index == 42_424) "uniquekey42424" else "普通记录 $index"
                documents.clearBindings()
                documents.bindString(1, id)
                documents.bindString(2, "MESSAGE")
                documents.bindString(3, "性能会话")
                documents.bindString(4, body)
                documents.bindString(5, "conversation-benchmark")
                documents.bindString(6, "benchmark-$index")
                documents.bindNull(7)
                documents.bindLong(8, index.toLong())
                documents.bindString(9, "PROJECT_MESSAGE")
                documents.bindString(10, "USER_STATED")
                documents.bindString(11, id)
                documents.bindString(12, "")
                documents.bindLong(13, 0L)
                documents.bindString(14, body)
                documents.bindString(15, "")
                documents.bindLong(16, index.toLong())
                documents.bindLong(17, index.toLong())
                documents.bindLong(18, 0L)
                documents.executeInsert()
                fts.clearBindings()
                fts.bindString(1, id)
                fts.bindString(2, body)
                fts.executeInsert()
            }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
        val repository = LocalSearchRepository(db.localSearchDao(), AppDispatchers(io = Dispatchers.IO))
        repository.search("uniquekey42424")
        val timings = List(20) {
            val started = System.nanoTime()
            val results = repository.search("uniquekey42424")
            assertEquals("benchmark-42424", (results.single().target() as LocalSearchTarget.ConversationMessage).messageId)
            (System.nanoTime() - started) / 1_000_000
        }.sorted()
        val p95 = timings[(timings.size * 0.95).toInt().coerceAtMost(timings.lastIndex)]

        Log.i("M1SearchBenchmark", "50,000 messages search p95=${p95}ms timings=$timings")
        assertTrue("50,000 条消息搜索 p95=${p95}ms", p95 < 200L)
        db.close()
    }

    private fun database(): AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .addCallback(AppDatabase.LOCAL_SEARCH_CALLBACK)
        .build()

    private fun conversation(id: String, title: String) = ConversationEntity(
        id = id,
        title = title,
        createdAt = 1L,
        updatedAt = 1L,
        defaultProviderId = null,
        defaultModel = null,
        isArchived = false,
        projectId = null,
        promptOriginal = "",
        promptOptimized = "",
        promptFinal = "",
        agentId = null,
        agentVersion = null,
    )

    private fun message(id: String, conversationId: String, content: String) = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = "USER",
        content = content,
        status = "SUCCEEDED",
        providerId = null,
        model = null,
        createdAt = 2L,
        updatedAt = 2L,
        errorCode = null,
        errorMessage = null,
    )
}
