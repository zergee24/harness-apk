package com.harnessapk.session

import com.harnessapk.storage.MarkdownChangeDraftEntity
import com.harnessapk.storage.MarkdownChangeDraftItemEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDraftApplyCoordinatorTest {
    @Test
    fun persistsEveryFileResultAndFinalState() = runTest {
        val store = FakeStore(items = listOf(item("one"), item("two")))
        val gateway = FakeGateway { proposal ->
            if (proposal.path == "two.md") failure(proposal) else success(proposal)
        }
        val coordinator = MarkdownDraftApplyCoordinator(store, gateway, { 20L })

        val result = coordinator.apply("draft", "project", setOf("one", "two"))

        assertEquals(1, result.succeeded.size)
        assertEquals(1, result.failed.size)
        assertEquals("PARTIALLY_APPLIED", store.draft.status)
        assertEquals(listOf("SUCCEEDED", "FAILED"), store.items.map { it.applyStatus })
        assertEquals(1, store.claimCount)
    }

    @Test
    fun cancellationKeepsCompletedItemsAndMakesDraftRetryable() = runTest {
        val store = FakeStore(items = listOf(item("one"), item("two")))
        var calls = 0
        val gateway = FakeGateway { proposal ->
            calls += 1
            if (calls == 2) throw CancellationException("stop")
            success(proposal)
        }
        val coordinator = MarkdownDraftApplyCoordinator(store, gateway, { 30L })

        val error = runCatching {
            coordinator.apply("draft", "project", setOf("one", "two"))
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals("SUCCEEDED", store.items.first().applyStatus)
        assertEquals("FAILED", store.items.last().applyStatus)
        assertEquals("PARTIALLY_APPLIED", store.draft.status)
        assertTrue("可安全重试" in store.draft.summary)
    }

    @Test
    fun onlySuccessfulContextWriteMarksFactApplied() = runTest {
        val store = FakeStore(items = listOf(item("context", "context.md")))
        val statuses = mutableListOf<String>()
        val coordinator = MarkdownDraftApplyCoordinator(
            store = store,
            gateway = FakeGateway(::success),
            timeProvider = { 40L },
            markContextFacts = { _, status -> statuses += status },
        )

        coordinator.apply("draft", "project", setOf("context"))

        assertEquals(listOf("APPLIED"), statuses)
    }

    @Test
    fun emptyEffectiveSelectionDoesNotClaimDraft() = runTest {
        val store = FakeStore(items = listOf(item("one").copy(applyStatus = "SUCCEEDED")))
        val coordinator = MarkdownDraftApplyCoordinator(store, FakeGateway(::success), { 50L })

        val error = runCatching {
            coordinator.apply("draft", "project", setOf("one"))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(0, store.claimCount)
        assertEquals("READY", store.draft.status)
    }

    private class FakeStore(items: List<MarkdownChangeDraftItemEntity>) : MarkdownDraftApplyStore {
        var draft = MarkdownChangeDraftEntity(
            id = "draft",
            conversationId = "conversation",
            projectId = "project",
            sourceUserMessageId = "user",
            assistantMessageId = null,
            status = "READY",
            summary = "ready",
            rawResponse = null,
            errorMessage = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
        var items = items
        var claimCount = 0

        override suspend fun findDraft(draftId: String) = draft
        override suspend fun listItems(draftId: String) = items
        override suspend fun claim(draftId: String, updatedAt: Long): Boolean {
            claimCount += 1
            if (draft.status !in setOf("READY", "FAILED", "PARTIALLY_APPLIED")) return false
            draft = draft.copy(status = "APPLYING", updatedAt = updatedAt)
            return true
        }

        override suspend fun updateItem(itemId: String, status: String, errorMessage: String?) {
            items = items.map { if (it.id == itemId) it.copy(applyStatus = status, applyErrorMessage = errorMessage) else it }
        }

        override suspend fun updateDraft(draft: MarkdownChangeDraftEntity) {
            this.draft = draft
        }
    }

    private class FakeGateway(
        private val apply: (MarkdownUpdateProposal) -> MarkdownFileApplyResult,
    ) : ProjectWorkspaceGateway {
        override suspend fun listProjects() = emptyList<WorkspaceProject>()
        override suspend fun listDeliverables(projectId: String) = emptyList<MarkdownDeliverable>()
        override suspend fun readProjectContext(projectId: String) = ""
        override suspend fun readDeliverable(projectId: String, deliverableId: String) = ""
        override suspend fun writeDeliverable(projectId: String, deliverableId: String, markdown: String) = Unit
        override suspend fun createDeliverable(projectId: String, templateType: String, title: String, markdown: String) =
            CreatedDeliverable(title, title, title)
        override suspend fun saveSessionSummary(projectId: String, sessionSummary: SessionSummary) =
            CreatedDeliverable(sessionSummary.title, sessionSummary.title, sessionSummary.title)
        override suspend fun applyMarkdownUpdates(
            projectId: String,
            updates: List<MarkdownUpdateProposal>,
        ) = MarkdownBatchApplyResult(updates.map(apply))
    }

    private fun item(id: String, path: String = "$id.md") = MarkdownChangeDraftItemEntity(
        id = id,
        draftId = "draft",
        itemIndex = if (id == "one") 0 else 1,
        operation = "CREATE",
        relativePath = path,
        title = id,
        reason = "test",
        proposedMarkdown = "# $id\n",
        retained = true,
        baselineSha256 = null,
        expectedAbsent = true,
        applyStatus = null,
        applyErrorMessage = null,
    )

    private fun success(proposal: MarkdownUpdateProposal) = MarkdownFileApplyResult(
        proposal = proposal,
        status = MarkdownFileApplyStatus.SUCCEEDED,
        writtenDeliverable = CreatedDeliverable(proposal.path, proposal.title, proposal.path),
    )

    private fun failure(proposal: MarkdownUpdateProposal) = MarkdownFileApplyResult(
        proposal = proposal,
        status = MarkdownFileApplyStatus.FAILED,
        errorMessage = "conflict",
    )
}
