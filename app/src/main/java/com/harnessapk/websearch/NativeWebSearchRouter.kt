package com.harnessapk.websearch

import com.harnessapk.provider.NativeWebSearchMode
import com.harnessapk.provider.ProviderProfile

fun nativeWebSearchModeForRequest(
    query: String,
    enabledForSession: Boolean,
    settings: WebSearchSettings,
    provider: ProviderProfile?,
): NativeWebSearchMode? {
    if (!enabledForSession || !settings.enabled || !shouldUseWebSearch(query)) return null
    return provider
        ?.nativeWebSearchMode
        ?.takeIf { it != NativeWebSearchMode.DISABLED }
}

fun shouldUseExternalWebSearch(
    query: String,
    enabledForSession: Boolean,
    settings: WebSearchSettings,
    nativeWebSearchMode: NativeWebSearchMode?,
): Boolean = enabledForSession &&
    settings.enabled &&
    nativeWebSearchMode == null &&
    shouldUseWebSearch(query)
