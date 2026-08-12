package com.harnessapk.remote

import com.harnessapk.storage.RemoteRunEventEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RemoteCompletionEvidence(
    val summary: String,
    val changedFiles: List<String>,
    val tests: List<RemoteTestEvidence>,
    val gitState: String?,
    val unresolved: List<String>,
    val completedAt: Long,
    val schemaVersion: Int = 1,
    val completionId: String? = null,
    val files: List<RemoteFileEvidence> = emptyList(),
    val workspace: RemoteWorkspaceLocator? = null,
    val verification: RemoteCompletionVerification = RemoteCompletionVerification.LEGACY_UNVERIFIED,
) {
    val fileSummary: String
        get() = if (changedFiles.isEmpty()) "文件未验证" else "文件 ${changedFiles.size} 个"

    val testSummary: String
        get() {
            if (tests.isEmpty()) return "测试未验证"
            val passed = tests.count { it.status == "PASSED" }
            val failed = tests.count { it.status == "FAILED" }
            val unverified = tests.count { it.status == "UNVERIFIED" }
            return buildString {
                append("测试 ")
                append("$passed 通过 · $failed 失败")
                if (unverified > 0) append(" · $unverified 未验证")
            }
        }

    val gitSummary: String
        get() = when (gitState) {
            "COMMITTED" -> "Git 已提交"
            "UNCOMMITTED" -> "Git 有未提交改动"
            "CLEAN" -> "Git 工作区干净"
            else -> "Git 未验证"
        }
}

data class RemoteTestEvidence(
    val command: String,
    val status: String,
    val exitCode: Int?,
    val evidenceId: String? = null,
    val evidenceSha256: String? = null,
)

data class RemoteFileEvidence(
    val evidenceId: String?,
    val evidenceSha256: String?,
    val path: String,
    val source: String?,
)

data class RemoteWorkspaceLocator(
    val workspaceId: String?,
    val repositoryFingerprint: String?,
    val cwd: String?,
)

enum class RemoteCompletionVerification {
    VERIFIED_V2,
    UNVERIFIED_V2,
    LEGACY_UNVERIFIED,
}

data class RemoteTimelinePresentation(
    val id: String,
    val itemId: String?,
    val title: String,
    val detail: String,
    val diagnosticPayload: String,
    val createdAt: Long,
    val compressible: Boolean,
    val kind: String,
)

internal fun parseRemoteCompletionEvidence(raw: String): RemoteCompletionEvidence {
    val root = Json.parseToJsonElement(raw).jsonObject
    val schemaVersion = root.long("schemaVersion")?.toInt() ?: 1
    require(schemaVersion in 1..2) { "不支持的 Remote completion schema：$schemaVersion" }
    val files = (root["changedFiles"] as? JsonArray).orEmpty().mapNotNull { element ->
        val file = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        val path = file.string("path") ?: return@mapNotNull null
        RemoteFileEvidence(
            evidenceId = file.string("evidenceId"),
            evidenceSha256 = file.string("evidenceSha256"),
            path = path,
            source = file.string("source"),
        )
    }
    val tests = (root["tests"] as? JsonArray).orEmpty().mapNotNull { element ->
        val test = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        val command = test.string("command") ?: return@mapNotNull null
        RemoteTestEvidence(
            command = command,
            status = test.string("status") ?: "UNVERIFIED",
            exitCode = test.long("exitCode")?.toInt(),
            evidenceId = test.string("evidenceId"),
            evidenceSha256 = test.string("evidenceSha256"),
        )
    }
    val unresolved = (root["unresolved"] as? JsonArray).orEmpty().mapNotNull {
        runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
    }
    val workspace = (root["workspace"] as? JsonObject)?.let { locator ->
        RemoteWorkspaceLocator(
            workspaceId = locator.string("workspaceId"),
            repositoryFingerprint = locator.string("repositoryFingerprint"),
            cwd = locator.string("cwd"),
        )
    }
    val completionId = root.string("completionId")
    val hasStableEvidence = files.all { !it.evidenceId.isNullOrBlank() && !it.evidenceSha256.isNullOrBlank() } &&
        tests.all { !it.evidenceId.isNullOrBlank() && !it.evidenceSha256.isNullOrBlank() }
    val verification = when {
        schemaVersion < 2 -> RemoteCompletionVerification.LEGACY_UNVERIFIED
        !completionId.isNullOrBlank() && hasStableEvidence -> RemoteCompletionVerification.VERIFIED_V2
        else -> RemoteCompletionVerification.UNVERIFIED_V2
    }
    return RemoteCompletionEvidence(
        summary = root.string("summary") ?: "任务已完成",
        changedFiles = files.map(RemoteFileEvidence::path),
        tests = tests,
        gitState = (root["git"] as? JsonObject)?.string("state"),
        unresolved = unresolved,
        completedAt = root.long("completedAt") ?: 0L,
        schemaVersion = schemaVersion,
        completionId = completionId,
        files = files,
        workspace = workspace,
        verification = verification,
    )
}

