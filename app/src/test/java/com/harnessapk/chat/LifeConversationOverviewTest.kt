package com.harnessapk.chat

import com.harnessapk.storage.ConversationDao
import com.harnessapk.storage.ConversationEntity
import com.harnessapk.storage.LifeConversationMetadata
import com.harnessapk.storage.LifeConversationOrigin
import com.harnessapk.storage.LifeConversationRoomRow
import com.harnessapk.storage.InMemoryLifeConversationMetadataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeConversationOverviewTest {
    @Test
    fun onlyExplicitLifeEmptyShellsAreHidden() {
        val rows = listOf(
            row("life-shell"),
            row("standard-shell"),
            row("legacy-shell"),
            row("retained-shell"),
        )
        val metadata = mapOf(
            "life-shell" to LifeConversationMetadata(origin = LifeConversationOrigin.LIFE_TEXT),
            "standard-shell" to LifeConversationMetadata(origin = LifeConversationOrigin.STANDARD),
            "legacy-shell" to LifeConversationMetadata(),
            "retained-shell" to LifeConversationMetadata(
                origin = LifeConversationOrigin.LIFE_TEXT,
                userRetained = true,
            ),
        )

        val all = aggregateLifeConversationItems(rows, emptyList(), emptyList(), metadata)
        val visible = all.filterNot { item ->
            isHiddenEmptyShell(item, rows, metadata)
        }

        assertEquals(listOf("legacy-shell", "retained-shell"), visible.map { it.conversationId })
    }

    @Test
    fun standardShellWithConfigurationOrDraftRemainsVisible() {
        val configuredRow = row("standard-configured").copy(defaultModel = "demo-model")
        val configuredItem = aggregateLifeConversationItems(
            rows = listOf(configuredRow),
            drafts = emptyList(),
            recoveries = emptyList(),
            metadata = mapOf(
                "standard-configured" to LifeConversationMetadata(origin = LifeConversationOrigin.STANDARD),
            ),
        ).single()
        val projectRow = row("standard-project").copy(projectId = "project-1")
        val projectItem = aggregateLifeConversationItems(
            rows = listOf(projectRow),
            drafts = emptyList(),
            recoveries = emptyList(),
            metadata = mapOf(
                "standard-project" to LifeConversationMetadata(origin = LifeConversationOrigin.STANDARD),
            ),
        ).single()
        val draftItem = aggregateLifeConversationItems(
            rows = listOf(row("standard-draft")),
            drafts = listOf(
                LifeConversationDraftEntry(
                    conversationId = "standard-draft",
                    text = "周末买菜要带什么？",
                ),
            ),
            recoveries = emptyList(),
            metadata = mapOf(
                "standard-draft" to LifeConversationMetadata(origin = LifeConversationOrigin.STANDARD),
            ),
        ).single()

        assertFalse(
            isHiddenEmptyShell(
                configuredItem,
                listOf(configuredRow),
                mapOf("standard-configured" to LifeConversationMetadata(origin = LifeConversationOrigin.STANDARD)),
            ),
        )
        assertFalse(
            isHiddenEmptyShell(
                draftItem,
                listOf(row("standard-draft")),
                mapOf("standard-draft" to LifeConversationMetadata(origin = LifeConversationOrigin.STANDARD)),
            ),
        )
        assertFalse(
            isHiddenEmptyShell(
                projectItem,
                listOf(projectRow),
                mapOf("standard-project" to LifeConversationMetadata(origin = LifeConversationOrigin.STANDARD)),
            ),
        )
    }

    @Test
    fun draftAndRecoveryFactsProduceReadableStatusAndSummary() {
        val draft = LifeConversationDraftEntry(
            conversationId = "c1",
            text = "明天出门要带什么？",
            attachmentCount = 1,
            importingAttachmentCount = 1,
            updatedAt = 20L,
        )
        val item = aggregateLifeConversationItems(
            rows = listOf(row("c1", updatedAt = 10L)),
            drafts = listOf(draft),
            recoveries = emptyList(),
            metadata = emptyMap(),
        ).single()

        assertEquals("明天出门要带什么？", item.title)
        assertEquals("明天出门要带什么？", item.summary)
        assertEquals(LifeConversationDisplayStatus.WAITING, item.status)
        assertTrue(item.hasDraft)
        assertFalse(item.canArchive)
    }

    @Test
    fun unknownRecoveryBlocksArchiveWithoutCallingMutation() = runTest {
        val dao = FakeLifeConversationDao(listOf(row("c1")))
        val repository = repository(
            dao = dao,
            recoveries = listOf(
                LifeConversationRecoveryEntry("c1", LifeConversationRecoveryPhase.UNKNOWN),
            ),
        )

        val result = repository.archive("c1")

        assertEquals(LifeConversationArchiveResult.Blocked("问题仍在处理，结束后再整理"), result)
        assertEquals(0, dao.archiveCalls)
    }

    @Test
    fun notLandedRecoveryKeepsQuestionVisibleAsFailed() {
        val item = aggregateLifeConversationItems(
            rows = listOf(row("c1")),
            drafts = emptyList(),
            recoveries = listOf(
                LifeConversationRecoveryEntry(
                    conversationId = "c1",
                    phase = LifeConversationRecoveryPhase.NOT_LANDED,
                    submittedText = "周末出行要带什么？",
                    currentDraftText = "周末出行要带什么？",
                ),
            ),
            metadata = emptyMap(),
        ).single()

        assertEquals("周末出行要带什么？", item.title)
        assertEquals(LifeConversationDisplayStatus.FAILED, item.status)
        assertTrue(item.hasDraft)
        assertTrue(item.canArchive)
    }

    @Test
    fun importingDraftBlocksArchive() = runTest {
        val dao = FakeLifeConversationDao(listOf(row("c1")))
        val repository = repository(
            dao = dao,
            drafts = listOf(
                LifeConversationDraftEntry(
                    conversationId = "c1",
                    importingAttachmentCount = 1,
                ),
            ),
        )

        val result = repository.archive("c1")

        assertEquals(LifeConversationArchiveResult.Blocked("附件或问题仍在准备，完成后再整理"), result)
        assertEquals(0, dao.archiveCalls)
    }

    @Test
    fun archiveAndUndoUseFiveSecondSidecarWindow() = runTest {
        val dao = FakeLifeConversationDao(listOf(row("c1")))
        val metadata = InMemoryLifeConversationMetadataStore()
        var now = 100L
        val repository = LifeConversationOverviewRepository(
            conversationDao = dao,
            metadataStore = metadata,
            draftStore = InMemoryLifeConversationDraftStore(),
            recoveryStore = InMemoryLifeConversationRecoveryStore(),
            nowMillis = { now },
        )

        val archived = repository.archive("c1")

        assertEquals(
            LifeConversationArchiveResult.Archived(5_100L),
            archived,
        )
        assertEquals(10_100L, repository.extendUndoDeadline("c1", 10_000L))
        assertEquals(10_100L, metadata.get("c1")?.undoDeadlineMillis)
        assertTrue(dao.findById("c1")!!.isArchived)
        assertTrue(repository.undoArchive("c1"))
        assertFalse(dao.findById("c1")!!.isArchived)
        assertEquals(null, metadata.get("c1")?.undoDeadlineMillis)

        now = 6_000L
        assertTrue(repository.archive("c1") is LifeConversationArchiveResult.Archived)
        now = 11_001L
        assertFalse(repository.undoArchive("c1"))
        assertTrue(dao.findById("c1")!!.isArchived)
    }

    @Test
    fun archiveRunsEligibilityAndMutationInsideArchiveGuard() = runTest {
        val dao = FakeLifeConversationDao(listOf(row("c1")))
        var guardedConversationId: String? = null
        var actionCalls = 0
        val repository = LifeConversationOverviewRepository(
            conversationDao = dao,
            metadataStore = InMemoryLifeConversationMetadataStore(),
            draftStore = InMemoryLifeConversationDraftStore(),
            recoveryStore = InMemoryLifeConversationRecoveryStore(),
            archiveGuard = { conversationId, action ->
                guardedConversationId = conversationId
                actionCalls += 1
                action()
            },
        )

        assertTrue(repository.archive("c1") is LifeConversationArchiveResult.Archived)
        assertEquals("c1", guardedConversationId)
        assertEquals(1, actionCalls)
        assertEquals(1, dao.archiveCalls)
    }

    @Test
    fun durableDraftAndRecoveryAdaptersPreservePendingFacts() {
        val draft = ConversationDraft(
            text = "带伞吗？",
            imageMetadata = listOf(
                DraftImageAttachment(
                    id = "image-1",
                    sourceUri = "content://image",
                    localUri = "file://image",
                    displayName = "天气.png",
                    mimeType = "image/png",
                    sizeBytes = 1L,
                    sha256 = "hash",
                    state = DraftAttachmentState.IMPORTING,
                ),
            ),
            updatedAt = 42L,
        )
        val draftEntry = LifeConversationDraftEntry.fromConversationDraft("c1", draft)
        val recoveryEntry = LifeConversationRecoveryEntry.fromRecoveryState(
            ChatSendRequestState(
                requestId = "r1",
                submittedText = "带伞吗？",
                submittedAttachments = emptyList(),
                isFirstUserMessage = true,
                conversationId = "c1",
                phase = ChatSendRequestPhase.UNKNOWN,
            ),
        )

        assertEquals(1, draftEntry.attachmentCount)
        assertTrue(draftEntry.hasPendingWork)
        assertEquals(LifeConversationRecoveryPhase.UNKNOWN, recoveryEntry?.phase)
    }

    @Test
    fun durableDraftAdapterExposesMissingAttachmentAsUnavailable() {
        val draft = ConversationDraft(
            text = "带伞吗？",
            imageMetadata = listOf(
                DraftImageAttachment(
                    id = "image-missing",
                    sourceUri = "content://image",
                    localUri = "file://gone",
                    displayName = "天气.png",
                    mimeType = "image/png",
                    sizeBytes = 1L,
                    sha256 = "hash",
                    state = DraftAttachmentState.MISSING,
                ),
            ),
            error = DraftStoreError(
                code = DraftStoreErrorCode.ATTACHMENT_MISSING,
                message = "附件副本不存在：天气.png",
            ),
            updatedAt = 42L,
        )
        val draftEntry = LifeConversationDraftEntry.fromConversationDraft("c1", draft)
        val item = aggregateLifeConversationItems(
            rows = listOf(row("c1")),
            drafts = listOf(draftEntry),
            recoveries = emptyList(),
            metadata = emptyMap(),
        ).single()

        assertEquals(1, draftEntry.missingAttachmentCount)
        assertTrue(draftEntry.hasUnavailableAttachments)
        assertFalse(draftEntry.hasPendingWork)
        assertEquals(LifeConversationDisplayStatus.FAILED, item.status)
        assertTrue(item.summary.contains("附件不可用"))
    }

    @Test
    fun unnamedConversationUsesExplicitFirstQuestionBeforeStoredPlaceholder() {
        val item = aggregateLifeConversationItems(
            rows = listOf(row("c1", title = "新问题", firstUserText = "首个天气问题")),
            drafts = emptyList(),
            recoveries = emptyList(),
            metadata = emptyMap(),
        ).single()

        assertEquals("首个天气问题", item.title)
    }

    @Test
    fun customTitleWinsOverDerivedQuestionTitle() {
        val item = aggregateLifeConversationItems(
            rows = listOf(row("c1", title = "新问题", firstUserText = "首个天气问题")),
            drafts = emptyList(),
            recoveries = emptyList(),
            metadata = mapOf(
                "c1" to LifeConversationMetadata(customTitle = "周末出行"),
            ),
        ).single()

        assertEquals("周末出行", item.title)
    }

    @Test
    fun unnamedConversationUsesDocumentNameWhenLegacyProtocolTitleIsConfirmed() {
        val context = requestContext(null, "life-attachment-weather.txt")
        val protocolTitle = smartConversationTitle(
            DocumentTextExtractor.withDocumentBlocks(
                userText = "",
                documents = context.documents.map { document ->
                    ExtractedDocument(
                        uri = document.uri,
                        fileName = document.fileName,
                        mimeType = document.mimeType,
                        text = document.extractedText,
                        truncated = document.truncated,
                        originalCharCount = document.extractedText.length,
                    )
                },
            ),
        )
        val title = lifeConversationTitle(
            row = row("c1", title = protocolTitle),
            draft = null,
            metadata = null,
            requestContext = context,
            firstRequestContext = context,
        )

        assertEquals("life-attachment-weather.txt", title)
    }

    @Test
    fun placeholderWithStoredDocumentProtocolUsesDocumentNameInsteadOfProtocolText() {
        val context = requestContext(null, "life-attachment-weather.txt")
        val protocol = DocumentTextExtractor.withDocumentBlocks(
            userText = "",
            documents = context.documents.map { document ->
                ExtractedDocument(
                    uri = document.uri,
                    fileName = document.fileName,
                    mimeType = document.mimeType,
                    text = document.extractedText,
                    truncated = document.truncated,
                    originalCharCount = document.extractedText.length,
                )
            },
        )

        val title = lifeConversationTitle(
            row = row("c1", title = "新问题", firstUserText = protocol),
            draft = null,
            metadata = null,
            requestContext = context,
            firstRequestContext = context,
        )

        assertEquals("life-attachment-weather.txt", title)
    }

    @Test
    fun explicitRequestTextWinsOverDocumentName() {
        val context = requestContext("明天出门要带什么？", "weather.txt")

        val title = lifeConversationTitle(
            row = row("c1", title = "新问题"),
            draft = null,
            metadata = null,
            requestContext = context,
            firstRequestContext = context,
        )

        assertEquals("明天出门要带什么？", title)
    }

    @Test
    fun handwrittenProtocolLikeQuestionIsKeptWithoutLegacyEvidence() {
        val title = lifeConversationTitle(
            row = row("c1", title = "新会话", firstUserText = "【附件：这是我手写的问题】"),
            draft = null,
            metadata = null,
        )

        assertEquals("【附件：这是我手写的问题】", title)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refreshResubscribesOverviewSource() = runTest {
        val dao = FakeLifeConversationDao(listOf(row("c1")))
        val repository = repository(dao)
        val collector = backgroundScope.launch {
            repository.observe().collect()
        }

        runCurrent()
        assertEquals(1, dao.overviewSubscriptions)

        repository.refresh()
        runCurrent()
        assertEquals(2, dao.overviewSubscriptions)
        collector.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun restoringFromACachedReadErrorResubscribesAndRemovesTheArchivedRow() = runTest {
        val dao = FakeLifeConversationDao(listOf(row("c1", isArchived = true)))
        val repository = repository(dao)
        val states = mutableListOf<LifeConversationOverviewState>()
        backgroundScope.launch { repository.observe(includeArchived = true).collect { states += it } }
        runCurrent()
        dao.readFailure.value = true
        runCurrent()
        val error = states.last() as LifeConversationOverviewState.Error
        assertTrue(error.cachedItems.single().isArchived)

        dao.readFailure.value = false
        assertTrue(repository.restoreFromArchiveList("c1"))
        runCurrent()

        assertEquals(2, dao.overviewSubscriptions)
        val restored = states.last() as LifeConversationOverviewState.Content
        assertTrue(restored.items.none { it.isArchived })
    }

    @Test
    fun oldAutomaticDocumentTitleUsesItsFilenameWithoutRewritingTheMessage() {
        val message = "【附件：old-report.txt】\n原始正文\n【附件结束】"
        val old = row("legacy", title = smartConversationTitle(message), firstUserText = message)
        assertEquals("old-report.txt", lifeConversationTitle(old, null, null))
        assertEquals(message, old.firstUserText)
        assertEquals("用户标题", lifeConversationTitle(old.copy(title = "用户标题"), null, null))
        assertEquals("明确标题", lifeConversationTitle(old, null, LifeConversationMetadata(customTitle = "明确标题")))
    }

    @Test
    fun failedUndoMetadataWriteDoesNotArchiveOrPromiseUndo() = runTest {
        val dao = FakeLifeConversationDao(listOf(row("c1")))
        val metadata = object : com.harnessapk.storage.LifeConversationMetadataStorePort {
            override val entries = MutableStateFlow<Map<String, LifeConversationMetadata>>(emptyMap())
            override fun put(conversationId: String, metadata: LifeConversationMetadata) {
                throw IllegalStateException("disk full")
            }
        }
        val repository = LifeConversationOverviewRepository(dao, metadata, EmptyLifeConversationDraftStore, EmptyLifeConversationRecoveryStore)

        assertEquals(LifeConversationArchiveResult.Blocked("归档信息未能保存，请重试"), repository.archive("c1"))
        assertEquals(0, dao.archiveCalls)
    }

    @Test
    fun failedArchiveAndFailedDeadlineCleanupNeverPromiseOrRestoreAnArchive() = runTest {
        val dao = FakeLifeConversationDao(listOf(row("c1")))
        dao.archiveFailure = IllegalStateException("database unavailable")
        val metadata = object : com.harnessapk.storage.LifeConversationMetadataStorePort {
            override val entries = MutableStateFlow<Map<String, LifeConversationMetadata>>(emptyMap())
            override fun put(conversationId: String, metadata: LifeConversationMetadata) {
                if (metadata.undoDeadlineMillis == null) throw IllegalStateException("cleanup unavailable")
                entries.value = entries.value + (conversationId to metadata)
            }
        }
        val repository = LifeConversationOverviewRepository(
            dao, metadata, EmptyLifeConversationDraftStore, EmptyLifeConversationRecoveryStore,
            nowMillis = { 100L },
        )

        assertEquals(LifeConversationArchiveResult.Blocked("暂时未能归档，请重试"), repository.archive("c1"))
        assertFalse(requireNotNull(dao.findById("c1")).isArchived)
        assertFalse(repository.undoArchive("c1"))
    }

    @Test
    fun aTitleSavedInRoomSurvivesASeparateMetadataFailure() {
        val stored = row("c1", title = "用户主动命名")
        val facts = mapOf("c1" to LifeConversationMetadata(origin = LifeConversationOrigin.LIFE_TEXT))
        val item = aggregateLifeConversationItems(listOf(stored), emptyList(), emptyList(), facts).single()
        assertFalse(isHiddenEmptyShell(item, listOf(stored), facts))
    }

    @Test
    fun landingWhileChatIsClosedDoesNotPresentTheSubmittedQuestionAsANextDraft() {
        val state = ChatSendRequestState(
            requestId = "r1", submittedText = "1+3=?", submittedAttachments = emptyList(), isFirstUserMessage = true,
            phase = ChatSendRequestPhase.LANDED,
            intent = ChatSendIntent("c1", "r1", "1+3=?"),
        )
        val item = aggregateLifeConversationItems(
            listOf(row("c1", firstUserText = "1+3=?").copy(latestExecutionStatus = "SUCCEEDED", lastAssistantText = "4")),
            listOf(LifeConversationDraftEntry("c1", text = "1+3=?")),
            listOf(requireNotNull(LifeConversationRecoveryEntry.fromRecoveryState(state))), emptyMap(),
        ).single()
        assertFalse(item.hasDraft)
        assertEquals(LifeConversationDisplayStatus.COMPLETED, item.status)
        assertEquals("4", item.summary)
    }

    @Test
    fun aDocumentOnlyRecoveryHasAFileClueEvenWithoutTheDraftStoreOrExecutionRow() {
        val file = ChatSendAttachmentSnapshot("file", ChatSendAttachmentKind.DOCUMENT, "private-copy", "text/plain", displayName = "说明.txt")
        val state = ChatSendRequestState(
            requestId = "r1", submittedText = "protocol text", submittedAttachments = emptyList(), isFirstUserMessage = true,
            currentDraftText = "", currentDraftAttachmentSnapshots = listOf(file), phase = ChatSendRequestPhase.NOT_LANDED,
            intent = ChatSendIntent("c1", "r1", "", originalAttachments = listOf(file)),
        )
        val item = aggregateLifeConversationItems(
            listOf(row("c1")), emptyList(), listOf(requireNotNull(LifeConversationRecoveryEntry.fromRecoveryState(state))), emptyMap(),
        ).single()
        assertTrue(item.hasDraft)
        assertEquals("说明.txt", item.title)
        assertTrue(item.summary.contains("说明.txt"))
        assertEquals("未发出", item.statusLabel)
        val failedAnswer = aggregateLifeConversationItems(
            listOf(row("c2").copy(latestExecutionStatus = "FAILED")), emptyList(), emptyList(), emptyMap(),
        ).single()
        assertEquals("回答失败", failedAnswer.statusLabel)
    }

    private fun repository(
        dao: FakeLifeConversationDao,
        drafts: List<LifeConversationDraftEntry> = emptyList(),
        recoveries: List<LifeConversationRecoveryEntry> = emptyList(),
    ) = LifeConversationOverviewRepository(
        conversationDao = dao,
        metadataStore = InMemoryLifeConversationMetadataStore(),
        draftStore = InMemoryLifeConversationDraftStore(drafts),
        recoveryStore = InMemoryLifeConversationRecoveryStore(recoveries),
        nowMillis = { 100L },
    )

    private fun row(
        id: String,
        title: String = "新会话",
        firstUserText: String? = null,
        updatedAt: Long = 1L,
        isArchived: Boolean = false,
    ) = LifeConversationRoomRow(
        id = id,
        title = title,
        createdAt = 1L,
        updatedAt = updatedAt,
        isArchived = isArchived,
        projectId = null,
        agentId = null,
        agentVersion = null,
        defaultProviderId = null,
        defaultModel = null,
        promptOriginal = "",
        promptOptimized = "",
        promptFinal = "",
        messageCount = 0L,
        lastMessageAt = null,
        lastUserText = null,
        firstUserText = firstUserText,
        lastAssistantText = null,
        latestExecutionStatus = null,
        latestRequestContextJson = null,
        openExecutionCount = 0L,
    )

    private fun requestContext(userText: String?, vararg fileNames: String) = ChatExecutionRequestContext(
        userInputText = userText,
        documents = fileNames.mapIndexed { index, fileName ->
            ChatDocumentPresentation(
                id = "document-$index",
                uri = "file://$fileName",
                fileName = fileName,
                mimeType = "text/plain",
                sizeBytes = 1L,
                sha256 = "hash-$index",
                extractedText = "天气清单内容",
                truncated = false,
            )
        },
    )
}

private class FakeLifeConversationDao(
    initialRows: List<LifeConversationRoomRow>,
) : ConversationDao {
    private val rows = MutableStateFlow(initialRows)
    private val conversations = MutableStateFlow(
        initialRows.associate { row -> row.id to row.toConversationEntity() },
    )
    var archiveCalls: Int = 0
        private set
    var overviewSubscriptions: Int = 0
        private set
    val readFailure = MutableStateFlow(false)
    var archiveFailure: Throwable? = null

    override fun observeActive(): Flow<List<ConversationEntity>> = conversations.map { values ->
        values.values.filter { !it.isArchived }.sortedByDescending { it.updatedAt }
    }

    override fun observeLifeOverviewRows(includeArchived: Boolean): Flow<List<LifeConversationRoomRow>> =
        combine(rows, readFailure) { values, fail ->
            if (fail) throw IllegalStateException("read unavailable")
            values.filter { includeArchived || !it.isArchived }
        }
            .onStart { overviewSubscriptions += 1 }

    override suspend fun findById(id: String): ConversationEntity? = conversations.value[id]

    override suspend fun findLatestActive(): ConversationEntity? =
        conversations.value.values.filter { !it.isArchived }.maxByOrNull { it.updatedAt }

    override suspend fun findLatestActiveInProject(projectId: String): ConversationEntity? =
        conversations.value.values.filter { !it.isArchived && it.projectId == projectId }.maxByOrNull { it.updatedAt }

    override suspend fun insert(entity: ConversationEntity) {
        conversations.value = conversations.value + (entity.id to entity)
    }

    override suspend fun update(entity: ConversationEntity) {
        conversations.value = conversations.value + (entity.id to entity)
    }

    override suspend fun updateIdentityIfNoUserMessages(
        id: String,
        agentId: String?,
        agentVersion: Int?,
        updatedAt: Long,
    ): Int = 0

    override suspend fun clearProject(projectId: String) = Unit

    override suspend fun archive(id: String, updatedAt: Long) {
        conversations.value[id]?.let { conversation ->
            conversations.value = conversations.value + (id to conversation.copy(isArchived = true, updatedAt = updatedAt))
        }
    }

    override suspend fun archiveLifeConversationIfIdle(id: String): Int {
        archiveCalls += 1
        archiveFailure?.let { throw it }
        val conversation = conversations.value[id] ?: return 0
        if (conversation.isArchived || conversation.projectId != null) return 0
        conversations.value = conversations.value + (id to conversation.copy(isArchived = true))
        rows.value = rows.value.map { row -> if (row.id == id) row.copy(isArchived = true) else row }
        return 1
    }

    override suspend fun restoreLifeConversation(id: String): Int {
        val conversation = conversations.value[id] ?: return 0
        if (!conversation.isArchived || conversation.projectId != null) return 0
        conversations.value = conversations.value + (id to conversation.copy(isArchived = false))
        rows.value = rows.value.map { row -> if (row.id == id) row.copy(isArchived = false) else row }
        return 1
    }

    override suspend fun countByAgentVersion(agentId: String, version: Int): Int = 0

    override suspend fun clearAgentReference(agentId: String, version: Int, updatedAt: Long): Int = 0
}

private fun LifeConversationRoomRow.toConversationEntity() = ConversationEntity(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    defaultProviderId = defaultProviderId,
    defaultModel = defaultModel,
    isArchived = isArchived,
    projectId = projectId,
    promptOriginal = promptOriginal,
    promptOptimized = promptOptimized,
    promptFinal = promptFinal,
    agentId = agentId,
    agentVersion = agentVersion,
)
