package com.harnessapk.wiki

data class WikiDocument(
    val id: String,
    val title: String,
    val responsibility: String,
    val edition: String,
    val language: String,
    val rights: String,
    val sourceHash: String,
    val ordinal: Int,
    val metadataJson: String,
)

data class WikiSection(
    val id: String,
    val documentId: String,
    val parentSectionId: String?,
    val title: String,
    val path: String,
    val ordinal: Int,
    val metadataJson: String,
)

data class WikiSourceLocator(
    val id: String,
    val chunkId: String,
    val label: String,
    val locatorJson: String,
)

data class WikiChunk(
    val id: String,
    val sectionId: String,
    val ordinal: Int,
    val originalText: String,
    val locator: WikiSourceLocator,
    val contentHash: String,
)

data class WikiContentStats(
    val documentCount: Int,
    val sectionCount: Int,
    val sourceChunkCount: Int,
)

data class WikiChunkNeighbors(
    val previous: WikiChunk?,
    val next: WikiChunk?,
)

data class WikiSummary(
    val id: String,
    val ownerType: String,
    val ownerId: String,
    val level: String,
    val text: String,
)

data class WikiTerm(
    val id: String,
    val conceptKey: String,
    val canonicalText: String,
    val kind: String,
    val confidence: Double,
    val metadataJson: String,
)

data class WikiAlias(
    val id: String,
    val termId: String,
    val text: String,
    val normalizedText: String,
    val confidence: Double,
)

data class WikiAnnotation(
    val id: String,
    val ownerType: String,
    val ownerId: String,
    val kind: String,
    val valueJson: String,
    val confidence: Double,
)

data class WikiLink(
    val id: String,
    val sourceType: String,
    val sourceId: String,
    val targetNamespace: String,
    val targetType: String,
    val targetId: String,
    val kind: String,
    val confidence: Double,
    val metadataJson: String,
)

enum class WikiSearchChannel {
    ORIGINAL,
    NORMALIZED,
    SUMMARY,
    TERM,
    TEMPORAL,
}

data class WikiSourceMatch(
    val channel: WikiSearchChannel,
    val label: String,
    val conceptKey: String? = null,
)

data class WikiSearchCandidate(
    val chunkId: String,
    val match: WikiSourceMatch,
)

data class WikiRankedSource(
    val chunkId: String,
    val matches: List<WikiSourceMatch>,
)

data class WikiSourceHit(
    val chunk: WikiChunk,
    val matches: List<WikiSourceMatch>,
) {
    val chunkId: String get() = chunk.id
    val originalText: String get() = chunk.originalText
    val locator: WikiSourceLocator get() = chunk.locator
}

fun interface WikiVersionHealthReporter {
    suspend fun markInvalid(ref: WikiRef, reason: String)
}

class WikiContentUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
