package com.harnessapk.provider

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderCapabilityCatalogClientTest {
    @Test
    fun fetchesAndParsesRemoteProviderCapabilityCatalog() = runTest {
        val server = MockWebServer()
        val body = """
            {
              "schemaVersion": 1,
              "catalogVersion": "remote",
              "providers": [
                {
                  "providerId": "kimi",
                  "displayName": "Kimi",
                  "defaultModelId": "kimi-k2.7-code",
                  "models": [
                    {"modelId": "kimi-k2.7-code", "contextWindowTokens": 256000}
                  ]
                }
              ]
            }
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(body),
        )
        server.start()

        val fetched = ProviderCapabilityCatalogClient(OkHttpClient(), Json { ignoreUnknownKeys = true })
            .fetchDocument(server.url("/provider-capabilities.json").toString())

        assertEquals(body, fetched.rawJson)
        assertEquals("remote", fetched.catalog.catalogVersion)
        assertEquals("kimi", fetched.catalog.providers.single().providerId)
        assertEquals(256_000, fetched.catalog.providers.single().models.single().contextWindowTokens)
        server.shutdown()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRemoteProviderCapabilityCatalogWhenSha256DoesNotMatch() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"schemaVersion":1,"catalogVersion":"remote","providers":[]}"""),
        )
        server.start()

        ProviderCapabilityCatalogClient(OkHttpClient(), Json { ignoreUnknownKeys = true })
            .fetch(
                url = server.url("/provider-capabilities.json").toString(),
                expectedSha256 = "0000",
            )
    }
}
