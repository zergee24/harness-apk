package com.harnessapk.websearch

import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.provider.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelNativeWebSearchTest {
    @Test
    fun nativeWebSearchIsUsedOnlyWhenSessionAndSettingsAndProviderAllowIt() {
        val provider = providerProfile(NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS)
        val settings = WebSearchSettings(enabled = true)

        assertEquals(
            NativeWebSearchMode.OPENAI_WEB_SEARCH_OPTIONS,
            nativeWebSearchModeForRequest(
                query = "联网查一下今天的新闻",
                enabledForSession = true,
                settings = settings,
                provider = provider,
            ),
        )
        assertEquals(
            null,
            nativeWebSearchModeForRequest(
                query = "联网查一下今天的新闻",
                enabledForSession = false,
                settings = settings,
                provider = provider,
            ),
        )
        assertEquals(
            null,
            nativeWebSearchModeForRequest(
                query = "帮我润色这段话",
                enabledForSession = true,
                settings = settings,
                provider = provider,
            ),
        )
    }

    @Test
    fun jinaFallbackIsNeededWhenProviderDoesNotSupportNativeSearch() {
        assertEquals(
            true,
            shouldUseExternalWebSearch(
                query = "搜索最新 Android 版本",
                enabledForSession = true,
                settings = WebSearchSettings(enabled = true),
                nativeWebSearchMode = null,
            ),
        )
        assertEquals(
            false,
            shouldUseExternalWebSearch(
                query = "搜索最新 Android 版本",
                enabledForSession = true,
                settings = WebSearchSettings(enabled = true),
                nativeWebSearchMode = NativeWebSearchMode.ENABLE_SEARCH_BOOLEAN,
            ),
        )
    }

    private fun providerProfile(nativeWebSearchMode: NativeWebSearchMode): ProviderProfile = ProviderProfile(
        id = "provider",
        name = "OpenAI",
        baseUrl = "https://example.com/v1",
        defaultModel = "gpt-5.5",
        defaultVisionModel = null,
        supportsVision = false,
        nativeWebSearchMode = nativeWebSearchMode,
        enabled = true,
        hasApiKey = true,
        availableModels = listOf("gpt-5.5"),
    )
}
