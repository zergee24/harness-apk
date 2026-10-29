package com.harnessapk.remote

import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 生图协议测试用的最小图片字节：只构造 PNG IHDR / JPEG SOF 头，
 * 足够覆盖 base64、MIME、尺寸校验逻辑（不依赖 Android 解码器）。
 */
internal fun fakePngBytes(width: Int = 1024, height: Int = 1024, extraBytes: Int = 16): ByteArray {
    val data = ByteArray(24 + extraBytes)
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(data)
    byteArrayOf(0x49, 0x48, 0x44, 0x52).copyInto(data, destinationOffset = 12)
    writeBigEndianInt(data, 16, width)
    writeBigEndianInt(data, 20, height)
    return data
}

internal fun fakeJpegBytes(width: Int = 1536, height: Int = 1024): ByteArray {
    val data = ByteArray(34)
    data[0] = 0xFF.toByte()
    data[1] = 0xD8.toByte()
    // APP0：marker(2) + 长度(2，含自身) + 14 字节载荷 → 下一个 marker 在 offset 20
    data[2] = 0xFF.toByte()
    data[3] = 0xE0.toByte()
    data[4] = 0x00
    data[5] = 0x10
    // SOF0：marker(2) + 长度(2) + 精度(1) + 高(2) + 宽(2) + 后续占位
    data[20] = 0xFF.toByte()
    data[21] = 0xC0.toByte()
    data[22] = 0x00
    data[23] = 0x11
    data[24] = 0x08
    writeBigEndianShort(data, 25, height)
    writeBigEndianShort(data, 27, width)
    return data
}

internal fun base64Of(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

internal fun generatedEvent(
    requestId: String,
    data: ByteArray,
    width: Int = 1024,
    height: Int = 1024,
    declaredBytes: Long? = data.size.toLong(),
    mimeType: String = "image/png",
    model: String = "gpt-image-2.5-flare",
    size: String = "1024x1024",
    quality: String = "medium",
    durationMs: Long = 8_123L,
    costEstimateUsd: Double? = 0.053,
    savedPath: String? = "/Users/tony/.harness-remote/imagegen/test.png",
): RemoteEvent = RemoteEvent(
    type = IMAGE_GENERATED_EVENT_TYPE,
    requestId = requestId,
    payload = buildJsonObject {
        put("id", JsonPrimitive("img-1"))
        put("prompt", JsonPrimitive("一只宇航员橘猫"))
        put("model", JsonPrimitive(model))
        put("size", JsonPrimitive(size))
        put("quality", JsonPrimitive(quality))
        put("mimeType", JsonPrimitive(mimeType))
        put("dataBase64", JsonPrimitive(base64Of(data)))
        declaredBytes?.let { put("bytes", JsonPrimitive(it)) }
        put("width", JsonPrimitive(width))
        put("height", JsonPrimitive(height))
        put("durationMs", JsonPrimitive(durationMs))
        costEstimateUsd?.let { put("costEstimateUsd", JsonPrimitive(it)) }
        savedPath?.let { put("savedPath", JsonPrimitive(it)) }
    },
)

internal fun imageErrorEvent(
    requestId: String,
    message: String? = "OpenAI 401：额度不足",
    code: String? = "OPENAI_HTTP_401",
): RemoteEvent = RemoteEvent(
    type = IMAGE_ERROR_EVENT_TYPE,
    requestId = requestId,
    message = message,
    payload = code?.let { value ->
        JsonObject(mapOf("code" to JsonPrimitive(value)))
    },
)

private fun writeBigEndianInt(data: ByteArray, offset: Int, value: Int) {
    data[offset] = (value ushr 24).toByte()
    data[offset + 1] = (value ushr 16).toByte()
    data[offset + 2] = (value ushr 8).toByte()
    data[offset + 3] = value.toByte()
}

private fun writeBigEndianShort(data: ByteArray, offset: Int, value: Int) {
    data[offset] = (value ushr 8).toByte()
    data[offset + 1] = value.toByte()
}
