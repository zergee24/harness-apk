package com.harnessapk.project.anchor

import com.harnessapk.storage.CodeAnchorEntity

/**
 * CodeAnchorEntity（Room 持久层，无 projectId 列）→ ProjectEvidenceAnchor（锚点协议）。
 * projectId 由调用方注入（来自简报归属）。
 */
fun CodeAnchorEntity.toEvidenceAnchor(projectId: String): ProjectEvidenceAnchor {
    val type = runCatching { AnchorType.valueOf(this.type) }
        .getOrElse { AnchorType.PROJECT_FILE }
    val anchor = ProjectEvidenceAnchor(
        id = this.id,
        type = type,
        projectId = projectId,
        relativePath = this.relativePath,
        contentHash = this.contentHash,
        gitRef = null,
        runSnapshotId = null,
        startLine = this.startLine,
        endLine = this.endLine,
        excerpt = this.manualLabel,
        createdAt = this.createdAt,
    )
    ProjectEvidenceAnchorValidator.requireValid(anchor)
    return anchor
}
