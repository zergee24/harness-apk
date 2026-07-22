package com.harnessapk.chat

import com.harnessapk.wiki.WikiCitationVerifier
import com.harnessapk.wiki.WikiEvidence
import com.harnessapk.wiki.WikiRef
import com.harnessapk.wiki.WikiRetrievalResult
import com.harnessapk.wiki.WikiRetrievalStatus
import com.harnessapk.wiki.WikiRouteDecision
import com.harnessapk.wiki.WikiRouteReason
import com.harnessapk.wiki.WikiRuntimeContext
import com.harnessapk.wiki.WikiTurnIntent
import com.harnessapk.wiki.WikiTurnIntentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiSourcePartWriterTest {
    @Test
    fun `writer appends one source part and does not persist duplicate invocation twice`() = kotlinx.coroutines.test.runTest {
        val persisted = mutableListOf<WikiSourcePersistenceRequest>()
        val writer = WikiSourcePartWriter(
            verifier = WikiCitationVerifier(),
            persistence = WikiSourcePersistence { request -> persisted += request },
        )
        val context = sourceContext()
        val initial = StreamingMessageSnapshot(
            status = MessageStatus.SUCCEEDED,
            parts = listOf(UiMessagePartDraft(0, UiMessagePartType.TEXT, "回答⟦W1⟧", stable = true)),
        )

        val first = writer.persist("message-1", initial, context)
        val second = writer.persist("message-1", first, context)

        assertEquals(1, persisted.size)
        assertEquals(UiMessagePartType.WIKI_SOURCES, first.parts.last().type)
        assertTrue(first.parts.last().metadata["citationIds"].orEmpty().contains("-"))
        assertEquals(first, second)
    }

    @Test
    fun `writer exposes prepared snapshot before a persistence rollback`() = kotlinx.coroutines.test.runTest {
        val writer = WikiSourcePartWriter(
            verifier = WikiCitationVerifier(),
            persistence = WikiSourcePersistence { error("transaction rolled back") },
        )
        var prepared: StreamingMessageSnapshot? = null

        val failure = runCatching {
            writer.persist(
                messageId = "message-1",
                snapshot = StreamingMessageSnapshot(
                    status = MessageStatus.SUCCEEDED,
                    parts = listOf(UiMessagePartDraft(0, UiMessagePartType.TEXT, "回答⟦W1⟧", stable = true)),
                ),
                context = sourceContext(),
                onPrepared = { prepared = it },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(requireNotNull(prepared).parts.any { it.type == UiMessagePartType.WIKI_SOURCES })
    }
}

private fun sourceContext(): WikiRuntimeContext {
    val ref = WikiRef("history.24", 1)
    return WikiRuntimeContext(
        scope = listOf(ref),
        intent = WikiTurnIntent(WikiTurnIntentMode.AUTO, emptySet(), false),
        retrieval = WikiRetrievalResult(
            retrieverVersion = "wiki-retriever-v1",
            status = WikiRetrievalStatus.HIT,
            routeDecision = WikiRouteDecision("wiki-router-v1", WikiRouteReason.SINGLE_AUTHORIZED, emptyList(), listOf(ref)),
            usages = emptyList(),
            evidence = listOf(
                WikiEvidence(
                    token = "⟦W1⟧",
                    ref = ref,
                    wikiTitle = "二十四史",
                    documentId = "shiji",
                    sectionId = "juan-1",
                    chunkId = "chunk-1",
                    sourceTitle = "史记",
                    sectionPath = "卷一",
                    locatorLabel = "项羽本纪",
                    originalText = "项羽本纪",
                    originalTextSha256 = "a".repeat(64),
                ),
            ),
            missingComparisonWikiIds = emptySet(),
        ),
        systemContext = "Wiki 原文证据",
    )
}
