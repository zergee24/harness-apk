package com.harnessapk.ui.imagegen

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.harnessapk.remote.ImageGenerateResult
import java.io.File
import java.io.IOException

/** 生成图片保存到相册（Android Q+ 用 MediaStore 的 Pictures/Harness；Q 以下走公共 Pictures 目录）。 */
internal const val IMAGE_GENERATE_ALBUM_RELATIVE_PATH = "Pictures/Harness"
private const val IMAGE_GENERATE_ALBUM_FOLDER = "Harness"
private const val IMAGE_GENERATE_SHARE_DIRECTORY = "image-generate"

internal fun imageGenerateAlbumNeedsPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

internal fun imageExtensionForMimeType(mimeType: String): String =
    if (mimeType.equals("image/jpeg", ignoreCase = true)) "jpg" else "png"

/** 保存成功返回最终文件名（便于 UI 展示），失败抛异常（message 可直接展示）。 */
internal fun saveGeneratedImageToAlbum(context: Context, result: ImageGenerateResult): String {
    val displayName = "harness-${System.currentTimeMillis()}.${imageExtensionForMimeType(result.mimeType)}"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, result.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, IMAGE_GENERATE_ALBUM_RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val destination = resolver.insert(collection, values) ?: throw IOException("无法在相册中创建图片")
        try {
            val output = resolver.openOutputStream(destination) ?: throw IOException("无法写入相册图片")
            output.use { it.write(result.data) }
            resolver.update(
                destination,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (error: Throwable) {
            resolver.delete(destination, null, null)
            throw error
        }
        return displayName
    }

    if (imageGenerateAlbumNeedsPermission(context)) {
        throw SecurityException("保存到相册需要存储权限")
    }
    @Suppress("DEPRECATION")
    val picturesDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    val albumDirectory = File(picturesDirectory, IMAGE_GENERATE_ALBUM_FOLDER)
    if (!albumDirectory.exists() && !albumDirectory.mkdirs()) {
        throw IOException("无法创建相册目录")
    }
    val file = File(albumDirectory, displayName)
    file.writeBytes(result.data)
    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(result.mimeType), null)
    return displayName
}

/** 把图片写进 cache 并通过 FileProvider 生成分享 Intent（调用方负责 startActivity）。 */
internal fun buildGeneratedImageShareIntent(context: Context, result: ImageGenerateResult): Intent {
    val shareDirectory = File(context.cacheDir, IMAGE_GENERATE_SHARE_DIRECTORY)
    if (!shareDirectory.exists() && !shareDirectory.mkdirs()) {
        throw IOException("无法创建分享缓存目录")
    }
    val file = File(
        shareDirectory,
        "harness-${System.currentTimeMillis()}.${imageExtensionForMimeType(result.mimeType)}",
    )
    file.writeBytes(result.data)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return Intent(Intent.ACTION_SEND).apply {
        type = result.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        result.prompt.takeIf { it.isNotBlank() }?.let { prompt ->
            putExtra(Intent.EXTRA_TEXT, prompt.take(280))
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
