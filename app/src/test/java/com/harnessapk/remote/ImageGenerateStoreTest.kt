package com.harnessapk.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageGenerateStoreTest {
    private val params = ImageGenerateParams(prompt = "一只戴宇航头盔的橘猫", size = ImageSize.PORTRAIT)

    @Test
    fun `generate 发送 host 级命令并进入生成中`() = runTest {
        val commands = mutableListOf<Pair<String, JsonObject>>()
        val store = ImageGenerateStore(
            scope = this,
            sendCommand = { requestId, command ->
                commands += requestId to command
                true
            },
            newRequestId = { "image.generate:req-1" },
        )

        val sent = store.generate(params)

        assertTrue(sent)
        val (requestId, command) = commands.single()
        assertEquals("image.generate:req-1", requestId)
        assertEquals(IMAGE_GENERATE_COMMAND_TYPE, command.string("type"))
        assertEquals("image.generate:req-1", command.string("requestId"))
        assertFalse(command.containsKey("backendId"))
        assertEquals(ImageGeneratePhase.GENERATING, store.state.value.phase)
        assertEquals("image.generate:req-1", store.state.value.request?.requestId)
        assertEquals("一只戴宇航头盔的橘猫", store.state.value.request?.params?.prompt)
    }

    @Test
    fun `prompt 非法时不发送命令并报中文错误`() = runTest {
        val commands = mutableListOf<String>()
        val store = ImageGenerateStore(
            scope = this,
            sendCommand = { requestId, _ ->
                commands += requestId
                true
            },
        )

        assertFalse(store.generate(ImageGenerateParams(prompt = " ")))
        assertTrue(commands.isEmpty())
        assertEquals(ImageGeneratePhase.ERROR, store.state.value.phase)
        assertEquals("INVALID_PARAMS", store.state.value.errorCode)
        assertEquals("请先输入图片描述", store.state.value.errorMessage)

        val tooLong = store.generate(ImageGenerateParams(prompt = "猫".repeat(MAX_IMAGE_PROMPT_CHARS + 1)))
        assertFalse(tooLong)
        assertTrue(store.state.value.errorMessage!!.contains("不能超过"))
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `Mac 未连接时给出可操作错误`() = runTest {
        val store = ImageGenerateStore(scope = this, sendCommand = { _, _ -> false })

        assertFalse(store.generate(params))
        assertEquals(ImageGeneratePhase.ERROR, store.state.value.phase)
        assertEquals("NOT_CONNECTED", store.state.value.errorCode)
        assertTrue(store.state.value.errorMessage!!.contains("Mac 尚未连接"))
        assertTrue(store.state.value.errorMessage!!.contains("Codex 远程节点"))
    }

    @Test
    fun `成功事件写入结果与历史`() = runTest {
        val store = newStore(this)
        store.generate(params)
        val data = fakePngBytes(width = 1024, height = 1536)

        val handled = store.onEvent(
            generatedEvent(
                requestId = "image.generate:req-1",
                data = data,
                width = 1024,
                height = 1536,
                size = "1024x1536",
                durationMs = 8_123L,
                costEstimateUsd = 0.053,
            ),
        )

        assertTrue(handled)
        val state = store.state.value
        assertEquals(ImageGeneratePhase.SUCCESS, state.phase)
        val result = assertNotNull(state.result).let { state.result!! }
        assertEquals("img-1", result.id)
        assertEquals("image/png", result.mimeType)
        assertEquals(1024, result.width)
        assertEquals(1536, result.height)
        assertEquals(8_123L, result.durationMs)
        assertEquals(0.053, result.costEstimateUsd!!, 1e-9)
        assertEquals(data.size, result.byteCount)
        assertEquals(listOf("image.generate:req-1"), state.history.map { it.requestId })
        assertNull(state.errorMessage)
    }

    @Test
    fun `失败事件展示中文 message`() = runTest {
        val store = newStore(this)
        store.generate(params)

        val handled = store.onEvent(
            RemoteEvent(
                type = IMAGE_ERROR_EVENT_TYPE,
                requestId = "image.generate:req-1",
                message = "OpenAI 401：额度不足，请检查 Mac 上的 API Key",
                payload = kotlinx.serialization.json.buildJsonObject {
                    put("code", kotlinx.serialization.json.JsonPrimitive("OPENAI_HTTP_401"))
                },
            ),
        )

        assertTrue(handled)
        assertEquals(ImageGeneratePhase.ERROR, store.state.value.phase)
        assertEquals("OPENAI_HTTP_401", store.state.value.errorCode)
        assertEquals("OpenAI 401：额度不足，请检查 Mac 上的 API Key", store.state.value.errorMessage)
    }

    @Test
    fun `超时后进入错误且迟到成功仍会显示`() = runTest {
        val store = ImageGenerateStore(
            scope = this,
            sendCommand = { _, _ -> true },
            timeoutMillis = 1_000L,
            newRequestId = { "image.generate:req-timeout" },
        )
        store.generate(params)

        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(ImageGeneratePhase.ERROR, store.state.value.phase)
        assertEquals("TIMEOUT", store.state.value.errorCode)
        assertTrue(store.state.value.errorMessage!!.contains("超时"))

        // 超时只是 UI 侧放弃等待；Mac 迟到回包仍然要能显示。
        val data = fakePngBytes()
        store.onEvent(generatedEvent(requestId = "image.generate:req-timeout", data = data))

        assertEquals(ImageGeneratePhase.SUCCESS, store.state.value.phase)
        assertEquals(data.size, store.state.value.result!!.byteCount)
    }

    @Test
    fun `其他 requestId 的事件被忽略`() = runTest {
        val store = newStore(this)
        store.generate(params)

        val handled = store.onEvent(generatedEvent(requestId = "image.generate:another", data = fakePngBytes()))

        assertFalse(handled)
        assertEquals(ImageGeneratePhase.GENERATING, store.state.value.phase)
        assertNull(store.state.value.result)
        assertTrue(store.state.value.history.isEmpty())
    }

    @Test
    fun `回包早于 send 返回时保持成功态`() = runTest {
        val data = fakePngBytes()
        lateinit var store: ImageGenerateStore
        store = ImageGenerateStore(
            scope = this,
            sendCommand = { requestId, _ ->
                store.onEvent(generatedEvent(requestId = requestId, data = data))
                true
            },
            newRequestId = { "image.generate:req-fast" },
        )

        assertTrue(store.generate(params))

        assertEquals(ImageGeneratePhase.SUCCESS, store.state.value.phase)
        assertEquals(1, store.state.value.history.size)
    }

    @Test
    fun `历史只保留最近 N 张`() = runTest {
        var sequence = 0
        val store = ImageGenerateStore(
            scope = this,
            sendCommand = { _, _ -> true },
            newRequestId = { "image.generate:req-${sequence++}" },
            historyLimit = 2,
        )
        repeat(3) { index ->
            store.generate(ImageGenerateParams(prompt = "第 $index 张"))
            store.onEvent(
                generatedEvent(
                    requestId = "image.generate:req-$index",
                    data = fakePngBytes(),
                ),
            )
        }

        val history = store.state.value.history
        assertEquals(2, history.size)
        assertEquals(listOf("image.generate:req-2", "image.generate:req-1"), history.map { it.requestId })
    }

    @Test
    fun `onEvent 只处理 image 事件`() = runTest {
        val store = newStore(this)

        assertFalse(store.onEvent(RemoteEvent(type = "host.status")))
        assertFalse(store.onEvent(RemoteEvent(type = "image.generated", requestId = "unknown")))
    }

    @Test
    fun `dismissError 回到 idle 但保留历史`() = runTest {
        val store = newStore(this)
        store.generate(params)
        store.onEvent(generatedEvent(requestId = "image.generate:req-1", data = fakePngBytes()))
        store.generate(params)
        store.onEvent(
            RemoteEvent(
                type = IMAGE_ERROR_EVENT_TYPE,
                requestId = "image.generate:req-1",
                message = "失败",
            ),
        )

        store.dismissError()

        assertEquals(ImageGeneratePhase.IDLE, store.state.value.phase)
        assertNull(store.state.value.errorMessage)
        assertEquals(1, store.state.value.history.size)
    }

    private fun newStore(
        scope: kotlinx.coroutines.CoroutineScope,
        historyLimit: Int = DEFAULT_IMAGE_HISTORY_LIMIT,
    ): ImageGenerateStore = ImageGenerateStore(
        scope = scope,
        sendCommand = { _, _ -> true },
        newRequestId = { "image.generate:req-1" },
        historyLimit = historyLimit,
    )

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.next(): T = first()
}
