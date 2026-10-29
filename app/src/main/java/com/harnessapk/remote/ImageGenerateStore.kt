package com.harnessapk.remote

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * 生图状态仓库：负责 `image.generate` 命令状态机、事件关联、超时与最近结果缓存。
 *
 * 传输完全复用现有远程链路（[RemoteRepository] 的加密 WS + [RemoteEvent] 分发）：
 * - 发命令：[sendCommand] 由 RemoteRepository 注入，走 `sendPayload`（不注入 backendId、不走 rpc 看门狗）；
 * - 收事件：[onEvent] 由 RemoteRepository.handleEvent 在 `image.generated` / `image.error` 时调用；
 * - 关联：requestId 由本仓库生成，事件必须原样回传该 id。
 *
 * 纯 Kotlin（无 Android 依赖），状态机可直接单元测试。
 */
class ImageGenerateStore(
    private val scope: CoroutineScope,
    private val sendCommand: (requestId: String, command: JsonObject) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = DEFAULT_IMAGE_GENERATE_TIMEOUT_MILLIS,
    private val historyLimit: Int = DEFAULT_IMAGE_HISTORY_LIMIT,
    private val newRequestId: () -> String = { "$IMAGE_GENERATE_COMMAND_TYPE:${UUID.randomUUID()}" },
) {
    private val _state = MutableStateFlow(ImageGenerateState())
    val state: StateFlow<ImageGenerateState> = _state.asStateFlow()

    private var timeoutJob: Job? = null

    /** 发起一次生图。参数非法/未连接/上一次仍在进行时返回 false，并把原因写进 state。 */
    fun generate(params: ImageGenerateParams): Boolean {
        val current = _state.value
        if (current.phase == ImageGeneratePhase.SUBMITTING || current.phase == ImageGeneratePhase.GENERATING) {
            _state.update {
                it.copy(errorCode = "BUSY", errorMessage = "上一张图片还在生成，请稍候")
            }
            return false
        }
        val validation = validateImageGenerateParams(params)
        if (validation != null) {
            timeoutJob?.cancel()
            _state.update {
                it.copy(
                    phase = ImageGeneratePhase.ERROR,
                    request = null,
                    errorCode = "INVALID_PARAMS",
                    errorMessage = validation,
                )
            }
            return false
        }
        timeoutJob?.cancel()
        val request = ImageGenerateRequest(
            requestId = newRequestId(),
            params = params.normalized(),
            startedAtMillis = now(),
        )
        _state.update {
            it.copy(
                phase = ImageGeneratePhase.SUBMITTING,
                request = request,
                errorCode = null,
                errorMessage = null,
            )
        }
        val sent = runCatching { sendCommand(request.requestId, imageGenerateCommandJson(request.requestId, request.params)) }
            .getOrDefault(false)
        if (!sent) {
            _state.update {
                it.copy(
                    phase = ImageGeneratePhase.ERROR,
                    errorCode = "NOT_CONNECTED",
                    errorMessage = "Mac 尚未连接，请先在「我的 → Codex 远程节点」完成配对",
                )
            }
            return false
        }
        // 回包可能比 send 返回更早（同线程假 sender 的测试即如此），此时保持已经落定的结果态。
        val settled = _state.value.phase != ImageGeneratePhase.SUBMITTING ||
            _state.value.request?.requestId != request.requestId
        if (settled) return true
        _state.update { it.copy(phase = ImageGeneratePhase.GENERATING) }
        armTimeout(request.requestId)
        return true
    }

    /** 远程事件分发入口；命中本仓库的 image.* 事件返回 true。 */
    fun onEvent(event: RemoteEvent): Boolean = when (event.type) {
        IMAGE_GENERATED_EVENT_TYPE -> onGenerated(event)
        IMAGE_ERROR_EVENT_TYPE -> onImageError(event)
        else -> false
    }

    /**
     * 提交一次生图并挂起等待最终态（SUCCESS / ERROR），供本地聊天的 function calling 工具循环使用。
     *
     * - 提交失败 / 参数非法 / 未连接：立即抛 [ImageGenerateException]（message 为可直接展示的中文）；
     * - 等待超过 [timeoutMillis]：抛 TIMEOUT；bridge 迟到的回包仍会照常写进 state/history，可重试；
     * - 协程取消：结束等待，不影响 bridge 侧任务（与 UI 侧行为一致）。
     */
    suspend fun generateAndAwait(
        params: ImageGenerateParams,
        timeoutMillis: Long = DEFAULT_IMAGE_GENERATE_TIMEOUT_MILLIS,
    ): ImageGenerateResult {
        if (!generate(params)) {
            val current = _state.value
            throw ImageGenerateException(
                code = current.errorCode ?: "SUBMIT_FAILED",
                message = current.errorMessage ?: "生图请求提交失败，请稍后重试",
            )
        }
        val requestId = _state.value.request?.requestId
            ?: throw ImageGenerateException("SUBMIT_FAILED", "生图请求提交失败，请稍后重试")
        val settled = withTimeoutOrNull(timeoutMillis.coerceAtLeast(1L)) {
            state.first { snapshot ->
                snapshot.request?.requestId == requestId &&
                    (snapshot.phase == ImageGeneratePhase.SUCCESS || snapshot.phase == ImageGeneratePhase.ERROR)
            }
        } ?: throw ImageGenerateException(
            code = "TIMEOUT",
            message = "生图等待超时（${timeoutMillis / 1000} 秒），图片可能仍在 Mac 上生成，请稍后重试",
        )
        if (settled.phase == ImageGeneratePhase.SUCCESS) {
            return settled.result?.takeIf { it.requestId == requestId }
                ?: throw ImageGenerateException("BAD_PAYLOAD", "生图结果解析失败，请稍后重试")
        }
        throw ImageGenerateException(
            code = settled.errorCode ?: "ERROR",
            message = settled.errorMessage ?: "生图失败，请稍后重试",
        )
    }

    /** 清掉错误提示并回到 idle（历史与上一张结果保留）。 */
    fun dismissError() {
        if (_state.value.phase != ImageGeneratePhase.ERROR) return
        timeoutJob?.cancel()
        _state.update {
            it.copy(phase = ImageGeneratePhase.IDLE, errorCode = null, errorMessage = null)
        }
    }

    private fun onGenerated(event: RemoteEvent): Boolean {
        val requestId = event.requestId?.takeIf { it.isNotBlank() } ?: return false
        val request = _state.value.request ?: return false
        if (request.requestId != requestId) return false
        timeoutJob?.cancel()
        val payload = try {
            parseImageGeneratedEvent(event)
        } catch (error: IllegalArgumentException) {
            _state.update {
                it.copy(
                    phase = ImageGeneratePhase.ERROR,
                    errorCode = "BAD_PAYLOAD",
                    errorMessage = error.message ?: "生图结果解析失败",
                )
            }
            return true
        }
        val result = ImageGenerateResult(
            requestId = request.requestId,
            id = payload.id,
            prompt = payload.prompt.ifBlank { request.params.prompt },
            model = payload.model.ifBlank { request.params.model.wireValue },
            size = payload.size.ifBlank { request.params.size.wireValue },
            quality = payload.quality.ifBlank { request.params.quality.wireValue },
            mimeType = payload.mimeType,
            data = payload.data,
            width = payload.width,
            height = payload.height,
            durationMs = payload.durationMs,
            costEstimateUsd = payload.costEstimateUsd,
            savedPath = payload.savedPath,
            completedAtMillis = now(),
        )
        _state.update {
            it.copy(
                phase = ImageGeneratePhase.SUCCESS,
                result = result,
                errorCode = null,
                errorMessage = null,
                history = (listOf(result) + it.history.filterNot { item -> item.requestId == result.requestId })
                    .take(historyLimit),
            )
        }
        return true
    }

    private fun onImageError(event: RemoteEvent): Boolean {
        val requestId = event.requestId?.takeIf { it.isNotBlank() } ?: return false
        val request = _state.value.request ?: return false
        if (request.requestId != requestId) return false
        timeoutJob?.cancel()
        val error = parseImageErrorEvent(event)
        _state.update {
            it.copy(
                phase = ImageGeneratePhase.ERROR,
                errorCode = error.code,
                errorMessage = error.message,
            )
        }
        return true
    }

    private fun armTimeout(requestId: String) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMillis)
            val current = _state.value
            if (current.phase == ImageGeneratePhase.GENERATING && current.request?.requestId == requestId) {
                _state.update {
                    it.copy(
                        phase = ImageGeneratePhase.ERROR,
                        errorCode = "TIMEOUT",
                        errorMessage = "本地等待超时（${timeoutMillis / 1000} 秒）：图片可能仍在 Mac 上生成（bridge 预算 ${BRIDGE_IMAGE_GENERATE_BUDGET_MILLIS / 1000} 秒），收到回包会自动显示；也可以重试重新发起。",
                    )
                }
            }
        }
    }
}

enum class ImageGeneratePhase {
    IDLE,
    SUBMITTING,
    GENERATING,
    SUCCESS,
    ERROR,
}

data class ImageGenerateRequest(
    val requestId: String,
    val params: ImageGenerateParams,
    val startedAtMillis: Long,
)

data class ImageGenerateResult(
    val requestId: String,
    val id: String,
    val prompt: String,
    val model: String,
    val size: String,
    val quality: String,
    val mimeType: String,
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val costEstimateUsd: Double?,
    val savedPath: String?,
    val completedAtMillis: Long,
) {
    val byteCount: Int get() = data.size
}

data class ImageGenerateState(
    val phase: ImageGeneratePhase = ImageGeneratePhase.IDLE,
    val request: ImageGenerateRequest? = null,
    val result: ImageGenerateResult? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val history: List<ImageGenerateResult> = emptyList(),
)

/** 生图失败（提交失败 / bridge 报错 / 本地等待超时）；[message] 可直接展示。 */
class ImageGenerateException(
    val code: String?,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
