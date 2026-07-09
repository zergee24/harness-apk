package com.harnessapk.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class AppHttpClientsTest {
    @Test
    fun chatClientTimesOutAfterThreeMinutes() {
        val client = AppHttpClients.chat()

        assertEquals(TimeUnit.SECONDS.toMillis(30).toInt(), client.connectTimeoutMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(3).toInt(), client.readTimeoutMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(3).toInt(), client.callTimeoutMillis)
    }

    @Test
    fun updateClientUsesSeparateDownloadTimeouts() {
        val client = AppHttpClients.updates()

        assertEquals(TimeUnit.SECONDS.toMillis(30).toInt(), client.connectTimeoutMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(2).toInt(), client.readTimeoutMillis)
    }

    @Test
    fun webSearchClientUsesShortMobileTimeouts() {
        val client = AppHttpClients.webSearch()

        assertEquals(TimeUnit.SECONDS.toMillis(20).toInt(), client.connectTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(45).toInt(), client.readTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(60).toInt(), client.callTimeoutMillis)
    }

    @Test
    fun providerCatalogClientUsesThreeSecondTimeouts() {
        val client = AppHttpClients.providerCatalog()

        assertEquals(TimeUnit.SECONDS.toMillis(3).toInt(), client.connectTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(3).toInt(), client.readTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(3).toInt(), client.callTimeoutMillis)
    }
}
