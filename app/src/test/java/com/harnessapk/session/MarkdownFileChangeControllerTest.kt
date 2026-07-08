package com.harnessapk.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownFileChangeControllerTest {
    private val controller = MarkdownFileChangeController(timeProvider = { 42L })

    @Test
    fun createPlanningDraftStartsWaitingForReviewPlan() {
        val state = controller.createPlanningDraft(
            conversationId = "conversation",
            projectId = "project",
            sourceUserMessageId = "user-1",
        )

        assertEquals(MarkdownFileChangeStatus.PLANNING, state.draft.status)
        assertEquals("正在生成 Markdown 文件变更...", state.draft.summary)
        assertEquals("project", state.draft.projectId)
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun markReadyBuildsFileItemsWithDiffStatsAndDefaultRetained() {
        val planning = controller.createPlanningDraft("conversation", "project", "user-1")
        val ready = controller.markReady(
            state = planning,
            plan = MarkdownUpdatePlan(
                proposals = listOf(
                    MarkdownUpdateProposal(
                        operation = MarkdownUpdateOperation.UPDATE,
                        path = "requirements/prd.md",
                        title = "PRD",
                        reason = "补充验收标准",
                        markdown = "# PRD\n\n新目标\n保留行\n新增验收",
                    ),
                ),
            ),
            snapshots = listOf(
                MarkdownSnapshot(
                    id = "requirements/prd.md",
                    title = "PRD",
                    path = "requirements/prd.md",
                    markdown = "# PRD\n\n旧目标\n保留行",
                ),
            ),
        )

        assertEquals(MarkdownFileChangeStatus.READY, ready.draft.status)
        assertEquals("已生成 1 个 Markdown 文件变更", ready.draft.summary)
        assertEquals(1, ready.items.size)
        assertEquals(2, ready.items[0].addedLineCount)
        assertEquals(1, ready.items[0].removedLineCount)
        assertTrue(ready.items[0].retained)
        assertEquals(1, ready.diffs.size)
    }

    @Test
    fun markReadyFailsEmptyPlanWithoutWritingRawContent() {
        val failed = controller.markReady(
            state = controller.createPlanningDraft("conversation", "project", "user-1"),
            plan = MarkdownUpdatePlan(proposals = emptyList()),
            snapshots = emptyList(),
        )

        assertEquals(MarkdownFileChangeStatus.FAILED, failed.draft.status)
        assertEquals("没有生成可审核的 Markdown 更新", failed.draft.summary)
        assertTrue(failed.items.isEmpty())
    }

    @Test
    fun toggleRetainedWithdrawsAndKeepsFileLevelItems() {
        val ready = readyState()
        val withdrawn = controller.toggleRetained(ready, itemIndex = 0)
        val kept = controller.toggleRetained(withdrawn, itemIndex = 0)

        assertFalse(withdrawn.items[0].retained)
        assertTrue(kept.items[0].retained)
    }

    @Test
    fun dismissAndApplyMoveDraftToTerminalStatuses() {
        val ready = readyState()
        val dismissed = controller.dismiss(ready)
        val applied = controller.markApplied(ready, writtenPaths = listOf("requirements/prd.md"))

        assertEquals(MarkdownFileChangeStatus.DISMISSED, dismissed.draft.status)
        assertEquals("已撤回 Markdown 文件变更", dismissed.draft.summary)
        assertEquals(MarkdownFileChangeStatus.APPLIED, applied.draft.status)
        assertEquals("已应用 1 个 Markdown 文件变更", applied.draft.summary)
    }

    @Test
    fun retainedProposalsOnlyReturnsKeptItems() {
        val ready = readyState()
        val withdrawn = controller.toggleRetained(ready, itemIndex = 0)

        assertTrue(controller.retainedProposals(withdrawn).isEmpty())
        assertEquals(listOf("requirements/prd.md"), controller.retainedProposals(ready).map { it.path })
    }

    private fun readyState(): MarkdownFileChangeState =
        controller.markReady(
            state = controller.createPlanningDraft("conversation", "project", "user-1"),
            plan = MarkdownUpdatePlan(
                proposals = listOf(
                    MarkdownUpdateProposal(
                        operation = MarkdownUpdateOperation.CREATE,
                        path = "requirements/prd.md",
                        title = "PRD",
                        reason = "新建项目需求文档",
                        markdown = "# PRD\n\n内容",
                    ),
                ),
            ),
            snapshots = emptyList(),
        )
}
