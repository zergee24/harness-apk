package com.harnessapk.chat

import com.harnessapk.common.SystemTimeProvider
import com.harnessapk.common.TimeProvider
import com.harnessapk.wiki.ConversationWikiRepository
import com.harnessapk.wiki.MessageWikiCitation
import com.harnessapk.wiki.MessageWikiUsage
import com.harnessapk.wiki.WikiCitationDraft
import com.harnessapk.wiki.WikiCitationVerificationState
import com.harnessapk.wiki.WikiCitationVerifier
import com.harnessapk.wiki.WikiContentStore
import com.harnessapk.wiki.WikiEvidence
import com.harnessapk.wiki.WikiRetrievalRun
import com.harnessapk.wiki.WikiRetrievalRunStatus
import com.harnessapk.wiki.WikiRetrievalStatus
import com.harnessapk.wiki.WikiRuntimeContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest

fun interface WikiSourcePersistence {
    suspend fun persist(request: WikiSourcePersistenceRequest)
}

data class WikiSourcePersistenceRequest(
    val messageId: String,
    val snapshot: StreamingMessageSnapshot,
    val context: WikiRuntimeContext,
    val verification: com.harnessapk.wiki.WikiCitationVerificationResult,
)

class WikiSourcePartWriter(
    private val verifier: WikiCitationVerifier,
    private val persistence: WikiSourcePersistence,
    private val initialPersistence: suspend (messageId: String, context: WikiRuntimeContext) -> Unit = { _, _ -> },
) {
    constructor(
        conversationWikiRepository: ConversationWikiRepository,
        chatRepository: ChatRepository,
        contentStore: WikiContentStore,
        timeProvider: TimeProvider = SystemTimeProvider,
    ) : this(
        verifier = WikiCitationVerifier(),
        persistence = RepositoryWikiSourcePersistence(
            conversationWikiRepository = conversationWikiRepository,
            chatRepository = chatRepository,
            contentStore = contentStore,
            timeProvider = timeProvider,
        ),
        initialPersistence = { messageId, context ->
            persistInitialRun(conversationWikiRepository, timeProvider, messageId, context)
        },
    )

    suspend fun persistInitial(messageId: String, context: WikiRuntimeContext) {
        if (context.retrieval != null) initialPersistence(messageId, context)
    }

    suspend fun persist(
        messageId: String,
        snapshot: StreamingMessageSnapshot,
        context: WikiRuntimeContext,
        onPrepared: (StreamingMessageSnapshot) -> Unit = {},
    ): StreamingMessageSnapshot {
        if (snapshot.parts.any { it.type == UiMessagePartType.WIKI_SOURCES }) return snapshot
        if (context.retrieval == null) return snapshot.removeWikiCitationTokensForTerminal()
        val verification = verifier.verify(messageId, snapshot, context)
        val prepared = appendWikiSourcesPart(verification.snapshot, verification.citations)
        onPrepared(prepared)
        persistence.persist(
            WikiSourcePersistenceRequest(
                messageId = messageId,
                snapshot = prepared,
                context = context,
                verification = verification,
            ),
        )
        return prepared
    }
}

private class RepositoryWikiSourcePersistence(
    private val conversationWikiRepository: ConversationWikiRepository,
    private val chatRepository: ChatRepository,
    private val contentStore: WikiContentStore,
    private val timeProvider: TimeProvider,
) : WikiSourcePersistence {
    override suspend fun persist(request: WikiSourcePersistenceRequest) {
        val retrieval = request.context.retrieval ?: return
        val now = timeProvider.nowMillis()
        val citations = request.verification.citations.map { draft ->
            draft.toMessageCitation(
                messageId = request.messageId,
                state = if (sourceStillMatches(draft.evidence)) {
                    draft.verificationState
                } else {
                    WikiCitationVerificationState.PACKAGE_UNAVAILABLE
                },
                createdAt = now,
            )
        }
        conversationWikiRepository.persistFinal(
            run = retrieval.toRun(request.messageId, request.context, now),
            usages = retrieval.usages.map { usage ->
                MessageWikiUsage(
                    messageId = request.messageId,
                    ref = usage.ref,
                    scoutRank = usage.scoutRank,
                    deepHitCount = usage.deepHitCount,
                    selectedEvidenceCount = usage.selectedEvidenceCount,
                    enteredContext = usage.enteredContext,
                )
            },
            citations = citations,
            replaceMessageParts = {
                chatRepository.replaceMessagePartsFromSnapshot(request.messageId, request.snapshot)
            },
        )
    }

    private suspend fun sourceStillMatches(evidence: WikiEvidence): Boolean = try {
        val current = contentStore.findChunk(evidence.ref, evidence.chunkId) ?: return false
        current.originalText == evidence.originalText &&
            sha256(current.originalText) == evidence.originalTextSha256
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }
}