internal fun presentRemoteTimeline(event: RemoteRunEventEntity): RemoteTimelinePresentation {
    val payload = runCatching { Json.parseToJsonElement(event.payloadJson) as? JsonObject }.getOrNull()
        ?: JsonObject(emptyMap())
    val kind = event.presentationKind
    val detail = when (kind) {
        "AGENT_DELTA" -> payload.string("delta").orEmpty()
        "OBJECTIVE", "STEER" -> payload.string("text") ?: payload.string("objective").orEmpty()
        "APPROVAL_DECISION" -> payload.string("decision").orEmpty()
        "INTERRUPT" -> payload.string("latestLine") ?: "已请求停止"
        else -> payload.string("detail") ?: payload.string("latestLine").orEmpty()
    }
    val title = when (kind) {
        "ANALYZING" -> "正在分析"
        "SEARCHING" -> "正在查找"
        "FILES" -> payload.long("fileCount")?.let { "正在修改 $it 个文件" } ?: "正在修改文件"
        "TEST" -> "正在运行测试"
        "APPROVAL", "APPROVAL_DECISION" -> "等待你的确认"
        "RESULT", "AGENT_DELTA" -> "正在整理结果"
        "OBJECTIVE" -> "任务目标"
        "STEER" -> "补充方向"
        "INTERRUPT" -> "停止任务"
        "COMPLETION" -> "已完成"
        "CANCELLED" -> "已停止"
        "RECOVERY" -> "需要恢复"
        else -> "正在处理"
    }
    return RemoteTimelinePresentation(
        id = event.logicalEventId,
        itemId = event.itemId,
        title = title,
        detail = detail,
        diagnosticPayload = event.payloadJson,
        createdAt = event.createdAt,
        compressible = kind !in setOf("OBJECTIVE", "STEER", "APPROVAL_DECISION", "INTERRUPT", "COMPLETION", "CANCELLED"),
        kind = kind,
    )
}

internal fun collapseRemoteTimeline(events: List<RemoteRunEventEntity>): List<RemoteTimelinePresentation> {
    val result = mutableListOf<RemoteTimelinePresentation>()
    val itemIndexes = mutableMapOf<String, Int>()
    events.forEach { event ->
        val presented = presentRemoteTimeline(event)
        val itemKey = presented.itemId?.takeIf { presented.compressible }
        if (itemKey != null && itemKey in itemIndexes) {
            val index = requireNotNull(itemIndexes[itemKey])
            val existing = result[index]
            result[index] = if (presented.kind == "AGENT_DELTA" && existing.kind == "AGENT_DELTA") {
                presented.copy(detail = existing.detail + presented.detail)
            } else {
                presented
            }
        } else if (
            presented.compressible && itemKey == null && result.lastOrNull()?.let {
                it.compressible && it.title == presented.title && it.itemId == null
            } == true
        ) {
            result[result.lastIndex] = presented
        } else {
            if (itemKey != null) itemIndexes[itemKey] = result.size
            result += presented
        }
    }
    return result
}
