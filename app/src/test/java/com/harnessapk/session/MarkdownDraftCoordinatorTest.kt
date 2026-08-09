package com.harnessapk.session

import com.harnessapk.projectsearch.ProjectSourceAuthority
import com.harnessapk.remote.RemoteCompletionEvidence
import com.harnessapk.remote.RemoteCompletionVerification
import com.harnessapk.remote.RemoteWorkspaceLocator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDraftCoordinatorTest {
    @Test
    fun planningAndFailureArePersistedBeforeAPlanExists() = runTest {
        val store = InMemoryDraftStore()
        val coordinator = coordinator(store = store)
        val owner = MarkdownDraftOwner(
            projectId = "project-1",
            conversationId = "conversation-1",
            sourceUserMessageId = "user-1",
            assistantMessageId = "assistant-1",
        )
        val origin = origin(MarkdownDraftOriginType.ASSISTANT_MESSAGE, "assistant-1")

        val planning = coordinator.persistPlanning(owner, origin, preferredDraftId = "draft-1")
        val failed = coordinator.persistFailure(owner, origin, "模型暂时不可用")

        assertEquals("draft-1", planning.draft.id)
        assertEquals("PLANNING", planning.draft.status)
        assertTrue(planning.items.isEmpty())
        assertEquals("draft-1", failed.draft.id)
        assertEquals("FAILED", failed.draft.status)
        assertEquals("模型暂时不可用", failed.draft.errorMessage)
    }

    @Test
    fun remoteOriginPersistsWithoutSyntheticConversationAndWithCreateBaseline() = runTest {
        var persisted: PersistedMarkdownDraft? = null
        val coordinator = coordinator(onSave = { persisted = it })

        val record = coordinator.persistPlan(
            owner = MarkdownDraftOwner(projectId = "project-1"),
            origin = origin(MarkdownDraftOriginType.REMOTE_RUN, "run-1"),
            plan = plan(MarkdownUpdateOperation.CREATE),
            snapshots = emptyList(),
        )

        assertNull(record.draft.conversationId)
        assertNull(record.draft.sourceUserMessageId)
        assertEquals("REMOTE_RUN", record.origin.sourceType)
        assertTrue(record.items.single().expectedAbsent)
        assertEquals(record, persisted)
    }

    @Test
    fun updateFreezesSnapshotHashAndAssistantOrigin() = runTest {
        val coordinator = coordinator()

        val record = coordinator.persistPlan(
            owner = MarkdownDraftOwner(
                projectId = "project-1",
                conversationId = "conversation-1",
                sourceUserMessageId = "user-1",
                assistantMessageId = "assistant-1",
            ),
            origin = origin(MarkdownDraftOriginType.ASSISTANT_MESSAGE, "assistant-1"),
            plan = plan(MarkdownUpdateOperation.UPDATE),
            snapshots = listOf(MarkdownSnapshot("context.md", "Context", "context.md", "# before\n")),
        )

        assertEquals("# before\n".sha256(), record.items.single().baselineSha256)
        assertEquals("ASSISTANT_MESSAGE", record.origin.sourceType)
    }

    @Test
    fun emptyPlanIsNormalNoChangesDraft() = runTest {
        val record = coordinator().persistPlan(
            owner = MarkdownDraftOwner(projectId = "project-1"),
            origin = origin(MarkdownDraftOriginType.EXPLICIT_CHANGE, "selection-1"),
            plan = MarkdownUpdatePlan(emptyList()),
            snapshots = emptyList(),
        )

        assertEquals("NO_CHANGES", record.draft.status)
        assertEquals("没有需要沉淀的稳定内容", record.draft.summary)
        assertTrue(record.items.isEmpty())
    }

    @Test
    fun readyDraftCannotRegressToPlanningOrLateFailure() = runTest {
        val store = InMemoryDraftStore()
        val coordinator = coordinator(store = store)
        val owner = MarkdownDraftOwner(
            projectId = "project-1",
            conversationId = "conversation-1",
            sourceUserMessageId = "user-1",
            assistantMessageId = "assistant-1",
        )
        val origin = origin(MarkdownDraftOriginType.ASSISTANT_MESSAGE, "assistant-1")
        val ready = coordinator.persistPlan(
            owner = owner,
            origin = origin,
            plan = plan(MarkdownUpdateOperation.CREATE),
            snapshots = emptyList(),
            preferredDraftId = "draft-1",
        )

        val repeatedPlanning = coordinator.persistPlanning(owner, origin, preferredDraftId = "draft-2")
        val lateFailure = coordinator.persistFailure(owner, origin, "late failure", preferredDraftId = "draft-2")
        val repeatedPlan = coordinator.persistPlan(
            owner = owner,
            origin = origin,
            plan = MarkdownUpdatePlan(emptyList()),
            snapshots = emptyList(),
            preferredDraftId = "draft-2",
        )

        assertEquals("READY", repeatedPlanning.draft.status)
        assertEquals("READY", lateFailure.draft.status)
        assertEquals("READY", repeatedPlan.draft.status)
        assertEquals(ready.draft.id, repeatedPlanning.draft.id)
        assertEquals(ready.items.map { it.id }, repeatedPlan.items.map { it.id })
    }

    @Test
    fun stableDraftIdDeduplicatesSameOriginButSeparatesProjects() {
        val first = stableMarkdownDraftId(MarkdownDraftOriginType.ASSISTANT_MESSAGE, "project-1", "message-1")

        assertEquals(first, stableMarkdownDraftId(MarkdownDraftOriginType.ASSISTANT_MESSAGE, "project-1", "message-1"))
        assertTrue(first != stableMarkdownDraftId(MarkdownDraftOriginType.ASSISTANT_MESSAGE, "project-2", "message-1"))
        assertTrue(first.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
    }

    @Test
    fun acceptedContextFactsAreCarriedIntoThePersistedDraftTransaction() = runTest {
        val fact = ContextFactCandidate(
            section = ContextSection.KEY_DECISIONS,
            statement = "继续使用本地 FTS",
            evidenceIds = listOf("evidence-1"),
            evidenceAuthorities = setOf(ProjectSourceAuthority.USER_STATED),
            operation = FactOperation.UPSERT,
            semanticKey = "key_decisions:${"a".repeat(64)}",
            evidenceHash = "b".repeat(64),
        )

        val record = coordinator().persistPlan(
            owner = MarkdownDraftOwner(projectId = "project-1", conversationId = "conversation-1"),
            origin = origin(MarkdownDraftOriginType.ASSISTANT_MESSAGE, "assistant-1"),
            plan = plan(MarkdownUpdateOperation.UPDATE).copy(contextFacts = listOf(fact)),
            snapshots = listOf(MarkdownSnapshot("context.md", "Context", "context.md", "# before\n")),
        )

        assertEquals(listOf(fact), record.contextFacts)
    }

    @Test
    fun remoteCompletionBuildsAuditableReportAndLabelsMacPath() {
        val plan = remoteCompletionMarkdownPlan(
            runId = "run-1",
            evidence = RemoteCompletionEvidence(
                summary = "完成检索闭环",
                changedFiles = listOf("app/src/main/M3.kt"),
                tests = emptyList(),
                gitState = "COMMITTED",
                unresolved = emptyList(),
                completedAt = 1L,
                schemaVersion = 2,
                completionId = "completion-1",
                workspace = RemoteWorkspaceLocator("workspace-1", "fingerprint", "/mac/harness-apk"),
                verification = RemoteCompletionVerification.VERIFIED_V2,
            ),
        )

        val proposal = plan.proposals.single()
        assertEquals(MarkdownUpdateOperation.CREATE, proposal.operation)
        assertTrue(proposal.expectedAbsent)
        assertTrue("Mac 工作区证据" in proposal.markdown)
        assertTrue("/mac/harness-apk" in proposal.markdown)
    }

    @Test
    fun repeatedOriginReusesDraftAndRejectsChangedSourceHash() = runTest {
        val store = InMemoryDraftStore()
        val coordinator = coordinator(store = store)
        val first = coordinator.persistPlan(
            owner = MarkdownDraftOwner(projectId = "project-1"),
            origin = origin(MarkdownDraftOriginType.REMOTE_RUN, "run-1"),
            plan = plan(MarkdownUpdateOperation.CREATE),
            snapshots = emptyList(),
        )
        val second = coordinator.persistPlan(
            owner = MarkdownDraftOwner(projectId = "project-1"),
            origin = origin(MarkdownDraftOriginType.REMOTE_RUN, "run-1"),
            plan = plan(MarkdownUpdateOperation.CREATE),
            snapshots = emptyList(),
        )

        assertEquals(first.draft.id, second.draft.id)
        val changed = origin(MarkdownDraftOriginType.REMOTE_RUN, "run-1").copy(
            sourceSha256 = "b".repeat(64),
        )
        val failure = runCatching {
            coordinator.persistPlan(
                owner = MarkdownDraftOwner(projectId = "project-1"),
                origin = changed,
                plan = plan(MarkdownUpdateOperation.CREATE),
                snapshots = emptyList(),
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private fun coordinator(
        store: InMemoryDraftStore = InMemoryDraftStore(),
        onSave: suspend (PersistedMarkdownDraft) -> Unit = {},
    ) = MarkdownDraftCoordinator(
        store = store.apply { this.onSave = onSave },
        timeProvider = { 10L },
        idFactory = generateSequence(0) { it + 1 }.map { "id-$it" }.iterator()::next,
    )

    private class InMemoryDraftStore : MarkdownDraftStore {
        private val records = mutableMapOf<Pair<MarkdownDraftOriginType, String>, PersistedMarkdownDraft>()
        var onSave: suspend (PersistedMarkdownDraft) -> Unit = {}

        override suspend fun find(
            originType: MarkdownDraftOriginType,
            sourceId: String,
        ): PersistedMarkdownDraft? = records[originType to sourceId]

        override suspend fun save(record: PersistedMarkdownDraft) {
            records[MarkdownDraftOriginType.valueOf(record.origin.sourceType) to record.origin.sourceId] = record
            onSave(record)
        }
    }

    private fun origin(type: MarkdownDraftOriginType, id: String) = MarkdownDraftOrigin(
        type = type,
        sourceId = id,
        sourceSha256 = "a".repeat(64),
        sourceProjectId = "project-1",
    )

    private fun plan(operation: MarkdownUpdateOperation) = MarkdownUpdatePlan(
        listOf(
            MarkdownUpdateProposal(
                operation = operation,
                path = if (operation == MarkdownUpdateOperation.CREATE) "reports/new.md" else "context.md",
                title = "M3",
                reason = "test",
                markdown = "# after\n",
            ),
        ),
    )
}
