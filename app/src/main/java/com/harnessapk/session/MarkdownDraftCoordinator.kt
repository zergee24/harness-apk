package com.harnessapk.session

import com.harnessapk.remote.RemoteCompletionEvidence
import com.harnessapk.remote.RemoteCompletionVerification
import com.harnessapk.storage.MarkdownChangeDraftEntity
import com.harnessapk.storage.MarkdownChangeDraftItemEntity
import com.harnessapk.storage.MarkdownDraftOriginEntity
import java.security.MessageDigest
import java.util.UUID

enum class MarkdownDraftOriginType {
    ASSISTANT_MESSAGE,
    REMOTE_RUN,
    EXPLICIT_CHANGE,
}

data class MarkdownDraftOwner(
    val projectId: String,
    val conversationId: String? = null,
    val sourceUserMessageId: String? = null,
    val assistantMessageId: String? = null,
)

data class MarkdownDraftOrigin(
    val type: MarkdownDraftOriginType,
    val sourceId: String,
    val sourceSha256: String,
    val sourceProjectId: String?,
)

data class PersistedMarkdownDraft(
    val draft: MarkdownChangeDraftEntity,
    val items: List<MarkdownChangeDraftItemEntity>,
    val origin: MarkdownDraftOriginEntity,
    val contextFacts: List<ContextFactCandidate> = emptyList(),
)

interface MarkdownDraftStore {
    suspend fun find(originType: MarkdownDraftOriginType, sourceId: String): PersistedMarkdownDraft?
    suspend fun save(record: PersistedMarkdownDraft)
}

class MarkdownDraftCoordinator(
    private val store: MarkdownDraftStore,
    private val timeProvider: () -> Long,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun persistPlanning(
        owner: MarkdownDraftOwner,
        origin: MarkdownDraftOrigin,
        preferredDraftId: String? = null,
    ): PersistedMarkdownDraft = persistLifecycleState(
        owner = owner,
        origin = origin,
        preferredDraftId = preferredDraftId,
        status = "PLANNING",
        summary = "正在生成 Markdown 文件变更...",
        errorMessage = null,
    )

    suspend fun persistFailure(
        owner: MarkdownDraftOwner,
        origin: MarkdownDraftOrigin,
        errorMessage: String,
        preferredDraftId: String? = null,
    ): PersistedMarkdownDraft = persistLifecycleState(
        owner = owner,
        origin = origin,
        preferredDraftId = preferredDraftId,
        status = "FAILED",
        summary = errorMessage.ifBlank { "Markdown 文件变更生成失败" },
        errorMessage = errorMessage.ifBlank { null },
    )

    suspend fun persistPlan(
        owner: MarkdownDraftOwner,
        origin: MarkdownDraftOrigin,
        plan: MarkdownUpdatePlan,
        snapshots: List<MarkdownSnapshot>,
        rawResponse: String? = null,
        preferredDraftId: String? = null,
    ): PersistedMarkdownDraft {
        validateOwnerAndOrigin(owner, origin)
        val existing = store.find(origin.type, origin.sourceId)
        require(existing == null || existing.origin.sourceSha256 == origin.sourceSha256) {
            "同一 Draft Origin 的内容哈希发生变化"
        }
        require(existing == null || existing.draft.projectId == owner.projectId) {
            "同一 Draft Origin 不得跨项目复用"
        }
        if (existing?.draft?.status in NON_REGRESSIBLE_STATUSES) return requireNotNull(existing)
        val now = timeProvider()
        val draftId = existing?.draft?.id ?: preferredDraftId ?: idFactory()
        val byPath = snapshots.associateBy(MarkdownSnapshot::path)
        val items = plan.proposals.mapIndexed { index, proposal ->
            val snapshot = byPath[proposal.path]
            val baseline = proposal.baselineSha256 ?: snapshot?.markdown?.sha256()
            val expectedAbsent = proposal.expectedAbsent ||
                (proposal.operation == MarkdownUpdateOperation.CREATE && snapshot == null)
            if (proposal.operation == MarkdownUpdateOperation.UPDATE) {
                require(!baseline.isNullOrBlank()) { "UPDATE 必须有可复核的文件基线：${proposal.path}" }
            }
            MarkdownChangeDraftItemEntity(
                id = idFactory(),
                draftId = draftId,
                itemIndex = index,
                operation = proposal.operation.name,
                relativePath = proposal.path,
                title = proposal.title,
                reason = proposal.reason,
                proposedMarkdown = proposal.markdown,
                retained = true,
                baselineSha256 = baseline,
                expectedAbsent = expectedAbsent,
                applyStatus = null,
                applyErrorMessage = null,
            )
        }
        val noChanges = items.isEmpty()
        val draft = MarkdownChangeDraftEntity(
            id = draftId,
            conversationId = owner.conversationId,
            projectId = owner.projectId,
            sourceUserMessageId = owner.sourceUserMessageId,
            assistantMessageId = owner.assistantMessageId,
            status = if (noChanges) "NO_CHANGES" else "READY",
            summary = if (noChanges) "没有需要沉淀的稳定内容" else "已生成 ${items.size} 个 Markdown 文件变更",
            rawResponse = rawResponse,
            errorMessage = null,
            createdAt = existing?.draft?.createdAt ?: now,
            updatedAt = now,
        )
        val originEntity = MarkdownDraftOriginEntity(
            draftId = draftId,
            sourceType = origin.type.name,
            sourceId = origin.sourceId,
            sourceSha256 = origin.sourceSha256,
            sourceProjectId = origin.sourceProjectId,
            createdAt = now,
        )
        return PersistedMarkdownDraft(
            draft = draft,
            items = items,
            origin = originEntity,
            contextFacts = plan.contextFacts,
        ).also { store.save(it) }
    }

    private suspend fun persistLifecycleState(
        owner: MarkdownDraftOwner,
        origin: MarkdownDraftOrigin,
        preferredDraftId: String?,
        status: String,
        summary: String,
        errorMessage: String?,
    ): PersistedMarkdownDraft {
        validateOwnerAndOrigin(owner, origin)
        val existing = store.find(origin.type, origin.sourceId)
        require(existing == null || existing.origin.sourceSha256 == origin.sourceSha256) {
            "同一 Draft Origin 的内容哈希发生变化"
        }
        require(existing == null || existing.draft.projectId == owner.projectId) {
            "同一 Draft Origin 不得跨项目复用"
        }
        if (existing?.draft?.status in NON_REGRESSIBLE_STATUSES) return requireNotNull(existing)
        val now = timeProvider()
        val draftId = existing?.draft?.id ?: preferredDraftId ?: idFactory()
        return PersistedMarkdownDraft(
            draft = MarkdownChangeDraftEntity(
                id = draftId,
                conversationId = owner.conversationId,
                projectId = owner.projectId,
                sourceUserMessageId = owner.sourceUserMessageId,
                assistantMessageId = owner.assistantMessageId,
                status = status,
                summary = summary,
                rawResponse = existing?.draft?.rawResponse,
                errorMessage = errorMessage,
                createdAt = existing?.draft?.createdAt ?: now,
                updatedAt = now,
            ),
            items = if (status == "PLANNING") emptyList() else existing?.items.orEmpty(),
            origin = MarkdownDraftOriginEntity(
                draftId = draftId,
                sourceType = origin.type.name,
                sourceId = origin.sourceId,
                sourceSha256 = origin.sourceSha256,
                sourceProjectId = origin.sourceProjectId,
                createdAt = existing?.origin?.createdAt ?: now,
            ),
        ).also { store.save(it) }
    }

    private fun validateOwnerAndOrigin(owner: MarkdownDraftOwner, origin: MarkdownDraftOrigin) {
        require(origin.sourceId.isNotBlank()) { "Draft origin sourceId 不能为空" }
        require(origin.sourceSha256.matches(SHA256)) { "Draft origin 必须携带 SHA-256" }
        if (origin.type == MarkdownDraftOriginType.REMOTE_RUN) {
            require(owner.conversationId == null && owner.sourceUserMessageId == null) {
                "Remote Draft 不得伪造 Conversation 或用户消息"
            }
        }
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-fA-F]{64}$")
        val NON_REGRESSIBLE_STATUSES = setOf("READY", "NO_CHANGES", "APPLYING", "APPLIED", "PARTIALLY_APPLIED")
    }
}

