package com.harnessapk.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderCapabilityCatalogJsonTest {
    @Test
    fun parsesProviderCapabilityCatalogJson() {
        val catalog = parseProviderCapabilityCatalogJson(
            """
            {
              "schemaVersion": 1,
              "catalogVersion": "2026.07.09.1",
              "providers": [
                {
                  "providerId": "openai",
                  "displayName": "OpenAI",
                  "defaultModelId": "gpt-5.5",
                  "models": [
                    {
                      "modelId": "gpt-5.5",
                      "contextWindowTokens": 200000,
                      "maxOutputTokens": 32000,
                      "defaultCompressionThresholdPercent": 70,
                      "inputModalities": ["text", "image"],
                      "outputModalities": ["text"],
                      "supportsReasoningEffort": true,
                      "reasoningEffortOptions": ["low", "medium", "high", "xhigh"],
                      "defaultReasoningEffort": "high",
                      "webSearch": {"mode": "openai_web_search_options"},
                      "timeouts": {"readMs": 180000}
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val provider = catalog.providers.single()
        val model = provider.models.single()
        assertEquals("2026.07.09.1", catalog.catalogVersion)
        assertEquals("openai", provider.providerId)
        assertEquals("OpenAI", provider.displayName)
        assertEquals("gpt-5.5", provider.defaultModelId)
        assertEquals(200_000, model.contextWindowTokens)
        assertEquals(32_000, model.maxOutputTokens)
        assertEquals(listOf("text", "image"), model.inputModalities)
        assertEquals(listOf("low", "medium", "high", "xhigh"), model.reasoningEffortOptions)
        assertEquals("high", model.defaultReasoningEffort)
        assertEquals(NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS, model.webSearchMode)
        assertEquals(180_000L, model.readTimeoutMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompatibleProviderCapabilityCatalogSchema() {
        parseProviderCapabilityCatalogJson(
            """
            {
              "schemaVersion": 2,
              "catalogVersion": "future",
              "providers": []
            }
            """.trimIndent(),
        )
    }
}
