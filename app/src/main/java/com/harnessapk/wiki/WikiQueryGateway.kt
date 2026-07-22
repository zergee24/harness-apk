package com.harnessapk.wiki

class WikiQueryGateway(
    private val contentStore: WikiContentStore,
) {
    suspend fun searchWiki(
        authorization: WikiQueryAuthorization,
        request: WikiSearchRequest,
    ): WikiSearchResult {
        authorization.requireAllowed(request.ref)
        validateWikiRef(request.ref)
        WikiSourceSearch.validateQuery(request.query)
        require(request.limit in 1..MAX_GATEWAY_SEARCH_RESULTS) {
            "授权 Wiki 检索数量必须在 1 到 $MAX_GATEWAY_SEARCH_RESULTS 之间"
        }
        val hits = contentStore.searchSources(request.ref, request.query, request.limit)
            .mapNotNull { hit ->
                val filteredMatches = hit.matches.filter { match -> match.channel == request.channel }
                hit.takeIf { filteredMatches.isNotEmpty() }?.copy(matches = filteredMatches)
            }
            .take(request.limit)
        return WikiSearchResult(
            ref = request.ref,
            query = request.query,
            channel = request.channel,
            hits = hits,
        )
    }

    suspend fun readSource(
        authorization: WikiQueryAuthorization,
        request: WikiReadRequest,
    ): WikiReadResult {
        authorization.requireAllowed(request.ref)
        validateWikiRef(request.ref)
        requireIdentifier(request.chunkId, "原文片段标识")
        val source = contentStore.findChunk(request.ref, request.chunkId)
            ?: throw WikiSourceNotFoundException(request.ref, request.chunkId)
        if (!request.includeAdjacent) {
            return WikiReadResult(request.ref, source, previous = null, next = null)
        }
        val neighbors = contentStore.chunkNeighbors(request.ref, source.id)
        return WikiReadResult(
            ref = request.ref,
            source = source,
            previous = neighbors?.previous?.takeIf { it.sectionId == source.sectionId },
            next = neighbors?.next?.takeIf { it.sectionId == source.sectionId },
        )
    }

    suspend fun describeSource(
        authorization: WikiQueryAuthorization,
        ref: WikiRef,
        source: WikiChunk,
    ): WikiSourceDescriptor {
        authorization.requireAllowed(ref)
        val section = contentStore.findSection(ref, source.sectionId)
        val document = section?.let { contentStore.findDocument(ref, it.documentId) }
        return WikiSourceDescriptor(
            documentId = section?.documentId ?: source.sectionId,
            sourceTitle = document?.title ?: ref.wikiId,
            sectionPath = section?.path ?: source.sectionId,
        )
    }

    private companion object {
        const val MAX_GATEWAY_SEARCH_RESULTS = 50
    }
}
