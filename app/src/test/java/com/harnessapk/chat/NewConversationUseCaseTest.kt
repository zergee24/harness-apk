package com.harnessapk.chat

import com.harnessapk.agent.AgentStatus
import com.harnessapk.agent.AgentLifecycleCoordinator
import com.harnessapk.agent.ConversationIdentityRepository
import com.harnessapk.agent.InitialConversationIdentity
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AgentChunkEntity
import com.harnessapk.storage.AgentChunkFtsEntity
import com.harnessapk.storage.AgentCorpusChunkCrossRef
import com.harnessapk.storage.AgentCorpusEntity
import com.harnessapk.storage.AgentDao
import com.harnessapk.storage.AgentEntity
import com.harnessapk.storage.AgentHierarchyFtsEntity
import com.harnessapk.storage.AgentHierarchyNodeEntity
import com.harnessapk.storage.AgentSourceFileEntity
import com.harnessapk.storage.AgentVersionCorpusCrossRef
import com.harnessapk.storage.AgentVersionEntity
import com.harnessapk.storage.AgentVersionSourceCrossRef
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
import org.junit.Assert.assertTrue
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

    @Test
    fun assistantConversationPersistsEmptyIdentity() = runTest {
        val id = useCase.create(identity = InitialConversationIdentity.Assistant)

        val created = chatRepository.conversation(id)!!
        assertEquals(null, created.agentId)
        assertEquals(null, created.agentVersion)
    }

    @Test
    fun explicitReadyAgentConversationPersistsCurrentActiveVersion() = runTest {
        agentDao.rows["a1"] = readyAgent("a1", activeVersion = 4)

        val id = useCase.create(identity = InitialConversationIdentity.Agent("a1"))

        val created = chatRepository.conversation(id)!!
        assertEquals("a1", created.agentId)
        assertEquals(4, created.agentVersion)
    }

    @Test
    fun explicitWaitingAgentConversationFailsInsteadOfCreatingAssistantConversation() = runTest {
        agentDao.rows["waiting"] = readyAgent("waiting", activeVersion = 1).copy(
            status = AgentStatus.WAITING_FOR_CORPUS.name,
            requiredCorpusCount = 2,
            installedCorpusCount = 1,
        )

        val failure = runCatching {
            useCase.create(identity = InitialConversationIdentity.Agent("waiting"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, conversationDao.count)
    }

    @Test
    fun explicitAgentWithReadyGlobalStatusButWaitingActiveVersionCannotStartConversation() = runTest {
        agentDao.rows["stale-ready"] = readyAgent("stale-ready", activeVersion = 2)
        agentDao.versionStates["stale-ready:2"] = AgentStatus.WAITING_FOR_CORPUS.name

        val failure = runCatching {
            useCase.create(identity = InitialConversationIdentity.Agent("stale-ready"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, conversationDao.count)
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
    val count: Int get() = rows.size

    override fun observeActive(): Flow<List<ConversationEntity>> = MutableStateFlow(rows.values.toList())
    override suspend fun findById(id: String): ConversationEntity? = rows[id]
    override suspend fun findLatestActive(): ConversationEntity? = rows.values.filter { !it.isArchived }
        .maxWithOrNull(compareBy<ConversationEntity> { it.updatedAt }.thenBy { it.id })
    override suspend fun findLatestActiveInProject(projectId: String): ConversationEntity? =
        rows.values.filter { !it.isArchived && it.projectId == projectId }
            .maxWithOrNull(compareBy<ConversationEntity> { it.updatedAt }.thenBy { it.id })
    override suspend fun insert(entity: ConversationEntity) { rows[entity.id] = entity }
    override suspend fun update(entity: ConversationEntity) { rows[entity.id] = entity }
    override suspend fun updateIdentityIfNoUserMessages(
        id: String,
        agentId: String?,
        agentVersion: Int?,
        updatedAt: Long,
    ): Int {
        val conversation = rows[id] ?: return 0
        rows[id] = conversation.copy(agentId = agentId, agentVersion = agentVersion, updatedAt = updatedAt)
        return 1
    }
    override suspend fun clearProject(projectId: String) {
        rows.replaceAll { _, conversation ->
            if (conversation.projectId == projectId) conversation.copy(projectId = null) else conversation
        }
    }
    override suspend fun archive(id: String, updatedAt: Long) = Unit
    override suspend fun countByAgentVersion(agentId: String, version: Int) = 0
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
    val versionStates = linkedMapOf<String, String>()

    override fun observeAgents(): Flow<List<AgentEntity>> = MutableStateFlow(rows.values.toList())
    override suspend fun findAgent(id: String): AgentEntity? = rows[id]
    override suspend fun listReadyAgents(): List<AgentEntity> = rows.values.filter { it.status == AgentStatus.READY.name }
    override suspend fun findVersion(agentId: String, version: Int): AgentVersionEntity? {
        val agent = rows[agentId]?.takeIf { it.activeVersion == version } ?: return null
        return AgentVersionEntity(
            agentId = agentId,
            version = version,
            schemaVersion = 2,
            bundlePath = "",
            bundleSha256 = "",
            manifestJson = "",
            persona = "",
            worldviewJsonl = "",
            installedAt = 0L,
            state = versionStates["$agentId:$version"] ?: AgentStatus.READY.name,
        )
    }
    override suspend fun listVersions(agentId: String) = emptyList<AgentVersionEntity>()
    override suspend fun findVersionPackage(agentId: String, version: Int, packageId: String) = null
    override suspend fun listVersionPackages(agentId: String, version: Int) = emptyList<com.harnessapk.storage.AgentVersionPackageEntity>()
    override suspend fun findCorpus(corpusId: String, sourceHash: String): AgentCorpusEntity? = null
    override suspend fun findCorpusById(corpusId: String): AgentCorpusEntity? = null
    override suspend fun listVersionCorpora(agentId: String, version: Int): List<AgentVersionCorpusCrossRef> = emptyList()
    override suspend fun findVersionCorpus(agentId: String, version: Int, corpusId: String) = null
    override suspend fun countVersionCorpusReferences(corpusId: String, sourceHash: String) = 0
    override suspend fun listCorpusChunkKeys(corpusId: String, corpusHash: String) = emptyList<String>()
    override suspend fun countAgentSourcePartsReferencingChunkKey(chunkKey: String) = 0
    override suspend fun countLegacyAgentSourceParts(agentId: String, version: Int) = 0
    override suspend fun listInstalledVersionChunkKeys(agentId: String, version: Int, chunkKeys: List<String>) = emptyList<String>()
    override suspend fun findSource(sourceId: String, sourceHash: String): AgentSourceFileEntity? = null
    override suspend fun listVersionSources(agentId: String, version: Int): List<AgentSourceFileEntity> = emptyList()
    override suspend fun upsertAgent(entity: AgentEntity) = Unit
    override suspend fun insertVersion(entity: AgentVersionEntity) = Unit
    override suspend fun upsertVersionPackages(entities: List<com.harnessapk.storage.AgentVersionPackageEntity>) = Unit
    override suspend fun markVersionPackageInstalled(agentId: String, version: Int, packageId: String, filePath: String, installedAt: Long) = 0
    override suspend fun markVersionPackageRemoved(agentId: String, version: Int, packageId: String) = 0
    override suspend fun countInstalledPackagePathReferences(filePath: String) = 0
    override suspend fun countVersionBundlePathReferences(filePath: String) = 0
    override suspend fun countSourceFilePathReferences(filePath: String) = 0
    override suspend fun countSourceReferences(sourceId: String, sourceHash: String) = 0
    override suspend fun listCorpusSources(corpusId: String, corpusHash: String) = emptyList<AgentSourceFileEntity>()
    override suspend fun listInstalledSources() = emptyList<AgentSourceFileEntity>()
    override suspend fun updateSourcePathByHash(sourceHash: String, filePath: String) = 0
    override suspend fun updateVersionState(agentId: String, version: Int, state: String, expandedAt: Long?) = 0
    override suspend fun updateAgentInstallState(agentId: String, status: String, requiredCount: Int, installedCount: Int, updatedAt: Long) = 0
    override suspend fun updateAgentStatus(agentId: String, status: String, updatedAt: Long) = 0
    override suspend fun deleteVersionCorpus(agentId: String, version: Int, corpusId: String) = 0
    override suspend fun deleteCorpus(corpusId: String, sourceHash: String) = 0
    override suspend fun insertCorpus(entity: AgentCorpusEntity): Long = 0L
    override suspend fun updateCorpusSize(corpusId: String, sourceHash: String, sizeBytes: Long) = Unit
    override suspend fun insertVersionCorpus(entity: AgentVersionCorpusCrossRef): Long = 0L
    override suspend fun insertChunks(entities: List<AgentChunkEntity>): List<Long> = emptyList()
    override suspend fun insertCorpusChunkRefs(entities: List<AgentCorpusChunkCrossRef>): List<Long> = emptyList()
    override suspend fun insertCorpusSourceRefs(entities: List<com.harnessapk.storage.AgentCorpusSourceCrossRef>) = emptyList<Long>()
    override suspend fun insertChunkSearchRows(entities: List<AgentChunkFtsEntity>): List<Long> = emptyList()
    override suspend fun insertHierarchyNodes(entities: List<AgentHierarchyNodeEntity>): List<Long> = emptyList()
    override suspend fun insertHierarchySearchRows(entities: List<AgentHierarchyFtsEntity>): List<Long> = emptyList()
    override suspend fun insertCorpusHierarchyRefs(entities: List<com.harnessapk.storage.AgentCorpusHierarchyCrossRef>) = emptyList<Long>()
    override suspend fun insertSource(entity: AgentSourceFileEntity): Long = 0L
    override suspend fun upsertSource(entity: AgentSourceFileEntity) = Unit
    override suspend fun insertVersionSource(entity: AgentVersionSourceCrossRef): Long = 0L
    override suspend fun deleteVersion(agentId: String, version: Int) = 0
    override suspend fun deleteAgent(agentId: String) = 0
    override suspend fun searchChunkKeys(corpusKeys: List<String>, ftsQuery: String, limit: Int): List<String> = emptyList()
    override suspend fun listChunks(chunkKeys: List<String>): List<AgentChunkEntity> = emptyList()
    override suspend fun countRequiredEvidenceChunk(agentId: String, version: Int, chunkId: String) = 0
    override suspend fun searchHierarchyNodeKeys(ftsQuery: String, limit: Int): List<String> = emptyList()
    override suspend fun listHierarchyNodes(nodeKeys: List<String>): List<AgentHierarchyNodeEntity> = emptyList()
    override suspend fun deleteOrphanChunkSearchRows(): Int = 0
    override suspend fun deleteOrphanChunks(): Int = 0
    override suspend fun deleteOrphanHierarchySearchRows(): Int = 0
    override suspend fun deleteOrphanHierarchyNodes(): Int = 0
    override suspend fun deleteOrphanSources(): Int = 0
    override suspend fun deleteOrphanCorpora(): Int = 0
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
