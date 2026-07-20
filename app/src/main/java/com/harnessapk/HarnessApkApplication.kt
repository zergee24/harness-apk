package com.harnessapk

import android.app.Application
import android.util.Log
import com.harnessapk.common.AppContainer
import com.harnessapk.chat.ChatExecutionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HarnessApkApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val container: AppContainer by lazy { AppContainer(this, applicationScope) }

    override fun onCreate() {
        super.onCreate()
        launchApplicationRecoveryJobs(
            scope = applicationScope,
            recoverAgentFiles = { container.agentRepository.recoverFileLifecycle() },
            hasOpenChatWork = { container.chatExecutionRepository.hasOpenWork() },
            startChatService = { ChatExecutionService.start(this@HarnessApkApplication) },
            onFailure = { task, error -> Log.e(TAG, "Application recovery failed: $task", error) },
        )
    }

    private companion object {
        const val TAG = "HarnessApkApplication"
    }
}

internal data class ApplicationRecoveryJobs(
    val agentFiles: Job,
    val chatExecution: Job,
)

internal fun launchApplicationRecoveryJobs(
    scope: CoroutineScope,
    recoverAgentFiles: suspend () -> Unit,
    hasOpenChatWork: suspend () -> Boolean,
    startChatService: () -> Unit,
    onFailure: (String, Throwable) -> Unit,
): ApplicationRecoveryJobs {
    fun launchIsolated(task: String, block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onFailure(task, error)
        }
    }

    return ApplicationRecoveryJobs(
        agentFiles = launchIsolated("agent-files", recoverAgentFiles),
        chatExecution = launchIsolated("chat-execution") {
            if (hasOpenChatWork()) startChatService()
        },
    )
}
