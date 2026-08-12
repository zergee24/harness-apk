package com.harnessapk.projectsearch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.ConversationEntity
import com.harnessapk.storage.LocalSearchDocumentEntity
import com.harnessapk.storage.MessageEntity
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectMessageIncrementalIndexInstrumentedTest {
    @Test
    fun queryIndexesOnlyDirtyOrHashlessProjectMessages() = runBlocking {
        withDatabase { database ->
            database.conversationDao().insert(conversation("conversation-a", "project-a"))
            database.messageDao().insert(message("message-clean", "conversation-a", "stabletoken stays clean", 2L))
            database.messageDao().insert(message("message-dirty", "conversation-a", "oldtoken before edit", 3L))
            val gateway = gateway(database)
            gateway.retrieve("project-a", "stabletoken")

            val initializedClean = document(database, "message:message-clean")
            database.localSearchDao().replaceDocument(
                initializedClean.copy(indexedAt = CLEAN_INDEXED_AT),
                initializedClean.searchableText,
            )
            val dirtyMessage = requireNotNull(database.messageDao().findById("message-dirty"))
            database.messageDao().update(
                dirtyMessage.copy(content = "deltatoken after edit", updatedAt = 4L),
            )

            val beforeQuery = document(database, "message:message-dirty")
            assertTrue("updated project message must be dirty before retrieval", beforeQuery.dirty)
            assertTrue("updated project message must require a content hash", beforeQuery.sourceSha256.isBlank())

            val result = gateway.retrieve("project-a", "deltatoken")

            val cleanAfterQuery = document(database, "message:message-clean")
            val dirtyAfterQuery = document(database, "message:message-dirty")
            assertEquals(CLEAN_INDEXED_AT, cleanAfterQuery.indexedAt)
            assertEquals("deltatoken after edit".sha256(), dirtyAfterQuery.sourceSha256)
            assertFalse(dirtyAfterQuery.dirty)
            assertEquals(listOf("message:message-dirty"), result.evidence.map { it.documentKey })
            assertEquals(
                listOf("message:message-dirty"),
                database.projectSearchDao().searchProjectFts("project-a", "deltatoken*", 6).map { it.id },
            )
        }
    }

    @Test
    fun archivingConversationRemovesMessageDocumentsAndFtsRows() = runBlocking {
        withDatabase { database ->
            database.conversationDao().insert(conversation("conversation-a", "project-a"))
            database.conversationDao().insert(conversation("conversation-b", "project-b"))
            database.messageDao().insert(message("message-a", "conversation-a", "archivetoken project a", 2L))
            database.messageDao().insert(message("message-b", "conversation-b", "archivetoken project b", 3L))
            val gateway = gateway(database)
            assertEquals(ProjectRetrievalStatus.MATCH, gateway.retrieve("project-a", "archivetoken").status)
            assertEquals(ProjectRetrievalStatus.MATCH, gateway.retrieve("project-b", "archivetoken").status)

            database.conversationDao().archive("conversation-a", 9L)

            assertTrue(
                database.localSearchDao().listDocuments().none { it.conversationId == "conversation-a" },
            )
            assertTrue(database.projectSearchDao().searchProjectFts("project-a", "archivetoken*", 6).isEmpty())
            assertEquals(ProjectRetrievalStatus.NO_MATCH, gateway.retrieve("project-a", "archivetoken").status)
            assertEquals(ProjectRetrievalStatus.MATCH, gateway.retrieve("project-b", "archivetoken").status)
        }
    }

    @Test
    fun fortyRoomFtsQueriesStayProjectScopedWithoutReindexingCleanMessages() = runBlocking {
        withDatabase { database ->
            database.conversationDao().insert(conversation("conversation-a", "project-a"))
            database.conversationDao().insert(conversation("conversation-b", "project-b"))
            repeat(QUERY_COUNT) { index ->
                val token = queryToken(index)
                database.messageDao().insert(
                    message("message-a-$index", "conversation-a", "$token accepted for project a", 10L + index),
                )
                database.messageDao().insert(
                    message("message-b-$index", "conversation-b", "$token belongs to project b", 100L + index),
                )
            }
            val gateway = gateway(database)

            repeat(QUERY_COUNT) { index ->
                val result = gateway.retrieve("project-a", queryToken(index))
                assertEquals("query $index", ProjectRetrievalStatus.MATCH, result.status)
                assertTrue("query $index leaked another project", result.evidence.all { it.projectId == "project-a" })
                assertEquals(listOf("message:message-a-$index"), result.evidence.map { it.documentKey })
            }

            val projectADocuments = database.localSearchDao().listDocuments()
                .filter { it.projectId == "project-a" && it.sourceType == ProjectSourceType.PROJECT_MESSAGE.name }
            val projectBDocuments = database.localSearchDao().listDocuments()
                .filter { it.projectId == "project-b" && it.sourceType == ProjectSourceType.PROJECT_MESSAGE.name }
            assertEquals(QUERY_COUNT, projectADocuments.size)
            assertTrue(projectADocuments.all { it.sourceSha256.isNotBlank() && !it.dirty })
            assertEquals(QUERY_COUNT, projectBDocuments.size)
            assertTrue(projectBDocuments.all { it.sourceSha256.isBlank() && it.dirty })
        }
    }

    private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.LOCAL_SEARCH_CALLBACK)
            .build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun gateway(database: AppDatabase) = RoomProjectRetrievalGateway(
        dao = database.projectSearchDao(),
        localSearchDao = database.localSearchDao(),
        messageDao = database.messageDao(),
    )

    private suspend fun document(database: AppDatabase, id: String): LocalSearchDocumentEntity =
        requireNotNull(database.localSearchDao().listDocuments().singleOrNull { it.id == id })

    private fun conversation(id: String, projectId: String) = ConversationEntity(
        id = id,
        title = "project chat",
        createdAt = 1L,
        updatedAt = 1L,
        defaultProviderId = null,
        defaultModel = null,
        isArchived = false,
        projectId = projectId,
        promptOriginal = "",
        promptOptimized = "",
        promptFinal = "",
    )

    private fun message(id: String, conversationId: String, content: String, updatedAt: Long) = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = "USER",
        content = content,
        status = "SUCCEEDED",
        providerId = null,
        model = null,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        errorCode = null,
        errorMessage = null,
    )

    private fun queryToken(index: Int): String = "gatequery${index.toString().padStart(2, '0')}"

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val QUERY_COUNT = 40
        const val CLEAN_INDEXED_AT = 17L
    }
}
