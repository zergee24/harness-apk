package com.harnessapk.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import java.io.IOException

/**
 * 协程一进入取消态就立即 [Call.cancel]，让阻塞在 `readUtf8Line()` 上的流式读取马上抛出。
 *
 * 不能用 `job.invokeOnCompletion`：它要等 Job **完成**才回调，而 Job 正卡在阻塞读上完成不了，
 * 结果是「停止」和卡流检测都要干等到 readTimeout（默认 180 秒）才生效。
 * 子协程在父协程取消时会被立即取消，finally 里的 cancel 不依赖阻塞读返回。
 */
internal suspend fun <T> withCallCancellation(call: Call, block: suspend () -> T): T = coroutineScope {
    var finished = false
    val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!finished) call.cancel()
        }
    }
    try {
        block()
    } catch (io: IOException) {
        // 我们主动 cancel 连接后，阻塞读会抛 SocketException/IOException。协程框架会把这种非取消异常
        // 当成根因，上层就会把「用户停止」误判为网络失败并自动重试；这里还原成取消。
        currentCoroutineContext().ensureActive()
        throw io
    } finally {
        finished = true
        canceller.cancel()
    }
}
