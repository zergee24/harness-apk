package com.harnessapk.remote

import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `image.generate` 命令与 `image.generated` / `image.error` 事件的纯逻辑封装。
 * 协议规格：remote/docs/image-generate.md（本文件只做序列化/解析/校验，不碰传输）。
 */

const val IMAGE_GENERATE_COMMAND_TYPE = "image.generate"
const val IMAGE_GENERATED_EVENT_TYPE = "image.generated"
const val IMAGE_ERROR_EVENT_TYPE = "image.error"

/** prompt 上限：与 bridge 侧规格一致（≤ 4000 字符）。 */
const val MAX_IMAGE_PROMPT_CHARS = 4_000

/** 线上帧 8 MiB 上限对应的图片本体上限；超过时 bridge 会转 JPEG 或直接报错。 */
const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

/** 解码前先限制 base64 字符数，避免恶意/损坏帧在解码阶段撑爆内存。 */
const val MAX_IMAGE_BASE64_CHARS = (MAX_IMAGE_BYTES / 3 + 1) * 4

/** 单边像素上限：bridge 长边 ≤ 2048，这里留足余量只做明显异常拦截。 */
const val MAX_IMAGE_EDGE_PIXELS = 8_192

/** bridge 侧生图预算：180s（高质/大图可能接近，见 remote/docs/image-generate.md）。 */
const val BRIDGE_IMAGE_GENERATE_BUDGET_MILLIS = 180_000L

/**
 * UI 侧等待 bridge 生图的超时：必须大于 bridge 侧预算，否则 APK 会先于 bridge 放弃。
 * 取 240s（规格要求 ≥ 120s，bridge 侧 180s）。
 */
const val DEFAULT_IMAGE_GENERATE_TIMEOUT_MILLIS = 240_000L

/** 历史记录只保留最近 N 张（内存缓存）。 */
const val DEFAULT_IMAGE_HISTORY_LIMIT = 4

private val SUPPORTED_IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg")

enum class ImageSize(val wireValue: String, val label: String, val width: Int, val height: Int) {
    SQUARE("1024x1024", "1024×1024 方形", 1024, 1024),
    PORTRAIT("1024x1536", "1024×1536 竖图", 1024, 1536),
    LANDSCAPE("1536x1024", "1536×1024 横图", 1536, 1024),
    ;

    companion object {
        fun fromWire(value: String?): ImageSize? =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() }
    }
}

enum class ImageQuality(val wireValue: String, val label: String) {
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),
    ;

    companion object {
        fun fromWire(value: String?): ImageQuality? =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() }
    }
}

enum class ImageModel(val wireValue: String, val label: String) {
    FLARE("gpt-image-2.5-flare", "Flare（默认）"),
    SUNBURST("gpt-image-2.5-sunburst", "Sunburst"),
    GPT_IMAGE_2("gpt-image-2", "GPT Image 2"),
    ;

    companion object {
        fun fromWire(value: String?): ImageModel? =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() }
    }
}

/** `image.generate` 的 params（backendId 留空：host 级命令）。 */
data class ImageGenerateParams(
    val prompt: String,
    val size: ImageSize = ImageSize.SQUARE,
    val quality: ImageQuality = ImageQuality.MEDIUM,
    val model: ImageModel = ImageModel.FLARE,
) {
    fun normalized(): ImageGenerateParams = copy(prompt = prompt.trim())
}

internal data class ImageGeneratedPayload(
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
)

internal data class ImageGenerateError(
    val code: String?,
    val message: String,
)

/** prompt 合法返回 null，否则返回可直接展示的中文错误。 */
internal fun validateImageGenerateParams(params: ImageGenerateParams): String? {
    val prompt = params.prompt.trim()
    return when {
        prompt.isEmpty() -> "请先输入图片描述"
        prompt.length > MAX_IMAGE_PROMPT_CHARS -> "图片描述不能超过 $MAX_IMAGE_PROMPT_CHARS 字（当前 ${prompt.length} 字）"
        else -> null
    }
}

/** 按协议拼出完整命令 JSON：`{type, requestId, params:{prompt,size,quality,model}}`，不带 backendId。 */
internal fun imageGenerateCommandJson(requestId: String, params: ImageGenerateParams): JsonObject {
    val normalized = params.normalized()
    return buildJsonObject {
        put("type", JsonPrimitive(IMAGE_GENERATE_COMMAND_TYPE))
        put("requestId", JsonPrimitive(requestId))
        put(
            "params",
            buildJsonObject {
                put("prompt", JsonPrimitive(normalized.prompt))
                put("size", JsonPrimitive(normalized.size.wireValue))
                put("quality", JsonPrimitive(normalized.quality.wireValue))
                put("model", JsonPrimitive(normalized.model.wireValue))
            },
        )
    }
}

