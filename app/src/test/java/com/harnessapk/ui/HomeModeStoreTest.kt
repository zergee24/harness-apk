package com.harnessapk.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeModeStoreTest {
    @Test
    fun nullOrUnknownStoredValueFallsBackToLife() {
        assertEquals(MainMode.LIFE, migrateStoredMode(null))
        assertEquals(MainMode.LIFE, migrateStoredMode("UNKNOWN_LEGACY"))
    }

    @Test
    fun lifeModeRoundTrips() {
        assertEquals(MainMode.LIFE, migrateStoredMode("LIFE"))
    }

    @Test
    fun workModeRoundTrips() {
        assertEquals(MainMode.WORK, migrateStoredMode("WORK"))
    }

    @Test
    fun legacySessionMapsToLife() {
        assertEquals(MainMode.LIFE, migrateStoredMode("SESSION"))
    }

    @Test
    fun legacyProjectMapsToWork() {
        assertEquals(MainMode.WORK, migrateStoredMode("PROJECT"))
    }

    @Test
    fun legacyRemoteMapsToWork() {
        assertEquals(MainMode.WORK, migrateStoredMode("REMOTE"))
    }
}
