package com.harnessapk.agent

import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AgentChunkEntity
import com.harnessapk.storage.AgentChunkFtsEntity
import com.harnessapk.storage.AgentCorpusEntity
import com.harnessapk.storage.AgentDao
import com.harnessapk.storage.AgentEntity
import com.harnessapk.storage.AgentVersionCorpusCrossRef
import com.harnessapk.storage.AgentVersionEntity
import com.harnessapk.storage.ConversationDao
import com.harnessapk.storage.ConversationEntity
import com.harnessapk.storage.MessageDao
import com.harnessapk.storage.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationIdentityRepositoryTest {
    private val conversationDao = FakeConversationDao()
    private val messageDao = FakeMessageDao()
    private val agentDao = IdentityFakeAgentDao()
    private val repository = ConversationIdentityRepository(
        conversationDao = conversationDao,
        messageDao = messageDao,
        agentDao = agentDao,
        timeProvider = TimeProvider { 100L },
    )

    @Test
    fun projectSuggestionUsesMostRecentReadyIdentityWithoutCreatingAssociation() = runTest {
        conversationDao.rows += conversation("old", projectId = "p1", agentId = "a1", agentVersion = 1, updatedAt = 10)
        agentDao.rows["a1"] = readyAgent("a1", activeVersion = 3)

        val result = repository.suggest("p1")

        assertEquals(ConversationIdentitySelection("a1", 3, "李德胜", locked = false), result)
        assertEquals(1, conversationDao.findById("old")!!.agentVersion)
        assertEquals(10L, conversationDao.findById("old")!!.updatedAt)
    }

    @Test
    fun globalSuggestionUsesMostRecentReadyIdentity() = runTest {
        conversationDao.rows += conversation("older", agentId = "a1", updatedAt = 10)
        conversationDao.rows += conversation("newer", agentId = "a2", updatedAt = 20)
        agentDao.rows["a1"] = readyAgent("a1", activeVersion = 1)
        agentDao.rows["a2"] = readyAgent("a2", activeVersion = 2)

        assertEquals("a2", repository.suggest(projectId = null).agentId)
    }

    @Test
    fun selectDraftUsesAssistantWhenIdentityIsCleared() = runTest {
        conversationDao.rows += conversation("c1", agentId = "a1", agentVersion = 1)

        val result = repository.selectDraft("c1", agentId = null)

        assertEquals(ConversationIdentitySelection(null, null, null, locked = false), result)
        assertNull(conversationDao.findById("c1")!!.agentId)
        assertEquals(100L, conversationDao.findById("c1")!!.updatedAt)
    }

    @Test
    fun selectDraftRejectsChangesAfterFirstUserMessage() = runTest {
        conversationDao.rows += conversation("c1", agentId = "a1", agentVersion = 1)
        messageDao.userMessageCount = 1
        conversationDao.rejectIdentityUpdate = true

        val failure = runCatching { repository.selectDraft("c1", "a1") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("a1", conversationDao.findById("c1")!!.agentId)
        assertEquals(1, conversationDao.atomicIdentityUpdateCalls)
    }

    @Test
    fun selectDraftRejectsAtomicUpdateWhenUserMessageArrivesAfterSelection() = runTest {
        conversationDao.rows += conversation("c1", agentId = "a1", agentVersion = 1)
        agentDao.rows["a2"] = readyAgent("a2", activeVersion = 2)
        conversationDao.rejectIdentityUpdate = true

        val failure = runCatching { repository.selectDraft("c1", "a2") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("a1", conversationDao.findById("c1")!!.agentId)
        assertEquals(1, conversationDao.atomicIdentityUpdateCalls)
    }

    @Test
    fun firstMessageRefreshesToLatestReadyVersionAndLocks() = runTest {
        conversationDao.rows += conversation("c1", agentId = "a1", agentVersion = 1)
        agentDao.rows["a1"] = readyAgent("a1", activeVersion = 4)

        val result = repository.pinForFirstMessage("c1")

        assertEquals(4, conversationDao.findById("c1")!!.agentVersion)
        assertTrue(result.locked)
    }

    @Test
    fun disabledSuggestedIdentityFallsBackToAssistant() = runTest {
        conversationDao.rows += conversation("recent", agentId = "a1", agentVersion = 1)
        agentDao.rows["a1"] = readyAgent("a1", activeVersion = 2).copy(status = AgentStatus.DRAFT.name)

        assertNull(repository.suggest(projectId = null).agentId)
    }

    @Test
    fun repeatedFirstMessageKeepsThePreviouslyPinnedVersion() = runTest {
        conversationDao.rows += conversation("c1", agentId = "a1", agentVersion = 3)
        messageDao.userMessageCount = 1
        agentDao.rows["a1"] = readyAgent("a1", activeVersion = 4)

        val result = repository.pinForFirstMessage("c1")

        assertEquals(ConversationIdentitySelection("a1", 3, "李德胜", locked = true), result)
        assertEquals(3, conversationDao.findById("c1")!!.agentVersion)
    }
}

private fun conversation(
    id: String,
    projectId: String? = null,
    agentId: String? = null,
    agentVersion: Int? = null,
    updatedAt: Long = 1L,
) = ConversationEntity(
    id = id,
    title = "会话",
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
)

private fun readyAgent(id: String, activeVersion: Int) = AgentEntity(
    id = id,
    name = "李德胜",
    summary = "",
    activeVersion = activeVersion,
    publisherPublicKey = byteArrayOf(),
    publisherFingerprint = "",
    installSource = "test",
    status = AgentStatus.READY.name,
    requiredCorpusCount = 0,
    installedCorpusCount = 0,
    createdAt = 1L,
    updatedAt = 1L,
)

private class FakeConversationDao : ConversationDao {
    val rows = mutableListOf<ConversationEntity>()
    var rejectIdentityUpdate = false
    var atomicIdentityUpdateCalls = 0

    override fun observeActive(): Flow<List<ConversationEntity>> = MutableStateFlow(rows)
    override suspend fun findById(id: String): ConversationEntity? = rows.firstOrNull { it.id == id }
    override suspend fun findLatestWithAgent(): ConversationEntity? = rows.filter { !it.isArchived && it.agentId != null }.maxByOrNull { it.updatedAt }
    override suspend fun findLatestWithAgentInProject(projectId: String): ConversationEntity? =
        rows.filter { !it.isArchived && it.projectId == projectId && it.agentId != null }.maxByOrNull { it.updatedAt }
    override suspend fun insert(entity: ConversationEntity) { rows += entity }
    override suspend fun update(entity: ConversationEntity) { rows.replaceAll { if (it.id == entity.id) entity else it } }
    override suspend fun updateIdentityIfNoUserMessages(
        id: String,
        agentId: String?,
        agentVersion: Int?,
        updatedAt: Long,
    ): Int {
        atomicIdentityUpdateCalls += 1
        if (rejectIdentityUpdate) return 0
        val existing = rows.firstOrNull { it.id == id } ?: return 0
        rows.replaceAll {
            if (it.id == id) it.copy(agentId = agentId, agentVersion = agentVersion, updatedAt = updatedAt) else it
        }
        return 1
    }
    override suspend fun archive(id: String, updatedAt: Long) = Unit
}

private class FakeMessageDao : MessageDao {
    var userMessageCount = 0

    override fun observeForConversation(conversationId: String): Flow<List<MessageEntity>> = MutableStateFlow(emptyList())
    override suspend fun listForConversation(conversationId: String): List<MessageEntity> = emptyList()
    override suspend fun findById(id: String): MessageEntity? = null
    override suspend fun countUserMessages(conversationId: String): Int = userMessageCount
    override suspend fun insert(entity: MessageEntity) = Unit
    override suspend fun update(entity: MessageEntity) = Unit
    override suspend fun deleteById(id: String) = Unit
    override suspend fun deleteForConversation(conversationId: String) = Unit
}

private class IdentityFakeAgentDao : AgentDao {
    val rows = linkedMapOf<String, AgentEntity>()

    override fun observeAgents(): Flow<List<AgentEntity>> = MutableStateFlow(rows.values.toList())
    override suspend fun findAgent(id: String): AgentEntity? = rows[id]
    override suspend fun listReadyAgents(): List<AgentEntity> = rows.values.filter { it.status == AgentStatus.READY.name }
    override suspend fun findVersion(agentId: String, version: Int): AgentVersionEntity? = null
    override suspend fun findCorpus(corpusId: String, sourceHash: String): AgentCorpusEntity? = null
    override suspend fun findCorpusById(corpusId: String): AgentCorpusEntity? = null
    override suspend fun listVersionCorpora(agentId: String, version: Int): List<AgentVersionCorpusCrossRef> = emptyList()
    override suspend fun upsertAgent(entity: AgentEntity) = Unit
    override suspend fun insertVersion(entity: AgentVersionEntity) = Unit
    override suspend fun insertCorpus(entity: AgentCorpusEntity): Long = 1L
    override suspend fun updateCorpusSize(corpusId: String, sourceHash: String, sizeBytes: Long) = Unit
    override suspend fun insertVersionCorpus(entity: AgentVersionCorpusCrossRef): Long = 1L
    override suspend fun insertChunks(entities: List<AgentChunkEntity>): List<Long> = emptyList()
    override suspend fun insertChunkSearchRows(entities: List<AgentChunkFtsEntity>): List<Long> = emptyList()
    override suspend fun searchChunkKeys(corpusKeys: List<String>, ftsQuery: String, limit: Int): List<String> = emptyList()
    override suspend fun listChunks(chunkKeys: List<String>): List<AgentChunkEntity> = emptyList()
}
