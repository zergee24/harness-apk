package com.harnessapk.project

import androidx.room.withTransaction
import com.harnessapk.storage.AppDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private fun interface ProjectDeletionCleanupHook {
    suspend fun beforeDatabaseCleanup()
}

class DeleteProjectUseCase private constructor(
    private val projectRepository: FileProjectRepository,
    private val database: AppDatabase,
    private val beforeDatabaseCleanup: ProjectDeletionCleanupHook,
) {
    constructor(
        projectRepository: FileProjectRepository,
        database: AppDatabase,
    ) : this(projectRepository, database, ProjectDeletionCleanupHook {})

    internal constructor(
        projectRepository: FileProjectRepository,
        database: AppDatabase,
        beforeDatabaseCleanup: suspend () -> Unit,
    ) : this(projectRepository, database, ProjectDeletionCleanupHook { beforeDatabaseCleanup() })

    suspend fun delete(projectId: String) {
        projectRepository.deleteProject(projectId)
        withContext(NonCancellable) {
            database.withTransaction {
                beforeDatabaseCleanup.beforeDatabaseCleanup()
                database.conversationDao().clearProject(projectId)
                database.conversationMarkdownLinkDao().deleteForProject(projectId)
                database.markdownChangeDraftDao().deleteExecutableOrRetryableForProject(projectId)
            }
        }
    }
}
