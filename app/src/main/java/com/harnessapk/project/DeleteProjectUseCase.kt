package com.harnessapk.project

import androidx.room.withTransaction
import com.harnessapk.common.TimeProvider
import com.harnessapk.storage.AppDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class DeleteProjectUseCase(
    private val projectRepository: FileProjectRepository,
    private val database: AppDatabase,
    private val timeProvider: TimeProvider,
) {
    suspend fun delete(projectId: String) {
        projectRepository.deleteProject(projectId)
        withContext(NonCancellable) {
            database.withTransaction {
                database.conversationDao().clearProject(projectId, timeProvider.nowMillis())
                database.conversationMarkdownLinkDao().deleteForProject(projectId)
                database.markdownChangeDraftDao().deleteExecutableOrRetryableForProject(projectId)
            }
        }
    }
}
