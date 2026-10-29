package com.harnessapk.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ImageGenerateStore.generateAndAwait]：供本地聊天 function calling 工具循环使用的挂起等待封装。 */
class ImageGenerateStoreAwaitTest {
    private val params = ImageGenerateParams(prompt = "一只戴宇航头盔的橘猫")

    @Test
    fun `等待成功事件后返回图片结果`() = runTest {
        val store = ImageGenerateStore(
            scope = this,
            sendCommand = { _, _ -> true },
            newRequestId = { "image.generate:req-1" },
        )
        val data = fakePngBytes(width = 1024, height = 1536)

        val pending = async { store.generateAndAwait(params) }
        runCurrent()
        store.onEvent(
            generatedEvent(
                requestId = "image.generate:req-1",
                data = data,
                width = 1024,
                height = 1536,
                size = "1024x1536",
                durationMs = 8_123L,
            ),
        )
        val result = pending.await()

        assertEquals("image.generate:req-1", result.requestId)
        assertEquals("image/png", result.mimeType)
        assertEquals(1024, result.width)
        assertEquals(1536, result.height)
        assertEquals(data.size, result.byteCount)
        assertEquals(8_123L, result.durationMs)
    }

    @Test
    fun `失败事件转成带 code 的中文异常`() = runTest {
        val store = ImageGenerateStore(
            scope = this,
            sendCommand = { _, _ -> true },
            newRequestId = { "image.generate:req-error" },
        )

        val pending = async { runCatching { store.generateAndAwait(params) } }
        runCurrent()
        store.onEvent(
            imageErrorEvent(
                requestId = "image.generate:req-error",
                message = "OpenAI 401：额度不足，请检查 Mac 上的 API Key",
                code = "OPENAI_HTTP_401",
            ),
        )
        val error = pending.await().exceptionOrNull()

        assertTrue(error is ImageGenerateException)
        assertEquals("OPENAI_HTTP_401", (error as ImageGenerateException).code)
        assertTrue(error.message!!.contains("额度不足"))
    }

    @Test
    fun `未连接时立即抛可展示错误`() = runTest {
        val store = ImageGenerateStore(scope = this, sendCommand = { _, _ -> false })

        val error = runCatching { store.generateAndAwait(params) }.exceptionOrNull()

        assertTrue(error is ImageGenerateException)
        assertEquals("NOT_CONNECTED", (error as ImageGenerateException).code)
        assertTrue(error.message!!.contains("Mac 尚未连接"))
    }

    @Test
    fun `本地等待超时抛 TIMEOUT`() = runTest {
        val store = ImageGenerateStore(
            scope = this,
            sendCommand = { _, _ -> true },
            timeoutMillis = 1_000L,
            newRequestId = { "image.generate:req-timeout" },
        )

        val pending = async { runCatching { store.generateAndAwait(params, timeoutMillis = 5_000L) } }
        advanceTimeBy(1_000L)
        val error = pending.await().exceptionOrNull()

        assertTrue(error is ImageGenerateException)
        assertEquals("TIMEOUT", (error as ImageGenerateException).code)
        assertTrue(error.message!!.contains("超时"))
    }
}
