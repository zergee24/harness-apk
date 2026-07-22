package com.harnessapk.session

import java.util.UUID

enum class MarkdownFileChangeStatus {
    PLANNING,
    READY,
    APPLIED,
    PARTIALLY_APPLIED,
    DISMISSED,
    FAILED,
}

data class MarkdownFileChangeDraft(
    val id: String,
    val conversationId: String,
    val projectId: String,
    val sourceUserMessageId: String,
    val status: MarkdownFileChangeStatus,
    val summary: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class MarkdownFileChangeConversationContext(
    val text: String,
    val messageIds: List<String>,
)

class MarkdownFileChangePlanningException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

data class MarkdownFileChangeItem(
    val draftId: String,
    val operation: MarkdownUpdateOperation,
    val path: String,
    val title: String,
    val reason: String,
    val markdown: String,
    val addedLineCount: Int,
    val removedLineCount: Int,
    val retained: Boolean,
)

data class MarkdownFileChangeFailure(
    val proposal: MarkdownUpdateProposal,
    val errorMessage: String,
)

data class MarkdownFileChangeState(
    val draft: MarkdownFileChangeDraft,
    val items: List<MarkdownFileChangeItem> = emptyList(),
    val diffs: List<List<MarkdownDiffLine>> = emptyList(),
    val appliedPaths: List<String> = emptyList(),
    val applyFailures: List<MarkdownFileChangeFailure> = emptyList(),
)

class MarkdownFileChangeController(
    private val timeProvider: () -> Long,
) {
    fun createPlanningDraft(
        conversationId: String,
        projectId: String,
        sourceUserMessageId: String,
    ): MarkdownFileChangeState {
        val now = timeProvider()
        return MarkdownFileChangeState(
            draft = MarkdownFileChangeDraft(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                projectId = projectId,
                sourceUserMessageId = sourceUserMessageId,
                status = MarkdownFileChangeStatus.PLANNING,
                summary = "正在生成 Markdown 文件变更...",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    fun markPlanning(state: MarkdownFileChangeState): MarkdownFileChangeState =
        state.copy(
            draft = state.draft.copy(
                status = MarkdownFileChangeStatus.PLANNING,
                summary = "正在生成 Markdown 文件变更...",
                updatedAt = timeProvider(),
            ),
            items = emptyList(),
            diffs = emptyList(),
            appliedPaths = emptyList(),
            applyFailures = emptyList(),
        )

    fun markReady(
        state: MarkdownFileChangeState,
        plan: MarkdownUpdatePlan,
        snapshots: List<MarkdownSnapshot>,
    ): MarkdownFileChangeState {
        if (plan.proposals.isEmpty()) {
            return markFailed(state, "没有生成可审核的 Markdown 更新")
        }

        val markdownByPath = snapshots.associateBy { it.path }
        val diffs = plan.proposals.map { proposal ->
            buildMarkdownDiff(
                oldMarkdown = markdownByPath[proposal.path]?.markdown.orEmpty(),
                newMarkdown = proposal.markdown,
            )
        }
        val items = plan.proposals.mapIndexed { index, proposal ->
            val stats = markdownDiffStats(diffs[index])
            MarkdownFileChangeItem(
                draftId = state.draft.id,
                operation = proposal.operation,
                path = proposal.path,
                title = proposal.title,
                reason = proposal.reason,
                markdown = proposal.markdown,
                addedLineCount = stats.addedLineCount,
                removedLineCount = stats.removedLineCount,
                retained = true,
            )
        }

        return state.copy(
            draft = state.draft.copy(
                status = MarkdownFileChangeStatus.READY,
                summary = "已生成 ${items.size} 个 Markdown 文件变更",
                updatedAt = timeProvider(),
            ),
            items = items,
            diffs = diffs,
            appliedPaths = emptyList(),
            applyFailures = emptyList(),
        )
    }

    fun markFailed(state: MarkdownFileChangeState, reason: String): MarkdownFileChangeState =
        state.copy(
            draft = state.draft.copy(
                status = MarkdownFileChangeStatus.FAILED,
                summary = reason.ifBlank { "Markdown 文件变更生成失败" },
                updatedAt = timeProvider(),
            ),
            items = emptyList(),
            diffs = emptyList(),
            appliedPaths = emptyList(),
            applyFailures = emptyList(),
        )

    fun toggleRetained(state: MarkdownFileChangeState, itemIndex: Int): MarkdownFileChangeState =
        state.copy(
            draft = state.draft.copy(updatedAt = timeProvider()),
            items = state.items.mapIndexed { index, item ->
                if (index == itemIndex) item.copy(retained = !item.retained) else item
            },
        )

    fun dismiss(state: MarkdownFileChangeState): MarkdownFileChangeState =
        state.copy(
            draft = state.draft.copy(
                status = MarkdownFileChangeStatus.DISMISSED,
                summary = "已撤回 Markdown 文件变更",
                updatedAt = timeProvider(),
            ),
        )

    fun markApplied(
        state: MarkdownFileChangeState,
        writtenPaths: List<String>,
    ): MarkdownFileChangeState =
        state.copy(
            draft = state.draft.copy(
                status = MarkdownFileChangeStatus.APPLIED,
                summary = "已应用 ${writtenPaths.size} 个 Markdown 文件变更",
                updatedAt = timeProvider(),
            ),
            appliedPaths = writtenPaths.distinct(),
            applyFailures = emptyList(),
        )

    fun markApplyResult(
        state: MarkdownFileChangeState,
        result: MarkdownBatchApplyResult,
    ): MarkdownFileChangeState {
        val appliedPaths = (
            state.appliedPaths + result.succeeded.mapNotNull { it.writtenDeliverable?.path }
        ).distinct()
        val failures = result.failed.map { failed ->
            MarkdownFileChangeFailure(
                proposal = failed.proposal,
                errorMessage = failed.errorMessage.orEmpty().ifBlank { "文件写入失败" },
            )
        }
        val status = when {
            failures.isEmpty() && appliedPaths.isNotEmpty() -> MarkdownFileChangeStatus.APPLIED
            failures.isNotEmpty() && appliedPaths.isNotEmpty() -> MarkdownFileChangeStatus.PARTIALLY_APPLIED
            else -> MarkdownFileChangeStatus.FAILED
        }
        val summary = when (status) {
            MarkdownFileChangeStatus.APPLIED -> "已写入 ${appliedPaths.size} 个 Markdown 文件"
            MarkdownFileChangeStatus.PARTIALLY_APPLIED ->
                "已写入 ${appliedPaths.size} 个，失败 ${failures.size} 个 Markdown 文件"
            MarkdownFileChangeStatus.FAILED -> "${failures.size} 个 Markdown 文件写入失败"
            else -> state.draft.summary
        }
        return state.copy(
            draft = state.draft.copy(status = status, summary = summary, updatedAt = timeProvider()),
            appliedPaths = appliedPaths,
            applyFailures = failures,
        )
    }

    fun retryableProposals(state: MarkdownFileChangeState): List<MarkdownUpdateProposal> =
        state.applyFailures.map { it.proposal }

    fun retainedProposals(state: MarkdownFileChangeState): List<MarkdownUpdateProposal> =
        state.items
            .filter { it.retained }
            .map {
                MarkdownUpdateProposal(
                    operation = it.operation,
                    path = it.path,
                    title = it.title,
                    reason = it.reason,
                    markdown = it.markdown,
                )
            }
}
