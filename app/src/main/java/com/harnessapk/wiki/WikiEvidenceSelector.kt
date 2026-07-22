package com.harnessapk.wiki

data class WikiEvidenceCandidate(
    val ref: WikiRef,
    val wikiTitle: String,
    val documentId: String,
    val sourceTitle: String,
    val sectionPath: String,
    val hit: WikiSourceHit,
    val rankScore: Double = 0.0,
) {
    val sectionId: String get() = hit.chunk.sectionId
    val chunkId: String get() = hit.chunkId
}

data class WikiEvidenceSelectionRequest(
    val query: String,
    val candidates: List<WikiEvidenceCandidate>,
    val comparisonRefs: Set<WikiRef> = emptySet(),
    val relaxedSemanticWeights: Boolean = false,
)

data class WikiEvidenceSelectionResult(
    val selected: List<WikiEvidenceCandidate>,
)

class WikiEvidenceSelector {
    fun select(request: WikiEvidenceSelectionRequest): WikiEvidenceSelectionResult {
        val candidates = canonicalCandidates(request.candidates)
        if (candidates.isEmpty()) return WikiEvidenceSelectionResult(emptyList())

        val queryTokens = WikiSourceSearch.normalizedTokens(request.query)
            .take(MAX_COVERAGE_QUERY_TOKENS)
            .toSet()
        val comparisonRefs = request.comparisonRefs.sortedWith(WIKI_REF_ORDER)
        val selected = mutableListOf<WikiEvidenceCandidate>()
        val sectionCounts = mutableMapOf<WikiRefSection, Int>()
        var selectedCharacters = 0

        comparisonRefs.forEach { ref ->
            candidates.asSequence()
                .filter { it.ref == ref }
                .sortedWith(candidateOrder(queryTokens, selected, request.relaxedSemanticWeights))
                .take(MIN_COMPARISON_EVIDENCE)
                .forEach { candidate ->
                    if (candidate !in selected && canAdd(candidate, selected, sectionCounts, selectedCharacters)) {
                        selected += candidate
                        selectedCharacters += candidate.hit.originalText.length
                        sectionCounts.increment(candidate)
                    }
                }
        }

        while (selected.size < DEFAULT_MAX_EVIDENCE) {
            val next = candidates.asSequence()
                .filterNot { it in selected }
                .filter { candidate -> canAdd(candidate, selected, sectionCounts, selectedCharacters) }
                .maxWithOrNull(candidateOrder(queryTokens, selected, request.relaxedSemanticWeights).reversed())
                ?: break
            if (
                selectedCharacters >= SOFT_CHARACTER_TARGET &&
                !improvesCoverage(next, selected, queryTokens) &&
                next.ref !in comparisonRefs
            ) {
                break
            }
            selected += next
            selectedCharacters += next.hit.originalText.length
            sectionCounts.increment(next)
        }

        return WikiEvidenceSelectionResult(
            selected = selected.sortedWith(
                compareBy<WikiEvidenceCandidate> { it.ref.wikiId }
                    .thenBy { it.ref.version }
                    .thenBy { it.documentId }
                    .thenBy { it.sectionId }
                    .thenBy { it.hit.chunk.ordinal }
                    .thenBy(WikiEvidenceCandidate::chunkId),
            ),
        )
    }

    private fun canonicalCandidates(candidates: List<WikiEvidenceCandidate>): List<WikiEvidenceCandidate> =
        candidates
            .filter { it.hit.originalText.isNotBlank() }
            .filter { it.hit.originalText.length <= HARD_CHARACTER_CAP }
            .groupBy { it.ref to it.chunkId }
            .values
            .map { duplicates ->
                duplicates.reduce { first, second ->
                    val mergedMatches = (first.hit.matches + second.hit.matches)
                        .distinct()
                        .sortedWith(matchOrder)
                    first.copy(
                        hit = first.hit.copy(matches = mergedMatches),
                        rankScore = maxOf(first.rankScore, second.rankScore),
                    )
                }
            }
            .sortedWith(
                compareBy<WikiEvidenceCandidate> { it.ref.wikiId }
                    .thenBy { it.ref.version }
                    .thenBy(WikiEvidenceCandidate::chunkId),
            )

    private fun candidateOrder(
        queryTokens: Set<String>,
        selected: List<WikiEvidenceCandidate>,
        relaxedSemanticWeights: Boolean,
    ): Comparator<WikiEvidenceCandidate> = compareByDescending<WikiEvidenceCandidate> {
        marginalScore(it, selected, queryTokens, relaxedSemanticWeights)
    }.thenBy { it.ref.wikiId }
        .thenBy { it.ref.version }
        .thenBy { it.documentId }
        .thenBy { it.sectionId }
        .thenBy { it.hit.chunk.ordinal }
        .thenBy(WikiEvidenceCandidate::chunkId)

