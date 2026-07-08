package com.harnessapk.chat

data class ImageSize(val width: Int, val height: Int)

data class ImageCompressionPolicy(
    val maxEdgePx: Int = 1800,
    val jpegQuality: Int = 82,
    val maxRawBytes: Int = 2_500_000,
) {
    fun scaledSize(width: Int, height: Int): ImageSize {
        val maxInputEdge = maxOf(width, height)
        if (maxInputEdge <= maxEdgePx) return ImageSize(width, height)

        val scale = maxEdgePx.toDouble() / maxInputEdge.toDouble()
        return ImageSize(
            width = (width * scale).toInt().coerceAtLeast(1),
            height = (height * scale).toInt().coerceAtLeast(1),
        )
    }

    fun shouldCompress(width: Int, height: Int, rawBytes: Int): Boolean =
        maxOf(width, height) > maxEdgePx || rawBytes > maxRawBytes
}
