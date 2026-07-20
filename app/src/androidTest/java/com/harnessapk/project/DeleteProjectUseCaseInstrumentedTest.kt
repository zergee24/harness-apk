package com.harnessapk.project

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harnessapk.agent.AgentStatus
import com.harnessapk.agent.ConversationIdentityRepository
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AgentEntity
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.ConversationEntity
import com.harnessapk.storage.ConversationMarkdownLinkEntity
import com.harnessapk.storage.MarkdownChangeDraftEntity
import com.harnessapk.storage.MessageEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteProjectUseCaseInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val rootDirectory = context.cacheDir.resolve("delete-project-use-case-${UUID.randomUUID()}")
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val projectRepository = FileProjectRepository(rootDirectory, TimeProvider { 100L })
    private val useCase = DeleteProjectUseCase(
        projectRepository = projectRepository,
        database = database,
    )

    @After
    fun tearDown() {
        database.close()
        rootDirectory.deleteRecursively()
    }

    @Test
    fun deletingProjectDetachesConversationWithoutChangingAgentMessagesOrHistoryDrafts() = runBlocking {
        val project = projectRepository.createProject("项目一")
        insertConversation(id = "c1", projectId = project.id, agentId = "a1", agentVersion = 2)
        insertMessage(id = "m1", conversationId = "c1")
        insertMarkdownLink(conversationId = "c1", projectId = project.id, path = "notes.md")
        listOf("PLANNING", "READY", "FAILED", "PARTIALLY_APPLIED", "APPLIED", "DISMISSED").forEach { status ->
            insertDraft(id = "draft-$status", conversationId = "c1", projectId = project.id, status = status)
        }

        useCase.delete(project.id)

        assertFalse(project.rootDirectory.exists())
        val conversation = database.conversationDao().findById("c1")!!
        assertNull(conversation.projectId)
        assertEquals("a1", conversation.agentId)
        assertEquals(2, conversation.agentVersion)
        assertEquals(1, database.messageDao().listForConversation("c1").size)
        assertTrue(database.conversationMarkdownLinkDao().listForConversation("c1").isEmpty())
        assertNull(database.markdownChangeDraftDao().findDraft("draft-PLANNING"))
        assertNull(database.markdownChangeDraftDao().findDraft("draft-READY"))
        assertNull(database.markdownChangeDraftDao().findDraft("draft-FAILED"))
        assertNull(database.markdownChangeDraftDao().findDraft("draft-PARTIALLY_APPLIED"))
        assertEquals("APPLIED", database.markdownChangeDraftDao().findDraft("draft-APPLIED")!!.status)
        assertEquals("DISMISSED", database.markdownChangeDraftDao().findDraft("draft-DISMISSED")!!.status)
    }

    @Test
    fun deletingProjectDoesNotChangeOtherProjectData() = runBlocking {
        val deletedProject = projectRepository.createProject("删除项目")
        val retainedProject = projectRepository.createProject("保留项目")
        insertConversation(id = "deleted", projectId = deletedProject.id)
        insertConversation(id = "retained", projectId = retainedProject.id, agentId = "a2", agentVersion = 3)
        insertMarkdownLink("deleted", deletedProject.id, "deleted.md")
        insertMarkdownLink("retained", retainedProject.id, "retained.md")
        insertDraft("deleted-draft", "deleted", deletedProject.id, "READY")
        insertDraft("retained-draft", "retained", retainedProject.id, "FAILED")

        useCase.delete(deletedProject.id)

        assertNull(database.conversationDao().findById("deleted")!!.projectId)
        val retainedConversation = database.conversationDao().findById("retained")!!
        assertEquals(retainedProject.id, retainedConversation.projectId)
        assertEquals("a2", retainedConversation.agentId)
        assertEquals(3, retainedConversation.agentVersion)
        assertEquals(listOf("retained.md"), database.conversationMarkdownLinkDao().listForConversation("retained").map { it.relativePath })
        assertEquals("FAILED", database.markdownChangeDraftDao().findDraft("retained-draft")!!.status)
    }

    @Test
    fun deletingOldProjectConversationPreservesActivityOrderingForGlobalIdentitySuggestion() = runBlocking {
        val project = projectRepository.createProject("旧人物项目")
        database.agentDao().upsertAgent(readyAgent("agent-1"))
        insertConversation(
            id = "old-person",
            projectId = project.id,
            agentId = "agent-1",
            agentVersion = 1,
            updatedAt = 100L,
        )
        insertConversation(id = "recent-assistant", projectId = null, updatedAt = 200L)
        val deletion = DeleteProjectUseCase(
            projectRepository = projectRepository,
            database = database,
        )

        deletion.delete(project.id)

        assertEquals(100L, database.conversationDao().findById("old-person")!!.updatedAt)
        val identityRepository = ConversationIdentityRepository(
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            agentDao = database.agentDao(),
            timeProvider = TimeProvider { 400L },
        )
        assertNull(identityRepository.suggest(projectId = null).agentId)
    }

    @Test
    fun fileDeletionFailureLeavesDatabaseReferencesUntouched() = runBlocking {
        insertConversation(id = "c1", projectId = "missing-project", agentId = "a1", agentVersion = 2)
        insertMarkdownLink("c1", "missing-project", "notes.md")
        insertDraft("draft-ready", "c1", "missing-project", "READY")

        try {
            useCase.delete("missing-project")
            throw AssertionError("Expected project deletion to fail")
        } catch (_: ProjectWorkspaceException) {
            // The file repository failed before any database cleanup began.
        }

        val conversation = database.conversationDao().findById("c1")!!
        assertEquals("missing-project", conversation.projectId)
        assertEquals("a1", conversation.agentId)
        assertEquals(2, conversation.agentVersion)
        assertEquals(listOf("notes.md"), database.conversationMarkdownLinkDao().listForConversation("c1").map { it.relativePath })
        assertEquals("READY", database.markdownChangeDraftDao().findDraft("draft-ready")!!.status)
    }

    @Test
    fun successfulFileDeletionCompletesDatabaseDetachmentAfterCallingCoroutineIsCancelled() = runBlocking {
        val project = projectRepository.createProject("取消删除项目")
        insertConversation(id = "c1", projectId = project.id, agentId = "a1", agentVersion = 2)
        insertMarkdownLink("c1", project.id, "notes.md")
        insertDraft("draft-ready", "c1", project.id, "READY")
        val transactionEntered = CountDownLatch(1)
        val continueTransaction = CountDownLatch(1)
        val cancellationUseCase = DeleteProjectUseCase(
            projectRepository = projectRepository,
            database = database,
            beforeDatabaseCleanup = {
                transactionEntered.countDown()
                check(continueTransaction.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to continue the Room transaction"
                }
            },
        )

        val deletion = async(Dispatchers.Default) {
            cancellationUseCase.delete(project.id)
        }
        try {
            assertTrue("Room transaction did not reach the cancellation barrier", transactionEntered.await(5, TimeUnit.SECONDS))
            deletion.cancel()
            assertTrue("Calling coroutine must enter the cancelled state", deletion.isCancelled)
            assertFalse("NonCancellable transaction must still be running", deletion.isCompleted)
        } finally {
            continueTransaction.countDown()
        }

        try {
            deletion.await()
            throw AssertionError("Expected cancelled caller to receive CancellationException")
        } catch (_: CancellationException) {
            // The caller remains cancelled even though the non-cancellable cleanup finished.
        }

        assertFalse(project.rootDirectory.exists())
        assertNull(database.conversationDao().findById("c1")!!.projectId)
        assertTrue(database.conversationMarkdownLinkDao().listForConversation("c1").isEmpty())
        assertNull(database.markdownChangeDraftDao().findDraft("draft-ready"))
    }

    private suspend fun insertConversation(
        id: String,
        projectId: String?,
        agentId: String? = null,
        agentVersion: Int? = null,
        updatedAt: Long = 1L,
    ) {
        database.conversationDao().insert(
            ConversationEntity(
                id = id,
                title = id,
                createdAt = 1L,
                updatedAt = updatedAt,
                defaultProviderId = null,
                defaultModel = null,
                isArchived = false,
                projectId = projectId,
                promptOriginal = "",
                promptOptimized = "",
                promptFinal = "",
                agentId = agentId,
                agentVersion = agentVersion,
            ),
        )
    }

    private fun readyAgent(id: String) = AgentEntity(
        id = id,
        name = "李德胜",
        summary = "",
        activeVersion = 1,
        publisherPublicKey = byteArrayOf(),
        publisherFingerprint = "",
        installSource = "test",
        status = AgentStatus.READY.name,
        requiredCorpusCount = 0,
        installedCorpusCount = 0,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private suspend fun insertMessage(id: String, conversationId: String) {
        database.messageDao().insert(
            MessageEntity(
                id = id,
                conversationId = conversationId,
                role = "USER",
                content = "消息",
                status = "SUCCEEDED",
                providerId = null,
                model = null,
                createdAt = 1L,
                updatedAt = 1L,
                errorCode = null,
                errorMessage = null,
            ),
        )
    }

    private suspend fun insertMarkdownLink(conversationId: String, projectId: String, path: String) {
        database.conversationMarkdownLinkDao().insert(
            ConversationMarkdownLinkEntity(
                conversationId = conversationId,
                projectId = projectId,
                relativePath = path,
                linkedAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    private suspend fun insertDraft(id: String, conversationId: String, projectId: String, status: String) {
        database.markdownChangeDraftDao().upsertDraft(
            MarkdownChangeDraftEntity(
                id = id,
                conversationId = conversationId,
                projectId = projectId,
                sourceUserMessageId = "source-$id",
                assistantMessageId = null,
                status = status,
                summary = status,
                rawResponse = null,
                errorMessage = null,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }
}
