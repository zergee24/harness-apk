package com.harnessapk.websearch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class JinaWebSearchClient(
    private val okHttpClient: OkHttpClient,
    private val apiHost: String = DEFAULT_SEARCH_HOST,
) {
    suspend fun searchKeywords(request: WebSearchRequest): WebSearchResult = withContext(Dispatchers.IO) {
        val normalizedQuery = request.query.trim()
        val httpRequest = Request.Builder()
            .url(searchUrl(normalizedQuery))
            .get()
            .build()
        val body = okHttpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) throw WebSearchException("联网搜索失败：HTTP ${response.code}")
            response.body.string()
        }
        val accessedAt = System.currentTimeMillis()
        val sources = parseJinaMarkdown(body, normalizedQuery, accessedAt)
            .take(normalizeWebSearchMaxResults(request.maxResults))
        WebSearchResult(
            query = normalizedQuery,
            providerId = "jina",
            capability = WebSearchCapability.SEARCH_KEYWORDS,
            inputs = listOf(normalizedQuery),
            results = sources,
        )
    }

    private fun searchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
        return "${apiHost.trimEnd('/')}/$encoded"
    }

    companion object {
        private const val DEFAULT_SEARCH_HOST = "https://s.jina.ai"
    }
}

class WebSearchException(message: String) : Exception(message)

internal fun parseJinaMarkdown(
    markdown: String,
    sourceInput: String,
    accessedAt: Long,
): List<WebSearchSource> {
    val blocks = mutableListOf<MutableList<String>>()
    markdown.lines().forEach { line ->
        if (isTitleLine(line) && blocks.lastOrNull()?.isNotEmpty() == true) {
            blocks.add(mutableListOf(line))
        } else {
            if (blocks.isEmpty()) blocks.add(mutableListOf())
            blocks.last().add(line)
        }
    }

    return blocks.mapNotNull { lines ->
        val title = lines.firstOrNull(::isTitleLine)
            ?.removeJinaPrefix("Title:")
            ?.trim()
            .orEmpty()
        val url = lines.firstOrNull(::isUrlLine)
            ?.removeJinaPrefix("URL Source:")
            ?.trim()
            .orEmpty()
        if (title.isBlank() || url.isBlank()) return@mapNotNull null
        val content = lines.dropWhile { !it.startsWith("Markdown Content:") }
            .drop(1)
            .joinToString("\n")
            .trim()
        WebSearchSource(
            id = "",
            title = title,
            url = url,
            domain = domainFromUrl(url),
            content = content,
            snippet = content.lineSequence().firstOrNull { it.isNotBlank() }?.take(220).orEmpty(),
            accessedAt = accessedAt,
            sourceInput = sourceInput,
        )
    }.mapIndexed { index, source -> source.copy(id = (index + 1).toString()) }
}

private fun isTitleLine(line: String): Boolean =
    line.trimStart().matches(Regex("""(?:\[\d+]\s*)?Title:\s*.+"""))

private fun isUrlLine(line: String): Boolean =
    line.trimStart().matches(Regex("""(?:\[\d+]\s*)?URL Source:\s*.+"""))

private fun String.removeJinaPrefix(prefix: String): String =
    trimStart().replace(Regex("""^\[\d+]\s*"""), "").removePrefix(prefix)
