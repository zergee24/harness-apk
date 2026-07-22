package com.harnessapk.wiki

data class WikiRuntimeContext(
    val scope: List<WikiRef>,
    val intent: WikiTurnIntent,
    val retrieval: WikiRetrievalResult?,
    val systemContext: String?,
    val retrievalElapsedMillis: Long = 0L,
) {
    val hasEvidence: Boolean get() = retrieval?.evidence?.isNotEmpty() == true
}
