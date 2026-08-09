package com.harnessapk.session

import com.harnessapk.storage.MarkdownChangeDraftEntity
import com.harnessapk.storage.MarkdownChangeDraftItemEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

interface MarkdownDraftApplyStore {
    suspend fun findDraft(draftId: String): MarkdownChangeDraftEntity?
    suspend fun listItems(draftId: String): List<MarkdownChangeDraftItemEntity>
    suspend fun claim(draftId: String, updatedAt: Long): Boolean
    suspend fun updateItem(itemId: String, status: String, errorMessage: String?)
    suspend fun updateDraft(draft: MarkdownChangeDraftEntity)
}

/**
 * Shared, Room-first apply pipeline for Assistant, Explicit and Remote origins.
 * Each file result is persisted before the next write starts, so retry and
 * process-death recovery never need to guess which writes completed.
 */
class MarkdownDraftApplyCoordinator(
    private val store: MarkdownDraftApplyStore,
    private val gateway: ProjectWorkspaceGateway,
    private val timeProvider: () -> Long,
    private val markContextFacts: suspend (draftId: String, status: String) -> Unit = { _, _ -> },
) {
    suspend fun apply(
        draftId: String,
        projectId: String,
        selectedItemIds: Set<String>,
    ): MarkdownBatchApplyResult {
        require(selectedItemIds.isNotEmpty()) { "没有可应用的保留项" }
        val selected = store.listItems(draftId)
            .filter { it.id in selectedItemIds && it.retained && it.applyStatus != MarkdownFileApplyStatus.SUCCEEDED.name }
        check(selected.isNotEmpty()) { "没有可应用的保留项" }
        check(store.claim(draftId, timeProvider())) { "该草稿正在应用或已完成" }
        val results = mutableListOf<MarkdownFileApplyResult>()
        try {
            selected.forEach { item ->
                val proposal = item.toProposal()
                val result = gateway.applyMarkdownUpdates(projectId, listOf(proposal)).results.single()
                store.updateItem(item.id, result.status.name, result.errorMessage)
                results += result
                if (isRootContextMarkdownPath(item.relativePath) && result.status == MarkdownFileApplyStatus.SUCCEEDED) {
                    markContextFacts(draftId, "APPLIED")
                }
            }
            finalizeDraft(draftId)
            dismissUnselectedContextFact(draftId, selectedItemIds)
            return MarkdownBatchApplyResult(results)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { finalizeInterruptedDraft(draftId, selected.mapTo(mutableSetOf()) { it.id }) }
            throw cancelled
        } catch (error: Throwable) {
            withContext(NonCancellable) { finalizeInterruptedDraft(draftId, selected.mapTo(mutableSetOf()) { it.id }) }
            throw error
        }
    }

    private suspend fun finalizeDraft(draftId: String) {
        val draft = requireNotNull(store.findDraft(draftId))
        val retained = store.listItems(draftId).filter(MarkdownChangeDraftItemEntity::retained)
        val succeeded = retained.count { it.applyStatus == MarkdownFileApplyStatus.SUCCEEDED.name }
        val failed = retained.count { it.applyStatus == MarkdownFileApplyStatus.FAILED.name }
        val status = when {
            succeeded > 0 && failed == 0 -> "APPLIED"
            succeeded > 0 -> "PARTIALLY_APPLIED"
            else -> "FAILED"
        }
        val summary = when (status) {
            "APPLIED" -> "已写入 $succeeded 个 Markdown 文件"
            "PARTIALLY_APPLIED" -> "已写入 $succeeded 个，失败 $failed 个 Markdown 文件"
            else -> "$failed 个 Markdown 文件写入失败"
        }
        store.updateDraft(
            draft.copy(status = status, summary = summary, errorMessage = null, updatedAt = timeProvider()),
        )
    }

    private suspend fun finalizeInterruptedDraft(draftId: String, selectedItemIds: Set<String>) {
        val draft = store.findDraft(draftId) ?: return
        if (draft.status != "APPLYING") return
        store.listItems(draftId)
            .filter { it.id in selectedItemIds && it.applyStatus == null }
            .forEach { store.updateItem(it.id, MarkdownFileApplyStatus.FAILED.name, "应用被中断，可安全重试") }
        val items = store.listItems(draftId).filter(MarkdownChangeDraftItemEntity::retained)
        val succeeded = items.count { it.applyStatus == MarkdownFileApplyStatus.SUCCEEDED.name }
        val status = if (succeeded > 0) "PARTIALLY_APPLIED" else "FAILED"
        store.updateDraft(
            draft.copy(
                status = status,
                summary = "应用被中断；已保存 $succeeded 个文件结果，可安全重试其余项",
                errorMessage = "应用被中断",
                updatedAt = timeProvider(),
            ),
        )
    }

    private suspend fun dismissUnselectedContextFact(draftId: String, selectedItemIds: Set<String>) {
        val context = store.listItems(draftId).firstOrNull {
            isRootContextMarkdownPath(it.relativePath) && it.applyStatus == null
        } ?: return
        if (context.id !in selectedItemIds) markContextFacts(draftId, "DISMISSED")
    }
}

private fun MarkdownChangeDraftItemEntity.toProposal() = MarkdownUpdateProposal(
    operation = MarkdownUpdateOperation.valueOf(operation),
    path = relativePath,
    title = title,
    reason = reason,
    markdown = proposedMarkdown,
    baselineSha256 = baselineSha256,
    expectedAbsent = expectedAbsent,
)

private fun isRootContextMarkdownPath(path: String): Boolean =
    path.trim().replace('\\', '/').removePrefix("./").equals("context.md", ignoreCase = true)
