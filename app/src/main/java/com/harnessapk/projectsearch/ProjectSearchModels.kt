package com.harnessapk.projectsearch

enum class ProjectSourceType {
    CONTEXT,
    MARKDOWN,
    PROJECT_MESSAGE,
    RUN_EVIDENCE,
}

enum class ProjectSourceAuthority {
    REVIEWED_ARTIFACT,
    USER_STATED,
    VERIFIED_RUN,
    ASSISTANT_PROPOSAL,
}

data class ProjectSearchDocument(
    val documentKey: String,
    val projectId: String,
    val sourceType: ProjectSourceType,
    val authority: ProjectSourceAuthority,
    val sourceKey: String,
    val conversationId: String?,
    val messageId: String?,
    val relativePath: String?,
    val title: String,
    val headingPath: String,
    val ordinal: Int,
    val text: String,
    val searchableText: String,
    val sourceSha256: String,
    val gitBlobId: String?,
    val sourceUpdatedAt: Long,
    val indexedAt: Long,
    val score: Double = 0.0,
) {
    /** Exact prompt budget, including provenance fields that accompany the excerpt. */
    val injectionCodePoints: Int
        get() = listOfNotNull(
            title,
            relativePath,
            headingPath,
            sourceSha256,
            text,
        ).sumOf { value -> value.codePointCount(0, value.length) }
}

data class MarkdownSemanticChunk(
    val headingPath: String,
    val ordinal: Int,
    val text: String,
)

data class ProjectCitationVerification(
    val text: String,
    val validTokens: List<String>,
    val unknownTokens: List<String>,
)
