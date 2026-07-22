package com.harnessapk.session

import java.security.MessageDigest
import java.util.UUID

data class WikiMarkdownCitation(
    val citationId: String,
    val sourceMessageId: String,
    val displayOrdinal: Int,
    val wikiId: String,
    val wikiVersion: Int,
    val wikiTitle: String,
    val sourceTitle: String,
    val sectionPath: String,
    val locatorLabel: String,
    val originalTextSnapshot: String,
    val originalTextSha256: String,
)

class WikiMarkdownCitationException(message: String) : IllegalArgumentException(message)

class WikiMarkdownCitationSet(citations: List<WikiMarkdownCitation>) {
    val citations: List<WikiMarkdownCitation> = citations.toList()

    private val citationsById: Map<String, WikiMarkdownCitation>
    private val labelsByCitationId: Map<String, String>

    init {
        this.citations.forEach(::validateWikiMarkdownCitation)
        require(this.citations.map(WikiMarkdownCitation::citationId).distinct().size == this.citations.size) {
            "Wiki 引用不能包含重复标识"
        }
        val baseLabels = this.citations.associate { citation ->
            citation.citationId to citation.footnoteLabel()
        }
        val labels = this.citations.associate { citation ->
            val baseLabel = baseLabels.getValue(citation.citationId)
            val label = if (baseLabels.values.count { it == baseLabel } == 1) {
                baseLabel
            } else {
                "$baseLabel-${sha256(citation.citationId).take(COLLISION_HASH_LENGTH)}"
            }
            citation.citationId to label
        }
        require(labels.values.distinct().size == labels.size) { "Wiki 脚注标签冲突" }
        citationsById = this.citations.associateBy(WikiMarkdownCitation::citationId)
        labelsByCitationId = labels
    }

    fun citation(citationId: String): WikiMarkdownCitation? = citationsById[citationId]

    fun footnoteLabel(citation: WikiMarkdownCitation): String =
        requireNotNull(labelsByCitationId[citation.citationId]) { "引用不属于当前脚注集合" }

    fun footnoteLabel(citationId: String): String? = labelsByCitationId[citationId]

    companion object {
        val EMPTY = WikiMarkdownCitationSet(emptyList())
    }
}

internal fun WikiMarkdownCitation.footnoteLabel(): String =
    "hwiki-${sourceMessageShortId(sourceMessageId)}-$displayOrdinal"

private fun validateWikiMarkdownCitation(citation: WikiMarkdownCitation) {
    val canonicalCitationId = runCatching { UUID.fromString(citation.citationId).toString() }.getOrNull()
    if (canonicalCitationId != citation.citationId) {
        throw WikiMarkdownCitationException("Wiki 引用标识必须是小写 UUID")
    }
    if (citation.sourceMessageId.isBlank() || citation.sourceMessageId.length > MAX_SOURCE_MESSAGE_ID_LENGTH) {
        throw WikiMarkdownCitationException("来源消息标识无效")
    }
    if (citation.displayOrdinal <= 0 || citation.wikiVersion <= 0) {
        throw WikiMarkdownCitationException("Wiki 引用序号或版本无效")
    }
    if (!WIKI_ID.matches(citation.wikiId)) throw WikiMarkdownCitationException("Wiki 标识无效")
    listOf(
        citation.wikiTitle,
        citation.sourceTitle,
        citation.sectionPath,
        citation.locatorLabel,
        citation.originalTextSnapshot,
    ).forEach { value ->
        if (value.isBlank()) throw WikiMarkdownCitationException("Wiki 引用展示信息不能为空")
    }
    if (!SHA_256.matches(citation.originalTextSha256)) {
        throw WikiMarkdownCitationException("Wiki 原文哈希无效")
    }
}

private fun sourceMessageShortId(sourceMessageId: String): String {
    val normalized = sourceMessageId.lowercase().filter { it in 'a'..'z' || it.isDigit() }
    return normalized.take(MESSAGE_SHORT_ID_LENGTH).takeIf { it.length == MESSAGE_SHORT_ID_LENGTH }
        ?: sha256(sourceMessageId).take(MESSAGE_SHORT_ID_LENGTH)
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private const val MAX_SOURCE_MESSAGE_ID_LENGTH = 256
private const val MESSAGE_SHORT_ID_LENGTH = 8
private const val COLLISION_HASH_LENGTH = 12
private val WIKI_ID = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
private val SHA_256 = Regex("[a-f0-9]{64}")
