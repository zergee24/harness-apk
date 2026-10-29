package com.harnessapk.remote

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerateProtocolTest {
    private val params = ImageGenerateParams(
        prompt = "  一只戴宇航头盔的橘猫，扁平插画风  ",
        size = ImageSize.PORTRAIT,
        quality = ImageQuality.HIGH,
        model = ImageModel.SUNBURST,
    )

    @Test
    fun `命令 JSON 形状与协议规格一致`() {
        val command = imageGenerateCommandJson("image.generate:req-1", params)

        assertEquals("image.generate", command.string("type"))
        assertEquals("image.generate:req-1", command.string("requestId"))
        assertEquals(setOf("type", "requestId", "params"), command.keys)
        val payload = command.getValue("params").jsonObject
        assertEquals(setOf("prompt", "size", "quality", "model"), payload.keys)
        assertEquals("一只戴宇航头盔的橘猫，扁平插画风", payload.string("prompt"))
        assertEquals("1024x1536", payload.string("size"))
        assertEquals("high", payload.string("quality"))
        assertEquals("gpt-image-2.5-sunburst", payload.string("model"))
        // backendId 留空：image.generate 是 host 级命令。
        assertTrue(!command.containsKey("backendId"))
    }

    @Test
    fun `默认参数使用协议默认值`() {
        val command = imageGenerateCommandJson(
            requestId = "image.generate:req-2",
            params = ImageGenerateParams(prompt = "test"),
        )
        val payload = command.getValue("params").jsonObject

        assertEquals("1024x1024", payload.string("size"))
        assertEquals("medium", payload.string("quality"))
        assertEquals("gpt-image-2.5-flare", payload.string("model"))
        assertEquals(ImageSize.SQUARE, ImageSize.fromWire("1024x1024"))
        assertEquals(ImageQuality.MEDIUM, ImageQuality.fromWire("medium"))
        assertEquals(ImageModel.FLARE, ImageModel.fromWire("gpt-image-2.5-flare"))
    }

    @Test
    fun `prompt 校验使用中文错误`() {
        assertNull(validateImageGenerateParams(ImageGenerateParams(prompt = "正常描述")))
        assertEquals("请先输入图片描述", validateImageGenerateParams(ImageGenerateParams(prompt = "   ")))
        val tooLong = validateImageGenerateParams(ImageGenerateParams(prompt = "猫".repeat(MAX_IMAGE_PROMPT_CHARS + 1)))
        assertNotNull(tooLong)
        assertTrue(tooLong!!.contains("不能超过"))
    }

    @Test
    fun `成功事件解析出图片与元数据`() {
        val data = fakePngBytes(width = 1024, height = 1536)
        val event = generatedEvent(
            requestId = "image.generate:req-1",
            data = data,
            width = 1024,
            height = 1536,
            size = "1024x1536",
            durationMs = 8_123L,
            costEstimateUsd = 0.053,
        )

        val payload = parseImageGeneratedEvent(event)

        assertEquals("img-1", payload.id)
        assertEquals("一只宇航员橘猫", payload.prompt)
        assertEquals("gpt-image-2.5-flare", payload.model)
        assertEquals("image/png", payload.mimeType)
        assertEquals(1024, payload.width)
        assertEquals(1536, payload.height)
        assertEquals(8_123L, payload.durationMs)
        assertEquals(0.053, payload.costEstimateUsd!!, 1e-9)
        assertEquals("/Users/tony/.harness-remote/imagegen/test.png", payload.savedPath)
        assertEquals(data.size, payload.data.size)
        assertEquals(data.toList(), payload.data.toList())
    }

    @Test
    fun `成功事件支持 JPEG`() {
        val data = fakeJpegBytes(width = 1536, height = 1024)
        val event = generatedEvent(
            requestId = "image.generate:req-2",
            data = data,
            width = 1536,
            height = 1024,
            mimeType = "image/jpeg",
        )

        val payload = parseImageGeneratedEvent(event)

        assertEquals("image/jpeg", payload.mimeType)
        assertEquals(1536, payload.width)
        assertEquals(1024, payload.height)
    }

    @Test
    fun `图片头尺寸与声明不一致时报错`() {
        val event = generatedEvent(
            requestId = "image.generate:req-3",
            data = fakePngBytes(width = 1024, height = 1024),
            width = 1536,
            height = 1024,
        )

        val error = assertThrows(IllegalArgumentException::class.java) { parseImageGeneratedEvent(event) }
        assertTrue(error.message!!.contains("尺寸与声明不一致"))
    }

    @Test
    fun `字节数与实际解码大小不一致时报错`() {
        val data = fakePngBytes()
        val event = generatedEvent(
            requestId = "image.generate:req-4",
            data = data,
            declaredBytes = data.size.toLong() + 1,
        )

        val error = assertThrows(IllegalArgumentException::class.java) { parseImageGeneratedEvent(event) }
        assertTrue(error.message!!.contains("大小与声明不一致"))
    }

    @Test
    fun `缺少图片数据时报错`() {
        val event = RemoteEvent(
            type = IMAGE_GENERATED_EVENT_TYPE,
            requestId = "image.generate:req-5",
            payload = buildJsonObject {
                put("id", JsonPrimitive("img-1"))
                put("mimeType", JsonPrimitive("image/png"))
            },
        )

        val error = assertThrows(IllegalArgumentException::class.java) { parseImageGeneratedEvent(event) }
        assertEquals("生图结果缺少图片数据", error.message)
    }

    @Test
    fun `非 PNG JPEG 数据被拒绝`() {
        val event = RemoteEvent(
            type = IMAGE_GENERATED_EVENT_TYPE,
            requestId = "image.generate:req-6",
            payload = buildJsonObject {
                put("dataBase64", JsonPrimitive(base64Of("hello world".toByteArray())))
                put("mimeType", JsonPrimitive("image/png"))
                put("width", JsonPrimitive(1024))
                put("height", JsonPrimitive(1024))
            },
        )

        val error = assertThrows(IllegalArgumentException::class.java) { parseImageGeneratedEvent(event) }
        assertTrue(error.message!!.contains("格式不受支持"))
    }

    @Test
    fun `失败事件解析 message 与 code`() {
        val error = parseImageErrorEvent(imageErrorEvent("image.generate:req-7"))

        assertEquals("OPENAI_HTTP_401", error.code)
        assertEquals("OpenAI 401：额度不足", error.message)
    }

    @Test
    fun `失败事件没有 message 时按 code 兜底中文`() {
        assertEquals(
            "生图请求太频繁（每分钟最多 4 次），请稍后再试",
            parseImageErrorEvent(imageErrorEvent("image.generate:req-8", message = null, code = "RATE_LIMITED")).message,
        )
        assertEquals(
            "生成的图片超过 8MB，Mac 没有回传",
            parseImageErrorEvent(imageErrorEvent("image.generate:req-9", message = "  ", code = "IMAGE_TOO_LARGE")).message,
        )
        assertEquals(
            "Mac 生图失败，请稍后重试",
            parseImageErrorEvent(imageErrorEvent("image.generate:req-10", message = null, code = null)).message,
        )
        assertNull(parseImageErrorEvent(imageErrorEvent("image.generate:req-11", message = null, code = null)).code)
    }

    @Test
    fun `base64 解码正常输入`() {
        assertEquals("hello world", String(decodeImageBase64(base64Of("hello world".toByteArray()))))
    }

    @Test
    fun `base64 非法输入报中文错误`() {
        assertThrows(IllegalArgumentException::class.java) { decodeImageBase64("!!!!") }
        assertThrows(IllegalArgumentException::class.java) { decodeImageBase64("abc") } // 长度不是 4 的倍数
        assertThrows(IllegalArgumentException::class.java) { decodeImageBase64("data:image/png;base64,aGVsbG8=") }
        val error = assertThrows(IllegalArgumentException::class.java) {
            decodeImageBase64("A".repeat(MAX_IMAGE_BASE64_CHARS + 4))
        }
        assertTrue(error.message!!.contains("8MB"))
    }

    @Test
    fun `PNG 与 JPEG 头部尺寸解析`() {
        assertEquals(ImagePixelSize(1024, 1536), inspectImagePixelSize(fakePngBytes(width = 1024, height = 1536)))
        assertEquals(ImagePixelSize(1536, 1024), inspectImagePixelSize(fakeJpegBytes(width = 1536, height = 1024)))
        assertNull(inspectImagePixelSize("不是图片".toByteArray()))
    }

    @Test
    fun `超时预算不低于规格要求且大于 bridge 预算`() {
        assertTrue(DEFAULT_IMAGE_GENERATE_TIMEOUT_MILLIS >= 180_000L)
        assertTrue(DEFAULT_IMAGE_GENERATE_TIMEOUT_MILLIS > BRIDGE_IMAGE_GENERATE_BUDGET_MILLIS)
        assertEquals(4, DEFAULT_IMAGE_HISTORY_LIMIT)
        assertEquals(4_000, MAX_IMAGE_PROMPT_CHARS)
    }

    @Test
    fun `bridge 限流错误码给出不同中文文案`() {
        assertEquals(
            "Mac 正在生成上一张图片，请等它完成后重试",
            imageGenerateErrorMessage("IMAGE_BUSY", null),
        )
        assertEquals(
            "生图请求太频繁（每分钟最多 4 次），请稍后再试",
            imageGenerateErrorMessage("IMAGE_RATE_LIMITED", null),
        )
        assertEquals(
            "今天的生图次数（每天 200 次）已用完，请明天再试",
            imageGenerateErrorMessage("IMAGE_DAILY_LIMIT", null),
        )
        assertEquals(
            "生图参数无效，请检查描述、尺寸或质量后重试",
            imageGenerateErrorMessage("INVALID_PARAMS", null),
        )
        assertEquals(
            "OpenAI 鉴权失败：请检查 Mac 上的 OPENAI_API_KEY 是否有效",
            imageGenerateErrorMessage("MISSING_API_KEY", null),
        )
        // bridge 返回的中文 message 优先于 code 兜底。
        assertEquals(
            "图片过大，已转 JPEG 后仍超预算",
            imageGenerateErrorMessage("IMAGE_TOO_LARGE", "图片过大，已转 JPEG 后仍超预算"),
        )
    }

    @Test
    fun `事件类型不匹配时拒绝解析`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseImageGeneratedEvent(RemoteEvent(type = "image.error", requestId = "x"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseImageErrorEvent(RemoteEvent(type = "image.generated", requestId = "x"))
        }
    }
}
