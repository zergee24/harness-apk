package com.harnessapk.agentmemory

import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AgentMemoryDao
import com.harnessapk.storage.AgentMemoryEntity
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryRepositoryTest {
    @Test
    fun stableIdTrimsAndLowercasesDedupeKeyWithoutDefaultLocaleDrift() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val turkish = agentMemoryId("agent-1", AgentMemoryKind.USER_PREFERENCE, "  TITLE I  ")
            Locale.setDefault(Locale.US)
            val english = agentMemoryId("agent-1", AgentMemoryKind.USER_PREFERENCE, "title i")

            assertEquals(english, turkish)
            assertEquals(64, english.length)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun mergeUsesStableKeyAndKeepsLatestSourceForConflict() = runTest {
        val fixture = fixture()
        fixture.repository.merge(
            agentId = "agent-1",
            conversationId = "conversation-1",
            candidates = listOf(candidate("address", "称呼我为 Tony", "message-1", confidence = 0.7)),
        )
        fixture.now.set(20L)

        val result = fixture.repository.merge(
            agentId = "agent-1",
            conversationId = "conversation-2",
            candidates = listOf(candidate(" ADDRESS ", "称呼我为老唐", "message-2", confidence = 0.9)),
        )

        val memory = fixture.repository.list("agent-1").single()
        assertEquals(0, result.insertedCount)
        assertEquals(1, result.updatedCount)
        assertEquals("称呼我为老唐", memory.content)
        assertEquals("conversation-2", memory.sourceConversationId)
        assertEquals("message-2", memory.sourceMessageId)
        assertEquals(0.9, memory.confidence, 0.0)
        assertEquals(10L, memory.createdAt)
        assertEquals(20L, memory.updatedAt)
        assertFalse(memory.userEdited)
    }

    @Test
    fun batchDuplicateUsesHighestConfidenceAndKeepsFirstOnTie() = runTest {
        val fixture = fixture()

        val result = fixture.repository.merge(
            agentId = "agent-1",
            conversationId = "conversation-1",
            candidates = listOf(
                candidate("language", "低置信", "message-1", confidence = 0.4),
                candidate(" LANGUAGE ", "高置信先到", "message-2", confidence = 0.9),
                candidate("language", "同分后到", "message-3", confidence = 0.9),
            ),
        )

        assertEquals(1, result.insertedCount)
        assertEquals(2, result.duplicateCount)
        val memory = fixture.repository.list("agent-1").single()
        assertEquals("高置信先到", memory.content)
        assertEquals("message-2", memory.sourceMessageId)
    }

    @Test
    fun userEditKeepsSourceAndCannotBeOverwrittenByAutomaticMerge() = runTest {
        val fixture = fixture()
        fixture.repository.merge(
            "agent-1",
            "conversation-1",
            listOf(candidate("language", "默认英文", "message-1")),
        )
        val beforeEdit = fixture.repository.list("agent-1").single()
        fixture.now.set(20L)

        assertTrue(fixture.repository.edit(beforeEdit.id, "  默认使用中文  "))
        fixture.now.set(30L)
        val merge = fixture.repository.merge(
            "agent-1",
            "conversation-2",
            listOf(candidate("language", "自动改成日语", "message-2", confidence = 1.0)),
        )

        val memory = fixture.repository.list("agent-1").single()
        assertEquals(1, merge.protectedCount)
        assertEquals("默认使用中文", memory.content)
        assertEquals("conversation-1", memory.sourceConversationId)
        assertEquals("message-1", memory.sourceMessageId)
        assertEquals(1.0, memory.confidence, 0.0)
        assertTrue(memory.userEdited)
        assertEquals(10L, memory.createdAt)
        assertEquals(20L, memory.updatedAt)
    }

    @Test
    fun deleteAndClearRemainScopedToTheRequestedMemoryOrAgent() = runTest {
        val fixture = fixture()
        fixture.repository.merge(
            "agent-1",
            "conversation-1",
            listOf(
                candidate("language", "默认中文", "message-1"),
                candidate("address", "称呼老唐", "message-2", AgentMemoryKind.ADDRESS_PREFERENCE),
            ),
        )
        fixture.repository.merge(
            "agent-2",
            "conversation-2",
            listOf(candidate("language", "默认中文", "message-3")),
        )
        val firstAgent = fixture.repository.list("agent-1")

        assertTrue(fixture.repository.delete(firstAgent.first().id))
        assertEquals(1, fixture.repository.list("agent-1").size)
        assertEquals(1, fixture.repository.clear("agent-1"))
        assertTrue(fixture.repository.list("agent-1").isEmpty())
        assertEquals(1, fixture.repository.list("agent-2").size)
    }

    @Test
    fun invalidCandidateBatchFailsBeforeWritingAnything() = runTest {
        val invalidCandidates = listOf(
            candidate("blank-key", "内容", "message").copy(dedupeKey = " "),
            candidate("blank-content", " ", "message"),
            candidate("blank-message", "内容", " "),
            candidate("long-message", "内容", "x".repeat(MAX_AGENT_MEMORY_ID_CHARS + 1)),
            candidate("blank-quote", "内容", "message").copy(sourceQuote = " "),
            candidate("nan", "内容", "message").copy(confidence = Double.NaN),
            candidate("infinite", "内容", "message").copy(confidence = Double.POSITIVE_INFINITY),
            candidate("negative", "内容", "message").copy(confidence = -0.1),
            candidate("overflow", "内容", "message").copy(confidence = 1.1),
            candidate("long-key", "内容", "message").copy(
                dedupeKey = "x".repeat(MAX_AGENT_MEMORY_DEDUPE_KEY_CHARS + 1),
            ),
            candidate("long-content", "x", "message").copy(
                content = "x".repeat(MAX_AGENT_MEMORY_CONTENT_CHARS + 1),
            ),
            candidate("long-quote", "内容", "message").copy(
                sourceQuote = "x".repeat(MAX_AGENT_MEMORY_SOURCE_QUOTE_CHARS + 1),
            ),
        )

        invalidCandidates.forEach { invalid ->
            val fixture = fixture()
            val failure = runCatching {
                fixture.repository.merge(
                    agentId = "agent-1",
                    conversationId = "conversation-1",
                    candidates = listOf(candidate("valid", "有效", "message-valid"), invalid),
                )
            }.exceptionOrNull()

            assertTrue(failure is AgentMemoryValidationException)
            assertTrue(fixture.repository.list("agent-1").isEmpty())
        }
    }

    @Test
    fun scopeFieldsAndCandidateLimitFailBeforeWritingAnything() = runTest {
        val fixture = fixture()
        val failures = listOf(
            runCatching {
                fixture.repository.merge(" ", "conversation", listOf(candidate("key", "内容", "message")))
            }.exceptionOrNull(),
            runCatching {
                fixture.repository.merge("agent", " ", listOf(candidate("key", "内容", "message")))
            }.exceptionOrNull(),
            runCatching {
                fixture.repository.merge(
                    "x".repeat(MAX_AGENT_MEMORY_ID_CHARS + 1),
                    "conversation",
                    listOf(candidate("key", "内容", "message")),
                )
            }.exceptionOrNull(),
            runCatching {
                fixture.repository.merge(
                    "agent",
                    "x".repeat(MAX_AGENT_MEMORY_ID_CHARS + 1),
                    listOf(candidate("key", "内容", "message")),
                )
            }.exceptionOrNull(),
            runCatching {
                fixture.repository.merge(
                    "agent",
                    "conversation",
                    List(MAX_AGENT_MEMORY_CANDIDATES_PER_MERGE + 1) { index ->
                        candidate("key-$index", "内容", "message-$index")
                    },
                )
            }.exceptionOrNull(),
        )

        assertTrue(failures.all { it is AgentMemoryValidationException })
        assertTrue(fixture.repository.list("agent").isEmpty())
    }

    @Test
    fun equalTimestampsUseStableIdOrderAndMissingMutationsDoNotCreateRows() = runTest {
        val fixture = fixture()
        fixture.repository.merge(
            "agent-1",
            "conversation-1",
            listOf(
                candidate("gamma", "第三", "message-3"),
                candidate("alpha", "第一", "message-1"),
                candidate("beta", "第二", "message-2"),
            ),
        )

        val memories = fixture.repository.list("agent-1")

        assertEquals(memories.map { it.id }.sorted(), memories.map { it.id })
        assertFalse(fixture.repository.edit("missing", "不会创建"))
        assertFalse(fixture.repository.delete("missing"))
        assertEquals(3, fixture.repository.list("agent-1").size)
    }

    @Test
    fun concurrentAutomaticMergesCannotOverwriteACompletedUserEdit() = runTest {
        val fixture = fixture()
        fixture.repository.merge(
            "agent-1",
            "conversation-1",
            listOf(candidate("language", "默认英文", "message-1")),
        )
        val id = fixture.repository.list("agent-1").single().id

        coroutineScope {
            buildList {
                add(async { fixture.repository.edit(id, "人工确认中文") })
                repeat(32) { index ->
                    add(
                        async {
                            fixture.repository.merge(
                                "agent-1",
                                "conversation-$index",
                                listOf(candidate("language", "自动内容 $index", "message-$index")),
                            )
                        },
                    )
                }
            }.awaitAll()
        }

        val memory = fixture.repository.list("agent-1").single()
        assertEquals("人工确认中文", memory.content)
        assertTrue(memory.userEdited)
        assertEquals(1.0, memory.confidence, 0.0)
    }

    @Test
    fun unknownStoredKindFailsClosed() = runTest {
        val fixture = fixture()
        fixture.dao.seed(
            AgentMemoryEntity(
                id = "unknown",
                agentId = "agent-1",
                kind = "UNKNOWN_KIND",
                content = "未知",
                sourceConversationId = "conversation-1",
                sourceMessageId = "message-1",
                confidence = 1.0,
                userEdited = false,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val failure = runCatching { fixture.repository.list("agent-1") }.exceptionOrNull()

        assertTrue(failure is AgentMemoryDataException)
    }

    @Test
    fun conflictingStoredIdentityFailsClosedWithoutCrossAgentUpdate() = runTest {
        val fixture = fixture()
        val conflictingId = agentMemoryId(
            "agent-1",
            AgentMemoryKind.USER_PREFERENCE,
            "language",
        )
        fixture.dao.seed(
            AgentMemoryEntity(
                id = conflictingId,
                agentId = "agent-2",
                kind = AgentMemoryKind.USER_PREFERENCE.name,
                content = "agent-2 原内容",
                sourceConversationId = "conversation-2",
                sourceMessageId = "message-2",
                confidence = 0.8,
                userEdited = false,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val failure = runCatching {
            fixture.repository.merge(
                "agent-1",
                "conversation-1",
                listOf(candidate("language", "agent-1 新内容", "message-1")),
            )
        }.exceptionOrNull()

        assertTrue(failure is AgentMemoryDataException)
        assertEquals("agent-2 原内容", fixture.dao.findById(conflictingId)?.content)
    }

    private fun fixture(): Fixture {
        val dao = FakeAgentMemoryDao()
        val now = AtomicLong(10L)
        val transactionMutex = Mutex()
        return Fixture(
            dao = dao,
            now = now,
            repository = AgentMemoryRepository(
                dao = dao,
                transactionRunner = AgentMemoryTransactionRunner { block ->
                    transactionMutex.lock()
                    try {
                        block()
                    } finally {
                        transactionMutex.unlock()
                    }
                },
                timeProvider = TimeProvider(now::get),
            ),
        )
    }

    private fun candidate(
        dedupeKey: String,
        content: String,
        messageId: String,
        kind: AgentMemoryKind = AgentMemoryKind.USER_PREFERENCE,
        confidence: Double = 0.8,
    ) = AgentMemoryCandidate(
        kind = kind,
        dedupeKey = dedupeKey,
        content = content,
        sourceMessageId = messageId,
        sourceQuote = content,
        confidence = confidence,
    )
}

private data class Fixture(
    val dao: FakeAgentMemoryDao,
    val now: AtomicLong,
    val repository: AgentMemoryRepository,
)

private class FakeAgentMemoryDao : AgentMemoryDao {
    private val rows = MutableStateFlow<Map<String, AgentMemoryEntity>>(emptyMap())

    override fun observeForAgent(agentId: String): Flow<List<AgentMemoryEntity>> =
        rows.map { current -> current.values.forAgent(agentId) }

    override suspend fun listForAgent(agentId: String): List<AgentMemoryEntity> =
        rows.value.values.forAgent(agentId)

    override suspend fun findById(id: String): AgentMemoryEntity? = rows.value[id]

    override suspend fun insert(entity: AgentMemoryEntity): Long {
        if (rows.value.containsKey(entity.id)) return -1L
        rows.value += entity.id to entity
        return rows.value.size.toLong()
    }

    override suspend fun updateAutomatically(
        id: String,
        agentId: String,
        kind: String,
        content: String,
        sourceConversationId: String,
        sourceMessageId: String,
        confidence: Double,
        updatedAt: Long,
    ): Int {
        val existing = rows.value[id] ?: return 0
        if (existing.agentId != agentId || existing.kind != kind || existing.userEdited) return 0
        rows.value += id to existing.copy(
            content = content,
            sourceConversationId = sourceConversationId,
            sourceMessageId = sourceMessageId,
            confidence = confidence,
            updatedAt = updatedAt,
        )
        return 1
    }

    override suspend fun markUserEdited(id: String, content: String, updatedAt: Long): Int {
        val existing = rows.value[id] ?: return 0
        rows.value += id to existing.copy(
            content = content,
            confidence = 1.0,
            userEdited = true,
            updatedAt = updatedAt,
        )
        return 1
    }

    override suspend fun delete(id: String): Int {
        if (!rows.value.containsKey(id)) return 0
        rows.value -= id
        return 1
    }

    override suspend fun clear(agentId: String): Int {
        val removed = rows.value.values.count { it.agentId == agentId }
        rows.value = rows.value.filterValues { it.agentId != agentId }
        return removed
    }

    fun seed(entity: AgentMemoryEntity) {
        rows.value += entity.id to entity
    }

    private fun Collection<AgentMemoryEntity>.forAgent(agentId: String): List<AgentMemoryEntity> =
        filter { it.agentId == agentId }
            .sortedWith(compareByDescending<AgentMemoryEntity> { it.updatedAt }.thenBy { it.id })
}
