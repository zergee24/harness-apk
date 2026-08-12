package com.harnessapk.projectsearch

class ProjectEvidenceSelector(
    private val maxEvidence: Int = 6,
    private val maxTotalChars: Int = 8_000,
    private val maxPerFile: Int = 2,
) {
    fun select(query: String, candidates: List<ProjectSearchDocument>): List<ProjectSearchDocument> {
        val needsAuthoritativeFact = AUTHORITATIVE_QUERY.any(query::contains)
        val eligible = if (needsAuthoritativeFact) {
            candidates.filter { it.authority != ProjectSourceAuthority.ASSISTANT_PROPOSAL }
        } else {
            candidates
        }
        val counts = mutableMapOf<String, Int>()
        val selected = mutableListOf<ProjectSearchDocument>()
        val selectedSourceTypes = mutableSetOf<ProjectSourceType>()
        var chars = 0
        val remaining = eligible.sortedWith(
            compareByDescending<ProjectSearchDocument> { authorityWeight(it.authority) + it.score }
                .thenByDescending(ProjectSearchDocument::sourceUpdatedAt)
                .thenBy(ProjectSearchDocument::documentKey),
        ).toMutableList()
        while (selected.size < maxEvidence) {
            val hasUnseenSourceType = remaining.any { candidate ->
                candidate.sourceType !in selectedSourceTypes && canSelect(candidate, counts, chars)
            }
            val nextIndex = remaining.indexOfFirst { candidate ->
                (!hasUnseenSourceType || candidate.sourceType !in selectedSourceTypes) &&
                    canSelect(candidate, counts, chars)
            }
            if (nextIndex < 0) break
            val candidate = remaining.removeAt(nextIndex)
            val fileKey = candidate.relativePath ?: candidate.sourceKey
            selected += candidate
            selectedSourceTypes += candidate.sourceType
            counts[fileKey] = counts.getOrDefault(fileKey, 0) + 1
            chars += candidate.injectionCodePoints
        }
        return selected
    }

    private fun canSelect(
        candidate: ProjectSearchDocument,
        counts: Map<String, Int>,
        usedChars: Int,
    ): Boolean {
        val fileKey = candidate.relativePath ?: candidate.sourceKey
        return counts.getOrDefault(fileKey, 0) < maxPerFile &&
            usedChars + candidate.injectionCodePoints <= maxTotalChars
    }

    private fun authorityWeight(authority: ProjectSourceAuthority): Double = when (authority) {
        ProjectSourceAuthority.REVIEWED_ARTIFACT -> 4.0
        ProjectSourceAuthority.USER_STATED -> 3.5
        ProjectSourceAuthority.VERIFIED_RUN -> 3.0
        ProjectSourceAuthority.ASSISTANT_PROPOSAL -> 1.0
    }

    private companion object {
        val AUTHORITATIVE_QUERY = listOf("决定", "决策", "完成", "做到哪", "进度", "现状", "当前状态")
    }
}
