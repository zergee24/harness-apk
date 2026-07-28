package com.harnessapk.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteSettingsLogicTest {
    @Test
    fun qrDecodeSampleSizeBoundsLargeImages() {
        assertEquals(1, qrDecodeSampleSize(1600, 1200))
        assertEquals(2, qrDecodeSampleSize(4000, 3000))
        assertEquals(4, qrDecodeSampleSize(8000, 6000))
    }
}