/** 解析 `image.generated`。任何结构/数据非法都抛 IllegalArgumentException（中文 message 可直接展示）。 */
internal fun parseImageGeneratedEvent(event: RemoteEvent): ImageGeneratedPayload {
    require(event.type == IMAGE_GENERATED_EVENT_TYPE) { "不是生图成功事件" }
    val payload = event.payload as? JsonObject ?: throw IllegalArgumentException("生图结果缺少 payload")
    val rawData = payload.string("dataBase64")?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("生图结果缺少图片数据")
    val data = decodeImageBase64(rawData)
    if (data.size > MAX_IMAGE_BYTES) {
        throw IllegalArgumentException("图片数据超过 8MB 上限，已拒绝显示")
    }
    val detectedMimeType = detectImageMimeType(data)
        ?: throw IllegalArgumentException("图片数据格式不受支持（仅支持 PNG/JPEG）")
    val declaredMimeType = payload.string("mimeType")?.substringBefore(';')?.trim()?.lowercase()
    if (!declaredMimeType.isNullOrEmpty() && declaredMimeType !in SUPPORTED_IMAGE_MIME_TYPES) {
        throw IllegalArgumentException("不支持的图片类型：$declaredMimeType")
    }
    // 声明与图片头不一致时以图片头为准（避免解码器按错误类型处理）。
    val mimeType = detectedMimeType

    val declaredBytes = payload.long("bytes")
    if (declaredBytes != null && declaredBytes != data.size.toLong()) {
        throw IllegalArgumentException("图片数据大小与声明不一致（声明 $declaredBytes 字节，实际 ${data.size} 字节）")
    }

    val inspected = inspectImagePixelSize(data)
    val declaredWidth = payload.long("width")?.toInt() ?: 0
    val declaredHeight = payload.long("height")?.toInt() ?: 0
    val width: Int
    val height: Int
    if (inspected != null) {
        if (
            declaredWidth > 0 && declaredHeight > 0 &&
            (declaredWidth != inspected.width || declaredHeight != inspected.height)
        ) {
            throw IllegalArgumentException(
                "图片尺寸与声明不一致（声明 ${declaredWidth}×${declaredHeight}，实际 ${inspected.width}×${inspected.height}）",
            )
        }
        width = inspected.width
        height = inspected.height
    } else {
        if (declaredWidth <= 0 || declaredHeight <= 0) {
            throw IllegalArgumentException("图片尺寸无效")
        }
        width = declaredWidth
        height = declaredHeight
    }
    if (width > MAX_IMAGE_EDGE_PIXELS || height > MAX_IMAGE_EDGE_PIXELS) {
        throw IllegalArgumentException("图片尺寸超出范围（${width}×${height}）")
    }

    return ImageGeneratedPayload(
        id = payload.string("id")?.takeIf { it.isNotBlank() } ?: event.requestId.orEmpty(),
        prompt = payload.string("prompt").orEmpty(),
        model = payload.string("model").orEmpty(),
        size = payload.string("size").orEmpty(),
        quality = payload.string("quality").orEmpty(),
        mimeType = mimeType,
        data = data,
        width = width,
        height = height,
        durationMs = payload.long("durationMs")?.coerceAtLeast(0L) ?: 0L,
        costEstimateUsd = payload.double("costEstimateUsd")?.takeIf { it >= 0.0 },
        savedPath = payload.string("savedPath")?.takeIf { it.isNotBlank() },
    )
}

/** 解析 `image.error`，message 缺失时按 code 兜底成中文。 */
internal fun parseImageErrorEvent(event: RemoteEvent): ImageGenerateError {
    require(event.type == IMAGE_ERROR_EVENT_TYPE) { "不是生图失败事件" }
    val payload = event.payload as? JsonObject
    val code = payload?.string("code")?.takeIf { it.isNotBlank() }
    val rawMessage = event.message?.takeIf { it.isNotBlank() } ?: payload?.string("message")
    return ImageGenerateError(code = code, message = imageGenerateErrorMessage(code, rawMessage))
}