private suspend fun persistInitialRun(
    repository: ConversationWikiRepository,
    timeProvider: TimeProvider,
    messageId: String,
    context: WikiRuntimeContext,
) {
    val retrieval = context.retrieval ?: return
    val now = timeProvider.nowMillis()
    repository.persistRunAndUsages(
        run = retrieval.toRun(messageId, context, now),
        usages = retrieval.usages.map { usage ->
            MessageWikiUsage(
                messageId = messageId,
                ref = usage.ref,
                scoutRank = usage.scoutRank,
                deepHitCount = usage.deepHitCount,
                selectedEvidenceCount = usage.selectedEvidenceCount,
                enteredContext = usage.enteredContext,
            )
        },
    )
}

private fun com.harnessapk.wiki.WikiRetrievalResult.toRun(
    messageId: String,
    context: WikiRuntimeContext,
    now: Long,
): WikiRetrievalRun = WikiRetrievalRun(
    messageId = messageId,
    allowedScope = context.scope,
    explicitOverrideJson = context.intent.toExplicitOverrideJson(),
    routerVersion = routeDecision.routerVersion,
    retrieverVersion = retrieverVersion,
    status = status.toRunStatus(),
    candidateCount = routeDecision.candidates.size,
    evidenceCount = evidence.size,
    elapsedMillis = context.retrievalElapsedMillis,
    errorCode = usages.firstNotNullOfOrNull { it.errorCode },
    createdAt = now,
    updatedAt = now,
)

private fun WikiRetrievalStatus.toRunStatus(): WikiRetrievalRunStatus = when (this) {
    WikiRetrievalStatus.HIT -> WikiRetrievalRunStatus.HIT
    WikiRetrievalStatus.NO_EVIDENCE -> WikiRetrievalRunStatus.NO_HIT
    WikiRetrievalStatus.FAILED -> WikiRetrievalRunStatus.FAILED
}

private fun com.harnessapk.wiki.WikiTurnIntent.toExplicitOverrideJson(): String? {
    if (mode == com.harnessapk.wiki.WikiTurnIntentMode.AUTO && !compareRequested) return null
    return buildJsonObject {
        put("mode", JsonPrimitive(mode.name))
        put("compareRequested", JsonPrimitive(compareRequested))
        put("namedWikiIds", JsonArray(namedWikiIds.sorted().map(::JsonPrimitive)))
        put("unavailableNamedWikiIds", JsonArray(unavailableNamedWikiIds.sorted().map(::JsonPrimitive)))
    }.toString()
}

private fun WikiCitationDraft.toMessageCitation(
    messageId: String,
    state: WikiCitationVerificationState,
    createdAt: Long,
): MessageWikiCitation = MessageWikiCitation(
    id = id,
    messageId = messageId,
    displayOrdinal = displayOrdinal,
    ref = evidence.ref,
    wikiTitle = evidence.wikiTitle,
    documentId = evidence.documentId,
    sectionId = evidence.sectionId,
    chunkId = evidence.chunkId,
    sourceTitle = evidence.sourceTitle,
    sectionPath = evidence.sectionPath,
    locatorLabel = evidence.locatorLabel,
    originalTextSnapshot = evidence.originalText,
    originalTextSha256 = evidence.originalTextSha256,
    answerRangesJson = JsonArray(
        answerRanges.map { range ->
            JsonArray(listOf(JsonPrimitive(range.start), JsonPrimitive(range.endExclusive)))
        },
    ).toString(),
    verificationState = state,
    createdAt = createdAt,
)

private fun appendWikiSourcesPart(
    snapshot: StreamingMessageSnapshot,
    citations: List<WikiCitationDraft>,
): StreamingMessageSnapshot {
    val partsWithoutSources = snapshot.parts.filterNot { it.type == UiMessagePartType.WIKI_SOURCES }
    if (citations.isEmpty()) return snapshot.copy(parts = partsWithoutSources.reindex())
    val summary = citations
        .groupingBy { it.evidence.wikiTitle }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .joinToString(separator = " · ") { (title, count) -> "$title $count" }
    val sourcePart = UiMessagePartDraft(
        index = partsWithoutSources.size,
        type = UiMessagePartType.WIKI_SOURCES,
        content = "引用 ${citations.size} · $summary",
        metadata = mapOf(
            "citationIds" to JsonArray(citations.map { citation -> JsonPrimitive(citation.id) }).toString(),
            "verification" to if (citations.any { it.verificationState != WikiCitationVerificationState.VERIFIED }) {
                "PARTIAL"
            } else {
                "VERIFIED"
            },
        ),
        stable = true,
    )
    return snapshot.copy(parts = (partsWithoutSources + sourcePart).reindex())
}

private fun List<UiMessagePartDraft>.reindex(): List<UiMessagePartDraft> = mapIndexed { index, part ->
    part.copy(index = index)
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
