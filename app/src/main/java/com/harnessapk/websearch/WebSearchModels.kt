package com.harnessapk.websearch

import java.net.URI

data class WebSearchSettings(
    val enabled: Boolean = false,
    val defaultKeywordProviderId: String = "jina",
    val defaultUrlProviderId: String = "jina",
    val maxResults: Int = 5,
)

fun normalizeWebSearchMaxResults(value: Int): Int = value.coerceIn(1, 10)

enum class WebSearchCapability { SEARCH_KEYWORDS, FETCH_URLS }

data class WebSearchRequest(
    val query: String,
    val maxResults: Int,
)

data class WebSearchResult(
    val query: String,
    val providerId: String,
    val capability: WebSearchCapability,
    val inputs: List<String>,
    val results: List<WebSearchSource>,
    val partial: Boolean = false,
    val errorMessage: String? = null,
)

data class WebSearchSource(
    val id: String,
    val title: String,
    val url: String,
    val domain: String,
    val content: String,
    val snippet: String,
    val accessedAt: Long,
    val sourceInput: String,
)

data class WebSearchContext(
    val results: WebSearchResult,
)

fun domainFromUrl(url: String): String =
    runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")

fun WebSearchContext.toVisibleSourcesMarkdown(): String {
    if (results.results.isEmpty()) return ""
    return buildString {
        appendLine()
        appendLine()
        appendLine("---")
        appendLine("联网来源")
        results.results.forEachIndexed { index, source ->
            val number = index + 1
            appendLine("[$number] [${source.title}](${source.url})")
            if (source.snippet.isNotBlank()) appendLine("摘要：${source.snippet}")
        }
    }.trimEnd()
}
