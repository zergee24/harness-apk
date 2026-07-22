package com.harnessapk.wiki

import com.harnessapk.common.SystemTimeProvider
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.ConversationWikiDao
import com.harnessapk.storage.ConversationWikiMountEntity
import com.harnessapk.storage.MessageWikiCitationEntity
import com.harnessapk.storage.MessageWikiUsageEntity
import com.harnessapk.storage.WikiRetrievalRunEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun interface ConversationWikiTransactionRunner {
    suspend fun run(block: suspend () -> Unit)
}

class ConversationWikiRepository(
    private val dao: ConversationWikiDao,
    private val transactionRunner: ConversationWikiTransactionRunner,
    private val timeProvider: TimeProvider = SystemTimeProvider,
) {
    suspend fun replaceMount(
        conversationId: String,
        ref: WikiRef,
        enabled: Boolean,
    ) {
        requireIdentifier(conversationId, "会话标识")
        validateWikiRef(ref)
        transactionRunner.run {
            requireReadyVersion(ref)
            val now = timeProvider.nowMillis()
            val existing = dao.findMount(conversationId, ref.wikiId)
            dao.upsertMount(
                ConversationWikiMountEntity(
                    conversationId = conversationId,
                    wikiId = ref.wikiId,
                    wikiVersion = ref.version,
                    enabled = enabled,
                    mountedAt = existing?.mountedAt ?: now,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun replaceMountScope(
        conversationId: String,
        selections: List<ConversationWikiMountSelection>,
    ) {
        transactionRunner.run {
            replaceMountScopeInTransaction(conversationId, selections)
        }
    }

    suspend fun replaceMountScopeInTransaction(
        conversationId: String,
        selections: List<ConversationWikiMountSelection>,
    ) {
        requireIdentifier(conversationId, "会话标识")
        val canonical = canonicalMountSelections(selections)
        canonical.filter(ConversationWikiMountSelection::enabled).forEach { selection ->
            requireReadyVersion(selection.ref)
        }
        val existing = dao.listMounts(conversationId).associateBy(ConversationWikiMountEntity::wikiId)
        val now = timeProvider.nowMillis()
        dao.deleteMounts(conversationId)
        canonical.forEach { selection ->
            dao.upsertMount(
                ConversationWikiMountEntity(
                    conversationId = conversationId,
                    wikiId = selection.ref.wikiId,
                    wikiVersion = selection.ref.version,
                    enabled = selection.enabled,
                    mountedAt = existing[selection.ref.wikiId]?.mountedAt ?: now,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun replaceEnabledScopeInTransaction(
        conversationId: String,
        scope: List<WikiRef>,
    ) {
        replaceMountScopeInTransaction(
            conversationId = conversationId,
            selections = canonicalWikiScope(scope).map { ref ->
                ConversationWikiMountSelection(ref = ref, enabled = true)
            },
        )
    }

    suspend fun removeMount(conversationId: String, wikiId: String): Boolean {
        requireIdentifier(conversationId, "会话标识")
        validateWikiRef(WikiRef(wikiId, 1))
        var removed = false
        transactionRunner.run {
            removed = dao.deleteMount(conversationId, wikiId) > 0
        }
        return removed
    }

    suspend fun mounts(conversationId: String): List<ConversationWikiMount> {
        requireIdentifier(conversationId, "会话标识")
        return dao.listMounts(conversationId).map(ConversationWikiMountEntity::toDomain)
    }

    suspend fun snapshotEnabled(conversationId: String): List<WikiRef> =
        canonicalWikiScope(mounts(conversationId).filter(ConversationWikiMount::enabled).map(ConversationWikiMount::ref))

    suspend fun restoreDefaults(conversationId: String) {
        transactionRunner.run {
            copyDefaultsToConversationInTransaction(conversationId)
        }
    }

    suspend fun copyDefaultsToConversation(conversationId: String) = restoreDefaults(conversationId)

    suspend fun copyDefaultsToConversationInTransaction(conversationId: String) {
        requireIdentifier(conversationId, "会话标识")
        val defaults = canonicalWikiScope(dao.listReadyDefaults().map { WikiRef(it.wikiId, it.version) })
        val now = timeProvider.nowMillis()
        dao.deleteMounts(conversationId)
        defaults.forEach { ref ->
            dao.upsertMount(
                ConversationWikiMountEntity(
                    conversationId = conversationId,
                    wikiId = ref.wikiId,
                    wikiVersion = ref.version,
                    enabled = true,
                    mountedAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun countReferences(ref: WikiRef): Int {
        validateWikiRef(ref)
        return dao.countMountReferences(ref.wikiId, ref.version) +
            dao.countCitationReferences(ref.wikiId, ref.version)
    }

    suspend fun canRemove(ref: WikiRef): Boolean = countReferences(ref) == 0

    suspend fun persistRun(run: WikiRetrievalRun) {
        validateRun(run)
        transactionRunner.run {
            dao.insertRun(run.toEntity())
        }
    }

    suspend fun run(messageId: String): WikiRetrievalRun? {
        requireIdentifier(messageId, "消息标识")
        return dao.findRun(messageId)?.toDomain()
    }

    suspend fun persistUsage(usage: MessageWikiUsage) {
        validateUsage(usage)
        transactionRunner.run {
            dao.insertUsage(usage.toEntity())
        }
    }

    suspend fun persistRunAndUsages(
        run: WikiRetrievalRun,
        usages: List<MessageWikiUsage>,
    ) {
        validateRun(run)
        usages.forEach { usage ->
            validateUsage(usage)
            require(usage.messageId == run.messageId) { "检索使用记录必须属于同一消息" }
        }
        transactionRunner.run {
            dao.insertRun(run.toEntity())
            usages.forEach { usage -> dao.insertUsage(usage.toEntity()) }
        }
    }

    suspend fun persistCitation(citation: MessageWikiCitation) {
        validateCitation(citation)
        transactionRunner.run {
            dao.insertCitation(citation.toEntity())
        }
    }

    suspend fun persistFinal(
        run: WikiRetrievalRun,
        usages: List<MessageWikiUsage>,
        citations: List<MessageWikiCitation>,
        replaceMessageParts: suspend () -> Unit,
    ) {
        validateRun(run)
        usages.forEach { usage ->
            validateUsage(usage)
            require(usage.messageId == run.messageId) { "检索使用记录必须属于同一消息" }
        }
        citations.forEach { citation ->
            validateCitation(citation)
            require(citation.messageId == run.messageId) { "Wiki 引用必须属于同一消息" }
        }
        transactionRunner.run {
            dao.insertRun(run.toEntity())
            usages.forEach { usage -> dao.insertUsage(usage.toEntity()) }
            dao.deleteCitationsForMessage(run.messageId)
            citations.forEach { citation -> dao.insertCitation(citation.toEntity()) }
            replaceMessageParts()
        }
    }

    suspend fun citation(id: String): MessageWikiCitation? {
        requireIdentifier(id, "引用标识")
        return dao.findCitation(id)?.toDomain()
    }

    fun observeCitationsForMessage(messageId: String): Flow<List<MessageWikiCitation>> {
        requireIdentifier(messageId, "消息标识")
        return dao.observeCitationsForMessage(messageId).map { rows -> rows.map { it.toDomain() } }
    }

    private suspend fun requireReadyVersion(ref: WikiRef) {
        if (dao.findReadyVersion(ref.wikiId, ref.version) == null) {
            throw ConversationWikiException("目标 Wiki 版本不存在或不可用")
        }
    }
}

private fun ConversationWikiMountEntity.toDomain(): ConversationWikiMount = ConversationWikiMount(
    conversationId = conversationId,
    ref = WikiRef(wikiId, wikiVersion),
    enabled = enabled,
    mountedAt = mountedAt,
    updatedAt = updatedAt,
)

private fun WikiRetrievalRun.toEntity(): WikiRetrievalRunEntity = WikiRetrievalRunEntity(
    messageId = messageId,
    allowedScopeJson = encodeWikiScopeSnapshot(allowedScope),
    explicitOverrideJson = explicitOverrideJson?.trim()?.ifBlank { null },
    routerVersion = routerVersion,
    retrieverVersion = retrieverVersion,
    status = status.name,
    candidateCount = candidateCount,
    evidenceCount = evidenceCount,
    elapsedMillis = elapsedMillis,
    errorCode = errorCode?.trim()?.ifBlank { null },
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun WikiRetrievalRunEntity.toDomain(): WikiRetrievalRun = WikiRetrievalRun(
    messageId = messageId,
    allowedScope = decodeWikiScopeSnapshot(allowedScopeJson),
    explicitOverrideJson = explicitOverrideJson,
    routerVersion = routerVersion,
    retrieverVersion = retrieverVersion,
    status = runCatching { WikiRetrievalRunStatus.valueOf(status) }.getOrDefault(WikiRetrievalRunStatus.FAILED),
    candidateCount = candidateCount,
    evidenceCount = evidenceCount,
    elapsedMillis = elapsedMillis,
    errorCode = errorCode,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun MessageWikiUsage.toEntity(): MessageWikiUsageEntity = MessageWikiUsageEntity(
    messageId = messageId,
    wikiId = ref.wikiId,
    wikiVersion = ref.version,
    scoutRank = scoutRank,
    deepHitCount = deepHitCount,
    selectedEvidenceCount = selectedEvidenceCount,
    enteredContext = enteredContext,
)

private fun MessageWikiCitation.toEntity(): MessageWikiCitationEntity = MessageWikiCitationEntity(
    id = id,
    messageId = messageId,
    displayOrdinal = displayOrdinal,
    wikiId = ref.wikiId,
    wikiVersion = ref.version,
    wikiTitle = wikiTitle,
    documentId = documentId,
    sectionId = sectionId,
    chunkId = chunkId,
    sourceTitle = sourceTitle,
    sectionPath = sectionPath,
    locatorLabel = locatorLabel,
    originalTextSnapshot = originalTextSnapshot,
    originalTextSha256 = originalTextSha256,
    answerRangesJson = answerRangesJson,
    verificationState = verificationState.name,
    createdAt = createdAt,
)

private fun MessageWikiCitationEntity.toDomain(): MessageWikiCitation = MessageWikiCitation(
    id = id,
    messageId = messageId,
    displayOrdinal = displayOrdinal,
    ref = WikiRef(wikiId, wikiVersion),
    wikiTitle = wikiTitle,
    documentId = documentId,
    sectionId = sectionId,
    chunkId = chunkId,
    sourceTitle = sourceTitle,
    sectionPath = sectionPath,
    locatorLabel = locatorLabel,
    originalTextSnapshot = originalTextSnapshot,
    originalTextSha256 = originalTextSha256,
    answerRangesJson = answerRangesJson,
    verificationState = runCatching { WikiCitationVerificationState.valueOf(verificationState) }
        .getOrDefault(WikiCitationVerificationState.PARTIAL),
    createdAt = createdAt,
)

private fun validateRun(run: WikiRetrievalRun) {
    requireIdentifier(run.messageId, "消息标识")
    canonicalWikiScope(run.allowedScope)
    require(run.routerVersion.isNotBlank() && run.retrieverVersion.isNotBlank()) { "检索算法版本不能为空" }
    require(run.candidateCount >= 0 && run.evidenceCount >= 0 && run.elapsedMillis >= 0L) { "检索统计无效" }
    require(run.createdAt >= 0L && run.updatedAt >= run.createdAt) { "检索时间无效" }
}

private fun validateUsage(usage: MessageWikiUsage) {
    requireIdentifier(usage.messageId, "消息标识")
    validateWikiRef(usage.ref)
    require(usage.scoutRank == null || usage.scoutRank > 0) { "检索排序无效" }
    require(usage.deepHitCount >= 0 && usage.selectedEvidenceCount >= 0) { "检索统计无效" }
}

private fun validateCitation(citation: MessageWikiCitation) {
    requireIdentifier(citation.id, "引用标识")
    requireIdentifier(citation.messageId, "消息标识")
    validateWikiRef(citation.ref)
    require(citation.displayOrdinal > 0) { "引用顺序无效" }
    listOf(
        citation.wikiTitle,
        citation.documentId,
        citation.sectionId,
        citation.chunkId,
        citation.sourceTitle,
        citation.sectionPath,
        citation.locatorLabel,
        citation.originalTextSnapshot,
    ).forEach { value -> require(value.isNotBlank()) { "引用内容不能为空" } }
    require(SHA_256.matches(citation.originalTextSha256)) { "引用原文摘要无效" }
    require(citation.answerRangesJson.isNotBlank()) { "引用回答范围不能为空" }
    require(citation.createdAt >= 0L) { "引用时间无效" }
}

private fun canonicalMountSelections(
    selections: List<ConversationWikiMountSelection>,
): List<ConversationWikiMountSelection> {
    selections.forEach { selection -> validateWikiRef(selection.ref) }
    if (selections.groupBy { it.ref.wikiId }.any { (_, values) -> values.size > 1 }) {
        throw ConversationWikiException("同一 Wiki 只能保留一个版本")
    }
    return selections.sortedBy { it.ref.wikiId }
}

private val SHA_256 = Regex("[0-9a-f]{64}")
