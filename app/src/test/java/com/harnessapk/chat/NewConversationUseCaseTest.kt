package com.harnessapk.chat

import com.harnessapk.agent.AgentStatus
import com.harnessapk.agent.ConversationIdentityRepository
import com.harnessapk.agent.ConversationIdentitySelection
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
import com.harnessapk.storage.ConversationMemoryDao
import com.harnessapk.storage.ConversationMemoryEntity
import com.harnessapk.storage.MessageAttachmentDao
import com.harnessapk.storage.MessageAttachmentEntity
import com.harnessapk.storage.MessageDao
import com.harnessapk.storage.MessageEntity
import com.harnessapk.storage.MessagePartDao
import com.harnessapk.storage.MessagePartEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NewConversationUseCaseTest {
    private val conversationDao = NewConversationFakeConversationDao()
    private val messageDao = NewConversationFakeMessageDao()
    private val agentDao = NewConversationFakeAgentDao()
    private val chatRepository = ChatRepository(
        conversationDao = conversationDao,
        messageDao = messageDao,
        messagePartDao = NewConversationFakeMessagePartDao(),
        attachmentDao = NewConversationFakeMessageAttachmentDao(),
        memoryDao = NewConversationFakeConversationMemoryDao(),
        timeProvider = TimeProvider { 100L },
    )
    private val identityRepository = ConversationIdentityRepository(
        conversationDao = conversationDao,
        messageDao = messageDao,
        agentDao = agentDao,
        timeProvider = TimeProvider { 100L },
    )
    private val useCase = NewConversationUseCase(chatRepository, identityRepository)

    @Test
    fun suggestedProjectConversationPersistsIndependentProjectAndIdentity() = runTest {
        conversationDao.insert(conversation("recent", projectId = "p1", agentId = "a1", agentVersion = 1, updatedAt = 10L))
        agentDao.rows["a1"] = readyAgent("a1", activeVersion = 2)

        val id = useCase.create(projectId = "p1")

        val created = chatRepository.conversation(id)!!
        assertEquals("p1", created.projectId)
        assertEquals("a1", created.agentId)
        assertEquals(2, created.agentVersion)
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

private class NewConversationFakeConversationDao : ConversationDao {
    private val rows = linkedMapOf<String, ConversationEntity>()

    override fun observeActive(): Flow<List<ConversationEntity>> = MutableStateFlow(rows.values.toList())
    override suspend fun findById(id: String): ConversationEntity? = rows[id]
    override suspend fun findLatestWithAgent(): ConversationEntity? = rows.values.filter { it.agentId != null }.maxByOrNull { it.updatedAt }
    override suspend fun findLatestWithAgentInProject(projectId: String): ConversationEntity? =
        rows.values.filter { it.projectId == projectId && it.agentId != null }.maxByOrNull { it.updatedAt }
    override suspend fun insert(entity: ConversationEntity) { rows[entity.id] = entity }
    override suspend fun update(entity: ConversationEntity) { rows[entity.id] = entity }
    override suspend fun archive(id: String, updatedAt: Long) = Unit
}

private class NewConversationFakeMessageDao : MessageDao {
    override fun observeForConversation(conversationId: String): Flow<List<MessageEntity>> = MutableStateFlow(emptyList())
    override suspend fun listForConversation(conversationId: String): List<MessageEntity> = emptyList()
    override suspend fun findById(id: String): MessageEntity? = null
    override suspend fun countUserMessages(conversationId: String): Int = 0
    override suspend fun insert(entity: MessageEntity) = Unit
    override suspend fun update(entity: MessageEntity) = Unit
    override suspend fun deleteById(id: String) = Unit
    override suspend fun deleteForConversation(conversationId: String) = Unit
}

private class NewConversationFakeAgentDao : AgentDao {
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
    override suspend fun insertCorpus(entity: AgentCorpusEntity): Long = 0L
    override suspend fun updateCorpusSize(corpusId: String, sourceHash: String, sizeBytes: Long) = Unit
    override suspend fun insertVersionCorpus(entity: AgentVersionCorpusCrossRef): Long = 0L
    override suspend fun insertChunks(entities: List<AgentChunkEntity>): List<Long> = emptyList()
    override suspend fun insertChunkSearchRows(entities: List<AgentChunkFtsEntity>): List<Long> = emptyList()
    override suspend fun searchChunkKeys(corpusKeys: List<String>, ftsQuery: String, limit: Int): List<String> = emptyList()
    override suspend fun listChunks(chunkKeys: List<String>): List<AgentChunkEntity> = emptyList()
}

private class NewConversationFakeMessagePartDao : MessagePartDao {
    override fun observeForMessage(messageId: String): Flow<List<MessagePartEntity>> = MutableStateFlow(emptyList())
    override suspend fun listForMessage(messageId: String): List<MessagePartEntity> = emptyList()
    override suspend fun insertAll(parts: List<MessagePartEntity>) = Unit
    override suspend fun replaceForMessage(messageId: String, parts: List<MessagePartEntity>) = Unit
    override suspend fun deleteForMessage(messageId: String) = Unit
    override suspend fun markStableForMessage(messageId: String, updatedAt: Long) = Unit
}

private class NewConversationFakeMessageAttachmentDao : MessageAttachmentDao {
    override fun observeForMessage(messageId: String): Flow<List<MessageAttachmentEntity>> = MutableStateFlow(emptyList())
    override suspend fun listForMessage(messageId: String): List<MessageAttachmentEntity> = emptyList()
    override suspend fun insert(entity: MessageAttachmentEntity) = Unit
}

private class NewConversationFakeConversationMemoryDao : ConversationMemoryDao {
    override suspend fun findByConversationId(conversationId: String): ConversationMemoryEntity? = null
    override fun observeForConversation(conversationId: String): Flow<ConversationMemoryEntity?> = MutableStateFlow(null)
    override suspend fun upsert(entity: ConversationMemoryEntity) = Unit
}
