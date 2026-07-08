package com.harnessapk.websearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebSearchSettingsTest {
    @Test
    fun defaultSettingsUseJinaWithFiveResults() {
        val settings = WebSearchSettings()

        assertFalse(settings.enabled)
        assertEquals("jina", settings.defaultKeywordProviderId)
        assertEquals("jina", settings.defaultUrlProviderId)
        assertEquals(5, settings.maxResults)
    }

    @Test
    fun maxResultsAreClampedToMobileFriendlyRange() {
        assertEquals(1, normalizeWebSearchMaxResults(-10))
        assertEquals(5, normalizeWebSearchMaxResults(5))
        assertEquals(10, normalizeWebSearchMaxResults(30))
    }
}
