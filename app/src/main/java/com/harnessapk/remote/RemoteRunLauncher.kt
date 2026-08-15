package com.harnessapk.remote

import androidx.room.withTransaction
import com.harnessapk.project.Project
import com.harnessapk.storage.AppDatabase
import com.harnessapk.storage.ProjectRemoteBindingEntity
import com.harnessapk.storage.RemoteRunEntity
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class LaunchedRemoteRun(
    val run: RemoteRunEntity,
    val command: RebuiltRemoteCommand,
)

class RemoteRunLauncher(
    private val database: AppDatabase,
    private val outbox: RemoteCommandOutbox,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun launch(
        project: Project,
        binding: ProjectRemoteBindingEntity,
        objective: String,
        runId: String = newId(),
        commandId: String = "run.start:$runId",
    ): LaunchedRemoteRun {
        val normalizedObjective = objective.trim()
        require(normalizedObjective.isNotEmpty()) { "任务目标不能为空" }
        require(binding.projectId == project.id) { "binding does not belong to project" }
        require(binding.state == "ACTIVE") { "binding is not active" }
        val timestamp = now()
        val bindingSnapshot = bindingSnapshot(binding)
        val run = RemoteRunEntity(
            id = runId,
            projectId = project.id,
            projectNameSnapshot = project.name,
            bindingId = binding.id,
            bindingSnapshotJson = canonicalJson(bindingSnapshot),
            hostId = binding.hostId,
            backendId = binding.backendId,
            threadId = null,
            turnId = null,
            objective = normalizedObjective,
            status = RemoteRunStatus.QUEUED.name,
            latestLine = "等待 Mac 接收",
            lastLogicalSequence = 0L,
            startedAt = timestamp,
            updatedAt = timestamp,
            completedAt = null,
            completionJson = null,
            errorMessage = null,
        )
        val payload = RemoteM2Command.Start(
            commandId = commandId,
            runId = runId,
            bindingId = binding.id,
            workspaceId = binding.workspaceId,
            repositoryFingerprint = binding.repositoryFingerprint,
            objective = normalizedObjective,
            contextSnapshot = buildJsonObject {
                put("schemaVersion", 1)
                put("projectId", project.id)
                put("projectName", project.name)
                put("binding", bindingSnapshot)
            },
        ).toJson()
        return database.withTransaction {
            val existingRun = database.remoteDao().run(runId)
            if (existingRun != null) {
                val existingCommand = requireNotNull(outbox.rebuild(commandId))
                require(existingRun.objective == normalizedObjective) { "runId already belongs to another objective" }
                return@withTransaction LaunchedRemoteRun(existingRun, existingCommand)
            }
            database.remoteDao().insertRun(run)
            val command = outbox.enqueue(
                commandId = commandId,
                runId = runId,
                type = "run.start",
                payload = payload,
                now = timestamp,
            )
            LaunchedRemoteRun(run, command)
        }
    }

    private fun bindingSnapshot(binding: ProjectRemoteBindingEntity): JsonObject = buildJsonObject {
        put("bindingId", binding.id)
        put("backendId", binding.backendId)
        put("hostId", binding.hostId)
        put("workspaceId", binding.workspaceId)
        put("displayName", binding.displayName)
        binding.repositoryLabel?.let { put("repositoryLabel", it) }
        put("repositoryFingerprint", binding.repositoryFingerprint)
        put("verifiedAt", binding.verifiedAt)
    }
}
