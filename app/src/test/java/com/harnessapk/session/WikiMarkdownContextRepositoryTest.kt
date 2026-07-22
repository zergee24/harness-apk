package com.harnessapk.session

import com.harnessapk.storage.MessageWikiCitationEntity
import com.harnessapk.storage.MessageWikiUsageEntity
import com.harnessapk.storage.WikiRetrievalRunEntity
import com.harnessapk.wiki.WikiRef
import com.harnessapk.wiki.encodeWikiScopeSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiMarkdownContextRepositoryTest {
    @Test
    fun assistantMessageKeepsVerifiedOldVersionCitationInDisplayOrderAndDeduplicatesChunk() = runTest {
        val ref = WikiRef("history.24", 7)
        val repository = repository(
            runs = mapOf("assistant-1" to run("assistant-1", listOf(ref))),
            citations = mapOf(
                "assistant-1" to listOf(
                    citation(
                        id = "00000000-0000-0000-0000-000000000002",
                        messageId = "assistant-1",
                        displayOrdinal = 2,
                        ref = ref,
                        chunkId = "chunk-duplicated",
                    ),
                    citation(
                        id = "00000000-0000-0000-0000-000000000001",
                        messageId = "assistant-1",
                        displayOrdinal = 1,
                        ref = ref,
                        chunkId = "chunk-duplicated",
                    ),
                    citation(
                        id = "00000000-0000-0000-0000-000000000003",
                        messageId = "assistant-1",
                        displayOrdinal = 3,
                        ref = ref,
                        chunkId = "chunk-partial",
                        verificationState = "PARTIAL",
                    ),
                ),
            ),
        )

        val context = repository.forAssistantMessage("assistant-1")

        assertEquals(1, context.citations.citations.size)
        assertEquals(1, context.citations.citations.single().displayOrdinal)
        assertEquals(ref, WikiRef(context.citations.citations.single().wikiId, context.citations.citations.single().wikiVersion))
        assertEquals(1, context.coverage.verifiedCitationCounts[ref])
    }

    @Test
    fun recentMessagesUseRequestedOrderAndIgnoreDeletedAndUnrelatedHistory() = runTest {
        val ref = WikiRef("history.zztj", 3)
        val repository = repository(
            runs = mapOf(
                "assistant-newer" to run("assistant-newer", listOf(ref)),
                "assistant-older" to run("assistant-older", listOf(ref)),
            ),
            citations = mapOf(
                "assistant-newer" to listOf(
                    citation(
                        id = "00000000-0000-0000-0000-000000000011",
                        messageId = "assistant-newer",
                        displayOrdinal = 1,
                        ref = ref,
                        chunkId = "chunk-newer",
                    ),
                ),
                "assistant-older" to listOf(
                    citation(
                        id = "00000000-0000-0000-0000-000000000012",
                        messageId = "assistant-older",
                        displayOrdinal = 1,
                        ref = ref,
                        chunkId = "chunk-older",
                    ),
                ),
                "assistant-unrelated" to listOf(
                    citation(
                        id = "00000000-0000-0000-0000-000000000013",
                        messageId = "assistant-unrelated",
                        displayOrdinal = 1,
                        ref = ref,
                        chunkId = "chunk-unrelated",
                    ),
                ),
            ),
        )

        val context = repository.forMessageIds(listOf("assistant-older", "assistant-deleted", "assistant-newer"))

        assertEquals(
            listOf("assistant-older", "assistant-newer"),
            context.citations.citations.map(WikiMarkdownCitation::sourceMessageId),
        )
        assertEquals(
            2,
            context.citations.citations.map(context.citations::footnoteLabel).distinct().size,
        )
        assertFalse(context.citations.citations.any { it.sourceMessageId == "assistant-unrelated" })
    }

    @Test
    fun explicitComparisonWithOneVerifiedSideMarksTheOtherSideMissing() = runTest {
        val first = WikiRef("history.24", 2)
        val second = WikiRef("history.zztj", 3)
        val repository = repository(
            runs = mapOf(
                "assistant-compare" to run(
                    messageId = "assistant-compare",
                    scope = listOf(first, second),
                    status = "HIT",
                    explicitOverrideJson = comparisonOverride(first.wikiId, second.wikiId),
                ),
            ),
            usages = mapOf(
                "assistant-compare" to listOf(usage("assistant-compare", first), usage("assistant-compare", second)),
            ),
            citations = mapOf(
                "assistant-compare" to listOf(
                    citation(
                        id = "00000000-0000-0000-0000-000000000021",
                        messageId = "assistant-compare",
                        displayOrdinal = 1,
                        ref = first,
                        chunkId = "chunk-first",
                    ),
                ),
            ),
        )

        val coverage = repository.forAssistantMessage("assistant-compare").coverage

        assertEquals(setOf(first, second), coverage.requestedComparisonRefs)
        assertEquals(setOf(first, second), coverage.queriedRefs)
        assertEquals(1, coverage.verifiedCitationCounts[first])
        assertEquals(setOf(second), coverage.missingComparisonRefs)
    }

    @Test
    fun noHitFailedAndNotQueriedComparisonSidesRemainMissing() = runTest {
        val first = WikiRef("history.24", 2)
        val second = WikiRef("history.zztj", 3)
        val scope = listOf(first, second)
        val repository = repository(
            runs = mapOf(
                "assistant-no-hit" to run(
                    messageId = "assistant-no-hit",
                    scope = scope,
                    status = "NO_HIT",
                    explicitOverrideJson = comparisonOverride(first.wikiId, second.wikiId),
                ),
                "assistant-failed-side" to run(
                    messageId = "assistant-failed-side",
                    scope = scope,
                    status = "HIT",
                    explicitOverrideJson = comparisonOverride(first.wikiId, second.wikiId),
                ),
                "assistant-no-query" to run(
                    messageId = "assistant-no-query",
                    scope = scope,
                    status = "NO_QUERY",
                    explicitOverrideJson = comparisonOverride(first.wikiId, second.wikiId),
                ),
            ),
            usages = mapOf(
                "assistant-no-hit" to listOf(usage("assistant-no-hit", first), usage("assistant-no-hit", second)),
                "assistant-failed-side" to listOf(
                    usage("assistant-failed-side", first),
                    usage("assistant-failed-side", second, selectedEvidenceCount = 0),
                ),
            ),
            citations = mapOf(
                "assistant-failed-side" to listOf(
                    citation(
                        id = "00000000-0000-0000-0000-000000000031",
                        messageId = "assistant-failed-side",
                        displayOrdinal = 1,
                        ref = first,
                        chunkId = "chunk-first",
                    ),
                ),
            ),
        )

        val noHit = repository.forAssistantMessage("assistant-no-hit").coverage
        val failedSide = repository.forAssistantMessage("assistant-failed-side").coverage
        val noQuery = repository.forAssistantMessage("assistant-no-query").coverage

        assertEquals(setOf(first, second), noHit.queriedRefs)
        assertEquals(setOf(first, second), noHit.missingComparisonRefs)
        assertEquals(setOf(first, second), failedSide.queriedRefs)
        assertEquals(setOf(second), failedSide.missingComparisonRefs)
        assertTrue(noQuery.queriedRefs.isEmpty())
        assertEquals(setOf(first, second), noQuery.missingComparisonRefs)
    }

    @Test
    fun contextRejectsUnboundedMessageHistory() {
        val repository = repository()

        val error = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.forMessageIds((1..13).map { "assistant-$it" })
            }
        }

        assertTrue(error.message.orEmpty().contains("12"))
    }

    @Test
    fun contextCapsVerifiedCitationsAtForty() = runTest {
        val ref = WikiRef("history.24", 2)
        val citations = (1..41).map { ordinal ->
            citation(
                id = "00000000-0000-0000-0000-${ordinal.toString().padStart(12, '0')}",
                messageId = "assistant-many",
                displayOrdinal = ordinal,
                ref = ref,
                chunkId = "chunk-$ordinal",
            )
        }
        val repository = repository(
            runs = mapOf("assistant-many" to run("assistant-many", listOf(ref))),
            citations = mapOf("assistant-many" to citations),
        )

        val context = repository.forAssistantMessage("assistant-many")

        assertEquals(40, context.citations.citations.size)
        assertEquals(40, context.citations.citations.last().displayOrdinal)
    }

    private fun repository(
        runs: Map<String, WikiRetrievalRunEntity> = emptyMap(),
        usages: Map<String, List<MessageWikiUsageEntity>> = emptyMap(),
        citations: Map<String, List<MessageWikiCitationEntity>> = emptyMap(),
    ): WikiMarkdownContextRepository = WikiMarkdownContextRepository(
        findRun = { messageId -> runs[messageId] },
        listUsages = { messageId -> usages[messageId].orEmpty() },
        listCitations = { messageId -> citations[messageId].orEmpty() },
    )

    private fun run(
        messageId: String,
        scope: List<WikiRef>,
        status: String = "HIT",
        explicitOverrideJson: String? = null,
    ): WikiRetrievalRunEntity = WikiRetrievalRunEntity(
        messageId = messageId,
        allowedScopeJson = encodeWikiScopeSnapshot(scope),
        explicitOverrideJson = explicitOverrideJson,
        routerVersion = "router-v1",
        retrieverVersion = "retriever-v1",
        status = status,
        candidateCount = 1,
        evidenceCount = 1,
        elapsedMillis = 1L,
        errorCode = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun usage(
        messageId: String,
        ref: WikiRef,
        selectedEvidenceCount: Int = 1,
    ): MessageWikiUsageEntity = MessageWikiUsageEntity(
        messageId = messageId,
        wikiId = ref.wikiId,
        wikiVersion = ref.version,
        scoutRank = 1,
        deepHitCount = 1,
        selectedEvidenceCount = selectedEvidenceCount,
        enteredContext = selectedEvidenceCount > 0,
    )

    private fun citation(
        id: String,
        messageId: String,
        displayOrdinal: Int,
        ref: WikiRef,
        chunkId: String,
        verificationState: String = "VERIFIED",
    ): MessageWikiCitationEntity = MessageWikiCitationEntity(
        id = id,
        messageId = messageId,
        displayOrdinal = displayOrdinal,
        wikiId = ref.wikiId,
        wikiVersion = ref.version,
        wikiTitle = ref.wikiId,
        documentId = "document-1",
        sectionId = "section-1",
        chunkId = chunkId,
        sourceTitle = "来源",
        sectionPath = "来源 / 章节",
        locatorLabel = "第 1 节",
        originalTextSnapshot = "可核对原文",
        originalTextSha256 = "a".repeat(64),
        answerRangesJson = "[]",
        verificationState = verificationState,
        createdAt = 1L,
    )

    private fun comparisonOverride(vararg wikiIds: String): String =
        """{"mode":"COMPARE_NAMED","compareRequested":true,"namedWikiIds":[${wikiIds.joinToString { "\"$it\"" }}],"unavailableNamedWikiIds":[]}"""
}
