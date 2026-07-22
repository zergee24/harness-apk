package com.harnessapk.session

import com.harnessapk.wiki.WikiRef

/** Immutable evidence coverage derived from the retrieval run that produced a chat answer. */
data class WikiEvidenceCoverage(
    val requestedComparisonRefs: Set<WikiRef> = emptySet(),
    val queriedRefs: Set<WikiRef> = emptySet(),
    val verifiedCitationCounts: Map<WikiRef, Int> = emptyMap(),
    val missingRefs: Set<WikiRef> = emptySet(),
) {
    val hasComparisonContext: Boolean
        get() = requestedComparisonRefs.isNotEmpty()

    val missingComparisonRefs: Set<WikiRef>
        get() = missingRefs.intersect(requestedComparisonRefs)

    companion object {
        val NONE = WikiEvidenceCoverage()
    }
}
