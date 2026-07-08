package com.harnessapk.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCompressionPolicyTest {
    @Test
    fun scalesLandscapeImageToMaxEdge() {
        val size = ImageCompressionPolicy(maxEdgePx = 1600).scaledSize(width = 3200, height = 1800)

        assertEquals(1600, size.width)
        assertEquals(900, size.height)
    }

    @Test
    fun doesNotUpscaleSmallImages() {
        val size = ImageCompressionPolicy(maxEdgePx = 1600).scaledSize(width = 1200, height = 800)

        assertEquals(1200, size.width)
        assertEquals(800, size.height)
    }

    @Test
    fun compressesLargeUploadsOrOversizedDimensions() {
        val policy = ImageCompressionPolicy(maxEdgePx = 1600, maxRawBytes = 2_000_000)

        assertTrue(policy.shouldCompress(width = 3000, height = 1200, rawBytes = 500_000))
        assertTrue(policy.shouldCompress(width = 1200, height = 900, rawBytes = 2_100_000))
        assertFalse(policy.shouldCompress(width = 1200, height = 900, rawBytes = 1_000_000))
    }
}
