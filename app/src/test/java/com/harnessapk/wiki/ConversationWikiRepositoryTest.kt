package com.harnessapk.wiki

import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.ConversationWikiDao
import com.harnessapk.storage.ConversationWikiMountEntity
import com.harnessapk.storage.MessageWikiCitationEntity
import com.harnessapk.storage.WikiRetrievalRunEntity
import com.harnessapk.storage.MessageWikiUsageEntity
import com.harnessapk.storage.WikiVersionEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWikiRepositoryTest {
    @Test
    fun `changing a mount version does not rewrite a historical citation`() = runTest {
        val dao = FakeConversationWikiDao().apply {
            readyVersions += WikiRef("history.zztj", 1)
            readyVersions += WikiRef("history.zztj", 2)
        }
        val repository = repository(dao)
        val conversationId = "conversation-1"

        repository.replaceMount(conversationId, WikiRef("history.zztj", 1), enabled = true)
        repository.persistCitation(
            MessageWikiCitation(
                id = "citation-1",
                messageId = "assistant-1",
                displayOrdinal = 1,
                ref = WikiRef("history.zztj", 1),
                wikiTitle = "资治通鉴",
                documentId = "document-1",
                sectionId = "section-1",
                chunkId = "chunk-1",
                sourceTitle = "卷一",
                sectionPath = "卷一 / 起始",
                locatorLabel = "第一段",
                originalTextSnapshot = "原文",
                originalTextSha256 = "a".repeat(64),
                answerRangesJson = "[[0,2]]",
                verificationState = WikiCitationVerificationState.VERIFIED,
                createdAt = 1L,
            ),
        )

        repository.replaceMount(conversationId, WikiRef("history.zztj", 2), enabled = true)

        assertEquals(WikiRef("history.zztj", 2), repository.mounts(conversationId).single().ref)
        assertEquals(WikiRef("history.zztj", 1), repository.citation("citation-1")!!.ref)
    }

    @Test
    fun `enabled snapshot is exact stable and ignores disabled mounts`() = runTest {
        val dao = FakeConversationWikiDao().apply {
            readyVersions += WikiRef("z.wiki", 2)
            readyVersions += WikiRef("a.wiki", 1)
            readyVersions += WikiRef("disabled.wiki", 1)
        }
        val repository = repository(dao)

        repository.replaceMount("conversation-1", WikiRef("z.wiki", 2), enabled = true)
        repository.replaceMount("conversation-1", WikiRef("disabled.wiki", 1), enabled = false)
        repository.replaceMount("conversation-1", WikiRef("a.wiki", 1), enabled = true)

        assertEquals(
            listOf(WikiRef("a.wiki", 1), WikiRef("z.wiki", 2)),
            repository.snapshotEnabled("conversation-1"),
        )
    }

    @Test
    fun `reference protection includes disabled mounts and citations but not usages`() = runTest {
        val dao = FakeConversationWikiDao().apply {
            readyVersions += WikiRef("history.zztj", 1)
        }
        val repository = repository(dao)
        val ref = WikiRef("history.zztj", 1)

        repository.replaceMount("conversation-1", ref, enabled = false)
        repository.persistUsage(
            MessageWikiUsage(
                messageId = "assistant-1",
                ref = ref,
                scoutRank = 1,
                deepHitCount = 4,
                selectedEvidenceCount = 0,
                enteredContext = false,
            ),
        )

        assertEquals(
            WikiVersionReferenceCounts(mountCount = 1, citationCount = 0),
            repository.referenceCounts(ref),
        )
        assertEquals(1, repository.countReferences(ref))
        repository.removeMount("conversation-1", ref.wikiId)
        assertEquals(WikiVersionReferenceCounts.EMPTY, repository.referenceCounts(ref))
        assertEquals(0, repository.countReferences(ref))

        repository.persistCitation(
            MessageWikiCitation(
                id = "citation-1",
                messageId = "assistant-1",
                displayOrdinal = 1,
                ref = ref,
                wikiTitle = "资治通鉴",
                documentId = "document-1",
                sectionId = "section-1",
                chunkId = "chunk-1",
                sourceTitle = "卷一",
                sectionPath = "卷一 / 起始",
                locatorLabel = "第一段",
                originalTextSnapshot = "原文",
                originalTextSha256 = "a".repeat(64),
                answerRangesJson = "[]",
                verificationState = WikiCitationVerificationState.VERIFIED,
                createdAt = 1L,
            ),
        )
        assertEquals(
            WikiVersionReferenceCounts(mountCount = 0, citationCount = 1),
            repository.referenceCounts(ref),
        )
        assertEquals(1, repository.countReferences(ref))
        assertFalse(repository.canRemove(ref))
    }

    @Test
    fun `restore defaults copies only ready defaults once`() = runTest {
        val dao = FakeConversationWikiDao().apply {
            readyDefaultVersions += WikiRef("history.zztj", 2)
            readyDefaultVersions += WikiRef("history.24", 1)
            readyVersions += readyDefaultVersions
            readyVersions += WikiRef("stale.wiki", 1)
        }
        val repository = repository(dao)
        repository.replaceMount("conversation-1", WikiRef("stale.wiki", 1), enabled = true)

        repository.restoreDefaults("conversation-1")
        dao.readyDefaultVersions.clear()

        assertEquals(
            listOf(WikiRef("history.24", 1), WikiRef("history.zztj", 2)),
            repository.snapshotEnabled("conversation-1"),
        )
    }

    private fun repository(dao: FakeConversationWikiDao): ConversationWikiRepository =
        ConversationWikiRepository(
            dao = dao,
            transactionRunner = ConversationWikiTransactionRunner { block -> block() },
            timeProvider = TimeProvider { 1L },
        )
}

