package com.harnessapk.wiki

import com.harnessapk.chat.MessageStatus
import com.harnessapk.chat.StreamingMessageSnapshot
import com.harnessapk.chat.UiMessagePartDraft
import com.harnessapk.chat.UiMessagePartType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiCitationVerifierTest {
    @Test
    fun `visible tokens are deduplicated by source and rewritten in first use order`() {
        val first = evidence(token = "⟦W1⟧", chunkId = "chunk-1", text = "原文一")
        val sameSource = evidence(token = "⟦W2⟧", chunkId = "chunk-1", text = "原文一")
        val second = evidence(token = "⟦W3⟧", chunkId = "chunk-2", text = "原文二")
        val result = WikiCitationVerifier().verify(
            messageId = "message-1",
            snapshot = snapshot("甲⟦W1⟧乙⟦W1⟧⟦W2⟧丙⟦W3⟧"),
            context = runtimeContext(first, sameSource, second),
        )

        assertEquals(2, result.citations.size)
        assertEquals(listOf(1, 2), result.citations.map(WikiCitationDraft::displayOrdinal))
        assertEquals(3, result.citations.first().answerRanges.size)
        val text = result.snapshot.parts.single().content
        assertFalse(text.contains("⟦W"))
        assertTrue(text.contains("[¹](harness-wiki://citation/"))
        assertTrue(text.contains("[²](harness-wiki://citation/"))
    }

    @Test
    fun `unknown tokens are removed only from visible prose while code is left untouched`() {
        val result = WikiCitationVerifier().verify(
            messageId = "message-1",
            snapshot = snapshot("正文⟦W9⟧，`⟦W9⟧`\n```text\n⟦W1⟧\n```"),
            context = runtimeContext(evidence()),
        )

        val text = result.snapshot.parts.single().content
        assertTrue(result.hasInvalidVisibleTokens)
        assertFalse(text.startsWith("正文⟦W9⟧"))
        assertTrue(text.contains("`⟦W9⟧`"))
        assertTrue(text.contains("```text\n⟦W1⟧\n```"))
    }

    @Test
    fun `direct quote is retained only when it is contiguous in cited source`() {
        val source = evidence(text = "项羽本纪记载了垓下之战")
        val matched = WikiCitationVerifier().verify(
            messageId = "message-1",
            snapshot = snapshot("“垓下之战”⟦W1⟧"),
            context = runtimeContext(source),
        )
        val mismatched = WikiCitationVerifier().verify(
            messageId = "message-2",
            snapshot = snapshot("“乌江自刎”⟦W1⟧"),
            context = runtimeContext(source),
        )

        assertEquals(WikiCitationVerificationState.VERIFIED, matched.citations.single().verificationState)
        assertEquals(WikiCitationVerificationState.QUOTE_MISMATCH, mismatched.citations.single().verificationState)
        assertFalse(mismatched.snapshot.parts.single().content.contains("“"))
        assertFalse(mismatched.snapshot.parts.single().content.contains("”"))
    }

    @Test
    fun `no visible text produces no citations`() {
        val result = WikiCitationVerifier().verify(
            messageId = "message-1",
            snapshot = StreamingMessageSnapshot(MessageStatus.SUCCEEDED, emptyList()),
            context = runtimeContext(evidence()),
        )

        assertTrue(result.citations.isEmpty())
    }
}

private fun snapshot(text: String): StreamingMessageSnapshot = StreamingMessageSnapshot(
    status = MessageStatus.SUCCEEDED,
    parts = listOf(UiMessagePartDraft(0, UiMessagePartType.TEXT, text, stable = true)),
)

private fun runtimeContext(vararg evidence: WikiEvidence): WikiRuntimeContext {
    val decision = WikiRouteDecision("wiki-router-v1", WikiRouteReason.SINGLE_AUTHORIZED, emptyList(), listOf(WikiRef("history.24", 1)))
    return WikiRuntimeContext(
        scope = listOf(WikiRef("history.24", 1)),
        intent = WikiTurnIntent(WikiTurnIntentMode.AUTO, emptySet(), false),
        retrieval = WikiRetrievalResult(
            retrieverVersion = "wiki-retriever-v1",
            status = WikiRetrievalStatus.HIT,
            routeDecision = decision,
            usages = emptyList(),
            evidence = evidence.toList(),
            missingComparisonWikiIds = emptySet(),
        ),
        systemContext = "Wiki 原文证据",
    )
}

private fun evidence(
    token: String = "⟦W1⟧",
    chunkId: String = "chunk-1",
    text: String = "项羽本纪",
): WikiEvidence = WikiEvidence(
    token = token,
    ref = WikiRef("history.24", 1),
    wikiTitle = "二十四史",
    documentId = "shiji",
    sectionId = "juan-1",
    chunkId = chunkId,
    sourceTitle = "史记",
    sectionPath = "卷一",
    locatorLabel = "项羽本纪",
    originalText = text,
    originalTextSha256 = "a".repeat(64),
)
