package com.harnessapk.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiEvidenceSelectorTest {
    @Test
    fun `selection obeys default chunk character and section budgets deterministically`() {
        val ref = WikiRef("history.24", 1)
        val candidates = (1..16).map { index ->
            evidenceCandidate(
                ref = ref,
                chunkId = "chunk-$index",
                sectionId = "section-${index % 4}",
                text = "王安石${"甲".repeat(1_180)}$index",
                ordinal = index,
            )
        }
        val request = WikiEvidenceSelectionRequest(
            query = "王安石如何变法",
            candidates = candidates,
        )

        val first = WikiEvidenceSelector().select(request)
        val second = WikiEvidenceSelector().select(request)

        assertTrue(first.selected.size <= 10)
        assertTrue(first.selected.sumOf { it.hit.originalText.length } <= 12_000)
        assertTrue(first.selected.groupingBy { it.hit.chunk.sectionId }.eachCount().values.all { it <= 3 })
        assertEquals(
            first.selected.map { it.hit.chunkId },
            second.selected.map { it.hit.chunkId },
        )
    }

    @Test
    fun `comparison selection retains each available side before extra coverage`() {
        val first = WikiRef("history.24", 1)
        val second = WikiRef("history.zztj", 1)
        val result = WikiEvidenceSelector().select(
            WikiEvidenceSelectionRequest(
                query = "王安石",
                comparisonRefs = setOf(first, second),
                candidates = listOf(
                    evidenceCandidate(first, "first-1", "first", "王安石事迹一", 1),
                    evidenceCandidate(first, "first-2", "first", "王安石事迹二", 2),
                    evidenceCandidate(second, "second-1", "second", "王安石编年一", 1),
                    evidenceCandidate(second, "second-2", "second", "王安石编年二", 2),
                ),
            ),
        )

        assertEquals(
            setOf(first, second),
            result.selected.map(WikiEvidenceCandidate::ref).toSet(),
        )
        assertTrue(result.selected.take(4).groupBy(WikiEvidenceCandidate::ref).values.all { it.size >= 2 })
    }
}

private fun evidenceCandidate(
    ref: WikiRef,
    chunkId: String,
    sectionId: String,
    text: String,
    ordinal: Int,
): WikiEvidenceCandidate = WikiEvidenceCandidate(
    ref = ref,
    wikiTitle = ref.wikiId,
    documentId = "document-${ref.wikiId}",
    sourceTitle = "史料",
    sectionPath = sectionId,
    hit = WikiSourceHit(
        chunk = WikiChunk(
            id = chunkId,
            sectionId = sectionId,
            ordinal = ordinal,
            originalText = text,
            locator = WikiSourceLocator("locator-$chunkId", chunkId, "位置 $chunkId", "{}"),
            contentHash = "a".repeat(64),
        ),
        matches = listOf(WikiSourceMatch(WikiSearchChannel.ORIGINAL, "原文")),
    ),
)
