package com.harnessapk.projectsearch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.ConversationEntity
import com.harnessapk.storage.LocalSearchDocumentEntity
import com.harnessapk.storage.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectSearchDaoInstrumentedTest {
    @Test
    fun ftsQueryFiltersProjectBeforeReturningCandidates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.LOCAL_SEARCH_CALLBACK)
            .build()
        val localSearch = database.localSearchDao()
        localSearch.replaceDocument(document("project-a", "a"), "room migration project memory")
        localSearch.replaceDocument(document("project-b", "b"), "room migration project memory")

        val results = database.projectSearchDao().searchProjectFts(
            projectId = "project-a",
            match = "room*",
            limit = 6,
        )

        assertEquals(listOf("project-a"), results.mapNotNull { it.projectId }.distinct())
        assertEquals(listOf("project-a:a"), results.map { it.id })
        database.close()
        Unit
    }

    @Test
    fun newlyInsertedChineseProjectMessageIsTokenizedAndProjectScoped() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.LOCAL_SEARCH_CALLBACK)
            .build()
        try {
            database.conversationDao().insert(conversation("conversation-a", "project-a"))
            database.conversationDao().insert(conversation("conversation-b", "project-b"))
            database.messageDao().insert(message("message-a", "conversation-a", "极光蓝图已经由用户确认"))
            database.messageDao().insert(message("message-b", "conversation-b", "极光蓝图属于另一项目"))

            val result = RoomProjectRetrievalGateway(
                dao = database.projectSearchDao(),
                localSearchDao = database.localSearchDao(),
                messageDao = database.messageDao(),
            ).retrieve("project-a", "极光蓝图")

            assertEquals(ProjectRetrievalStatus.MATCH, result.status)
            assertTrue(result.evidence.isNotEmpty())
            assertEquals(setOf("project-a"), result.evidence.map { it.projectId }.toSet())
            assertEquals(ProjectSourceAuthority.USER_STATED, result.evidence.first().authority)
            assertEquals("message:message-a", result.evidence.first().documentKey)
        } finally {
            database.close()
        }
        Unit
    }

    private fun document(projectId: String, suffix: String) = LocalSearchDocumentEntity(
        id = "$projectId:$suffix",
        type = ProjectSourceType.MARKDOWN.name,
        title = "context.md",
        body = "Room migration project memory",
        conversationId = null,
        messageId = null,
        projectId = projectId,
        updatedAt = 1L,
        sourceType = ProjectSourceType.MARKDOWN.name,
        authority = ProjectSourceAuthority.REVIEWED_ARTIFACT.name,
        sourceKey = "$projectId:context.md",
        relativePath = "context.md",
        searchableText = "room migration project memory",
        sourceSha256 = suffix.repeat(64),
        sourceUpdatedAt = 1L,
        indexedAt = 2L,
    )

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
