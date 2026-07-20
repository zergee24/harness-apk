package com.harnessapk.updater

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(val versionCode: Int) : UpdateDownloadState
    data class Ready(val versionCode: Int, val result: ApkDownloadResult) : UpdateDownloadState
    data class Failed(val versionCode: Int, val message: String) : UpdateDownloadState
}

class UpdateDownloadCoordinator(
    private val downloader: UpdateArtifactDownloader,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val state: StateFlow<UpdateDownloadState> = mutableState.asStateFlow()

    fun startDownload(manifest: UpdateManifest) {
        scope.launch {
            runCatching { download(manifest) }
        }
    }

    suspend fun download(manifest: UpdateManifest): ApkDownloadResult = mutex.withLock {
        val ready = mutableState.value as? UpdateDownloadState.Ready
        if (
            ready?.versionCode == manifest.versionCode &&
            ready.result.file.exists() &&
            ready.result.sha256.equals(manifest.sha256, ignoreCase = true)
        ) {
            return ready.result
        }

        mutableState.value = UpdateDownloadState.Downloading(manifest.versionCode)
        try {
            withContext(ioDispatcher) {
                downloader.downloadApk(manifest)
            }.also { result ->
                mutableState.value = UpdateDownloadState.Ready(manifest.versionCode, result)
            }
        } catch (error: CancellationException) {
            mutableState.value = UpdateDownloadState.Idle
            throw error
        } catch (error: Throwable) {
            mutableState.value = UpdateDownloadState.Failed(
                versionCode = manifest.versionCode,
                message = error.message ?: "更新下载失败",
            )
            throw error
        }
    }
}
