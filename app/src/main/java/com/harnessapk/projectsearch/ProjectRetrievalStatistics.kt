package com.harnessapk.projectsearch

import kotlin.math.ceil

object ProjectRetrievalStatistics {
    fun p95Millis(elapsedNanos: List<Long>): Double {
        require(elapsedNanos.isNotEmpty()) { "at least one timing sample is required" }
        require(elapsedNanos.all { it >= 0 }) { "timing samples must be non-negative" }
        val ordered = elapsedNanos.sorted()
        val nearestRank = ceil(ordered.size * 0.95).toInt().coerceAtLeast(1)
        return ordered[nearestRank - 1] / NANOS_PER_MILLISECOND
    }

    private const val NANOS_PER_MILLISECOND = 1_000_000.0
}
