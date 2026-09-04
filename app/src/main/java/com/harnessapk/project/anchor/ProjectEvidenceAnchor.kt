package com.harnessapk.project.anchor

enum class AnchorType {
    PROJECT_FILE,
    FILE_RANGE,
    DIFF,
    COMMIT,
    TEST_EVIDENCE,
    RUN_EVENT,
    RUN_COMPLETION,
    SCREENSHOT_REGION,
}

data class ProjectEvidenceAnchor(
    val id: String,
    val type: AnchorType,
    val projectId: String,
    val relativePath: String?,
    val contentHash: String,
    val gitRef: String?,
    val runSnapshotId: String?,
    val startLine: Int?,
    val endLine: Int?,
    val excerpt: String?,
    val createdAt: Long,
)
