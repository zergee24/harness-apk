package com.harnessapk.chat

import com.harnessapk.network.ChatToolDefinition
import com.harnessapk.remote.ImageGenerateParams
import com.harnessapk.remote.ImageGenerateResult
import com.harnessapk.remote.ImageGenerateStore
import com.harnessapk.remote.ImageModel
import com.harnessapk.remote.ImageQuality
import com.harnessapk.remote.ImageSize
import com.harnessapk.remote.MAX_IMAGE_PROMPT_CHARS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/** 本地聊天里 `image_generate` function tool 的固定名称。 */
const val IMAGE_GENERATE_TOOL_NAME = "image_generate"

/**
 * 仅当生图工具可用时追加进 system prompt 的能力说明。
 * 工具不可用（Mac 离线/未配对）时绝不追加，避免模型空调用一个不存在的工具。
 */
internal const val IMAGE_GENERATE_TOOL_SYSTEM_PROMPT =
    "你可以使用 image_generate 工具为用户生成图片：用户明确要求画图、生成图片、制作配图时调用它。" +
        "参数 prompt 必填（最多 $MAX_IMAGE_PROMPT_CHARS 字，尽量具体）；可选 size（1024x1024 方形 / 1024x1536 竖图 / 1536x1024 横图）、" +
        "quality（low / medium / high）、model（gpt-image-2.5-flare / gpt-image-2.5-sunburst / gpt-image-2）。" +
        "图片生成后会直接显示在会话里，不要再用 Markdown 图片语法重复输出。不要滥用：用户没有明确要图时不要调用。"

/** 远程生图工具在本地聊天里的抽象；[isAvailable] 为假时不声明 function tools。 */
interface ImageGenerateTool {
    fun isAvailable(): Boolean

    suspend fun generate(params: ImageGenerateParams): ImageGenerateResult
}

/** 生成图片落成本地文件后的会话引用，uri 直接写进 ChatAttachment.uri。 */
data class GeneratedImageFile(
    val uri: String,
    val mimeType: String,
)

/**
 * 把生成图片写入 app 私有目录并返回 FileProvider uri；
 * 复用 [ChatImageStore] 的落盘与 FileProvider 配置，ChatScreen 的图片渲染/「保存图片」无需改动。
 */
interface GeneratedImageFileWriter {
    suspend fun write(bytes: ByteArray, mimeType: String): GeneratedImageFile
}

class ChatImageStoreGeneratedImageFileWriter(
    private val store: ChatImageStore,
) : GeneratedImageFileWriter {
    override suspend fun write(bytes: ByteArray, mimeType: String): GeneratedImageFile {
        val persisted = store.materialize(ChatImageSource.Data(bytes, mimeType))
        return GeneratedImageFile(uri = persisted.uri.toString(), mimeType = persisted.mimeType)
    }
}

/** 生产实现：可用性由远程 pairing + WS 连接状态决定，生成走 [ImageGenerateStore.generateAndAwait]。 */
class RemoteImageGenerateTool(
    private val store: ImageGenerateStore,
    private val isRemoteReady: () -> Boolean,
) : ImageGenerateTool {
    override fun isAvailable(): Boolean = isRemoteReady()

    override suspend fun generate(params: ImageGenerateParams): ImageGenerateResult =
        store.generateAndAwait(params)
}

/** `image_generate` 的 function schema：prompt 必填，尺寸/质量/模型为受控枚举。 */
internal fun imageGenerateToolDefinition(): ChatToolDefinition = ChatToolDefinition(
    name = IMAGE_GENERATE_TOOL_NAME,
    description = "在 Mac 上生成图片并直接显示在会话中。用户明确要求画图、生成图片、做配图时调用；" +
        "用户只是描述需求或让你解释时不要调用。",
    parameters = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                put(
                    "prompt",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put(
                            "description",
                            JsonPrimitive("图片描述，尽量具体（主体、风格、构图），最多 $MAX_IMAGE_PROMPT_CHARS 字"),
                        )
                    },
                )
                put(
                    "size",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put(
                            "description",
                            JsonPrimitive("图片尺寸，默认 1024x1024：1024x1024 方形 / 1024x1536 竖图 / 1536x1024 横图"),
                        )
                        put(
                            "enum",
                            buildJsonArray { ImageSize.entries.forEach { add(JsonPrimitive(it.wireValue)) } },
                        )
                    },
                )
                put(
                    "quality",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("生成质量，默认 medium"))
                        put(
                            "enum",
                            buildJsonArray { ImageQuality.entries.forEach { add(JsonPrimitive(it.wireValue)) } },
                        )
                    },
                )
                put(
                    "model",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("生图模型，默认 gpt-image-2.5-flare"))
                        put(
                            "enum",
                            buildJsonArray { ImageModel.entries.forEach { add(JsonPrimitive(it.wireValue)) } },
                        )
                    },
                )
            },
        )
        put("required", buildJsonArray { add(JsonPrimitive("prompt")) })
    },
)

/** 解析后的工具参数。 */
internal data class ParsedImageToolArguments(
    val params: ImageGenerateParams,
)

/**
 * 解析并校验 `image_generate` 的 arguments。
 * 失败时返回中文错误信息，由调用方作为工具错误回执交给模型，不中断整次发送。
 */
internal fun parseImageToolArguments(argumentsJson: String): Result<ParsedImageToolArguments> = runCatching {
    val trimmed = argumentsJson.trim()
    if (trimmed.isEmpty()) throw IllegalArgumentException("缺少参数：arguments 为空")
    val root = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
        ?: throw IllegalArgumentException("arguments 不是合法的 JSON 对象")
    val prompt = root.stringArgument("prompt").orEmpty()
    if (prompt.isEmpty()) throw IllegalArgumentException("缺少必填参数 prompt")
    if (prompt.length > MAX_IMAGE_PROMPT_CHARS) {
        throw IllegalArgumentException("prompt 超过 $MAX_IMAGE_PROMPT_CHARS 字上限（当前 ${prompt.length} 字）")
    }
    val size = root.stringArgument("size")?.let { raw ->
        ImageSize.fromWire(raw) ?: throw IllegalArgumentException(
            "size 不支持：$raw（可用 ${ImageSize.entries.joinToString(" / ") { it.wireValue }}）",
        )
    } ?: ImageSize.SQUARE
    val quality = root.stringArgument("quality")?.let { raw ->
        ImageQuality.fromWire(raw) ?: throw IllegalArgumentException(
            "quality 不支持：$raw（可用 ${ImageQuality.entries.joinToString(" / ") { it.wireValue }}）",
        )
    } ?: ImageQuality.MEDIUM
    val model = root.stringArgument("model")?.let { raw ->
        ImageModel.fromWire(raw) ?: throw IllegalArgumentException(
            "model 不支持：$raw（可用 ${ImageModel.entries.joinToString(" / ") { it.wireValue }}）",
        )
    } ?: ImageModel.FLARE
    ParsedImageToolArguments(
        params = ImageGenerateParams(prompt = prompt, size = size, quality = quality, model = model),
    )
}

private fun JsonObject.stringArgument(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val value = (element as? JsonPrimitive)?.contentOrNull?.trim()
        ?: throw IllegalArgumentException("$key 必须是字符串")
    return value.ifBlank { null }
}
