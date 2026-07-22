package com.harnessapk.wiki

class WikiAuthorizationException(
    val ref: WikiRef,
) : SecurityException("本轮会话未授权访问 Wiki：${ref.wikiId}@${ref.version}")

class WikiSourceNotFoundException(
    val ref: WikiRef,
    val chunkId: String,
) : NoSuchElementException("Wiki 原文不存在：${ref.wikiId}@${ref.version}/$chunkId")

class WikiQueryAuthorization(refs: Collection<WikiRef>) {
    private val allowedRefs = canonicalWikiScope(refs.toList()).toSet()

    fun allows(ref: WikiRef): Boolean = ref in allowedRefs

    fun refs(): List<WikiRef> = allowedRefs.sortedWith(compareBy<WikiRef> { it.wikiId }.thenBy { it.version })

    fun requireAllowed(ref: WikiRef) {
        if (!allows(ref)) throw WikiAuthorizationException(ref)
    }
}

data class WikiSearchRequest(
    val ref: WikiRef,
    val query: String,
    val channel: WikiSearchChannel,
    val limit: Int,
)

data class WikiSearchResult(
    val ref: WikiRef,
    val query: String,
    val channel: WikiSearchChannel,
    val hits: List<WikiSourceHit>,
)

data class WikiReadRequest(
    val ref: WikiRef,
    val chunkId: String,
    val includeAdjacent: Boolean = false,
)

data class WikiReadResult(
    val ref: WikiRef,
    val source: WikiChunk,
    val previous: WikiChunk?,
    val next: WikiChunk?,
)

enum class WikiTurnIntentMode {
    AUTO,
    ONLY_NAMED,
    COMPARE_NAMED,
}

data class WikiTurnAlias(
    val wikiId: String,
    val title: String,
    val displayAliases: Set<String> = emptySet(),
)

data class WikiTurnIntent(
    val mode: WikiTurnIntentMode,
    val namedWikiIds: Set<String>,
    val compareRequested: Boolean,
    val unavailableNamedWikiIds: Set<String> = emptySet(),
)
