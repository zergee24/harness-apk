package com.harnessapk.websearch

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JinaWebSearchClientTest {
    @Test
    fun searchKeywordsMapsJinaMarkdownToSources() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                Title: First Result
                URL Source: https://example.com/a
                Markdown Content:
                Alpha summary about the topic.

                Title: Second Result
                URL Source: https://news.example.com/b
                Markdown Content:
                Beta summary with more context.
                """.trimIndent(),
            ),
        )
        server.start()
        val client = JinaWebSearchClient(
            okHttpClient = OkHttpClient.Builder().build(),
            apiHost = server.url("/").toString(),
        )

        val result = client.searchKeywords(WebSearchRequest(query = "GLM 最新", maxResults = 2))

        assertEquals("jina", result.providerId)
        assertEquals(WebSearchCapability.SEARCH_KEYWORDS, result.capability)
        assertEquals(2, result.results.size)
        assertEquals("First Result", result.results[0].title)
        assertEquals("example.com", result.results[0].domain)
        assertTrue(result.results[0].snippet.contains("Alpha"))
        assertTrue(server.takeRequest().path.orEmpty().contains("GLM"))
        server.shutdown()
    }
}
