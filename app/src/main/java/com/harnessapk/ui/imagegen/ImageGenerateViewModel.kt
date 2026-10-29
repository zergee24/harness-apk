package com.harnessapk.ui.imagegen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.harnessapk.remote.ImageGenerateParams
import com.harnessapk.remote.ImageGeneratePhase
import com.harnessapk.remote.ImageGenerateRequest
import com.harnessapk.remote.ImageGenerateResult
import com.harnessapk.remote.ImageGenerateState
import com.harnessapk.remote.ImageGenerateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compose 可直接消费的生图界面状态：命令状态机 + 解码后的 Bitmap + 历史。 */
internal data class ImageGenerateUiState(
    val phase: ImageGeneratePhase = ImageGeneratePhase.IDLE,
    val request: ImageGenerateRequest? = null,
    val result: ImageGenerateResult? = null,
    val bitmap: Bitmap? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val history: List<ImageGenerateResult> = emptyList(),
    val isDecoding: Boolean = false,
    val decodeError: String? = null,
)

/**
 * 生图界面 ViewModel：把 [ImageGenerateStore] 的纯状态机桥接到 Compose，
 * 负责把 base64 解出的图片字节经 BitmapFactory 解码成 Bitmap（StateFlow 暴露）。
 */
internal class ImageGenerateViewModel(
    private val store: ImageGenerateStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val decodeBitmap: (ByteArray) -> Bitmap? = { data ->
        BitmapFactory.decodeByteArray(data, 0, data.size)
    },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _uiState = MutableStateFlow(ImageGenerateUiState())
    val uiState: StateFlow<ImageGenerateUiState> = _uiState.asStateFlow()

    private var displayedRequestId: String? = null
    private var decodeJob: Job? = null

    init {
        scope.launch {
            store.state.collect(::onStoreState)
        }
    }

    fun generate(params: ImageGenerateParams): Boolean = store.generate(params)

    fun dismissError() = store.dismissError()

    /** 历史记录里点回某一张：切换当前展示图并解码。 */
    fun previewHistory(result: ImageGenerateResult) {
        _uiState.update { it.copy(result = result, decodeError = null) }
        if (result.requestId != displayedRequestId) decode(result)
    }

    fun close() {
        decodeJob?.cancel()
        scope.cancel()
    }

    private fun onStoreState(state: ImageGenerateState) {
        _uiState.update { current ->
            current.copy(
                phase = state.phase,
                request = state.request,
                errorCode = state.errorCode,
                errorMessage = state.errorMessage,
                history = state.history,
                // 成功后总是切到最新结果；生成中/失败时保留上一张，避免画面闪空。
                result = if (state.phase == ImageGeneratePhase.SUCCESS && state.result != null) {
                    state.result
                } else {
                    current.result
                },
            )
        }
        val result = _uiState.value.result ?: return
        if (result.requestId != displayedRequestId) decode(result)
    }

    private fun decode(result: ImageGenerateResult) {
        decodeJob?.cancel()
        displayedRequestId = result.requestId
        _uiState.update { it.copy(bitmap = null, isDecoding = true, decodeError = null) }
        decodeJob = scope.launch {
            val bitmap = withContext(ioDispatcher) {
                runCatching { decodeBitmap(result.data) }.getOrNull()
            }
            _uiState.update { current ->
                if (current.result?.requestId != result.requestId) {
                    current
                } else {
                    current.copy(
                        bitmap = bitmap,
                        isDecoding = false,
                        decodeError = if (bitmap == null) "图片解码失败，可能是格式不受支持" else null,
                    )
                }
            }
        }
    }
}
