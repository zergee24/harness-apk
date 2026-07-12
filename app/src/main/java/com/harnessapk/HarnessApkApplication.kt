package com.harnessapk

import android.app.Application
import com.harnessapk.common.AppContainer
import com.harnessapk.chat.ChatExecutionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HarnessApkApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (container.chatExecutionRepository.hasOpenWork()) {
                ChatExecutionService.start(this@HarnessApkApplication)
            }
        }
    }
}
