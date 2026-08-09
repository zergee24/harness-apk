package com.harnessapk.projectsearch

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.harnessapk.common.AppContainer

class ProjectIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT_ID)?.takeIf(String::isNotBlank) ?: return Result.failure()
        return runCatching {
            // A worker must not enqueue a replacement for itself while building its dependencies.
            val container = AppContainer(
                context = applicationContext,
                scheduleProjectIndexWarmup = false,
            )
            val projectRoot = container.projectRepository.resolveProjectDirectory(projectId)
            val report = container.projectMarkdownIndexer.refreshProject(projectId, projectRoot)
            if (report.failedSources.isEmpty()) Result.success() else Result.retry()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val KEY_PROJECT_ID = "projectId"
        private const val UNIQUE_PREFIX = "m3-project-index:"

        fun enqueue(context: Context, projectId: String) {
            if (projectId.isBlank()) return
            val request = OneTimeWorkRequestBuilder<ProjectIndexWorker>()
                .setInputData(Data.Builder().putString(KEY_PROJECT_ID, projectId).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_PREFIX + projectId,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context, projectId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PREFIX + projectId)
        }
    }
}
