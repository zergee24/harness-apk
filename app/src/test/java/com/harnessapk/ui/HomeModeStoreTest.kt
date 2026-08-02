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
    fun meModeRoundTrips() {
        assertEquals(MainMode.ME, migrateStoredMode("ME"))
    }
}
