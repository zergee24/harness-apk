package com.harnessapk.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.harnessapk.common.AppDispatchers
import com.harnessapk.common.AppError
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.util.Base64
import java.util.UUID

data class PersistedChatImage(
    val uri: Uri,
    val mimeType: String,
)

sealed interface ChatImageSource {
    data class Local(val uri: Uri, val mimeType: String) : ChatImageSource
    data class Data(val bytes: ByteArray, val mimeType: String) : ChatImageSource
    data class Remote(val httpsUrl: String, val mimeType: String?) : ChatImageSource
    data class Invalid(val reason: String) : ChatImageSource
}

class ChatImageStore(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val dispatchers: AppDispatchers,
    private val uriForFile: (File) -> Uri = { file ->
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    },
) {
    suspend fun persist(source: Uri, mimeType: String): PersistedChatImage = withContext(dispatchers.io) {
        val normalizedMimeType = normalizedImageMimeType(mimeType)
        val input = when (source.scheme?.lowercase()) {
            "file" -> FileInputStream(File(requireNotNull(source.path) { "图片路径无效" }))
            else -> context.contentResolver.openInputStream(source)
        } ?: throw AppError.Network("无法读取图片")

        input.use { persistInput(it, normalizedMimeType) }
    }

    fun createCameraUri(): Uri {
        val file = managedFiles.create("image/jpeg")
        return uriForFile(file)
    }

    suspend fun resolveDisplaySource(raw: String, mimeType: String?): ChatImageSource =
        withContext(dispatchers.io) { resolveChatImageDisplaySource(raw, mimeType) }

    suspend fun materialize(source: ChatImageSource): PersistedChatImage = withContext(dispatchers.io) {
        when (source) {
            is ChatImageSource.Local -> persist(source.uri, source.mimeType)
            is ChatImageSource.Data -> persistBytes(source.bytes, normalizedImageMimeType(source.mimeType))
            is ChatImageSource.Remote -> downloadImage(source)
            is ChatImageSource.Invalid -> throw AppError.Network(source.reason)
        }
    }

    suspend fun saveToMediaStore(source: Uri, mimeType: String): String = withContext(dispatchers.io) {
        val normalizedMimeType = normalizedImageMimeType(mimeType)
        val displayName = "chat-image-${UUID.randomUUID()}.${imageExtensionFor(normalizedMimeType)}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, normalizedMimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val destination = context.contentResolver.insert(collection, values)
            ?: throw AppError.Network("无法保存图片到相册")

        try {
            val input = inputFor(source)
            input.use { sourceInput ->
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    sourceInput.copyTo(output)
                } ?: throw AppError.Network("无法保存图片到相册")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(destination, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            displayName
        } catch (error: Throwable) {
            context.contentResolver.delete(destination, null, null)
            throw error
        }
    }

    suspend fun deleteIfManaged(uri: Uri) = withContext(dispatchers.io) {
        if (uri.scheme != "content" || uri.authority != "${context.packageName}.fileprovider") return@withContext
        if (uri.pathSegments.firstOrNull() != "chat_images") return@withContext
        val name = uri.lastPathSegment ?: return@withContext
        managedFiles.delete(name)
    }

    private fun downloadImage(source: ChatImageSource.Remote): PersistedChatImage {
        val request = Request.Builder().url(source.httpsUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AppError.Network("图片下载失败：HTTP ${response.code}")
            val mimeType = response.header("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.startsWith("image/", ignoreCase = true) }
                ?: throw AppError.Network("图片下载失败：响应不是图片")
            val bytes = response.body.bytes()
            if (bytes.isEmpty()) throw AppError.Network("图片下载失败：响应为空")
            return persistBytes(bytes, normalizedImageMimeType(mimeType))
        }
    }

    private fun persistBytes(bytes: ByteArray, mimeType: String): PersistedChatImage {
        val file = managedFiles.write(bytes, mimeType)
        return PersistedChatImage(uriForFile(file), mimeType)
    }

    private fun persistInput(input: InputStream, mimeType: String): PersistedChatImage {
        val file = managedFiles.copy(input, mimeType)
        return PersistedChatImage(uriForFile(file), mimeType)
    }

    private val managedFiles: ManagedChatImageFiles by lazy {
        ManagedChatImageFiles(context.filesDir)
    }

    private fun inputFor(uri: Uri) = when (uri.scheme?.lowercase()) {
        "file" -> FileInputStream(File(requireNotNull(uri.path) { "图片路径无效" }))
        else -> context.contentResolver.openInputStream(uri)
    } ?: throw AppError.Network("无法读取图片")

    private fun normalizedImageMimeType(mimeType: String): String {
        require(mimeType.startsWith("image/", ignoreCase = true)) { "图片类型无效" }
        return mimeType.substringBefore(';').lowercase()
    }
}

internal fun resolveChatImageDisplaySource(raw: String, mimeType: String?): ChatImageSource {
    val value = raw.trim()
    return when {
        value.startsWith("data:image/", ignoreCase = true) -> parseDataImage(value)
        else -> when (value.substringBefore(':', missingDelimiterValue = "").lowercase()) {
            "content", "file" -> {
                val uri = Uri.parse(value)
                ChatImageSource.Local(
                    uri = uri,
                    mimeType = mimeType?.takeIf { it.startsWith("image/", ignoreCase = true) } ?: "image/*",
                )
            }
            "https" -> if (value.toHttpUrlOrNull() == null) {
                ChatImageSource.Invalid("图片地址无效")
            } else {
                ChatImageSource.Remote(value, mimeType)
            }
            else -> ChatImageSource.Invalid("不支持的图片来源")
        }
    }
}

private fun parseDataImage(raw: String): ChatImageSource {
    val separator = raw.indexOf(',')
    if (separator <= 0) return ChatImageSource.Invalid("图片数据无效")
    val metadata = raw.substring(5, separator)
    val mimeType = metadata.substringBefore(';').lowercase()
    if (!mimeType.startsWith("image/")) {
        return ChatImageSource.Invalid("图片数据无效")
    }
    val data = raw.substring(separator + 1)
    val bytes = if (metadata.contains(";base64", ignoreCase = true)) {
        runCatching { Base64.getDecoder().decode(data) }.getOrNull()
    } else {
        decodePercentEncodedData(data)
    }
        ?: return ChatImageSource.Invalid("图片数据无效")
    return ChatImageSource.Data(bytes, mimeType)
}

private fun decodePercentEncodedData(value: String): ByteArray? = runCatching {
    ByteArrayOutputStream().use { output ->
        var literalStart = 0
        var index = 0
        while (index < value.length) {
            if (value[index] != '%') {
                index++
                continue
            }
            output.write(value.substring(literalStart, index).toByteArray())
            if (index + 2 >= value.length) throw IllegalArgumentException("Invalid percent encoding")
            val high = value[index + 1].digitToIntOrNull(16)
            val low = value[index + 2].digitToIntOrNull(16)
            if (high == null || low == null) throw IllegalArgumentException("Invalid percent encoding")
            output.write(high * 16 + low)
            index += 3
            literalStart = index
        }
        output.write(value.substring(literalStart).toByteArray())
        output.toByteArray()
    }
}.getOrNull()

internal class ManagedChatImageFiles(
    filesDir: File,
) {
    private val directory = File(filesDir, CHAT_IMAGES_DIRECTORY)

    fun copy(input: InputStream, mimeType: String): File {
        val file = newFile(mimeType)
        try {
            file.outputStream().use { input.copyTo(it) }
            return file
        } catch (error: Throwable) {
            file.delete()
            throw AppError.Network("无法保存图片")
        }
    }

    fun write(bytes: ByteArray, mimeType: String): File = copy(bytes.inputStream(), mimeType)

    fun create(mimeType: String): File = newFile(mimeType).apply { outputStream().close() }

    fun delete(name: String) {
        val candidate = File(directory.canonicalFile, name).canonicalFile
        if (candidate.parentFile == directory.canonicalFile) candidate.delete()
    }

    private fun newFile(mimeType: String): File = File(
        directory(),
        "${UUID.randomUUID()}.${imageExtensionFor(mimeType)}",
    )

    private fun directory(): File = directory.apply {
        if (!exists() && !mkdirs()) throw AppError.Network("无法创建图片目录")
    }

}

private const val CHAT_IMAGES_DIRECTORY = "chat-images"

private fun imageExtensionFor(mimeType: String): String = when (mimeType.lowercase()) {
    "image/jpeg", "image/jpg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/heic" -> "heic"
    "image/heif" -> "heif"
    else -> "img"
}
