package com.harnessapk.session

import com.harnessapk.storage.ConversationWikiDao
import com.harnessapk.storage.MessageWikiCitationEntity
import com.harnessapk.storage.MessageWikiUsageEntity
import com.harnessapk.storage.WikiRetrievalRunEntity
import com.harnessapk.wiki.WikiRef
import com.harnessapk.wiki.decodeWikiScopeSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class WikiMarkdownSourceContext(
    val citations: WikiMarkdownCitationSet,
    val coverage: WikiEvidenceCoverage,
)

/** Rehydrates immutable Wiki evidence for Markdown planning from persisted message records. */
class WikiMarkdownContextRepository(
    private val findRun: suspend (String) -> WikiRetrievalRunEntity?,
    private val listUsages: suspend (String) -> List<MessageWikiUsageEntity>,
    private val listCitations: suspend (String) -> List<MessageWikiCitationEntity>,
) {
    constructor(dao: ConversationWikiDao) : this(
        findRun = dao::findRun,
        listUsages = dao::listUsagesForMessage,
        listCitations = dao::listCitationsForMessage,
    )

    suspend fun forAssistantMessage(messageId: String): WikiMarkdownSourceContext =
        forMessageIds(listOf(messageId))

    suspend fun forMessageIds(messageIds: List<String>): WikiMarkdownSourceContext {
        require(messageIds.size <= MAX_MESSAGE_IDS) { "项目 Markdown 最多读取 $MAX_MESSAGE_IDS 条历史消息" }
        require(messageIds.distinct().size == messageIds.size) { "历史消息不能重复" }
        messageIds.forEach { messageId ->
            require(messageId.isNotBlank() && messageId.length <= MAX_MESSAGE_ID_LENGTH) { "历史消息标识无效" }
        }

        val records = messageIds.mapNotNull { messageId ->
            val run = findRun(messageId) ?: return@mapNotNull null
            MessageEvidenceRecord(
                messageId = messageId,
                run = run,
                usages = listUsages(messageId),
                citations = listCitations(messageId),
            )
        }
        val verifiedCitationsByMessage = records.associate { record ->
            record.messageId to record.verifiedCitations()
        }
        val citations = records
            .flatMap { record -> verifiedCitationsByMessage.getValue(record.messageId) }
            .distinctBy(MessageWikiCitationEntity::id)
            .take(MAX_CITATIONS)
            .map(MessageWikiCitationEntity::toMarkdownCitation)
        return WikiMarkdownSourceContext(
            citations = WikiMarkdownCitationSet(citations),
            coverage = buildCoverage(records, verifiedCitationsByMessage),
        )
    }
}

private data class MessageEvidenceRecord(
    val messageId: String,
    val run: WikiRetrievalRunEntity,
    val usages: List<MessageWikiUsageEntity>,
    val citations: List<MessageWikiCitationEntity>,
)

private fun MessageEvidenceRecord.verifiedCitations(): List<MessageWikiCitationEntity> = citations
    .asSequence()
    .filter { it.verificationState == VERIFIED_CITATION_STATE }
    .sortedBy(MessageWikiCitationEntity::displayOrdinal)
    .distinctBy { citation ->
        CitationChunkIdentity(
            ref = WikiRef(citation.wikiId, citation.wikiVersion),
            documentId = citation.documentId,
            sectionId = citation.sectionId,
            chunkId = citation.chunkId,
            originalTextSha256 = citation.originalTextSha256,
        )
    }
    .toList()

private data class CitationChunkIdentity(
    val ref: WikiRef,
    val documentId: String,
    val sectionId: String,
    val chunkId: String,
    val originalTextSha256: String,
)

private fun buildCoverage(
    records: List<MessageEvidenceRecord>,
    verifiedCitationsByMessage: Map<String, List<MessageWikiCitationEntity>>,
): WikiEvidenceCoverage {
    val requested = linkedSetOf<WikiRef>()
    val queried = linkedSetOf<WikiRef>()
    val citationCounts = linkedMapOf<WikiRef, Int>()
    val missing = linkedSetOf<WikiRef>()

    records.forEach { record ->
        val verified = verifiedCitationsByMessage.getValue(record.messageId)
        verified.groupingBy { WikiRef(it.wikiId, it.wikiVersion) }
            .eachCount()
            .forEach { (ref, count) -> citationCounts[ref] = (citationCounts[ref] ?: 0) + count }

        val comparisonRefs = record.run.comparisonRefs()
        if (comparisonRefs.isEmpty()) return@forEach
        requested += comparisonRefs
        val queriedForRecord = if (record.run.status != NO_QUERY_STATUS) {
            record.usages
                .map { usage -> WikiRef(usage.wikiId, usage.wikiVersion) }
                .filterTo(linkedSetOf()) { ref -> ref in comparisonRefs }
        } else {
            emptySet()
        }
        queried += queriedForRecord
        val citedRefs = verified.mapTo(linkedSetOf()) { citation -> WikiRef(citation.wikiId, citation.wikiVersion) }
        comparisonRefs.forEach { ref ->
            if (ref !in queriedForRecord || ref !in citedRefs) missing += ref
        }
    }
    return WikiEvidenceCoverage(
        requestedComparisonRefs = requested,
        queriedRefs = queried,
        verifiedCitationCounts = citationCounts,
        missingRefs = missing,
    )
}

private fun WikiRetrievalRunEntity.comparisonRefs(): Set<WikiRef> {
    val scope = decodeWikiScopeSnapshot(allowedScopeJson)
    val namedWikiIds = explicitComparisonNamedWikiIds(explicitOverrideJson) ?: return emptySet()
    return scope.filterTo(linkedSetOf()) { ref -> namedWikiIds.isEmpty() || ref.wikiId in namedWikiIds }
}

private fun explicitComparisonNamedWikiIds(raw: String?): Set<String>? {
    if (raw.isNullOrBlank()) return null
    val root = runCatching { explicitOverrideJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    val comparisonRequested = root["compareRequested"]?.jsonPrimitive?.contentOrNull
        ?.toBooleanStrictOrNull() == true
    if (!comparisonRequested) return null
    return root["namedWikiIds"]
        ?.jsonArray
        .orEmpty()
        .mapNotNull { element -> element.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
        .toCollection(linkedSetOf())
}

private fun MessageWikiCitationEntity.toMarkdownCitation(): WikiMarkdownCitation = WikiMarkdownCitation(
    citationId = id,
    sourceMessageId = messageId,
    displayOrdinal = displayOrdinal,
    wikiId = wikiId,
    wikiVersion = wikiVersion,
    wikiTitle = wikiTitle,
    sourceTitle = sourceTitle,
    sectionPath = sectionPath,
    locatorLabel = locatorLabel,
    originalTextSnapshot = originalTextSnapshot,
    originalTextSha256 = originalTextSha256,
)

private const val MAX_MESSAGE_IDS = 12
private const val MAX_MESSAGE_ID_LENGTH = 256
private const val MAX_CITATIONS = 40
private const val VERIFIED_CITATION_STATE = "VERIFIED"
private const val NO_QUERY_STATUS = "NO_QUERY"
private val explicitOverrideJson = Json { ignoreUnknownKeys = true }
