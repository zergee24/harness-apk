package com.harnessapk.ui.wiki

import com.harnessapk.wiki.WikiRef
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

object WikiRoutes {
    const val Library = "wiki-library"
    const val BrowserPattern = "wiki/{wikiId}/{version}"
    const val SearchPattern = "wiki/{wikiId}/{version}/search"
    const val SourcePattern = "wiki/{wikiId}/{version}/source/{chunkId}"
    const val CitationPattern = "wiki-citation/{citationId}"

    fun browser(ref: WikiRef): String = "wiki/${encodeValidatedRef(ref)}"

    fun search(ref: WikiRef): String = "${browser(ref)}/search"

    fun source(ref: WikiRef, chunkId: String): String {
        require(isValidChunkId(chunkId)) { "chunkId 无效" }
        return "${browser(ref)}/source/${encode(chunkId)}"
    }

    fun citation(citationId: String): String {
        require(isValidCitationId(citationId)) { "引用标识无效" }
        return "wiki-citation/${encode(citationId)}"
    }

    fun decodeRef(wikiId: String?, version: String?): WikiRef? {
        val decodedId = wikiId?.let(::decode) ?: return null
        val decodedVersion = version?.toIntOrNull() ?: return null
        return decodedId.takeIf(::isValidWikiId)
            ?.takeIf { decodedVersion > 0 }
            ?.let { WikiRef(it, decodedVersion) }
    }

    fun decodeChunkId(chunkId: String?): String? = chunkId
        ?.let(::decode)
        ?.takeIf(::isValidChunkId)

    fun decodeCitationId(citationId: String?): String? = citationId
        ?.let(::decode)
        ?.takeIf(::isValidCitationId)
        ?.let { UUID.fromString(it).toString() }

    private fun encodeValidatedRef(ref: WikiRef): String {
        require(isValidWikiId(ref.wikiId) && ref.version > 0) { "Wiki 路由参数无效" }
        return "${encode(ref.wikiId)}/${ref.version}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
        .replace("+", "%20")

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    }.getOrDefault("")

    private fun isValidWikiId(value: String): Boolean = WIKI_ID.matches(value)

    private fun isValidChunkId(value: String): Boolean = value.isNotBlank() &&
        value.length <= MAX_CHUNK_ID_LENGTH &&
        value.none(Char::isISOControl)

    private fun isValidCitationId(value: String): Boolean = runCatching { UUID.fromString(value) }
        .getOrNull()
        ?.toString()
        ?.equals(value, ignoreCase = true)
        ?: false

    private val WIKI_ID = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
    private const val MAX_CHUNK_ID_LENGTH = 512
}
