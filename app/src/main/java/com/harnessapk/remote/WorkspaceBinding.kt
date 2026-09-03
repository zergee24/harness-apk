package com.harnessapk.remote

import com.harnessapk.storage.ProjectRemoteBindingEntity
import com.harnessapk.storage.RemoteDao
import java.util.Locale
import java.util.UUID

data class WorkspaceCandidate(
    val workspaceId: String,
    val displayName: String,
    val cwd: String,
    val repositoryLabel: String?,
    val branch: String?,
    val repositoryFingerprint: String,
    val lastUsedAt: Long,
)

data class BindingChangeEvaluation(
    val allowed: Boolean,
    val requiresConfirmation: Boolean,
)

fun evaluateBindingChange(
    existingFingerprint: String?,
    candidate: WorkspaceCandidate,
    confirmed: Boolean,
): BindingChangeEvaluation {
    val mismatch = existingFingerprint != null && existingFingerprint != candidate.repositoryFingerprint
    return BindingChangeEvaluation(
        allowed = !mismatch || confirmed,
        requiresConfirmation = mismatch && !confirmed,
    )
}

fun rankWorkspaceCandidates(
    projectName: String,
    candidates: List<WorkspaceCandidate>,
): List<WorkspaceCandidate> {
    val normalizedProject = normalizedWorkspaceLabel(projectName)
    return candidates.sortedWith(
        compareByDescending<WorkspaceCandidate> { candidate ->
            val labels = listOfNotNull(
                candidate.displayName,
                candidate.cwd.substringAfterLast('/'),
                candidate.repositoryLabel?.substringAfterLast('/'),
            ).map(::normalizedWorkspaceLabel)
            when {
                normalizedProject.isNotEmpty() && labels.any { it == normalizedProject } -> 2
                normalizedProject.isNotEmpty() && labels.any {
                    it.contains(normalizedProject) || normalizedProject.contains(it)
                } -> 1
                else -> 0
            }
        }.thenByDescending { it.lastUsedAt }
            .thenBy { it.displayName.lowercase(Locale.ROOT) },
    )
}

private fun normalizedWorkspaceLabel(value: String): String = value
    .lowercase(Locale.ROOT)
    .filter(Char::isLetterOrDigit)

class RemoteBindingRepository(
    private val dao: RemoteDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun bindingForProject(projectId: String, backendId: String): ProjectRemoteBindingEntity? =
        dao.bindingForProject(projectId, backendId)

    suspend fun bind(
        projectId: String,
        backendId: String,
        hostId: String,
        candidate: WorkspaceCandidate,
        confirmFingerprintChange: Boolean = false,
    ): ProjectRemoteBindingEntity {
        require(projectId.isNotBlank()) { "projectId is required" }
        require(backendId.isNotBlank()) { "backendId is required" }
        require(hostId.isNotBlank()) { "hostId is required" }
        val existing = dao.bindingForProject(projectId, backendId)
        val evaluation = evaluateBindingChange(
            existingFingerprint = existing?.repositoryFingerprint,
            candidate = candidate,
            confirmed = confirmFingerprintChange,
        )
        require(evaluation.allowed) { "工作区仓库指纹已变化，需要明确确认重新绑定" }
        val timestamp = now()
        val binding = ProjectRemoteBindingEntity(
            id = existing?.id ?: newId(),
            projectId = projectId,
            backendId = backendId,
            hostId = hostId,
            workspaceId = candidate.workspaceId,
            cwd = candidate.cwd,
            displayName = candidate.displayName,
            repositoryFingerprint = candidate.repositoryFingerprint,
            repositoryLabel = candidate.repositoryLabel,
            state = "ACTIVE",
            verifiedAt = timestamp,
            createdAt = existing?.createdAt ?: timestamp,
            updatedAt = timestamp,
        )
        dao.upsertBinding(binding)
        return binding
    }

    suspend fun unbind(projectId: String, backendId: String) {
        dao.deleteBindingByProject(projectId, backendId)
    }
}