internal fun imageGenerateErrorMessage(code: String?, message: String?): String {
    val trimmed = message?.trim().orEmpty()
    if (trimmed.isNotEmpty()) return trimmed
    return when (code) {
        "INVALID_PARAMS" -> "生图参数无效，请检查描述、尺寸或质量后重试"
        "MISSING_API_KEY", "OPENAI_HTTP_401" ->
            "OpenAI 鉴权失败：请检查 Mac 上的 OPENAI_API_KEY 是否有效"
        "IMAGE_BUSY" -> "Mac 正在生成上一张图片，请等它完成后重试"
        "IMAGE_RATE_LIMITED", "OPENAI_HTTP_429", "RATE_LIMITED" ->
            "生图请求太频繁（每分钟最多 4 次），请稍后再试"
        "IMAGE_DAILY_LIMIT" -> "今天的生图次数（每天 200 次）已用完，请明天再试"
        "IMAGE_TOO_LARGE" -> "生成的图片超过 8MB，Mac 没有回传"
        "IMAGE_DECODE_FAILED", "IMAGE_ENCODE_FAILED" -> "Mac 处理图片数据失败，请稍后重试"
        "UPSTREAM_INVALID_RESPONSE" -> "OpenAI 返回了无法识别的结果，请稍后重试"
        "OPENAI_NETWORK" -> "Mac 连接 OpenAI 失败，请检查 Mac 的网络或代理"
        "IMAGE_TIMEOUT", "TIMEOUT" -> "Mac 生图超时，请稍后重试"
        "IMAGE_INTERNAL_ERROR" -> "Mac 生图内部错误，请稍后重试"
        null -> "Mac 生图失败，请稍后重试"
        else -> "Mac 生图失败（$code），请稍后重试"
    }
}

/** 严格 base64 解码（bridge 用标准 base64 带 padding）。 */
internal fun decodeImageBase64(value: String): ByteArray {
    val compact = value.trim()
    require(compact.isNotEmpty()) { "图片数据为空" }
    require(compact.length <= MAX_IMAGE_BASE64_CHARS) { "图片数据超过 8MB 上限，已拒绝解码" }
    require(compact.length % 4 == 0) { "图片数据不是合法的 base64" }
    return try {
        Base64.getDecoder().decode(compact)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("图片数据不是合法的 base64")
    }
}

internal fun detectImageMimeType(data: ByteArray): String? = when {
    data.hasPngSignature() -> "image/png"
    data.hasJpegSignature() -> "image/jpeg"
    else -> null
}

internal data class ImagePixelSize(val width: Int, val height: Int)

/** 从 PNG IHDR / JPEG SOF 头里读真实像素尺寸；无法识别返回 null。 */
internal fun inspectImagePixelSize(data: ByteArray): ImagePixelSize? = when {
    data.hasPngSignature() -> pngPixelSize(data)
    data.hasJpegSignature() -> jpegPixelSize(data)
    else -> null
}

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)

private fun ByteArray.hasPngSignature(): Boolean {
    if (size < PNG_SIGNATURE.size) return false
    return PNG_SIGNATURE.indices.all { index -> this[index] == PNG_SIGNATURE[index] }
}

private fun ByteArray.hasJpegSignature(): Boolean =
    size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()

private fun pngPixelSize(data: ByteArray): ImagePixelSize? {
    // 8 字节签名 + 4 字节长度 + 4 字节类型 + 4 字节宽 + 4 字节高
    if (data.size < 24) return null
    if (data[12] != 0x49.toByte() || data[13] != 0x48.toByte() || data[14] != 0x44.toByte() || data[15] != 0x52.toByte()) {
        return null
    }
    val width = data.readBigEndianInt(16)
    val height = data.readBigEndianInt(20)
    if (width <= 0 || height <= 0) return null
    return ImagePixelSize(width, height)
}

private fun jpegPixelSize(data: ByteArray): ImagePixelSize? {
    var index = 2
    while (index + 1 < data.size) {
        if (data[index] != 0xFF.toByte()) {
            index++
            continue
        }
        var markerIndex = index + 1
        while (markerIndex < data.size && data[markerIndex] == 0xFF.toByte()) markerIndex++
        if (markerIndex >= data.size) return null
        val marker = data[markerIndex].toInt() and 0xFF
        index = markerIndex + 1
        when {
            // 无长度字段的 marker：SOI/EOI/RSTn/TEM
            marker == 0xD8 || marker == 0xD9 || marker == 0x01 || marker in 0xD0..0xD7 -> continue
            // SOF0..SOF15（排除 DHT/JPG/DAC）
            marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> {
                if (index + 7 > data.size) return null
                val height = data.readBigEndianShort(index + 3)
                val width = data.readBigEndianShort(index + 5)
                if (width <= 0 || height <= 0) return null
                return ImagePixelSize(width, height)
            }
            else -> {
                if (index + 2 > data.size) return null
                val segmentLength = data.readBigEndianShort(index)
                if (segmentLength < 2) return null
                index += segmentLength
            }
        }
    }
    return null
}

private fun ByteArray.readBigEndianInt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

private fun ByteArray.readBigEndianShort(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)
