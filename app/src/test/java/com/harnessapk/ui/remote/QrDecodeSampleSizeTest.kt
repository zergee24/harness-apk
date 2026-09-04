package com.harnessapk.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class QrDecodeSampleSizeTest {
    @Test
    fun smallImagesDecodeAtFullResolution() {
        assertEquals(1, qrDecodeSampleSize(512, 512))
        assertEquals(1, qrDecodeSampleSize(1184, 1184))
        assertEquals(1, qrDecodeSampleSize(2048, 1024))
    }

    @Test
    fun oversizedImagesStepDownInPowersOfTwo() {
        assertEquals(2, qrDecodeSampleSize(4097, 4097))
        assertEquals(4, qrDecodeSampleSize(6000, 3000))
        assertEquals(32, qrDecodeSampleSize(40000, 40000))
    }

    @Test
    fun maxDimensionOverrideControlsSampling() {
        assertEquals(2, qrDecodeSampleSize(4000, 4000, maxDimension = 2048))
        assertEquals(4, qrDecodeSampleSize(4000, 4000, maxDimension = 1024))
    }
}
