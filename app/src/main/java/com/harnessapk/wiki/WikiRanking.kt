package com.harnessapk.wiki

data class WikiRrfRank(
    val id: String,
    val score: Double,
)

fun reciprocalRankFusion(
    rankings: List<List<String>>,
    k: Int = WikiSourceSearch.RRF_K,
): List<WikiRrfRank> {
    require(k > 0) { "RRF k 必须大于 0" }
    val scores = linkedMapOf<String, Double>()
    rankings.forEach { ranking ->
        val seen = mutableSetOf<String>()
        ranking.forEachIndexed { index, id ->
            if (id.isBlank() || !seen.add(id)) return@forEachIndexed
            scores[id] = (scores[id] ?: 0.0) + 1.0 / (k + index + 1).toDouble()
        }
    }
    return scores.entries
        .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
        .map { (id, score) -> WikiRrfRank(id, score) }
}
