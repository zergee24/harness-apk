package com.harnessapk.projectsearch

import com.harnessapk.search.LocalSearchTokenizer
import java.text.Normalizer
import java.util.Locale

fun interface ProjectSearchCandidateSource {
    /** Implementations must apply projectId in the storage query itself. */
    fun searchProject(projectId: String, query: String, limit: Int): List<ProjectSearchDocument>
}

enum class ProjectRetrievalStatus {
    MATCH,
    NO_MATCH,
    INVALID_PROJECT,
}

data class ProjectRetrievalResult(
    val status: ProjectRetrievalStatus,
    val evidence: List<ProjectSearchDocument>,
) {
    val totalCodePoints: Int
        get() = evidence.sumOf { it.text.codePointCount(0, it.text.length) }
}

class ProjectRetrievalRepository(
    private val candidateSource: ProjectSearchCandidateSource,
    private val maxCandidates: Int = 10_000,
    private val maxEvidence: Int = 6,
    private val maxPerFile: Int = 2,
    private val maxTotalCodePoints: Int = 8_000,
) {
    init {
        require(maxCandidates > 0)
        require(maxEvidence > 0)
        require(maxPerFile > 0)
        require(maxTotalCodePoints > 0)
    }

    fun retrieve(projectId: String?, query: String): ProjectRetrievalResult {
        val scopedProjectId = projectId?.trim().orEmpty()
        if (scopedProjectId.isEmpty()) return empty(ProjectRetrievalStatus.INVALID_PROJECT)
        val queryTokens = LocalSearchTokenizer.tokens(query).toSet()
        if (queryTokens.isEmpty()) return empty(ProjectRetrievalStatus.NO_MATCH)

        val authoritativeIntent = AUTHORITATIVE_INTENT.any(query::contains)
        val ranked = candidateSource.searchProject(scopedProjectId, query, maxCandidates)
            .asSequence()
            .filter { it.projectId == scopedProjectId }
            .filterNot {
                authoritativeIntent && it.authority == ProjectSourceAuthority.ASSISTANT_PROPOSAL
            }
            .mapNotNull { document ->
                score(document, query, queryTokens, authoritativeIntent)
                    ?.let { RankedDocument(document, it) }
            }
            .sortedWith(
                compareByDescending<RankedDocument>(RankedDocument::score)
                    .thenByDescending { authorityWeight(it.document.authority) }
                    .thenByDescending { it.document.sourceUpdatedAt }
                    .thenBy { it.document.documentKey },
            )
            .toList()

        val perFile = mutableMapOf<String, Int>()
        val selected = mutableListOf<ProjectSearchDocument>()
        var codePoints = 0
        ranked.forEach { rankedDocument ->
            if (selected.size >= maxEvidence) return@forEach
            val document = rankedDocument.document
            val fileKey = document.relativePath ?: document.sourceKey
            if (perFile.getOrDefault(fileKey, 0) >= maxPerFile) return@forEach
            val documentPoints = document.text.codePointCount(0, document.text.length)
            if (codePoints + documentPoints > maxTotalCodePoints) return@forEach
            selected += document
            codePoints += documentPoints
            perFile[fileKey] = perFile.getOrDefault(fileKey, 0) + 1
        }

        return if (selected.isEmpty()) {
            empty(ProjectRetrievalStatus.NO_MATCH)
        } else {
            ProjectRetrievalResult(ProjectRetrievalStatus.MATCH, selected)
        }
    }

    private fun score(
        document: ProjectSearchDocument,
        query: String,
        queryTokens: Set<String>,
        authoritativeIntent: Boolean,
    ): Double? {
        val titleAndPath = listOfNotNull(document.title, document.relativePath, document.headingPath)
            .joinToString(" ")
        val normalizedTitlePath = normalize(titleAndPath)
        val normalizedBody = normalize(document.searchableText.ifBlank { document.text })
        val overlap = queryTokens.count { normalize(it) in "$normalizedTitlePath $normalizedBody" }
        if (overlap == 0) return null

        val normalizedQuery = normalize(query)
        val coverage = overlap.toDouble() / queryTokens.size
        val titleCoverage = queryTokens.count { normalize(it) in normalizedTitlePath }.toDouble() / queryTokens.size
        val exactTitleOrPath = normalizedQuery.isNotEmpty() && normalizedQuery in normalizedTitlePath
        val exactBody = normalizedQuery.isNotEmpty() && normalizedQuery in normalizedBody
        val matchedIntent = authoritativeIntent && AUTHORITATIVE_INTENT.any { intent ->
            intent in query && intent in document.text
        }
        if (coverage < MIN_TOKEN_COVERAGE && !exactTitleOrPath && !exactBody && !matchedIntent) return null
        val contextBoost = if (
            authoritativeIntent && (
                document.sourceType == ProjectSourceType.CONTEXT ||
                    document.relativePath?.substringAfterLast('/') == "context.md"
                )
        ) {
            2.0
        } else {
            0.0
        }
        return document.score +
            coverage * 8.0 +
            titleCoverage * 4.0 +
            authorityWeight(document.authority) +
            contextBoost +
            (if (exactTitleOrPath) 8.0 else 0.0) +
            (if (exactBody) 4.0 else 0.0)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE, "")

    private fun authorityWeight(authority: ProjectSourceAuthority): Double = when (authority) {
        ProjectSourceAuthority.REVIEWED_ARTIFACT -> 4.0
        ProjectSourceAuthority.USER_STATED -> 3.5
        ProjectSourceAuthority.VERIFIED_RUN -> 3.0
        ProjectSourceAuthority.ASSISTANT_PROPOSAL -> 1.0
    }

    private fun empty(status: ProjectRetrievalStatus) = ProjectRetrievalResult(status, emptyList())

    private data class RankedDocument(
        val document: ProjectSearchDocument,
        val score: Double,
    )

    private companion object {
        val AUTHORITATIVE_INTENT = listOf(
            "决定",
            "决策",
            "完成",
            "做到哪",
            "进度",
            "现状",
            "当前状态",
            "待办",
        )
        val WHITESPACE = Regex("\\s+")
        const val MIN_TOKEN_COVERAGE = 0.30
    }
}
