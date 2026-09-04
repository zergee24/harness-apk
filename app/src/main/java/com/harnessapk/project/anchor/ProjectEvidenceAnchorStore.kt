package com.harnessapk.project.anchor

import java.util.UUID

interface ProjectEvidenceAnchorStore {
    fun create(
        type: AnchorType,
        projectId: String,
        contentHash: String,
        relativePath: String? = null,
        gitRef: String? = null,
        runSnapshotId: String? = null,
        startLine: Int? = null,
        endLine: Int? = null,
        excerpt: String? = null,
    ): ProjectEvidenceAnchor

    fun get(anchorId: String): ProjectEvidenceAnchor?
    fun listByProject(projectId: String): List<ProjectEvidenceAnchor>
}

class InMemoryProjectEvidenceAnchorStore : ProjectEvidenceAnchorStore {
    private data class Entry(
        val anchor: ProjectEvidenceAnchor,
        val sequence: Long,
    )

    private val anchors = linkedMapOf<String, Entry>()
    private var sequence = 0L

    override fun create(
        type: AnchorType,
        projectId: String,
        contentHash: String,
        relativePath: String?,
        gitRef: String?,
        runSnapshotId: String?,
        startLine: Int?,
        endLine: Int?,
        excerpt: String?,
    ): ProjectEvidenceAnchor {
        val anchor = ProjectEvidenceAnchor(
            id = UUID.randomUUID().toString(),
            type = type,
            projectId = projectId,
            relativePath = relativePath,
            contentHash = contentHash,
            gitRef = gitRef,
            runSnapshotId = runSnapshotId,
            startLine = startLine,
            endLine = endLine,
            excerpt = excerpt,
            createdAt = System.currentTimeMillis(),
        )
        ProjectEvidenceAnchorValidator.requireValid(anchor)
        anchors[anchor.id] = Entry(anchor, ++sequence)
        return anchor
    }

    override fun get(anchorId: String): ProjectEvidenceAnchor? = anchors[anchorId]?.anchor

    override fun listByProject(projectId: String): List<ProjectEvidenceAnchor> =
        anchors.values
            .filter { it.anchor.projectId == projectId }
            .sortedByDescending { it.sequence }
            .map { it.anchor }
}