internal fun stableMarkdownDraftId(
    originType: MarkdownDraftOriginType,
    projectId: String,
    sourceId: String,
): String {
    val hash = "M3|${originType.name}|$projectId|$sourceId".sha256()
    return listOf(
        hash.substring(0, 8),
        hash.substring(8, 12),
        hash.substring(12, 16),
        hash.substring(16, 20),
        hash.substring(20, 32),
    ).joinToString("-")
}

fun remoteCompletionMarkdownPlan(
    runId: String,
    evidence: RemoteCompletionEvidence,
): MarkdownUpdatePlan {
    require(evidence.verification == RemoteCompletionVerification.VERIFIED_V2) {
        "只有已冻结验证的 completion v2 可以沉淀到项目"
    }
    val safeRunId = runId.lowercase().replace(Regex("[^a-z0-9_-]"), "-").take(48).ifBlank { "run" }
    val markdown = buildString {
        appendLine("# Remote Run $runId")
        appendLine()
        appendLine(evidence.summary.trim().ifBlank { "任务已完成" })
        appendLine()
        appendLine("## 文件（Mac 工作区证据）")
        appendLine()
        if (evidence.changedFiles.isEmpty()) appendLine("- 未验证")
        else evidence.changedFiles.forEach { appendLine("- `$it`") }
        appendLine()
        appendLine("## 测试")
        appendLine()
        if (evidence.tests.isEmpty()) appendLine("- 未验证")
        else evidence.tests.forEach { test -> appendLine("- ${test.status}: `${test.command}`") }
        appendLine()
        appendLine("## Git")
        appendLine()
        appendLine("- ${evidence.gitSummary}")
        evidence.workspace?.cwd?.takeIf(String::isNotBlank)?.let { appendLine("- Mac 路径：`$it`") }
        appendLine()
        appendLine("## 遗留")
        appendLine()
        if (evidence.unresolved.isEmpty()) appendLine("- 无")
        else evidence.unresolved.forEach { appendLine("- $it") }
    }.trimEnd() + "\n"
    return MarkdownUpdatePlan(
        listOf(
            MarkdownUpdateProposal(
                operation = MarkdownUpdateOperation.CREATE,
                path = "reports/remote-run-$safeRunId.md",
                title = "Remote Run $runId",
                reason = "把冻结的 Remote Completion 证据整理为项目验收报告",
                markdown = markdown,
                expectedAbsent = true,
            ),
        ),
    )
}

internal fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