    private fun marginalScore(
        candidate: WikiEvidenceCandidate,
        selected: List<WikiEvidenceCandidate>,
        queryTokens: Set<String>,
        relaxedSemanticWeights: Boolean,
    ): Double {
        val selectedSections = selected.map { WikiRefSection(it.ref, it.sectionId) }.toSet()
        val selectedWikis = selected.map(WikiEvidenceCandidate::ref).toSet()
        val selectedConcepts = selected.flatMap { evidence ->
            evidence.hit.matches.mapNotNull(WikiSourceMatch::conceptKey)
        }.toSet()
        val candidateConcepts = candidate.hit.matches.mapNotNull(WikiSourceMatch::conceptKey).toSet()
        val lexicalCoverage = queryTokens.count { token -> candidate.hit.originalText.contains(token) }
        val channelCoverage = candidate.hit.matches.map(WikiSourceMatch::channel).toSet().size
        val semanticWeight = if (relaxedSemanticWeights) 0.06 else 0.16
        val temporalWeight = if (relaxedSemanticWeights) 0.03 else 0.12
        val duplicatePenalty = selected.maxOfOrNull { selectedCandidate ->
            textOverlap(candidate.hit.originalText, selectedCandidate.hit.originalText)
        } ?: 0.0
        return candidate.rankScore +
            lexicalCoverage * 0.18 +
            channelCoverage * 0.08 +
            (if (WikiRefSection(candidate.ref, candidate.sectionId) !in selectedSections) 0.16 else 0.0) +
            (if (candidate.ref !in selectedWikis) 0.14 else 0.0) +
            (if (candidateConcepts.isNotEmpty()) semanticWeight else 0.0) +
            (if (candidateConcepts.any { it in selectedConcepts }) semanticWeight else 0.0) +
            (if (candidate.hit.matches.any { it.channel == WikiSearchChannel.TEMPORAL }) temporalWeight else 0.0) -
            duplicatePenalty * 0.45
    }

    private fun improvesCoverage(
        candidate: WikiEvidenceCandidate,
        selected: List<WikiEvidenceCandidate>,
        queryTokens: Set<String>,
    ): Boolean {
        if (selected.none { it.ref == candidate.ref }) return true
        if (selected.none { it.ref == candidate.ref && it.sectionId == candidate.sectionId }) return true
        val selectedText = selected.joinToString(separator = "\n") { it.hit.originalText }
        return queryTokens.any { token -> candidate.hit.originalText.contains(token) && !selectedText.contains(token) }
    }

    private fun canAdd(
        candidate: WikiEvidenceCandidate,
        selected: List<WikiEvidenceCandidate>,
        sectionCounts: Map<WikiRefSection, Int>,
        selectedCharacters: Int,
    ): Boolean {
        if (selected.size >= HARD_MAX_EVIDENCE) return false
        if (selected.size >= DEFAULT_MAX_EVIDENCE) return false
        if (selectedCharacters + candidate.hit.originalText.length > HARD_CHARACTER_CAP) return false
        return (sectionCounts[WikiRefSection(candidate.ref, candidate.sectionId)] ?: 0) < MAX_PER_SECTION
    }

    private fun textOverlap(first: String, second: String): Double {
        val firstTokens = WikiSourceSearch.normalizedTokens(first).take(MAX_TEXT_OVERLAP_TOKENS).toSet()
        val secondTokens = WikiSourceSearch.normalizedTokens(second).take(MAX_TEXT_OVERLAP_TOKENS).toSet()
        if (firstTokens.isEmpty() || secondTokens.isEmpty()) return 0.0
        val shared = firstTokens.intersect(secondTokens).size
        return shared.toDouble() / minOf(firstTokens.size, secondTokens.size).toDouble()
    }

    private fun MutableMap<WikiRefSection, Int>.increment(candidate: WikiEvidenceCandidate) {
        val key = WikiRefSection(candidate.ref, candidate.sectionId)
        this[key] = (this[key] ?: 0) + 1
    }

    private data class WikiRefSection(
        val ref: WikiRef,
        val sectionId: String,
    )

    private companion object {
        const val DEFAULT_MAX_EVIDENCE = 10
        const val HARD_MAX_EVIDENCE = 12
        const val SOFT_CHARACTER_TARGET = 6_000
        const val HARD_CHARACTER_CAP = 12_000
        const val MAX_PER_SECTION = 3
        const val MIN_COMPARISON_EVIDENCE = 2
        const val MAX_COVERAGE_QUERY_TOKENS = 32
        const val MAX_TEXT_OVERLAP_TOKENS = 96
        val WIKI_REF_ORDER = compareBy<WikiRef> { it.wikiId }.thenBy { it.version }
        val matchOrder = compareBy<WikiSourceMatch>(WikiSourceMatch::channel)
            .thenBy(WikiSourceMatch::label)
            .thenBy { it.conceptKey.orEmpty() }
    }
}