private class FakeConversationWikiDao : ConversationWikiDao {
    val readyVersions = linkedSetOf<WikiRef>()
    val readyDefaultVersions = linkedSetOf<WikiRef>()
    private val mounts = linkedMapOf<Pair<String, String>, ConversationWikiMountEntity>()
    private val citations = linkedMapOf<String, MessageWikiCitationEntity>()
    private val usages = linkedMapOf<Triple<String, String, Int>, MessageWikiUsageEntity>()
    private val runs = linkedMapOf<String, WikiRetrievalRunEntity>()

    override suspend fun findReadyVersion(wikiId: String, version: Int): WikiVersionEntity? =
        readyVersions.firstOrNull { it.wikiId == wikiId && it.version == version }?.let { ref ->
            WikiVersionEntity(
                wikiId = ref.wikiId,
                version = ref.version,
                contentPath = "/private/${ref.wikiId}/${ref.version}",
                schemaVersion = 1,
                contentHash = "a".repeat(64),
                packageHash = "b".repeat(64),
                publisherKeyId = "key",
                publisherFingerprint = "c".repeat(64),
                manifestJson = "{}",
                sizeBytes = 1L,
                enabledForNewConversations = ref in readyDefaultVersions,
                state = WikiVersionState.READY.name,
                installedAt = 1L,
            )
        }

    override suspend fun listReadyDefaults(): List<WikiVersionEntity> = readyDefaultVersions
        .sortedWith(compareBy<WikiRef> { it.wikiId }.thenBy { it.version })
        .mapNotNull { findReadyVersion(it.wikiId, it.version) }

    override suspend fun findMount(conversationId: String, wikiId: String): ConversationWikiMountEntity? =
        mounts[conversationId to wikiId]

    override suspend fun listMounts(conversationId: String): List<ConversationWikiMountEntity> = mounts.values
        .filter { it.conversationId == conversationId }
        .sortedWith(compareBy<ConversationWikiMountEntity> { it.wikiId }.thenBy { it.wikiVersion })

    override suspend fun upsertMount(entity: ConversationWikiMountEntity) {
        mounts[entity.conversationId to entity.wikiId] = entity
    }

    override suspend fun deleteMount(conversationId: String, wikiId: String): Int =
        if (mounts.remove(conversationId to wikiId) != null) 1 else 0

    override suspend fun deleteMounts(conversationId: String): Int {
        val keys = mounts.keys.filter { it.first == conversationId }
        keys.forEach(mounts::remove)
        return keys.size
    }

    override suspend fun countMountReferences(wikiId: String, version: Int): Int = mounts.values.count {
        it.wikiId == wikiId && it.wikiVersion == version
    }

    override suspend fun countCitationReferences(wikiId: String, version: Int): Int = citations.values.count {
        it.wikiId == wikiId && it.wikiVersion == version
    }

    override suspend fun insertRun(entity: WikiRetrievalRunEntity) {
        runs[entity.messageId] = entity
    }

    override suspend fun findRun(messageId: String): WikiRetrievalRunEntity? = runs[messageId]

    override suspend fun insertUsage(entity: MessageWikiUsageEntity) {
        usages[Triple(entity.messageId, entity.wikiId, entity.wikiVersion)] = entity
    }

    override suspend fun listUsagesForMessage(messageId: String): List<MessageWikiUsageEntity> = usages.values
        .filter { it.messageId == messageId }
        .sortedWith(compareBy<MessageWikiUsageEntity> { it.wikiId }.thenBy { it.wikiVersion })

    override suspend fun insertCitation(entity: MessageWikiCitationEntity) {
        citations[entity.id] = entity
    }

    override suspend fun deleteCitationsForMessage(messageId: String): Int {
        val ids = citations.values.filter { it.messageId == messageId }.map(MessageWikiCitationEntity::id)
        ids.forEach(citations::remove)
        return ids.size
    }

    override suspend fun findCitation(id: String): MessageWikiCitationEntity? = citations[id]

    override fun observeCitationsForMessage(messageId: String) = flowOf(
        citations.values
            .filter { it.messageId == messageId }
            .sortedBy(MessageWikiCitationEntity::displayOrdinal),
    )

    override suspend fun listCitationsForMessage(messageId: String): List<MessageWikiCitationEntity> = citations.values
        .filter { it.messageId == messageId }
        .sortedBy(MessageWikiCitationEntity::displayOrdinal)
}
